package org.mockserver.mock.action.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.base64.Base64;
import io.netty.util.AttributeKey;
import org.apache.commons.text.StringEscapeUtils;
import org.mockserver.closurecallback.websocketregistry.LocalCallbackRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.cors.CORSHeaders;
import org.mockserver.filters.HopByHopHeaderFilter;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.httpclient.SocketCommunicationException;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.CrossProtocolEventBus;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.crud.CrudDispatcher;
import org.mockserver.model.*;
import org.mockserver.model.StreamingBody;
import org.mockserver.openapi.OpenAPIRequestValidator;
import org.mockserver.openapi.OpenAPIResponseValidator;
import org.mockserver.openapi.OpenApiRuntimeExpressionResolver;
import org.mockserver.proxyconfiguration.NoProxyHostsUtils;
import org.mockserver.proxyconfiguration.ProxyConfiguration;
import org.mockserver.responsewriter.GrpcStreamResponseWriter;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.mockserver.telemetry.RequestSpans;
import org.mockserver.telemetry.TraceContextAttributes;
import org.mockserver.telemetry.W3CTraceContext;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.exception.ExceptionHandling.*;
import static org.mockserver.log.model.LogEntry.LogMessageType.*;
import static org.mockserver.log.model.LogEntryMessages.*;
import static org.mockserver.model.HttpResponse.badGatewayResponse;
import static org.mockserver.model.HttpResponse.notFoundResponse;
import static org.mockserver.model.HttpResponse.response;
import static org.slf4j.event.Level.TRACE;

/**
 * @author jamesdbloom
 */
@SuppressWarnings({"rawtypes", "FieldMayBeFinal"})
public class HttpActionHandler {

    public static final AttributeKey<InetSocketAddress> REMOTE_SOCKET = AttributeKey.valueOf("REMOTE_SOCKET");

    private final Configuration configuration;
    private final HttpState httpStateHandler;
    private final Scheduler scheduler;
    private MockServerLogger mockServerLogger;
    private HttpResponseActionHandler httpResponseActionHandler;
    private HttpResponseTemplateActionHandler httpResponseTemplateActionHandler;
    private HttpResponseClassCallbackActionHandler httpResponseClassCallbackActionHandler;
    private HttpResponseObjectCallbackActionHandler httpResponseObjectCallbackActionHandler;
    private HttpForwardActionHandler httpForwardActionHandler;
    private HttpForwardTemplateActionHandler httpForwardTemplateActionHandler;
    private HttpForwardClassCallbackActionHandler httpForwardClassCallbackActionHandler;
    private HttpForwardObjectCallbackActionHandler httpForwardObjectCallbackActionHandler;
    private HttpOverrideForwardedRequestActionHandler httpOverrideForwardedRequestCallbackActionHandler;
    private HttpForwardValidateActionHandler httpForwardValidateActionHandler;
    private HttpForwardWithFallbackActionHandler httpForwardWithFallbackActionHandler;
    private HttpSseResponseActionHandler httpSseResponseActionHandler;
    private HttpLlmResponseActionHandler httpLlmResponseActionHandler;
    private HttpWebSocketResponseActionHandler httpWebSocketResponseActionHandler;
    private GrpcStreamResponseActionHandler grpcStreamResponseActionHandler;
    private HttpErrorActionHandler httpErrorActionHandler;

    // forwarding
    private NettyHttpClient httpClient;
    private HopByHopHeaderFilter hopByHopHeaderFilter = new HopByHopHeaderFilter();
    private HttpRequestToCurlSerializer httpRequestToCurlSerializer;
    private final org.mockserver.metrics.Metrics metrics;

    public HttpActionHandler(Configuration configuration, EventLoopGroup eventLoopGroup, HttpState httpStateHandler, List<ProxyConfiguration> proxyConfigurations, NettySslContextFactory nettySslContextFactory) {
        this.configuration = configuration;
        this.httpStateHandler = httpStateHandler;
        this.scheduler = httpStateHandler.getScheduler();
        this.mockServerLogger = httpStateHandler.getMockServerLogger();
        this.httpRequestToCurlSerializer = new HttpRequestToCurlSerializer(mockServerLogger);
        this.httpClient = new NettyHttpClient(configuration, mockServerLogger, eventLoopGroup, proxyConfigurations, true, nettySslContextFactory);
        this.metrics = new org.mockserver.metrics.Metrics(configuration);
    }

    /**
     * Dispatch an early-matched expectation before the request body has been received.
     * Supports {@link Action.Type#RESPONSE} and {@link Action.Type#ERROR} only; other action types
     * are rejected by {@code RequestMatchers.validateRespondBeforeBody} at expectation-add time.
     */
    public void processEarlyAction(final HttpRequest request, final Expectation expectation, final ChannelHandlerContext ctx, final ResponseWriter earlyResponseWriter, final boolean synchronous) {
        mockServerLogger.logEvent(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setCorrelationId(request.getLogCorrelationId())
                .setHttpRequest(request)
                .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                .setArguments(request)
        );

        final AtomicBoolean postProcessed = new AtomicBoolean(false);
        Runnable expectationPostProcessor = () -> {
            if (postProcessed.compareAndSet(false, true)) {
                httpStateHandler.postProcess(expectation);
            }
        };

        final Action action = expectation.getAction();
        switch (action.getType()) {
            case RESPONSE -> {
                // capture matchCount before scheduling to avoid race with concurrent requests
                final int capturedMatchCount = expectation.getMatchCount();
                // chaos: gate by the time-based outage window + apply degradation ramp (see effectiveChaos)
                final HttpChaosProfile effectiveChaos = effectiveChaos(expectation);
                scheduler.schedule(() -> handleAnyException(request, earlyResponseWriter, synchronous, action, () -> {
                    final HttpResponse response = getHttpResponseActionHandler().handle((HttpResponse) action);
                    // chaos: inject HTTP chaos faults on early mocked responses
                    writeResponseActionResponse(response, earlyResponseWriter, request, action, synchronous, expectation.getHttpRequest(), expectationPostProcessor, effectiveChaos, capturedMatchCount, ctx);
                }, expectationPostProcessor), synchronous);
            }
            case ERROR -> scheduler.schedule(() -> handleAnyException(request, earlyResponseWriter, synchronous, action, () -> {
                getHttpErrorActionHandler().handle((HttpError) action, ctx);
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXPECTATION_RESPONSE)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setHttpError((HttpError) action)
                        .setExpectationId(action.getExpectationId())
                        .setMessageFormat("returning error:{}for request:{}for action:{}from expectation:{}")
                        .setArguments(action, request, action, action.getExpectationId())
                );
                expectationPostProcessor.run();
            }, expectationPostProcessor), synchronous, combineWithGlobalDelay(action.getDelay()));
            default ->
                // Other action types are rejected at expectation-add time; nothing to dispatch here.
                expectationPostProcessor.run();
        }
    }

    public void processAction(final HttpRequest request, final ResponseWriter responseWriter, final ChannelHandlerContext ctx, Set<String> localAddresses, boolean proxyingRequest, final boolean synchronous) {
        if (request.getHeaders() == null || !request.getHeaders().containsEntry(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue())) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(RECEIVED_REQUEST)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                    .setArguments(request)
            );
        }

        CrudDispatcher crudDispatcher = httpStateHandler.getCrudDispatcher();
        HttpResponse crudResponse = crudDispatcher.dispatch(request);
        if (crudResponse != null) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(EXPECTATION_RESPONSE)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setHttpResponse(crudResponse)
                    .setMessageFormat("returning CRUD response:{}for request:{}")
                    .setArguments(crudResponse, request)
            );
            responseWriter.writeResponse(request, crudResponse, false);
            return;
        }

        final Expectation expectation = httpStateHandler.firstMatchingExpectation(request);
        final AtomicBoolean postProcessed = new AtomicBoolean(false);
        Runnable expectationPostProcessor = () -> {
            if (postProcessed.compareAndSet(false, true)) {
                httpStateHandler.postProcess(expectation);
                if (expectation != null && expectation.getAfterActions() != null) {
                    for (AfterAction afterAction : expectation.getAfterActions()) {
                        dispatchAfterAction(afterAction, request);
                    }
                }
            }
        };
        final boolean hasConfiguredRemoteProxy = isNotBlank(configuration.proxyRemoteHost()) && configuration.proxyRemotePort() != null;
        final boolean potentiallyHttpProxy = !proxyingRequest && (hasConfiguredRemoteProxy || (configuration.attemptToProxyIfNoMatchingExpectation() && !isEmpty(request.getFirstHeader(HOST.toString())) && !localAddresses.contains(request.getFirstHeader(HOST.toString())) && !NoProxyHostsUtils.isHostOnNoProxyList(request.getFirstHeader(HOST.toString()), configuration.noProxyHosts())));

        if (expectation != null && expectation.getAction() != null) {

            // steps-based dispatch supersedes beforeActions+primary when steps are configured
            if (expectation.getSteps() != null && !expectation.getSteps().isEmpty()) {
                final Expectation matchedExpectation = expectation;
                scheduler.submit(() -> {
                    if (runStepsPreResponder(matchedExpectation, request, responseWriter)) {
                        dispatchPrimaryAction(matchedExpectation, request, responseWriter, ctx, synchronous, () -> {
                            // post-process and dispatch post-responder steps (like after-actions)
                            if (postProcessed.compareAndSet(false, true)) {
                                httpStateHandler.postProcess(matchedExpectation);
                                dispatchPostResponderSteps(matchedExpectation, request);
                                if (matchedExpectation.getAfterActions() != null) {
                                    for (AfterAction afterAction : matchedExpectation.getAfterActions()) {
                                        dispatchAfterAction(afterAction, request);
                                    }
                                }
                            }
                        });
                    } else {
                        expectationPostProcessor.run();
                    }
                }, synchronous);
            } else {
                final List<AfterAction> beforeActions = expectation.getBeforeActions();
                if (beforeActions != null && !beforeActions.isEmpty()) {
                    // run before-actions ahead of the primary action; blocking before-actions may gate
                    // (fail-fast) the response. Wrapped in scheduler.submit so any blocking wait happens
                    // off the event loop (async) or inline (synchronous), mirroring forward-action threading.
                    final Expectation matchedExpectation = expectation;
                    scheduler.submit(() -> {
                        if (runBeforeActions(matchedExpectation, request, responseWriter)) {
                            dispatchPrimaryAction(matchedExpectation, request, responseWriter, ctx, synchronous, expectationPostProcessor);
                        } else {
                            // fail-fast abort: the 502 is the response, so still post-process to clear
                            // responseInProgress, remove exhausted expectations, and fire after-actions
                            // (idempotent via compareAndSet).
                            expectationPostProcessor.run();
                        }
                    }, synchronous);
                } else {
                    dispatchPrimaryAction(expectation, request, responseWriter, ctx, synchronous, expectationPostProcessor);
                }
            }

        } else if (CORSHeaders.isPreflightRequest(configuration, request) && (configuration.enableCORSForAPI() || configuration.enableCORSForAllResponses() || isControlPlanePreflight(request))) {

            responseWriter.writeResponse(request, OK);
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(INFO)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setMessageFormat("returning CORS response for OPTIONS request")
                );
            }

        } else if (handleProxyPass(request, responseWriter, synchronous)) {

            // handled by proxy pass

        } else if (proxyingRequest || potentiallyHttpProxy) {

            handleUnmatchedProxyForward(request, responseWriter, ctx, synchronous, potentiallyHttpProxy);

        } else {

            returnNotFound(responseWriter, request, null);

        }
    }

    /**
     * Whether an (unmatched) preflight targets a control-plane / dashboard endpoint, identified by
     * the {@code /mockserver} path prefix the dashboard always uses. Such preflights are answered
     * with a CORS response regardless of {@code enableCORSForAPI}, so the dashboard works
     * cross-origin (e.g. pointed at a different MockServer via its host/port fields) without
     * requiring users to enable CORS explicitly. Scoped to the prefix so unmatched OPTIONS on a
     * user's own mocked paths still fall through to normal matching / not-found handling.
     */
    private boolean isControlPlanePreflight(HttpRequest request) {
        return request.getPath() != null
            && request.getPath().getValue() != null
            && request.getPath().getValue().startsWith(org.mockserver.mock.HttpState.PATH_PREFIX);
    }

    /**
     * Dispatches the matched expectation's primary action to the appropriate per-type handler.
     * Extracted from {@link #processAction} so the high-level request flow stays readable; the
     * action-type switch and secondary-action fan-out live here.
     */
    private void dispatchPrimaryAction(final Expectation expectation, final HttpRequest request, final ResponseWriter responseWriter, final ChannelHandlerContext ctx, final boolean synchronous, final Runnable expectationPostProcessor) {
        // fire cross-protocol scenario transitions when this expectation has them
        fireCrossProtocolEvents(expectation, request);
        final Action action = expectation.getAction();
        // capture matchCount before scheduling to avoid race with concurrent requests
        final int capturedMatchCount = expectation.getMatchCount();
        // chaos: gate by the time-based outage window once per request and apply the
        // degradation ramp (relative to first match, via the controllable clock);
        // outside the window chaos is disabled (see effectiveChaos)
        final HttpChaosProfile effectiveChaos = effectiveChaos(expectation);
        // service-scoped chaos: when a matched FORWARD expectation carries no chaos of its own,
        // fall back to a host-scoped profile (keyed by the request Host header) registered via
        // PUT /mockserver/serviceChaos. An expectation-level chaos profile always takes precedence
        // (even when currently gated off by its outage window). The anonymous/unmatched proxy
        // fall-through path is intentionally not affected.
        final HttpChaosProfile forwardChaos = (effectiveChaos == null && expectation.getChaos() == null && action.getType().name().startsWith("FORWARD"))
            ? ServiceChaosRegistry.getInstance().get(request.getFirstHeader("host"))
            : effectiveChaos;
        switch (action.getType()) {
            case RESPONSE -> scheduler.schedule(() -> handleAnyException(request, responseWriter, synchronous, action, () -> {
                final HttpResponse response = getHttpResponseActionHandler().handle((HttpResponse) action);
                // chaos: inject HTTP chaos faults on mocked responses
                writeResponseActionResponse(response, responseWriter, request, action, synchronous, expectation.getHttpRequest(), expectationPostProcessor, effectiveChaos, capturedMatchCount, ctx);
            }, expectationPostProcessor), synchronous);
            case RESPONSE_TEMPLATE -> scheduler.schedule(() -> handleAnyException(request, responseWriter, synchronous, action, () -> {
                final HttpResponse response = getHttpResponseTemplateActionHandler().handle((HttpTemplate) action, request);
                // chaos: inject HTTP chaos faults on mocked responses
                writeResponseActionResponse(response, responseWriter, request, action, synchronous, expectation.getHttpRequest(), expectationPostProcessor, effectiveChaos, capturedMatchCount, ctx);
            }, expectationPostProcessor), synchronous, action.getDelay());
            case RESPONSE_CLASS_CALLBACK -> scheduler.schedule(() -> handleAnyException(request, responseWriter, synchronous, action, () -> {
                final HttpResponse response = getHttpResponseClassCallbackActionHandler().handle((HttpClassCallback) action, request);
                // chaos: inject HTTP chaos faults on mocked responses
                writeResponseActionResponse(response, responseWriter, request, action, synchronous, expectation.getHttpRequest(), expectationPostProcessor, effectiveChaos, capturedMatchCount, ctx);
            }, expectationPostProcessor), synchronous, action.getDelay());
            case RESPONSE_OBJECT_CALLBACK -> scheduler.schedule(() ->
                    getHttpResponseObjectCallbackActionHandler().handle(HttpActionHandler.this, (HttpObjectCallback) action, request, responseWriter, synchronous, expectationPostProcessor),
                synchronous, action.getDelay());
            // chaos: inject HTTP chaos faults on expectation-based forwarded responses (FORWARD, FORWARD_TEMPLATE,
            // FORWARD_CLASS_CALLBACK, FORWARD_REPLACE, FORWARD_VALIDATE). Deferred: FORWARD_OBJECT_CALLBACK has
            // its own write path and the unmatched/anonymous proxy-pass path.
            case FORWARD -> scheduler.schedule(() -> handleAnyException(request, responseWriter, synchronous, action, () -> {
                final HttpForwardActionResult responseFuture = getHttpForwardActionHandler().handle((HttpForward) action, request);
                writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous, expectationPostProcessor, forwardChaos, capturedMatchCount, ctx);
            }, expectationPostProcessor), synchronous, combineWithGlobalDelay(action.getDelay()));
            case FORWARD_TEMPLATE -> scheduler.schedule(() -> handleAnyException(request, responseWriter, synchronous, action, () -> {
                final HttpForwardActionResult responseFuture = getHttpForwardTemplateActionHandler().handle((HttpTemplate) action, request);
                writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous, expectationPostProcessor, forwardChaos, capturedMatchCount, ctx);
            }, expectationPostProcessor), synchronous, combineWithGlobalDelay(action.getDelay()));
            case FORWARD_CLASS_CALLBACK -> scheduler.schedule(() -> handleAnyException(request, responseWriter, synchronous, action, () -> {
                final HttpForwardActionResult responseFuture = getHttpForwardClassCallbackActionHandler().handle((HttpClassCallback) action, request);
                writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous, expectationPostProcessor, forwardChaos, capturedMatchCount, ctx);
            }, expectationPostProcessor), synchronous, combineWithGlobalDelay(action.getDelay()));
            // deferred: FORWARD_OBJECT_CALLBACK chaos injection — uses its own write path
            case FORWARD_OBJECT_CALLBACK -> scheduler.schedule(() ->
                    getHttpForwardObjectCallbackActionHandler().handle(HttpActionHandler.this, (HttpObjectCallback) action, request, responseWriter, synchronous, expectationPostProcessor),
                synchronous, combineWithGlobalDelay(action.getDelay()));
            case FORWARD_REPLACE -> scheduler.schedule(() -> handleAnyException(request, responseWriter, synchronous, action, () -> {
                final HttpForwardActionResult responseFuture = getHttpOverrideForwardedRequestCallbackActionHandler().handle((HttpOverrideForwardedRequest) action, request);
                writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous, expectationPostProcessor, forwardChaos, capturedMatchCount, ctx);
            }, expectationPostProcessor), synchronous, combineWithGlobalDelay(action.getDelay()));
            case FORWARD_VALIDATE -> scheduler.schedule(() -> handleAnyException(request, responseWriter, synchronous, action, () -> {
                final HttpForwardActionResult responseFuture = getHttpForwardValidateActionHandler().handle((HttpForwardValidateAction) action, request);
                writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous, expectationPostProcessor, forwardChaos, capturedMatchCount, ctx);
            }, expectationPostProcessor), synchronous, combineWithGlobalDelay(action.getDelay()));
            case FORWARD_WITH_FALLBACK -> scheduler.schedule(() -> handleAnyException(request, responseWriter, synchronous, action, () -> {
                final HttpForwardActionResult responseFuture = getHttpForwardWithFallbackActionHandler().handle((HttpForwardWithFallback) action, request);
                writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous, expectationPostProcessor, forwardChaos, capturedMatchCount, ctx);
            }, expectationPostProcessor), synchronous, combineWithGlobalDelay(action.getDelay()));
            case SSE_RESPONSE -> {
                if (ctx == null) {
                    writeResponseActionResponse(
                        response().withStatusCode(501).withBody("SSE streaming is not supported in WAR deployments"),
                        responseWriter, request, action, synchronous, null, expectationPostProcessor
                    );
                } else {
                    scheduler.schedule(() -> {
                        try {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(EXPECTATION_RESPONSE)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setExpectationId(action.getExpectationId())
                                    .setMessageFormat("returning SSE response for request:{}for action:{}from expectation:{}")
                                    .setArguments(request, action, action.getExpectationId())
                            );
                            getHttpSseResponseActionHandler().handle((HttpSseResponse) action, ctx, request);
                        } catch (Throwable throwable) {
                            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(WARN)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setMessageFormat(throwable.getMessage())
                                        .setThrowable(throwable)
                                );
                            }
                            ctx.close();
                        } finally {
                            expectationPostProcessor.run();
                        }
                    }, synchronous, combineWithGlobalDelay(action.getDelay()));
                }
            }
            case LLM_RESPONSE -> {
                HttpLlmResponse llmAction = (HttpLlmResponse) action;
                // Chaos: a probabilistic provider error short-circuits to a normal
                // (non-streaming) HTTP error response, even for a would-be stream.
                final HttpResponse chaosErrorResponse = getHttpLlmResponseActionHandler().chaosErrorResponseOrNull(llmAction);
                boolean isStreaming = chaosErrorResponse == null && llmAction.getCompletion() != null && Boolean.TRUE.equals(llmAction.getCompletion().getStreaming());
                if (isStreaming) {
                    if (ctx == null) {
                        writeResponseActionResponse(
                            response().withStatusCode(501).withBody("SSE streaming is not supported in WAR deployments"),
                            responseWriter, request, action, synchronous, null, expectationPostProcessor
                        );
                    } else {
                        scheduler.schedule(() -> {
                            try {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(EXPECTATION_RESPONSE)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setExpectationId(action.getExpectationId())
                                        .setMessageFormat("returning streaming LLM response for request:{}for action:{}from expectation:{}")
                                        .setArguments(request, action, action.getExpectationId())
                                );
                                java.util.List<SseEvent> sseEvents = getHttpLlmResponseActionHandler().handleStreaming(llmAction, request);
                                if (!sseEvents.isEmpty()
                                    && llmAction.getChaos() != null
                                    && (llmAction.getChaos().getTruncateMode() == org.mockserver.model.LlmChaosProfile.TruncateMode.MID_STREAM
                                    || Boolean.TRUE.equals(llmAction.getChaos().getMalformedSse()))) {
                                    metrics.increment(org.mockserver.metrics.Metrics.Name.LLM_CHAOS_INJECTED_COUNT);
                                }
                                org.mockserver.llm.StreamingFormat streamingFormat = getHttpLlmResponseActionHandler().streamingFormatFor(llmAction.getProvider());
                                String contentType;
                                switch (streamingFormat) {
                                    case NDJSON:
                                        contentType = "application/x-ndjson";
                                        break;
                                    case AWS_EVENT_STREAM:
                                        contentType = org.mockserver.llm.codec.BedrockEventStreamEncoder.CONTENT_TYPE;
                                        break;
                                    default:
                                        contentType = "text/event-stream";
                                        break;
                                }
                                HttpSseResponse sseResponse = HttpSseResponse.sseResponse()
                                    .withStatusCode(200)
                                    .withHeader("content-type", contentType)
                                    .withHeader("cache-control", "no-cache")
                                    .withEvents(sseEvents);
                                getHttpSseResponseActionHandler().handle(sseResponse, ctx, request, streamingFormat);
                            } catch (Throwable throwable) {
                                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                    mockServerLogger.logEvent(
                                        new LogEntry()
                                            .setType(WARN)
                                            .setLogLevel(Level.INFO)
                                            .setCorrelationId(request.getLogCorrelationId())
                                            .setHttpRequest(request)
                                            .setMessageFormat(throwable.getMessage())
                                            .setThrowable(throwable)
                                    );
                                }
                                ctx.close();
                            } finally {
                                expectationPostProcessor.run();
                            }
                        }, synchronous, combineWithGlobalDelay(action.getDelay()));
                    }
                } else {
                    scheduler.schedule(() -> {
                        try {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(EXPECTATION_RESPONSE)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setExpectationId(action.getExpectationId())
                                    .setMessageFormat("returning LLM response for request:{}for action:{}from expectation:{}")
                                    .setArguments(request, action, action.getExpectationId())
                            );
                            HttpResponse llmResponse = chaosErrorResponse != null
                                ? chaosErrorResponse
                                : getHttpLlmResponseActionHandler().handle(llmAction, request);
                            if (chaosErrorResponse != null) {
                                metrics.increment(org.mockserver.metrics.Metrics.Name.LLM_CHAOS_INJECTED_COUNT);
                            }
                            writeResponseActionResponse(llmResponse, responseWriter, request, action, synchronous, null, expectationPostProcessor);
                        } catch (Throwable throwable) {
                            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(WARN)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setMessageFormat(throwable.getMessage())
                                        .setThrowable(throwable)
                                );
                            }
                            expectationPostProcessor.run();
                        }
                    }, synchronous, combineWithGlobalDelay(action.getDelay()));
                }
            }
            case WEBSOCKET_RESPONSE -> {
                if (ctx == null) {
                    writeResponseActionResponse(
                        response().withStatusCode(501).withBody("WebSocket mocking is not supported in WAR deployments"),
                        responseWriter, request, action, synchronous, null, expectationPostProcessor
                    );
                } else {
                    scheduler.schedule(() -> {
                        try {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(EXPECTATION_RESPONSE)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setExpectationId(action.getExpectationId())
                                    .setMessageFormat("returning WebSocket response for request:{}for action:{}from expectation:{}")
                                    .setArguments(request, action, action.getExpectationId())
                            );
                            getHttpWebSocketResponseActionHandler().handle((HttpWebSocketResponse) action, ctx, request);
                        } catch (Throwable throwable) {
                            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(WARN)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setMessageFormat(throwable.getMessage())
                                        .setThrowable(throwable)
                                );
                            }
                            ctx.close();
                        } finally {
                            expectationPostProcessor.run();
                        }
                    }, synchronous, combineWithGlobalDelay(action.getDelay()));
                }
            }
            case GRPC_STREAM_RESPONSE -> {
                if (ctx == null && !(responseWriter instanceof GrpcStreamResponseWriter)) {
                    writeResponseActionResponse(
                        response().withStatusCode(501).withBody("gRPC streaming is not supported in WAR deployments"),
                        responseWriter, request, action, synchronous, null, expectationPostProcessor
                    );
                } else if (responseWriter instanceof GrpcStreamResponseWriter) {
                    // HTTP/3 path: a QUIC stream has no HTTP/2 frame codec, so delegate the
                    // server-streaming write to the transport-specific response writer (which
                    // emits initial HEADERS + DATA frames + trailing HEADERS over the QUIC stream).
                    scheduler.schedule(() -> {
                        try {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(EXPECTATION_RESPONSE)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setExpectationId(action.getExpectationId())
                                    .setMessageFormat("returning gRPC stream response over HTTP/3 for request:{}for action:{}from expectation:{}")
                                    .setArguments(request, action, action.getExpectationId())
                            );
                            ((GrpcStreamResponseWriter) responseWriter).writeGrpcStreamResponse((GrpcStreamResponse) action, request);
                        } catch (Throwable throwable) {
                            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(WARN)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setMessageFormat(throwable.getMessage())
                                        .setThrowable(throwable)
                                );
                            }
                        } finally {
                            expectationPostProcessor.run();
                        }
                    }, synchronous, combineWithGlobalDelay(action.getDelay()));
                } else {
                    scheduler.schedule(() -> {
                        try {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(EXPECTATION_RESPONSE)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setExpectationId(action.getExpectationId())
                                    .setMessageFormat("returning gRPC stream response for request:{}for action:{}from expectation:{}")
                                    .setArguments(request, action, action.getExpectationId())
                            );
                            getGrpcStreamResponseActionHandler().handle((GrpcStreamResponse) action, ctx, request);
                        } catch (Throwable throwable) {
                            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(WARN)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setMessageFormat(throwable.getMessage())
                                        .setThrowable(throwable)
                                );
                            }
                            ctx.close();
                        } finally {
                            expectationPostProcessor.run();
                        }
                    }, synchronous, combineWithGlobalDelay(action.getDelay()));
                }
            }
            case GRPC_BIDI_RESPONSE -> {
                if (ctx == null) {
                    writeResponseActionResponse(
                        response().withStatusCode(501).withBody("gRPC bidi streaming is not supported in WAR deployments"),
                        responseWriter, request, action, synchronous, null, expectationPostProcessor
                    );
                } else {
                    // The normal bidi flow is driven by GrpcBidiRouterHandler/GrpcBidiStreamHandler
                    // at the Netty layer when grpcBidiStreamingEnabled is on. If the action reaches
                    // HttpActionHandler (flag off or non-multiplex transport), respond with 501.
                    writeResponseActionResponse(
                        response().withStatusCode(501).withBody("gRPC bidi streaming requires the multiplex pipeline (grpcBidiStreamingEnabled=true)"),
                        responseWriter, request, action, synchronous, null, expectationPostProcessor
                    );
                }
            }
            case ERROR -> scheduler.schedule(() -> handleAnyException(request, responseWriter, synchronous, action, () -> {
                getHttpErrorActionHandler().handle((HttpError) action, ctx);
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXPECTATION_RESPONSE)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setHttpError((HttpError) action)
                        .setExpectationId(action.getExpectationId())
                        .setMessageFormat("returning error:{}for request:{}for action:{}from expectation:{}")
                        .setArguments(action, request, action, action.getExpectationId())
                );
                expectationPostProcessor.run();
            }, expectationPostProcessor), synchronous, combineWithGlobalDelay(action.getDelay()));
        }

        final List<Action> secondaryActions = expectation.getSecondaryActions();
        if (!secondaryActions.isEmpty()) {
            for (final Action secondaryAction : secondaryActions) {
                dispatchSecondaryAction(secondaryAction, request, synchronous);
            }
        }
    }

    /**
     * Forwards a request that matched no expectation through the (reverse or forward) proxy path:
     * loop-prevention, optional proxy authentication, the upstream call, and response/streaming/error
     * handling. Extracted from {@link #processAction} to keep the top-level dispatch chain readable.
     */
    private void handleUnmatchedProxyForward(final HttpRequest request, final ResponseWriter responseWriter, final ChannelHandlerContext ctx, final boolean synchronous, final boolean potentiallyHttpProxy) {
        if (request.getHeaders() != null && request.getHeaders().containsEntry(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue())) {

            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(TRACE)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setMessageFormat("received \"x-forwarded-by\" header caused by exploratory HTTP proxy or proxy loop - falling back to no proxy:{}")
                        .setArguments(request)
                );
            }
            returnNotFound(responseWriter, request, null);

        } else {

            String username = configuration.proxyAuthenticationUsername();
            String password = configuration.proxyAuthenticationPassword();
            // only authenticate potentiallyHttpProxy because other proxied requests should have already been authenticated (i.e. in CONNECT request)
            if (potentiallyHttpProxy && isNotBlank(username) && isNotBlank(password) &&
                !request.containsHeader(PROXY_AUTHORIZATION.toString(), "Basic " + Base64.encode(Unpooled.copiedBuffer(username + ':' + password, StandardCharsets.UTF_8), false).toString(StandardCharsets.US_ASCII))) {

                HttpResponse response = response()
                    .withStatusCode(PROXY_AUTHENTICATION_REQUIRED.code())
                    .withHeader(PROXY_AUTHENTICATE.toString(), "Basic realm=\"" + StringEscapeUtils.escapeJava(configuration.proxyAuthenticationRealm()) + "\", charset=\"UTF-8\"");
                responseWriter.writeResponse(request, response, false);
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(AUTHENTICATION_FAILED)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setHttpResponse(response)
                        .setExpectation(request, response)
                        .setMessageFormat("proxy authentication failed so returning response:{}for forwarded request:{}")
                        .setArguments(response, request)
                );

            } else {

                final InetSocketAddress remoteAddress = getRemoteAddressWithFallback(ctx);
                final HttpRequest clonedRequest = hopByHopHeaderFilter.onRequest(request).withHeader(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue());
                adjustHostHeaderForUnmatchedRequest(clonedRequest, remoteAddress);

                // validation proxy: request validation runs inside the scheduler (off the Netty event loop)
                // to avoid blocking I/O threads on cold-cache OpenAPI spec parsing / JSON-schema validation
                final boolean validationEnabled = isValidationProxyEnabled();
                scheduler.submit(() -> {
                    try {
                        // pre-flight request validation (enforce mode blocks with 400 before upstream call)
                        if (validationEnabled) {
                            HttpResponse rejectResponse = validateProxyRequest(request);
                            if (rejectResponse != null) {
                                responseWriter.writeResponse(request, rejectResponse, false);
                                return;
                            }
                        }

                        // breakpoint intercept: pause the request if breakpoints are enabled (async mode only).
                        // IMPORTANT: does NOT block any thread — the decision future's continuation runs
                        // asynchronously on the scheduler executor when the control-plane resolves it (or
                        // when the timeout auto-completes it). This avoids exhausting the scheduler pool
                        // and, via CallerRunsPolicy, the Netty event loop.
                        if (!synchronous && Boolean.TRUE.equals(configuration.breakpointEnabled())) {
                            org.mockserver.mock.breakpoint.BreakpointRegistry breakpointRegistry = org.mockserver.mock.breakpoint.BreakpointRegistry.getInstance();
                            org.mockserver.mock.breakpoint.PausedExchange pausedExchange = breakpointRegistry.pause(
                                request.getLogCorrelationId(), request, null, configuration
                            );
                            if (pausedExchange != null) {
                                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                    mockServerLogger.logEvent(
                                        new LogEntry()
                                            .setLogLevel(Level.INFO)
                                            .setCorrelationId(request.getLogCorrelationId())
                                            .setHttpRequest(request)
                                            .setMessageFormat("request paused at breakpoint, awaiting resolution for:{}")
                                            .setArguments(request)
                                    );
                                }
                                // Chain the forward-and-respond continuation onto the decision future
                                // asynchronously. The current scheduler worker thread returns immediately.
                                java.util.concurrent.Executor continuationExecutor = scheduler.getExecutorService() != null
                                    ? scheduler.getExecutorService()
                                    : Runnable::run;
                                pausedExchange.getDecisionFuture().thenAcceptAsync(decision -> {
                                    try {
                                        switch (decision.getAction()) {
                                            case ABORT:
                                                HttpResponse abortResponse = decision.getAbortResponse();
                                                if (abortResponse == null) {
                                                    abortResponse = response().withStatusCode(503).withReasonPhrase("Breakpoint Aborted");
                                                }
                                                responseWriter.writeResponse(request, abortResponse, false);
                                                return;
                                            case MODIFY:
                                                HttpRequest modified = decision.getModifiedRequest();
                                                HttpRequest modifiedToForward = hopByHopHeaderFilter.onRequest(modified)
                                                    .withHeader(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue());
                                                adjustHostHeaderForUnmatchedRequest(modifiedToForward, remoteAddress);
                                                executeUnmatchedForward(modifiedToForward, request, remoteAddress, potentiallyHttpProxy, validationEnabled, responseWriter);
                                                return;
                                            case CONTINUE:
                                            default:
                                                executeUnmatchedForward(clonedRequest, request, remoteAddress, potentiallyHttpProxy, validationEnabled, responseWriter);
                                                return;
                                        }
                                    } catch (SocketCommunicationException sce) {
                                        returnBadGateway(responseWriter, request, sce.getMessage());
                                    } catch (Throwable throwable) {
                                        returnBadGateway(responseWriter, request, "breakpoint continuation failed: " + throwable.getMessage());
                                    }
                                }, continuationExecutor);
                                // Return immediately — do NOT block the scheduler worker thread
                                return;
                            }
                        }

                        final HttpForwardActionResult responseFuture = new HttpForwardActionResult(clonedRequest, httpClient.sendRequest(clonedRequest, remoteAddress, potentiallyHttpProxy ? 1000 : configuration.socketConnectionTimeoutInMillis()), null, remoteAddress);
                        HttpResponse response = responseFuture.getHttpResponse().get(configuration.maxFutureTimeoutInMillis(), MILLISECONDS);
                        if (response == null) {
                            response = badGatewayResponse();
                        }
                        if (response.containsHeader(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue())) {
                            response.removeHeader(httpStateHandler.getUniqueLoopPreventionHeaderName());
                            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(NO_MATCH_RESPONSE)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setHttpResponse(notFoundResponse())
                                        .setMessageFormat(NO_MATCH_RESPONSE_NO_EXPECTATION_MESSAGE_FORMAT)
                                        .setArguments(request, response)
                                );
                            }
                            responseWriter.writeResponse(request, response, false);
                        } else if (response.getStreamingBody() != null) {
                            // Streaming response: write the head immediately and log
                            // the FORWARDED_REQUEST entry after the stream completes.
                            // Note: enforce mode cannot replace a streaming response since the body
                            // has already been written to the client — violations are logged (report-only).
                            final HttpResponse streamingResponse = response;
                            responseWriter.writeResponse(request, streamingResponse, false);
                            streamingResponse.getStreamingBody().addCompletionListener(() -> {
                                HttpResponse logResponse = streamingResponse.clone();
                                byte[] captured = streamingResponse.getStreamingBody().capturedBytes();
                                setCapturedStreamingBody(logResponse, captured);
                                attachStreamingHeaders(logResponse, streamingResponse.getStreamingBody());
                                // validation proxy: validate completed streaming response (report-only)
                                if (validationEnabled) {
                                    validateProxyResponse(request, logResponse, true);
                                }
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(FORWARDED_REQUEST)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setHttpResponse(logResponse)
                                        .setExpectation(request, logResponse)
                                        .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}")
                                        .setArguments(logResponse, request, httpRequestToCurlSerializer.toCurl(request, remoteAddress))
                                );
                            });
                        } else {
                            // validation proxy: validate non-streaming response (enforce mode returns 502)
                            if (validationEnabled) {
                                response = validateProxyResponse(request, response, false);
                            }
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(FORWARDED_REQUEST)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setHttpResponse(response)
                                    .setExpectation(request, response)
                                    .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}")
                                    .setArguments(response, request, httpRequestToCurlSerializer.toCurl(request, remoteAddress))
                            );
                            responseWriter.writeResponse(request, response, false);
                        }
                    } catch (SocketCommunicationException sce) {
                        returnBadGateway(responseWriter, request, sce.getMessage());
                    } catch (Throwable throwable) {
                        if (potentiallyHttpProxy && connectionException(throwable)) {
                            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setLogLevel(TRACE)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setMessageFormat("failed to connect to proxied socket due to exploratory HTTP proxy for:{}due to:{}falling back to no proxy")
                                        .setArguments(request, throwable.getCause())
                                );
                            }
                            returnBadGateway(responseWriter, request, "failed to connect to proxied socket due to exploratory HTTP proxy");
                        } else if (sslHandshakeException(throwable)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setLogLevel(Level.ERROR)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setMessageFormat("TLS handshake exception while proxying request{}to remote address{}with channel" + (ctx != null ? String.valueOf(ctx.channel()) : ""))
                                    .setArguments(request, remoteAddress)
                                    .setThrowable(throwable)
                                );
                            returnBadGateway(responseWriter, request, "TLS handshake exception while proxying request to remote address" + remoteAddress);
                        } else if (!connectionClosedException(throwable)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(EXCEPTION)
                                    .setLogLevel(Level.ERROR)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setMessageFormat(throwable.getMessage())
                                    .setThrowable(throwable)
                            );
                            returnBadGateway(responseWriter, request, "connection closed while proxying request to remote address" + remoteAddress);
                        } else {
                            returnBadGateway(responseWriter, request, throwable.getMessage());
                        }
                    }
                }, synchronous);

            }

        }
    }

    /**
     * Executes the actual HTTP forward for an unmatched proxy request and writes the response.
     * Extracted so the breakpoint async continuation can reuse the same forward+response logic
     * without duplicating the streaming/non-streaming/loop-prevention handling.
     *
     * @param requestToForward the (possibly modified) request to send upstream
     * @param originalRequest  the original inbound request (for logging and correlation)
     * @param remoteAddress    the resolved upstream address
     * @param potentiallyHttpProxy whether this is an exploratory HTTP proxy request
     * @param validationEnabled whether validation-proxy mode is active
     * @param responseWriter   the writer to send the response back to the client
     */
    private void executeUnmatchedForward(HttpRequest requestToForward, HttpRequest originalRequest,
                                         InetSocketAddress remoteAddress, boolean potentiallyHttpProxy,
                                         boolean validationEnabled, ResponseWriter responseWriter) {
        try {
            final HttpForwardActionResult responseFuture = new HttpForwardActionResult(
                requestToForward,
                httpClient.sendRequest(requestToForward, remoteAddress,
                    potentiallyHttpProxy ? 1000 : configuration.socketConnectionTimeoutInMillis()),
                null, remoteAddress
            );
            HttpResponse response = responseFuture.getHttpResponse().get(configuration.maxFutureTimeoutInMillis(), MILLISECONDS);
            if (response == null) {
                response = badGatewayResponse();
            }
            if (response.containsHeader(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue())) {
                response.removeHeader(httpStateHandler.getUniqueLoopPreventionHeaderName());
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(NO_MATCH_RESPONSE)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(originalRequest.getLogCorrelationId())
                            .setHttpRequest(originalRequest)
                            .setHttpResponse(notFoundResponse())
                            .setMessageFormat(NO_MATCH_RESPONSE_NO_EXPECTATION_MESSAGE_FORMAT)
                            .setArguments(originalRequest, response)
                    );
                }
                responseWriter.writeResponse(originalRequest, response, false);
            } else if (response.getStreamingBody() != null) {
                final HttpResponse streamingResponse = response;
                responseWriter.writeResponse(originalRequest, streamingResponse, false);
                streamingResponse.getStreamingBody().addCompletionListener(() -> {
                    HttpResponse logResponse = streamingResponse.clone();
                    byte[] captured = streamingResponse.getStreamingBody().capturedBytes();
                    setCapturedStreamingBody(logResponse, captured);
                    attachStreamingHeaders(logResponse, streamingResponse.getStreamingBody());
                    if (validationEnabled) {
                        validateProxyResponse(originalRequest, logResponse, true);
                    }
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(FORWARDED_REQUEST)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(originalRequest.getLogCorrelationId())
                            .setHttpRequest(originalRequest)
                            .setHttpResponse(logResponse)
                            .setExpectation(originalRequest, logResponse)
                            .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}")
                            .setArguments(logResponse, originalRequest, httpRequestToCurlSerializer.toCurl(originalRequest, remoteAddress))
                    );
                });
            } else {
                if (validationEnabled) {
                    response = validateProxyResponse(originalRequest, response, false);
                }
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(FORWARDED_REQUEST)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(originalRequest.getLogCorrelationId())
                        .setHttpRequest(originalRequest)
                        .setHttpResponse(response)
                        .setExpectation(originalRequest, response)
                        .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}")
                        .setArguments(response, originalRequest, httpRequestToCurlSerializer.toCurl(originalRequest, remoteAddress))
                );
                responseWriter.writeResponse(originalRequest, response, false);
            }
        } catch (SocketCommunicationException sce) {
            returnBadGateway(responseWriter, originalRequest, sce.getMessage());
        } catch (Throwable throwable) {
            if (potentiallyHttpProxy && connectionException(throwable)) {
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(TRACE)
                            .setCorrelationId(originalRequest.getLogCorrelationId())
                            .setMessageFormat("failed to connect to proxied socket due to exploratory HTTP proxy for:{}due to:{}falling back to no proxy")
                            .setArguments(originalRequest, throwable.getCause())
                    );
                }
                returnBadGateway(responseWriter, originalRequest, "failed to connect to proxied socket due to exploratory HTTP proxy");
            } else if (sslHandshakeException(throwable)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setCorrelationId(originalRequest.getLogCorrelationId())
                        .setHttpRequest(originalRequest)
                        .setMessageFormat("TLS handshake exception while proxying request{}to remote address{}")
                        .setArguments(originalRequest, remoteAddress)
                        .setThrowable(throwable)
                );
                returnBadGateway(responseWriter, originalRequest, "TLS handshake exception while proxying request to remote address" + remoteAddress);
            } else if (!connectionClosedException(throwable)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXCEPTION)
                        .setLogLevel(Level.ERROR)
                        .setCorrelationId(originalRequest.getLogCorrelationId())
                        .setHttpRequest(originalRequest)
                        .setMessageFormat(throwable.getMessage())
                        .setThrowable(throwable)
                );
                returnBadGateway(responseWriter, originalRequest, "connection closed while proxying request to remote address" + remoteAddress);
            } else {
                returnBadGateway(responseWriter, originalRequest, throwable.getMessage());
            }
        }
    }

    private boolean handleProxyPass(final HttpRequest request, final ResponseWriter responseWriter, final boolean synchronous) {
        List<ProxyPassMapping> mappings = configuration.proxyPassMappings();
        if (mappings == null || mappings.isEmpty() || request.getPath() == null) {
            return false;
        }
        String requestPath = request.getPath().getValue();
        if (requestPath == null) {
            return false;
        }
        for (ProxyPassMapping mapping : mappings) {
            if (requestPath.startsWith(mapping.getPathPrefix())) {
                String remainder = requestPath.substring(mapping.getPathPrefix().length());
                String targetPath = mapping.getTargetPath();
                String newPath;
                if (remainder.isEmpty()) {
                    newPath = targetPath.isEmpty() ? "/" : targetPath;
                } else if (remainder.startsWith("/") || targetPath.endsWith("/")) {
                    newPath = targetPath + remainder;
                } else {
                    newPath = targetPath + "/" + remainder;
                }
                HttpRequest clonedRequest = hopByHopHeaderFilter.onRequest(request);
                clonedRequest.withPath(newPath);
                clonedRequest.withSecure(mapping.isTargetSecure());

                if (!mapping.isPreserveHost() && configuration.forwardAdjustHostHeader()) {
                    boolean defaultPort = (mapping.isTargetSecure() && mapping.getTargetPort() == 443)
                        || (!mapping.isTargetSecure() && mapping.getTargetPort() == 80);
                    String hostHeader = defaultPort ? mapping.getTargetHost() : mapping.getTargetHost() + ":" + mapping.getTargetPort();
                    clonedRequest.replaceHeader(new Header("Host", hostHeader));
                }

                // validation proxy: request + response validation runs inside the scheduler
                // (off the Netty event loop) to avoid blocking I/O threads on cold-cache OpenAPI parsing
                final boolean validationEnabled = isValidationProxyEnabled();
                InetSocketAddress targetAddress = new InetSocketAddress(mapping.getTargetHost(), mapping.getTargetPort());
                scheduler.submit(() -> {
                    try {
                        // pre-flight request validation (enforce mode blocks with 400 before upstream call)
                        if (validationEnabled) {
                            HttpResponse rejectResponse = validateProxyRequest(request);
                            if (rejectResponse != null) {
                                responseWriter.writeResponse(request, rejectResponse, false);
                                return;
                            }
                        }

                        final HttpForwardActionResult responseFuture = new HttpForwardActionResult(clonedRequest, httpClient.sendRequest(clonedRequest, targetAddress), null, targetAddress);
                        HttpResponse response = responseFuture.getHttpResponse().get(configuration.maxFutureTimeoutInMillis(), MILLISECONDS);
                        if (response == null) {
                            response = badGatewayResponse();
                        }
                        if (response.getStreamingBody() != null) {
                            // Note: enforce mode cannot replace a streaming response since the body
                            // has already been written to the client — violations are logged (report-only).
                            final HttpResponse streamingResponse = response;
                            responseWriter.writeResponse(request, streamingResponse, false);
                            streamingResponse.getStreamingBody().addCompletionListener(() -> {
                                HttpResponse logResponse = streamingResponse.clone();
                                byte[] captured = streamingResponse.getStreamingBody().capturedBytes();
                                setCapturedStreamingBody(logResponse, captured);
                                attachStreamingHeaders(logResponse, streamingResponse.getStreamingBody());
                                // validation proxy: validate completed streaming response (report-only)
                                if (validationEnabled) {
                                    validateProxyResponse(request, logResponse, true);
                                }
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(FORWARDED_REQUEST)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setHttpResponse(logResponse)
                                        .setExpectation(request, logResponse)
                                        .setMessageFormat("returning response:{}for proxy pass forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}")
                                        .setArguments(logResponse, request, httpRequestToCurlSerializer.toCurl(request, targetAddress))
                                );
                            });
                        } else {
                            // validation proxy: validate non-streaming response (enforce mode returns 502)
                            if (validationEnabled) {
                                response = validateProxyResponse(request, response, false);
                            }
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(FORWARDED_REQUEST)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setHttpResponse(response)
                                    .setExpectation(request, response)
                                    .setMessageFormat("returning response:{}for proxy pass forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}")
                                    .setArguments(response, request, httpRequestToCurlSerializer.toCurl(request, targetAddress))
                            );
                            responseWriter.writeResponse(request, response, false);
                        }
                    } catch (Throwable throwable) {
                        returnBadGateway(responseWriter, request, "proxy pass forwarding failed for " + mapping.getTargetUri() + ": " + throwable.getMessage());
                    }
                }, synchronous);
                return true;
            }
        }
        return false;
    }

    /**
     * If the matched expectation carries cross-protocol scenario triggers,
     * fire the HTTP_REQUEST event so registered listeners can advance
     * scenario state.
     */
    private void fireCrossProtocolEvents(Expectation expectation, HttpRequest request) {
        if (expectation.getCrossProtocolScenarios() != null && !expectation.getCrossProtocolScenarios().isEmpty()) {
            String path = request.getPath() != null ? request.getPath().getValue() : "/";
            CrossProtocolEventBus.getInstance().fire(CrossProtocolTrigger.HTTP_REQUEST, path);
        }
    }

    private void handleAnyException(HttpRequest request, ResponseWriter responseWriter, boolean synchronous, Action action, Runnable processAction, Runnable postProcessor) {
        try {
            processAction.run();
        } catch (Throwable throwable) {
            writeResponseActionResponse(notFoundResponse(), responseWriter, request, action, synchronous, null, postProcessor);
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(WARN)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setMessageFormat(throwable.getMessage())
                        .setThrowable(throwable)
                );
            }
        }
    }

    private void dispatchSecondaryAction(final Action secondaryAction, final HttpRequest request, final boolean synchronous) {
        scheduler.submitAsync(() -> {
            try {
                switch (secondaryAction.getType()) {
                    case RESPONSE -> getHttpResponseActionHandler().handle((HttpResponse) secondaryAction);
                    case RESPONSE_TEMPLATE -> getHttpResponseTemplateActionHandler().handle((HttpTemplate) secondaryAction, request);
                    case RESPONSE_CLASS_CALLBACK -> getHttpResponseClassCallbackActionHandler().handle((HttpClassCallback) secondaryAction, request);
                    case RESPONSE_OBJECT_CALLBACK -> {
                        String clientId = ((HttpObjectCallback) secondaryAction).getClientId();
                        if (LocalCallbackRegistry.responseClientExists(clientId)) {
                            LocalCallbackRegistry.retrieveResponseCallback(clientId).handle(request);
                        }
                    }
                    case FORWARD -> {
                        HttpForwardActionResult result = getHttpForwardActionHandler().handle((HttpForward) secondaryAction, request);
                        logForwardResultAsync(result, request, secondaryAction);
                    }
                    case FORWARD_TEMPLATE -> {
                        HttpForwardActionResult result = getHttpForwardTemplateActionHandler().handle((HttpTemplate) secondaryAction, request);
                        logForwardResultAsync(result, request, secondaryAction);
                    }
                    case FORWARD_CLASS_CALLBACK -> {
                        HttpForwardActionResult result = getHttpForwardClassCallbackActionHandler().handle((HttpClassCallback) secondaryAction, request);
                        logForwardResultAsync(result, request, secondaryAction);
                    }
                    case FORWARD_OBJECT_CALLBACK -> {
                        String clientId = ((HttpObjectCallback) secondaryAction).getClientId();
                        if (LocalCallbackRegistry.forwardClientExists(clientId)) {
                            HttpRequest callbackRequest = LocalCallbackRegistry.retrieveForwardCallback(clientId).handle(request);
                            if (callbackRequest != null) {
                                httpClient.sendRequest(callbackRequest)
                                    .whenComplete((response, throwable) -> {
                                        if (throwable != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                            mockServerLogger.logEvent(
                                                new LogEntry()
                                                    .setType(WARN)
                                                    .setLogLevel(Level.INFO)
                                                    .setCorrelationId(request.getLogCorrelationId())
                                                    .setHttpRequest(request)
                                                    .setMessageFormat("secondary forward object callback failed - " + throwable.getMessage())
                                                    .setThrowable(throwable)
                                            );
                                        }
                                    });
                            }
                        }
                    }
                    case FORWARD_REPLACE -> {
                        HttpForwardActionResult result = getHttpOverrideForwardedRequestCallbackActionHandler().handle((HttpOverrideForwardedRequest) secondaryAction, request);
                        logForwardResultAsync(result, request, secondaryAction);
                    }
                    case FORWARD_VALIDATE -> {
                        HttpForwardActionResult result = getHttpForwardValidateActionHandler().handle((HttpForwardValidateAction) secondaryAction, request);
                        logForwardResultAsync(result, request, secondaryAction);
                    }
                    case FORWARD_WITH_FALLBACK -> {
                        HttpForwardActionResult result = getHttpForwardWithFallbackActionHandler().handle((HttpForwardWithFallback) secondaryAction, request);
                        logForwardResultAsync(result, request, secondaryAction);
                    }
                    case ERROR -> { }
                    default -> { }
                }
            } catch (Exception e) {
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(WARN)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("exception handling secondary action " + secondaryAction.getType() + " - " + e.getMessage())
                            .setThrowable(e)
                    );
                }
            }
        }, secondaryAction.getDelay());
    }

    private void logForwardResultAsync(HttpForwardActionResult result, HttpRequest request, Action action) {
        if (result != null && result.getHttpResponse() != null) {
            result.getHttpResponse().whenComplete((response, throwable) -> {
                if (throwable != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(WARN)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("secondary forward action " + action.getType() + " failed - " + throwable.getMessage())
                            .setThrowable(throwable)
                    );
                }
            });
        }
    }

    private void dispatchAfterAction(final AfterAction afterAction, final HttpRequest request) {
        dispatchSideAction(afterAction, request, "after-action");
    }

    /**
     * Fire-and-forget dispatch of a side-effect action (used by after-actions and by
     * non-blocking before-actions). The side-effect's response, if any, is discarded; failures are
     * logged but never propagated to the client. {@code label} only flavours the log messages.
     */
    private void dispatchSideAction(final AfterAction action, final HttpRequest request, final String label) {
        scheduler.submitAsync(() -> {
            try {
                if (action.getHttpRequest() != null) {
                    // Resolve OpenAPI runtime expressions (no-op when none present)
                    HttpRequest callbackRequest = OpenApiRuntimeExpressionResolver.resolve(
                        action.getHttpRequest(), request
                    );
                    httpClient.sendRequest(callbackRequest)
                        .whenComplete((response, throwable) -> {
                            if (throwable != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(WARN)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setMessageFormat(label + " webhook failed for request{} - " + throwable.getMessage())
                                        .setArguments(callbackRequest)
                                        .setThrowable(throwable)
                                );
                            }
                        });
                } else if (action.getHttpClassCallback() != null) {
                    getHttpResponseClassCallbackActionHandler().handle(action.getHttpClassCallback(), request);
                } else if (action.getHttpObjectCallback() != null) {
                    HttpObjectCallback callback = action.getHttpObjectCallback();
                    callback.withActionType(Action.Type.RESPONSE_OBJECT_CALLBACK);
                    String clientId = callback.getClientId();
                    if (LocalCallbackRegistry.responseClientExists(clientId)) {
                        LocalCallbackRegistry.retrieveResponseCallback(clientId).handle(request);
                    }
                }
            } catch (Exception e) {
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(WARN)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("exception dispatching " + label + " - " + e.getMessage())
                            .setThrowable(e)
                    );
                }
            }
        }, action.getDelay());
    }

    /**
     * Runs an expectation's before-actions ahead of its primary action.
     *
     * <p>Each before-action is either <em>blocking</em> (default) — the response waits for it to
     * complete — or non-blocking (started but not waited for). Only HTTP-request (webhook)
     * before-actions can be awaited; class/object-callback before-actions are always dispatched
     * fire-and-forget. When a blocking webhook fails or times out its {@code failurePolicy}
     * decides the outcome: {@link FailurePolicy#FAIL_FAST} writes a 502 and aborts (returns
     * {@code false}); {@link FailurePolicy#BEST_EFFORT} (the default) logs and continues.</p>
     *
     * @return {@code true} to proceed to the primary action, {@code false} if a fail-fast
     * before-action already wrote an error response.
     */
    private boolean runBeforeActions(final Expectation expectation, final HttpRequest request, final ResponseWriter responseWriter) {
        for (AfterAction beforeAction : expectation.getBeforeActions()) {
            final boolean blocking = beforeAction.getBlocking() == null || beforeAction.getBlocking();
            final FailurePolicy failurePolicy = beforeAction.getFailurePolicy() == null
                ? FailurePolicy.BEST_EFFORT
                : beforeAction.getFailurePolicy();

            if (!blocking || beforeAction.getHttpRequest() == null) {
                // non-blocking, or a callback before-action (no awaitable result in increment 1):
                // dispatch fire-and-forget, started before the response
                if (blocking && beforeAction.getHttpRequest() == null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(WARN)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("ignoring blocking=true on a callback before-action - only httpRequest (webhook) before-actions can block the response; dispatching fire-and-forget")
                    );
                }
                dispatchSideAction(beforeAction, request, "before-action");
                continue;
            }

            // blocking webhook before-action: send and wait, honouring the optional timeout
            final HttpRequest callbackRequest = OpenApiRuntimeExpressionResolver.resolve(beforeAction.getHttpRequest(), request);
            final long timeoutMillis = beforeAction.getTimeout() != null
                ? beforeAction.getTimeout().getTimeUnit().toMillis(beforeAction.getTimeout().getValue())
                : configuration.maxSocketTimeoutInMillis();
            try {
                httpClient.sendRequest(callbackRequest, timeoutMillis, MILLISECONDS);
            } catch (Exception e) {
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(WARN)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("blocking before-action webhook failed for request{} - " + e.getMessage())
                            .setArguments(callbackRequest)
                            .setThrowable(e)
                    );
                }
                if (failurePolicy == FailurePolicy.FAIL_FAST) {
                    responseWriter.writeResponse(request, badGatewayResponse().withBody("before-action failed: " + e.getMessage()), false);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Runs the pre-responder steps from the expectation's steps list. Each pre-responder step
     * is a side-effect (webhook/callback/forward) that runs before the responder. Follows the
     * same blocking/timeout/failurePolicy semantics as {@link #runBeforeActions}.
     *
     * @return {@code true} to proceed to the responder step, {@code false} if a fail-fast
     * step already wrote an error response
     */
    private boolean runStepsPreResponder(final Expectation expectation, final HttpRequest request, final ResponseWriter responseWriter) {
        List<ExpectationStep> preSteps = expectation.getPreResponderSteps();
        for (ExpectationStep step : preSteps) {
            // Convert step to an AfterAction-like dispatch: reuse the same blocking/timeout/failurePolicy logic
            final boolean blocking = step.getBlocking() == null || step.getBlocking();
            final FailurePolicy failurePolicy = step.getFailurePolicy() == null
                ? FailurePolicy.BEST_EFFORT
                : step.getFailurePolicy();

            if (!blocking || step.getHttpRequest() == null) {
                // non-blocking, or a callback/forward step: dispatch fire-and-forget
                if (blocking && step.getHttpRequest() == null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(WARN)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("ignoring blocking=true on a non-webhook step - only httpRequest (webhook) steps can block the response; dispatching fire-and-forget")
                    );
                }
                dispatchStepSideEffect(step, request);
                continue;
            }

            // blocking webhook step: send and wait
            final HttpRequest callbackRequest = OpenApiRuntimeExpressionResolver.resolve(step.getHttpRequest(), request);
            final long timeoutMillis = step.getTimeout() != null
                ? step.getTimeout().getTimeUnit().toMillis(step.getTimeout().getValue())
                : configuration.maxSocketTimeoutInMillis();
            try {
                httpClient.sendRequest(callbackRequest, timeoutMillis, MILLISECONDS);
            } catch (Exception e) {
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(WARN)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("blocking step webhook failed for request{} - " + e.getMessage())
                            .setArguments(callbackRequest)
                            .setThrowable(e)
                    );
                }
                if (failurePolicy == FailurePolicy.FAIL_FAST) {
                    responseWriter.writeResponse(request, badGatewayResponse().withBody("step failed: " + e.getMessage()), false);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Dispatches post-responder steps (steps that come after the responder in the list)
     * as fire-and-forget side-effects, similar to after-actions.
     */
    private void dispatchPostResponderSteps(final Expectation expectation, final HttpRequest request) {
        List<ExpectationStep> postSteps = expectation.getPostResponderSteps();
        for (ExpectationStep step : postSteps) {
            dispatchStepSideEffect(step, request);
        }
    }

    /**
     * Dispatches a single step as a fire-and-forget side-effect. Supports webhook
     * (httpRequest), class callback, object callback, forward, and forward-replace targets.
     */
    private void dispatchStepSideEffect(final ExpectationStep step, final HttpRequest request) {
        scheduler.submitAsync(() -> {
            try {
                if (step.getHttpRequest() != null) {
                    HttpRequest callbackRequest = OpenApiRuntimeExpressionResolver.resolve(
                        step.getHttpRequest(), request
                    );
                    httpClient.sendRequest(callbackRequest)
                        .whenComplete((response, throwable) -> {
                            if (throwable != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(WARN)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setMessageFormat("step webhook failed for request{} - " + throwable.getMessage())
                                        .setArguments(callbackRequest)
                                        .setThrowable(throwable)
                                );
                            }
                        });
                } else if (step.getHttpClassCallback() != null) {
                    getHttpResponseClassCallbackActionHandler().handle(step.getHttpClassCallback(), request);
                } else if (step.getHttpObjectCallback() != null) {
                    HttpObjectCallback callback = step.getHttpObjectCallback();
                    callback.withActionType(Action.Type.RESPONSE_OBJECT_CALLBACK);
                    String clientId = callback.getClientId();
                    if (LocalCallbackRegistry.responseClientExists(clientId)) {
                        LocalCallbackRegistry.retrieveResponseCallback(clientId).handle(request);
                    }
                } else if (step.getHttpForward() != null) {
                    getHttpForwardActionHandler().handle(step.getHttpForward(), request);
                } else if (step.getHttpOverrideForwardedRequest() != null) {
                    getHttpOverrideForwardedRequestCallbackActionHandler().handle(step.getHttpOverrideForwardedRequest(), request);
                }
            } catch (Exception e) {
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(WARN)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("exception dispatching step side-effect - " + e.getMessage())
                            .setThrowable(e)
                    );
                }
            }
        }, step.getDelay());
    }

    void writeResponseActionResponse(final HttpResponse response, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous) {
        writeResponseActionResponse(response, responseWriter, request, action, synchronous, null, null);
    }

    void writeResponseActionResponse(final HttpResponse response, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous, final RequestDefinition requestDefinition) {
        writeResponseActionResponse(response, responseWriter, request, action, synchronous, requestDefinition, null);
    }

    void writeResponseActionResponse(final HttpResponse response, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous, final RequestDefinition requestDefinition, final Runnable postProcessor) {
        writeResponseActionResponse(response, responseWriter, request, action, synchronous, requestDefinition, postProcessor, null, 0);
    }

    /**
     * Returns {@code true} when the chaos profile's drop-connection fault should
     * fire — i.e. the connection should be dropped without sending any response.
     * Uses a derived seed ({@code seed ^ 0x44524F50L}) so the drop draw is
     * independent of (but reproducible alongside) the error draw.
     *
     * @param chaos      the chaos profile (may be null)
     * @param matchCount 1-based match count from the expectation; used for count-window gating
     */
    boolean shouldDropConnection(final HttpChaosProfile chaos, int matchCount) {
        return chaos != null
            && chaos.getDropConnectionProbability() != null
            && chaos.countWindowEligible(matchCount)
            && ChaosProbability.shouldInject(
                chaos.getDropConnectionProbability(),
                chaos.getSeed() == null ? null : chaos.getSeed() ^ 0x44524F50L
            );
    }

    /**
     * Shared helper: builds the synthetic chaos error response when the chaos
     * profile's error injection should fire, or returns {@code null} when no
     * error should be injected (probability miss, no errorStatus, null chaos,
     * or matchCount outside the count window).
     * Used by both mocked-response and forwarded-response chaos paths.
     *
     * @param chaos      the chaos profile (may be null)
     * @param matchCount 1-based match count from the expectation; used for count-window gating
     */
    HttpResponse chaosErrorResponseOrNull(final HttpChaosProfile chaos, int matchCount) {
        if (chaos == null || chaos.getErrorStatus() == null
            || !chaos.countWindowEligible(matchCount)
            || !ChaosProbability.shouldInject(chaos.getErrorProbability(), chaos.getSeed())) {
            return null;
        }
        HttpResponse errorResponse = response()
            .withStatusCode(chaos.getErrorStatus())
            .withHeader("content-type", "application/json")
            .withBody("{\"error\":{\"type\":\"chaos_injected\",\"message\":\"injected HTTP chaos error\"}}");
        if (chaos.getRetryAfter() != null && !chaos.getRetryAfter().isEmpty()) {
            errorResponse.withHeader("Retry-After", chaos.getRetryAfter());
        }
        return errorResponse;
    }

    /**
     * Resolves the chaos profile to apply to a request: the expectation's profile
     * gated by the time-based outage window, and — when {@code degradationRampMillis}
     * is set — a gradually-degraded copy whose {@code errorProbability} /
     * {@code dropConnectionProbability} are scaled by the ramp factor (0 at first
     * match, rising to full over the ramp window). Returns {@code null} when there is
     * no chaos profile or the request is outside the outage window. Uses the
     * controllable clock, so both the outage window and the degradation ramp are
     * deterministic under clock freeze/advance.
     */
    HttpChaosProfile effectiveChaos(final Expectation expectation) {
        final HttpChaosProfile chaos = expectation.getChaos();
        if (chaos == null) {
            return null;
        }
        final long firstMatch = expectation.getChaosFirstMatchEpochMillis();
        final long now = org.mockserver.time.TimeService.currentTimeMillis();
        if (!chaos.timeWindowEligible(firstMatch, now)) {
            return null;
        }
        if (chaos.getDegradationRampMillis() == null) {
            return chaos;
        }
        final double f = chaos.degradationFactor(firstMatch, now);
        return chaos.copy()
            .withErrorProbability(chaos.getErrorProbability() != null ? chaos.getErrorProbability() * f : null)
            .withDropConnectionProbability(chaos.getDropConnectionProbability() != null ? chaos.getDropConnectionProbability() * f : null)
            .withDegradationRampMillis(null);
    }

    /**
     * Builds the synthetic quota-exceeded response when the chaos profile's stateful
     * request quota ({@code quotaName} + {@code quotaLimit} + {@code quotaWindowMillis})
     * is exceeded for the current fixed window, or returns {@code null} when the quota
     * is not fully configured, the count window is not eligible, or the request is
     * still within the quota. Counts every eligible matched request against the named
     * quota in {@link HttpQuotaRegistry}.
     *
     * @param chaos      the chaos profile (may be null)
     * @param matchCount 1-based match count; used for count-window gating
     */
    HttpResponse quotaErrorResponseOrNull(final HttpChaosProfile chaos, int matchCount) {
        if (chaos == null || chaos.getQuotaName() == null || chaos.getQuotaLimit() == null || chaos.getQuotaWindowMillis() == null
            || !chaos.countWindowEligible(matchCount)) {
            return null;
        }
        boolean allowed = HttpQuotaRegistry.getInstance()
            .tryAcquire(chaos.getQuotaName(), chaos.getQuotaLimit(), chaos.getQuotaWindowMillis());
        if (allowed) {
            return null;
        }
        int status = chaos.getQuotaErrorStatus() != null ? chaos.getQuotaErrorStatus() : 429;
        HttpResponse errorResponse = response()
            .withStatusCode(status)
            .withHeader("content-type", "application/json")
            .withBody("{\"error\":{\"type\":\"quota_exceeded\",\"message\":\"HTTP request quota exceeded\"}}");
        if (chaos.getRetryAfter() != null && !chaos.getRetryAfter().isEmpty()) {
            errorResponse.withHeader("Retry-After", chaos.getRetryAfter());
        }
        return errorResponse;
    }

    // Appended to a body to make it malformed; an unterminated JSON object so any
    // JSON payload becomes unparseable and any other payload gains clear garbage.
    private static final byte[] MALFORMED_BODY_SUFFIX = "{\"__chaos_malformed__\":".getBytes(StandardCharsets.UTF_8);

    /**
     * Applies "real response" chaos to a non-error response: body corruption
     * ({@code truncateBodyAtFraction} and/or {@code malformedBody}) and/or a slow
     * dribbled response ({@code slowResponseChunkSize} + {@code slowResponseChunkDelay}).
     * Returns the response unchanged when none of those fields is set, the count
     * window is not eligible, or the response is streaming (streaming bodies are
     * out of scope — the LLM response path has its own mid-stream truncation).
     * <ul>
     *   <li>Truncation keeps a leading fraction of the body bytes; malformed-body
     *       appends a broken-JSON fragment. The clone preserves the original
     *       content-type and drops any stale {@code Content-Length}.</li>
     *   <li>Slow response sets {@code chunkSize}/{@code chunkDelay} on a copy of the
     *       connection options so {@code NettyResponseWriter} dribbles the body in
     *       chunks (chunked transfer-encoding, so {@code Content-Length} is dropped).</li>
     * </ul>
     *
     * @param chaos      the chaos profile (may be null)
     * @param matchCount 1-based match count; used for count-window gating
     */
    HttpResponse applyResponseChaos(final HttpResponse response, final HttpChaosProfile chaos, int matchCount) {
        if (response == null || chaos == null || !chaos.countWindowEligible(matchCount)) {
            return response;
        }
        // GraphQL error envelope: when graphqlErrors is true, build a structured GraphQL
        // error body and set status 200. This takes precedence over truncate/malformed body
        // corruption because the envelope IS the intended body -- truncating or appending
        // garbage to it would defeat the purpose of simulating a realistic GraphQL error.
        // Slow-response (dribble) still composes with the GraphQL envelope since it only
        // affects delivery timing, not body content.
        final boolean graphql = Boolean.TRUE.equals(chaos.getGraphqlErrors());
        final Double fraction = chaos.getTruncateBodyAtFraction();
        final boolean malformed = Boolean.TRUE.equals(chaos.getMalformedBody());
        // When graphqlErrors is set, skip truncate/malformed body corruption
        final boolean corruptBody = !graphql && (fraction != null || malformed);
        final boolean slow = chaos.getSlowResponseChunkSize() != null && chaos.getSlowResponseChunkDelay() != null;
        if (!corruptBody && !slow && !graphql) {
            return response;
        }
        if (response.getStreamingBody() != null) {
            return response;
        }
        HttpResponse out = response.clone();
        if (graphql) {
            String envelopeJson = buildGraphqlErrorEnvelope(chaos, response);
            out.withStatusCode(200);
            out.withBody(envelopeJson);
            out.replaceHeader("content-type", "application/json");
            out.removeHeader("content-length");
            org.mockserver.metrics.Metrics.incrementHttpChaosInjected("graphql");
        } else if (corruptBody) {
            // getBodyAsRawBytes() returns an empty array (never null) when there is no body
            byte[] corrupted = response.getBodyAsRawBytes();
            if (fraction != null) {
                // fraction is validated to [0.0, 1.0] by withTruncateBodyAtFraction, so
                // keep is always within [0, corrupted.length]
                int keep = (int) Math.floor(corrupted.length * fraction);
                corrupted = java.util.Arrays.copyOf(corrupted, keep);
                org.mockserver.metrics.Metrics.incrementHttpChaosInjected("truncate");
            }
            if (malformed) {
                byte[] combined = java.util.Arrays.copyOf(corrupted, corrupted.length + MALFORMED_BODY_SUFFIX.length);
                System.arraycopy(MALFORMED_BODY_SUFFIX, 0, combined, corrupted.length, MALFORMED_BODY_SUFFIX.length);
                corrupted = combined;
                org.mockserver.metrics.Metrics.incrementHttpChaosInjected("malformed");
            }
            String contentType = response.getFirstHeader("content-type");
            if (!isNotBlank(contentType) && response.getBody() != null) {
                contentType = response.getBody().getContentType();
            }
            out.withBody(corrupted);
            if (isNotBlank(contentType)) {
                out.replaceHeader("content-type", contentType);
            }
            out.removeHeader("content-length");
        }
        if (slow) {
            out.withConnectionOptions(connectionOptionsWithChunking(response.getConnectionOptions(), chaos.getSlowResponseChunkSize(), chaos.getSlowResponseChunkDelay()));
            // chunked transfer-encoding is used when chunkSize is set, so any explicit
            // Content-Length would conflict — drop it and let the encoder chunk
            out.removeHeader("content-length");
            org.mockserver.metrics.Metrics.incrementHttpChaosInjected("slow");
        }
        return out;
    }

    /**
     * Builds the JSON body for a GraphQL error envelope. Uses Jackson ObjectMapper
     * so the message and code strings are properly escaped.
     *
     * @param chaos    the chaos profile with graphql fields
     * @param response the original response (used to attempt data preservation when graphqlNullifyData=false)
     * @return the JSON string for the GraphQL error envelope
     */
    private String buildGraphqlErrorEnvelope(final HttpChaosProfile chaos, final HttpResponse response) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = org.mockserver.serialization.ObjectMapperFactory.createObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode();

            // data: null (default) or the original body JSON when graphqlNullifyData=false
            boolean nullifyData = !Boolean.FALSE.equals(chaos.getGraphqlNullifyData());
            if (nullifyData) {
                root.putNull("data");
            } else {
                // attempt to preserve original body as JSON data value
                byte[] bodyBytes = response.getBodyAsRawBytes();
                if (bodyBytes != null && bodyBytes.length > 0) {
                    try {
                        com.fasterxml.jackson.databind.JsonNode originalData = mapper.readTree(bodyBytes);
                        root.set("data", originalData);
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        // original body is not valid JSON — fall back to data:null
                        root.putNull("data");
                    }
                } else {
                    root.putNull("data");
                }
            }

            // errors array with a single error object
            com.fasterxml.jackson.databind.node.ArrayNode errorsArray = root.putArray("errors");
            com.fasterxml.jackson.databind.node.ObjectNode errorObj = errorsArray.addObject();
            String message = chaos.getGraphqlErrorMessage();
            errorObj.put("message", message != null ? message : "simulated GraphQL error");

            // extensions.code only when graphqlErrorCode is set
            if (chaos.getGraphqlErrorCode() != null) {
                com.fasterxml.jackson.databind.node.ObjectNode extensions = errorObj.putObject("extensions");
                extensions.put("code", chaos.getGraphqlErrorCode());
            }

            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            // fallback: hand-build a minimal envelope (should never happen with Jackson)
            return "{\"data\":null,\"errors\":[{\"message\":\"simulated GraphQL error\"}]}";
        }
    }

    /**
     * Returns a fresh {@link ConnectionOptions} carrying the chaos chunk settings,
     * copying any other fields from {@code src} so the original (shared) response
     * connection options are not mutated.
     */
    private ConnectionOptions connectionOptionsWithChunking(ConnectionOptions src, Integer chunkSize, Delay chunkDelay) {
        ConnectionOptions out = ConnectionOptions.connectionOptions();
        if (src != null) {
            out.withSuppressContentLengthHeader(src.getSuppressContentLengthHeader())
                .withContentLengthHeaderOverride(src.getContentLengthHeaderOverride())
                .withSuppressConnectionHeader(src.getSuppressConnectionHeader())
                .withKeepAliveOverride(src.getKeepAliveOverride())
                .withCloseSocket(src.getCloseSocket())
                .withCloseSocketDelay(src.getCloseSocketDelay());
        }
        return out.withChunkSize(chunkSize).withChunkDelay(chunkDelay);
    }

    /**
     * Core response-writing choke point. When a non-null {@code chaos} profile is provided,
     * HTTP chaos injection is applied before the response is written:
     * <ol>
     *   <li><b>Error injection</b> — if the chaos probability fires and {@code errorStatus} is set,
     *       the mocked response is replaced with a synthetic error response. When error injection
     *       fires, the original response's action delay is discarded (only chaos latency + global
     *       delay apply).</li>
     *   <li><b>Latency injection</b> — if {@code chaos.getLatency()} is set, it is added to the
     *       delay applied before writing the response (combined with action + global delay).</li>
     * </ol>
     */
    void writeResponseActionResponse(final HttpResponse response, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous, final RequestDefinition requestDefinition, final Runnable postProcessor, final HttpChaosProfile chaos, int matchCount) {
        writeResponseActionResponse(response, responseWriter, request, action, synchronous, requestDefinition, postProcessor, chaos, matchCount, null);
    }

    void writeResponseActionResponse(final HttpResponse response, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous, final RequestDefinition requestDefinition, final Runnable postProcessor, final HttpChaosProfile chaos, int matchCount, final ChannelHandlerContext ctx) {
        // Chaos: drop connection takes priority over error and latency
        if (shouldDropConnection(chaos, matchCount)) {
            org.mockserver.metrics.Metrics.incrementHttpChaosInjected("drop");
            if (ctx != null) {
                getHttpErrorActionHandler().handle(HttpError.error().withDropConnection(true), ctx);
            }
            if (postProcessor != null) {
                postProcessor.run();
            }
            return;
        }

        // Chaos: the deterministic quota (rate limit) takes priority over the probabilistic error
        HttpResponse quotaError = quotaErrorResponseOrNull(chaos, matchCount);
        HttpResponse chaosError = quotaError != null ? quotaError : chaosErrorResponseOrNull(chaos, matchCount);
        final HttpResponse effectiveResponse = chaosError != null ? chaosError : applyResponseChaos(response, chaos, matchCount);
        // Gate latency by the same count window as error injection
        final Delay chaosLatency = chaos != null && chaos.countWindowEligible(matchCount) ? chaos.getLatency() : null;

        // Metrics: record chaos faults only when they actually fire
        if (quotaError != null) {
            org.mockserver.metrics.Metrics.incrementHttpChaosInjected("quota");
        } else if (chaosError != null) {
            org.mockserver.metrics.Metrics.incrementHttpChaosInjected("error");
        }
        if (chaosLatency != null) {
            org.mockserver.metrics.Metrics.incrementHttpChaosInjected("latency");
        }

        Delay[] delays = combineWithChaosAndGlobalDelay(effectiveResponse.getDelay(), chaosLatency);
        scheduler.schedule(() -> {
            try {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXPECTATION_RESPONSE)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setHttpResponse(effectiveResponse)
                        .setExpectationId(action.getExpectationId())
                        .setMessageFormat("returning response:{}for request:{}for action:{}from expectation:{}")
                        .setArguments(effectiveResponse, request, action, action.getExpectationId())
                );
                validateOpenAPIResponse(effectiveResponse, request, action, requestDefinition);
                responseWriter.writeResponse(request, effectiveResponse, false);
                emitRequestSpan(request, effectiveResponse, action, ctx, 0);
            } finally {
                if (postProcessor != null) {
                    postProcessor.run();
                }
            }
        }, synchronous, delays);
    }

    private Delay[] combineWithGlobalDelay(Delay actionDelay) {
        Long globalDelayMillis = configuration.globalResponseDelayMillis();
        if (globalDelayMillis != null && globalDelayMillis > 0) {
            Delay globalDelay = Delay.milliseconds(globalDelayMillis);
            if (actionDelay != null) {
                return new Delay[]{actionDelay, globalDelay};
            }
            return new Delay[]{globalDelay};
        }
        if (actionDelay != null) {
            return new Delay[]{actionDelay};
        }
        return new Delay[0];
    }

    /**
     * Combines the action delay, optional chaos latency, and global delay into a
     * single array of delays to apply before writing the response.
     */
    private Delay[] combineWithChaosAndGlobalDelay(Delay actionDelay, Delay chaosLatency) {
        Delay[] baseDelays = combineWithGlobalDelay(actionDelay);
        if (chaosLatency == null) {
            return baseDelays;
        }
        Delay[] combined = new Delay[baseDelays.length + 1];
        System.arraycopy(baseDelays, 0, combined, 0, baseDelays.length);
        combined[baseDelays.length] = chaosLatency;
        return combined;
    }

    private void validateOpenAPIResponse(final HttpResponse response, final HttpRequest request, final Action action, final RequestDefinition requestDefinition) {
        if (configuration.openAPIResponseValidation() && requestDefinition instanceof OpenAPIDefinition openAPIDefinition) {
            if (isNotBlank(openAPIDefinition.getSpecUrlOrPayload()) && isNotBlank(openAPIDefinition.getOperationId())) {
                List<String> validationErrors = OpenAPIResponseValidator.validate(
                    openAPIDefinition.getSpecUrlOrPayload(),
                    openAPIDefinition.getOperationId(),
                    response,
                    mockServerLogger
                );
                if (!validationErrors.isEmpty()) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(OPENAPI_RESPONSE_VALIDATION_FAILED)
                            .setLogLevel(Level.WARN)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setHttpResponse(response)
                            .setExpectationId(action.getExpectationId())
                            .setMessageFormat("OpenAPI response validation failed for operation " + openAPIDefinition.getOperationId() + ":{}for request:{}for response:{}")
                            .setArguments(String.join(NEW_LINE, validationErrors), request, response)
                    );
                }
            }
        }
    }

    void executeAfterForwardActionResponse(final HttpForwardActionResult responseFuture, final BiConsumer<HttpResponse, Throwable> command, final boolean synchronous) {
        scheduler.submit(responseFuture, command, synchronous);
    }

    void writeForwardActionResponse(final HttpForwardActionResult responseFuture, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous) {
        writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous, null, null, 0);
    }

    void writeForwardActionResponse(final HttpForwardActionResult responseFuture, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous, final Runnable postProcessor) {
        writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous, postProcessor, null, 0);
    }

    /**
     * Forward response choke point with optional HTTP chaos injection.
     * <p>
     * When {@code chaos} is non-null the same drop/error/latency logic used for
     * mocked responses is applied to the upstream response received from the forwarded
     * request. For streaming responses an injected error replaces the stream with a
     * non-streaming synthetic error response; latency is applied before writing.
     * <p>
     * Deferred: {@code FORWARD_OBJECT_CALLBACK}'s own write path and the unmatched /
     * anonymous proxy-pass path are not yet wired and will follow in later slices.
     */
    void writeForwardActionResponse(final HttpForwardActionResult responseFuture, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous, final Runnable postProcessor, final HttpChaosProfile chaos, int matchCount) {
        writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous, postProcessor, chaos, matchCount, null);
    }

    void writeForwardActionResponse(final HttpForwardActionResult responseFuture, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous, final Runnable postProcessor, final HttpChaosProfile chaos, int matchCount, final ChannelHandlerContext ctx) {
        scheduler.submit(responseFuture, () -> {
            try {
                long forwardStartNanos = System.nanoTime();
                HttpResponse response = responseFuture.getHttpResponse().get(configuration.maxFutureTimeoutInMillis(), MILLISECONDS);
                long responseTimeMs = (System.nanoTime() - forwardStartNanos) / 1_000_000;

                // chaos: drop connection takes priority over error and latency
                if (shouldDropConnection(chaos, matchCount)) {
                    org.mockserver.metrics.Metrics.incrementHttpChaosInjected("drop");
                    if (ctx != null) {
                        getHttpErrorActionHandler().handle(HttpError.error().withDropConnection(true), ctx);
                    }
                    if (postProcessor != null) {
                        postProcessor.run();
                    }
                    return;
                }

                // chaos: quota (deterministic rate limit) then probabilistic error injection on forwarded responses — replaces the upstream response
                HttpResponse quotaError = quotaErrorResponseOrNull(chaos, matchCount);
                HttpResponse chaosError = quotaError != null ? quotaError : chaosErrorResponseOrNull(chaos, matchCount);
                final HttpResponse effectiveResponse = chaosError != null ? chaosError : applyResponseChaos(response, chaos, matchCount);
                // Gate latency by the same count window as error injection
                final Delay chaosLatency = chaos != null && chaos.countWindowEligible(matchCount) ? chaos.getLatency() : null;
                final boolean chaosErrorInjected = chaosError != null;

                // Metrics: record chaos faults only when they actually fire
                if (quotaError != null) {
                    org.mockserver.metrics.Metrics.incrementHttpChaosInjected("quota");
                } else if (chaosErrorInjected) {
                    org.mockserver.metrics.Metrics.incrementHttpChaosInjected("error");
                }
                if (chaosLatency != null) {
                    org.mockserver.metrics.Metrics.incrementHttpChaosInjected("latency");
                }

                // Drift detection: asynchronously compare the real upstream response against
                // any response-type stub expectations matching this request.
                // responseTimeMs already captured at line above via nanoTime delta.
                analyseDrift(request, response, responseTimeMs);

                // OpenTelemetry: emit a request-level span for the forwarded request
                emitRequestSpan(request, effectiveResponse, action, ctx, responseTimeMs);

                // Factor the write (streaming vs non-streaming) into a single command so
                // it can be dispatched either directly or via the non-blocking scheduler.
                final Runnable writeCommand;
                if (!chaosErrorInjected && effectiveResponse != null && effectiveResponse.getStreamingBody() != null) {
                    writeCommand = () -> writeStreamingForwardActionResponse(effectiveResponse, responseWriter, request, action, responseFuture, postProcessor);
                } else {
                    writeCommand = () -> {
                        responseWriter.writeResponse(request, effectiveResponse, false);
                        String logMessageFormat = chaosErrorInjected
                            ? "returning chaos-injected error response:{}replacing forwarded response" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}for action:{}from expectation:{}"
                            : "returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}for action:{}from expectation:{}";
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(FORWARDED_REQUEST)
                                .setLogLevel(Level.INFO)
                                .setCorrelationId(request.getLogCorrelationId())
                                .setHttpRequest(request)
                                .setHttpResponse(effectiveResponse)
                                .setExpectation(request, effectiveResponse)
                                .setExpectationId(action.getExpectationId())
                                .setMessageFormat(logMessageFormat)
                                .setArguments(effectiveResponse, responseFuture.getHttpRequest(), httpRequestToCurlSerializer.toCurl(responseFuture.getHttpRequest(), responseFuture.getRemoteAddress()), action, action.getExpectationId())
                        );
                        if (postProcessor != null) {
                            postProcessor.run();
                        }
                    };
                }

                // Apply chaos latency via the non-blocking scheduler timer rather than a
                // blocking Thread.sleep — avoids starving the bounded scheduler thread pool.
                // Only chaos latency is scheduled here because the forward path's action +
                // global delay was already applied when the forward handler was dispatched
                // (see combineWithGlobalDelay(action.getDelay()) in the processAction switch).
                if (chaosLatency != null) {
                    scheduler.schedule(writeCommand, synchronous, chaosLatency);
                } else {
                    writeCommand.run();
                }
            } catch (Throwable throwable) {
                handleExceptionDuringForwardingRequest(action, request, responseWriter, throwable);
                if (postProcessor != null) {
                    postProcessor.run();
                }
            }
        }, synchronous, throwable -> true);
    }

    private void writeStreamingForwardActionResponse(final HttpResponse response, final ResponseWriter responseWriter, final HttpRequest request, final Action action, final HttpForwardActionResult responseFuture, final Runnable postProcessor) {
        final StreamingBody streamingBody = response.getStreamingBody();

        // Write the response head through the response writer (which will subscribe to the streaming body)
        responseWriter.writeResponse(request, response, false);

        // Register a completion callback on the streaming body to write the log entry
        // We wrap the existing subscriber's onComplete/onError to add logging after the stream finishes
        final Runnable logAndPostProcess = () -> {
            try {
                HttpResponse logResponse = response.clone();
                byte[] captured = streamingBody.capturedBytes();
                setCapturedStreamingBody(logResponse, captured);
                attachStreamingHeaders(logResponse, streamingBody);
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(FORWARDED_REQUEST)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setHttpResponse(logResponse)
                        .setExpectation(request, logResponse)
                        .setExpectationId(action != null ? action.getExpectationId() : null)
                        .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}" + (action != null ? "for action:{}from expectation:{}" : ""))
                        .setArguments(action != null
                            ? new Object[]{logResponse, responseFuture.getHttpRequest(), httpRequestToCurlSerializer.toCurl(responseFuture.getHttpRequest(), responseFuture.getRemoteAddress()), action, action.getExpectationId()}
                            : new Object[]{logResponse, responseFuture.getHttpRequest(), httpRequestToCurlSerializer.toCurl(responseFuture.getHttpRequest(), responseFuture.getRemoteAddress())}
                        )
                );
            } catch (Throwable throwable) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(WARN)
                            .setLogLevel(Level.WARN)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("exception logging streaming forward response - " + throwable.getMessage())
                            .setThrowable(throwable)
                    );
                }
            } finally {
                if (postProcessor != null) {
                    postProcessor.run();
                }
            }
        };

        streamingBody.addCompletionListener(logAndPostProcess);
    }

    void writeForwardActionResponse(final HttpResponse response, final ResponseWriter responseWriter, final HttpRequest request, final Action action) {
        try {
            responseWriter.writeResponse(request, response, false);
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(FORWARDED_REQUEST)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setHttpResponse(response)
                    .setExpectation(request, response)
                    .setExpectationId(action.getExpectationId())
                    .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}for action:{}from expectation:{}")
                    .setArguments(response, response, httpRequestToCurlSerializer.toCurl(request), action, action.getExpectationId())
            );
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(EXCEPTION)
                    .setLogLevel(Level.ERROR)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setMessageFormat(throwable.getMessage())
                    .setThrowable(throwable)
            );
        }
    }

    void handleExceptionDuringForwardingRequest(Action action, HttpRequest request, ResponseWriter responseWriter, Throwable exception) {
        if (connectionException(exception)) {
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(TRACE)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setMessageFormat("failed to connect to remote socket while forwarding request{}for action{}")
                        .setArguments(request, action)
                        .setThrowable(exception)
                );
            }
            returnBadGateway(responseWriter, request, "failed to connect to remote socket while forwarding request");
        } else if (sslHandshakeException(exception)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setMessageFormat("TLS handshake exception while forwarding request{}for action{}")
                    .setArguments(request, action)
                    .setThrowable(exception)
            );
            returnBadGateway(responseWriter, request, "TLS handshake exception while forwarding request");
        } else {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(EXCEPTION)
                    .setLogLevel(Level.ERROR)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setMessageFormat(exception != null ? isNotBlank(exception.getMessage()) ? exception.getMessage() : exception.getClass().getSimpleName() : null)
                    .setThrowable(exception)
            );
            returnBadGateway(responseWriter, request, exception != null ? exception.getMessage() : null);
        }
    }

    private void returnBadGateway(ResponseWriter responseWriter, HttpRequest request, String error) {
        HttpResponse response = badGatewayResponse();
        if (isNotBlank(error)) {
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(NO_MATCH_RESPONSE)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setHttpResponse(response)
                        .setMessageFormat(FORWARD_FAILURE_MESSAGE_FORMAT)
                        .setArguments(request, error, response)
                );
            }
        } else {
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(NO_MATCH_RESPONSE)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setHttpResponse(response)
                        .setMessageFormat(FORWARD_FAILURE_MESSAGE_FORMAT)
                        .setArguments(request, "unknown error", response)
                );
            }
        }
        responseWriter.writeResponse(request, response, false);
    }

    private void returnNotFound(ResponseWriter responseWriter, HttpRequest request, String error) {
        HttpResponse response = notFoundResponse();
        if (request.getHeaders() != null && request.getHeaders().containsEntry(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue())) {
            response.withHeader(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue());
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(TRACE)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setMessageFormat(NO_MATCH_RESPONSE_NO_EXPECTATION_MESSAGE_FORMAT)
                        .setArguments(request, notFoundResponse())
                );
            }
        } else if (isNotBlank(error)) {
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(NO_MATCH_RESPONSE)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setHttpResponse(notFoundResponse())
                        .setMessageFormat(NO_MATCH_RESPONSE_ERROR_MESSAGE_FORMAT)
                        .setArguments(error, request, notFoundResponse())
                );
            }
        } else {
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(NO_MATCH_RESPONSE)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setHttpResponse(notFoundResponse())
                        .setMessageFormat(NO_MATCH_RESPONSE_NO_EXPECTATION_MESSAGE_FORMAT)
                        .setArguments(request, notFoundResponse())
                );
            }
        }
        if (configuration.detailedVerificationFailures() && mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
            try {
                java.util.Map<org.mockserver.matchers.MatchDifference.Field, java.util.List<String>> closestDiff = httpStateHandler.findClosestMatchDiff(request);
                if (closestDiff != null && !closestDiff.isEmpty()) {
                    String diffBody = org.mockserver.matchers.MatchDifferenceFormatter.formatDifferences(closestDiff);
                    if (isNotBlank(diffBody)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.DEBUG)
                                .setHttpRequest(request)
                                .setMessageFormat("closest match diff for unmatched request:{}")
                                .setArguments(diffBody)
                        );
                    }
                }
            } catch (Exception e) {
                if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.TRACE)
                            .setMessageFormat("exception generating closest match diff for 404 response:{}")
                            .setArguments(e.getMessage())
                            .setThrowable(e)
                    );
                }
            }
        }
        if (configuration.attachMismatchDiagnosticToResponse()) {
            attachMismatchDiagnostic(request, response);
        }
        responseWriter.writeResponse(request, response, false);
    }

    private void attachMismatchDiagnostic(HttpRequest request, HttpResponse response) {
        try {
            java.util.Map<org.mockserver.matchers.MatchDifference.Field, java.util.List<String>> closestDiff = httpStateHandler.findClosestMatchDiff(request);
            if (closestDiff != null && !closestDiff.isEmpty()) {
                String summary = org.mockserver.matchers.MatchDifferenceFormatter.formatDifferences(closestDiff);
                if (isNotBlank(summary)) {
                    // header: concise one-line summary of the mismatched fields
                    String headerValue = closestDiff.keySet().stream()
                        .map(org.mockserver.matchers.MatchDifference.Field::getName)
                        .collect(java.util.stream.Collectors.joining(", "));
                    response.withHeader("x-mockserver-closest-match", "fields differ: " + headerValue);
                    // body: structured JSON diagnostic
                    com.fasterxml.jackson.databind.ObjectMapper objectMapper = org.mockserver.serialization.ObjectMapperFactory.createObjectMapper();
                    com.fasterxml.jackson.databind.node.ObjectNode diagnosticNode = objectMapper.createObjectNode();
                    diagnosticNode.put("matchedFieldCount", org.mockserver.matchers.MatchDifference.Field.values().length - closestDiff.size());
                    diagnosticNode.put("totalFieldCount", org.mockserver.matchers.MatchDifference.Field.values().length);
                    com.fasterxml.jackson.databind.node.ObjectNode differencesNode = objectMapper.createObjectNode();
                    for (java.util.Map.Entry<org.mockserver.matchers.MatchDifference.Field, java.util.List<String>> entry : closestDiff.entrySet()) {
                        com.fasterxml.jackson.databind.node.ArrayNode fieldDiffs = differencesNode.putArray(entry.getKey().getName());
                        for (String diff : entry.getValue()) {
                            fieldDiffs.add(org.mockserver.matchers.MatchDifferenceFormatter.truncateDiffLine(diff));
                        }
                    }
                    diagnosticNode.set("differences", differencesNode);
                    response.withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(diagnosticNode), MediaType.JSON_UTF_8);
                }
            } else {
                response.withHeader("x-mockserver-closest-match", "no expectations configured");
            }
        } catch (Exception e) {
            if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.TRACE)
                        .setMessageFormat("exception attaching mismatch diagnostic to 404 response:{}")
                        .setArguments(e.getMessage())
                        .setThrowable(e)
                );
            }
        }
    }

    /**
     * Set the captured streaming body on a log response. Textual content types
     * (Server-Sent Events, JSON, XML, ...) are stored as a plain {@link StringBody}
     * so the body is human-readable in the dashboard, the retrieve API and the HAR
     * export; other content is stored as binary. The captured body may be truncated
     * (see {@code maxStreamingCaptureBytes}), so it is stored verbatim as text rather
     * than re-parsed into a structured JsonBody/XmlBody - re-parsing would fail to
     * serialize when the captured JSON is incomplete.
     */
    private static void setCapturedStreamingBody(HttpResponse logResponse, byte[] captured) {
        if (captured.length == 0) {
            return;
        }
        String contentTypeHeader = logResponse.getFirstHeader(CONTENT_TYPE.toString());
        if (contentTypeHeader != null && !contentTypeHeader.isEmpty()) {
            MediaType mediaType = MediaType.parse(contentTypeHeader);
            if (mediaType != null && mediaType.isString()) {
                logResponse.withBody(new String(captured, mediaType.getCharsetOrDefault()));
                return;
            }
        }
        logResponse.withBody(captured);
    }

    /**
     * Attach internal streaming metadata headers to a log response. This must be called
     * consistently from every streaming completion path so that the log entry (and any
     * fixture derived from it) carries the same set of headers.
     *
     * @param logResponse   the cloned response that will be stored in the event log
     * @param streamingBody the streaming body that captured bytes and timestamps
     */
    private static void attachStreamingHeaders(HttpResponse logResponse, StreamingBody streamingBody) {
        logResponse.withHeader("x-mockserver-streamed", "true");
        if (streamingBody.isTruncated()) {
            logResponse.withHeader("x-mockserver-stream-truncated", "true");
        }
        List<Long> interChunkDelays = streamingBody.interChunkDelaysMillis();
        if (interChunkDelays != null && !interChunkDelays.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < interChunkDelays.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(interChunkDelays.get(i));
            }
            logResponse.withHeader("x-mockserver-chunk-delays-ms", sb.toString());
        }
    }

    private HttpResponseActionHandler getHttpResponseActionHandler() {
        if (httpResponseActionHandler == null) {
            httpResponseActionHandler = new HttpResponseActionHandler();
        }
        return httpResponseActionHandler;
    }

    private HttpResponseTemplateActionHandler getHttpResponseTemplateActionHandler() {
        if (httpResponseTemplateActionHandler == null) {
            httpResponseTemplateActionHandler = new HttpResponseTemplateActionHandler(mockServerLogger, configuration);
        }
        return httpResponseTemplateActionHandler;
    }

    private HttpResponseClassCallbackActionHandler getHttpResponseClassCallbackActionHandler() {
        if (httpResponseClassCallbackActionHandler == null) {
            httpResponseClassCallbackActionHandler = new HttpResponseClassCallbackActionHandler(mockServerLogger);
        }
        return httpResponseClassCallbackActionHandler;
    }

    private HttpResponseObjectCallbackActionHandler getHttpResponseObjectCallbackActionHandler() {
        if (httpResponseObjectCallbackActionHandler == null) {
            httpResponseObjectCallbackActionHandler = new HttpResponseObjectCallbackActionHandler(httpStateHandler);
        }
        return httpResponseObjectCallbackActionHandler;
    }

    private HttpForwardActionHandler getHttpForwardActionHandler() {
        if (httpForwardActionHandler == null) {
            httpForwardActionHandler = new HttpForwardActionHandler(mockServerLogger, configuration, httpClient);
        }
        return httpForwardActionHandler;
    }

    private HttpForwardTemplateActionHandler getHttpForwardTemplateActionHandler() {
        if (httpForwardTemplateActionHandler == null) {
            httpForwardTemplateActionHandler = new HttpForwardTemplateActionHandler(mockServerLogger, configuration, httpClient);
        }
        return httpForwardTemplateActionHandler;
    }

    private HttpForwardClassCallbackActionHandler getHttpForwardClassCallbackActionHandler() {
        if (httpForwardClassCallbackActionHandler == null) {
            httpForwardClassCallbackActionHandler = new HttpForwardClassCallbackActionHandler(mockServerLogger, configuration, httpClient);
        }
        return httpForwardClassCallbackActionHandler;
    }

    private HttpForwardObjectCallbackActionHandler getHttpForwardObjectCallbackActionHandler() {
        if (httpForwardObjectCallbackActionHandler == null) {
            httpForwardObjectCallbackActionHandler = new HttpForwardObjectCallbackActionHandler(httpStateHandler, configuration, httpClient);
        }
        return httpForwardObjectCallbackActionHandler;
    }

    private HttpOverrideForwardedRequestActionHandler getHttpOverrideForwardedRequestCallbackActionHandler() {
        if (httpOverrideForwardedRequestCallbackActionHandler == null) {
            httpOverrideForwardedRequestCallbackActionHandler = new HttpOverrideForwardedRequestActionHandler(mockServerLogger, configuration, httpClient);
        }
        return httpOverrideForwardedRequestCallbackActionHandler;
    }

    private HttpForwardValidateActionHandler getHttpForwardValidateActionHandler() {
        if (httpForwardValidateActionHandler == null) {
            httpForwardValidateActionHandler = new HttpForwardValidateActionHandler(mockServerLogger, configuration, httpClient);
        }
        return httpForwardValidateActionHandler;
    }

    private HttpForwardWithFallbackActionHandler getHttpForwardWithFallbackActionHandler() {
        if (httpForwardWithFallbackActionHandler == null) {
            httpForwardWithFallbackActionHandler = new HttpForwardWithFallbackActionHandler(mockServerLogger, configuration, httpClient);
        }
        return httpForwardWithFallbackActionHandler;
    }

    private HttpSseResponseActionHandler getHttpSseResponseActionHandler() {
        if (httpSseResponseActionHandler == null) {
            httpSseResponseActionHandler = new HttpSseResponseActionHandler(mockServerLogger, scheduler);
        }
        return httpSseResponseActionHandler;
    }

    private HttpLlmResponseActionHandler getHttpLlmResponseActionHandler() {
        if (httpLlmResponseActionHandler == null) {
            httpLlmResponseActionHandler = new HttpLlmResponseActionHandler(mockServerLogger);
        }
        return httpLlmResponseActionHandler;
    }

    private HttpWebSocketResponseActionHandler getHttpWebSocketResponseActionHandler() {
        if (httpWebSocketResponseActionHandler == null) {
            httpWebSocketResponseActionHandler = new HttpWebSocketResponseActionHandler(mockServerLogger, scheduler);
        }
        return httpWebSocketResponseActionHandler;
    }

    private GrpcStreamResponseActionHandler getGrpcStreamResponseActionHandler() {
        if (grpcStreamResponseActionHandler == null) {
            grpcStreamResponseActionHandler = new GrpcStreamResponseActionHandler(mockServerLogger, scheduler, httpStateHandler.getGrpcDescriptorStore());
        }
        return grpcStreamResponseActionHandler;
    }

    private HttpErrorActionHandler getHttpErrorActionHandler() {
        if (httpErrorActionHandler == null) {
            httpErrorActionHandler = new HttpErrorActionHandler();
        }
        return httpErrorActionHandler;
    }

    public NettyHttpClient getHttpClient() {
        return httpClient;
    }


    public static InetSocketAddress getRemoteAddress(final ChannelHandlerContext ctx) {
        if (ctx != null && ctx.channel() != null && ctx.channel().attr(REMOTE_SOCKET) != null) {
            return ctx.channel().attr(REMOTE_SOCKET).get();
        } else {
            return null;
        }
    }

    private InetSocketAddress getRemoteAddressWithFallback(final ChannelHandlerContext ctx) {
        InetSocketAddress remoteAddress = getRemoteAddress(ctx);
        if (remoteAddress == null && configuration != null) {
            String host = configuration.proxyRemoteHost();
            Integer port = configuration.proxyRemotePort();
            if (isNotBlank(host) && port != null) {
                remoteAddress = new InetSocketAddress(host, port);
            }
        }
        return remoteAddress;
    }

    private void adjustHostHeaderForUnmatchedRequest(HttpRequest request, InetSocketAddress remoteAddress) {
        if (configuration != null) {
            String defaultHostHeader = configuration.forwardDefaultHostHeader();
            if (isNotBlank(defaultHostHeader)) {
                request.replaceHeader(new Header("Host", defaultHostHeader));
            } else if (remoteAddress != null && isNotBlank(configuration.proxyRemoteHost())) {
                Integer port = configuration.proxyRemotePort();
                boolean defaultPort = port == null || port == 80 || port == 443;
                String hostHeader = defaultPort ? configuration.proxyRemoteHost() : configuration.proxyRemoteHost() + ":" + port;
                request.replaceHeader(new Header("Host", hostHeader));
            }
        }
    }


    public static void setRemoteAddress(final ChannelHandlerContext ctx, final InetSocketAddress inetSocketAddress) {
        if (ctx != null && ctx.channel() != null) {
            ctx.channel().attr(REMOTE_SOCKET).set(inetSocketAddress);
        }
    }

    /**
     * Asynchronously compares a forwarded upstream response against any response-type
     * stub expectations that match the same request, recording structural drift
     * (status, headers, JSON schema) and performance drift into the
     * {@link org.mockserver.mock.drift.DriftStore}.
     */
    private void analyseDrift(final HttpRequest request, final HttpResponse realResponse, final long responseTimeMs) {
        if (realResponse == null) {
            return;
        }
        scheduler.submit(() -> {
            try {
                List<Expectation> matching = httpStateHandler.allMatchingExpectation(request);
                org.mockserver.mock.drift.DriftAnalyzer analyzer = org.mockserver.mock.drift.DriftAnalyzer.getInstance();
                for (Expectation expectation : matching) {
                    if (expectation.getAction() instanceof HttpResponse) {
                        analyzer.analyse(expectation, realResponse);
                        // Record response time and check for performance drift
                        org.mockserver.mock.drift.PercentileTracker.getInstance()
                            .record(expectation.getId(), responseTimeMs);
                        analyzer.checkPerformanceDrift(expectation.getId(), responseTimeMs,
                            org.mockserver.time.TimeService.currentTimeMillis());
                    }
                }
            } catch (Exception e) {
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(TRACE)
                            .setHttpRequest(request)
                            .setMessageFormat("exception during drift analysis - " + e.getMessage())
                            .setThrowable(e)
                    );
                }
            }
        });
    }

    // -------- validation proxy (OpenAPI contract validation on forwarded traffic) --------

    /**
     * Returns {@code true} when the validation-proxy mode is enabled: a spec has been configured
     * via {@code validateProxyOpenAPISpec}.
     */
    private boolean isValidationProxyEnabled() {
        String spec = configuration.validateProxyOpenAPISpec();
        return spec != null && !spec.isEmpty();
    }

    /**
     * Validates the forwarded request against the configured OpenAPI spec before the request is sent upstream.
     * If violations are found they are logged as {@code OPENAPI_REQUEST_VALIDATION_FAILED}. In enforce mode
     * a 400 response is returned; otherwise {@code null} (meaning "proceed normally").
     *
     * <p>This method may perform an expensive cold-cache OpenAPI parse / JSON-schema validation,
     * so callers MUST invoke it off the Netty event loop (inside a {@code scheduler.submit} block).</p>
     *
     * @return an {@link HttpResponse} to short-circuit with, or {@code null} to proceed
     */
    private HttpResponse validateProxyRequest(HttpRequest request) {
        String spec = configuration.validateProxyOpenAPISpec();
        if (spec == null || spec.isEmpty()) {
            return null;
        }
        try {
            List<String> requestErrors = OpenAPIRequestValidator.validate(spec, request, mockServerLogger);
            if (!requestErrors.isEmpty()) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(OPENAPI_REQUEST_VALIDATION_FAILED)
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setMessageFormat("validation proxy: request does not conform to OpenAPI spec{}errors:{}")
                        .setArguments(request, String.join("; ", requestErrors))
                );
                if (Boolean.TRUE.equals(configuration.validateProxyEnforce())) {
                    return response()
                        .withStatusCode(400)
                        .withBody("OpenAPI request validation failed: " + String.join("; ", requestErrors));
                }
            }
        } catch (Exception e) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setMessageFormat("validation proxy: failed to validate request against OpenAPI spec{}due to:{}")
                        .setArguments(request, e.getMessage())
                );
            }
        }
        return null;
    }

    /**
     * Validates the upstream response (only) against the configured OpenAPI spec. Violations are logged
     * as {@code OPENAPI_RESPONSE_VALIDATION_FAILED}. In enforce mode a 502 response is returned instead
     * of the upstream response; otherwise the original response is returned unmodified.
     *
     * <p>Unlike the previous implementation this method validates the <em>response only</em> using
     * {@link OpenAPIResponseValidator} directly, avoiding the double request validation that
     * {@link OpenApiTrafficValidator} would perform.</p>
     *
     * <p>For streaming responses the body has already been written to the client before validation
     * runs, so enforce mode cannot replace the response. Streaming responses are therefore validated
     * in report-only fashion (violations logged) even when enforce is enabled.</p>
     *
     * @param request  the forwarded request (used to resolve the matching operation)
     * @param response the upstream response to validate
     * @param streaming {@code true} when the response was a streaming response whose body has already
     *                  been written to the client (enforce mode is ineffective for streaming)
     * @return the original response (if valid or report-only/streaming), or a 502 in enforce mode
     */
    private HttpResponse validateProxyResponse(HttpRequest request, HttpResponse response, boolean streaming) {
        String spec = configuration.validateProxyOpenAPISpec();
        if (spec == null || spec.isEmpty() || response == null) {
            return response;
        }
        try {
            String operationId = resolveOperationId(spec, request);
            if (operationId == null) {
                // could not match the request to a spec operation — skip response validation
                return response;
            }
            List<String> responseErrors = OpenAPIResponseValidator.validate(spec, operationId, response, mockServerLogger);
            if (!responseErrors.isEmpty()) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(OPENAPI_RESPONSE_VALIDATION_FAILED)
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setHttpResponse(response)
                        .setMessageFormat("validation proxy: upstream response does not conform to OpenAPI spec{}errors:{}")
                        .setArguments(request, String.join("; ", responseErrors))
                );
                if (!streaming && Boolean.TRUE.equals(configuration.validateProxyEnforce())) {
                    return response()
                        .withStatusCode(502)
                        .withBody("OpenAPI response validation failed: " + String.join("; ", responseErrors));
                }
            }
        } catch (Exception e) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setMessageFormat("validation proxy: failed to validate upstream response against OpenAPI spec{}due to:{}")
                        .setArguments(request, e.getMessage())
                );
            }
        }
        return response;
    }

    /**
     * Resolves the OpenAPI operationId for the given request by matching its path and method
     * against the spec. Returns {@code null} if no matching operation is found.
     */
    private String resolveOperationId(String specUrlOrPayload, HttpRequest request) {
        try {
            io.swagger.v3.oas.models.OpenAPI openAPI = org.mockserver.openapi.OpenAPIParser.buildOpenAPI(specUrlOrPayload, mockServerLogger);
            String requestPath = request.getPath() != null ? request.getPath().getValue() : "/";
            String requestMethod = request.getMethod() != null ? request.getMethod().getValue().toLowerCase() : "get";
            for (java.util.Map.Entry<String, io.swagger.v3.oas.models.PathItem> entry : openAPI.getPaths().entrySet()) {
                String templatePath = entry.getKey();
                if (pathMatchesTemplate(templatePath, requestPath)) {
                    for (org.apache.commons.lang3.tuple.Pair<String, io.swagger.v3.oas.models.Operation> methodOp : org.mockserver.openapi.OpenAPIParser.mapOperations(entry.getValue())) {
                        if (methodOp.getLeft().equalsIgnoreCase(requestMethod)) {
                            return methodOp.getRight().getOperationId();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // fall through — unable to resolve operation
        }
        return null;
    }

    /**
     * Checks whether a concrete request path matches an OpenAPI path template (e.g. {@code /pets/{petId}}).
     */
    private static boolean pathMatchesTemplate(String templatePath, String actualPath) {
        StringBuilder regex = new StringBuilder();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{[^}]+}").matcher(templatePath);
        int lastEnd = 0;
        while (matcher.find()) {
            regex.append(java.util.regex.Pattern.quote(templatePath.substring(lastEnd, matcher.start())));
            regex.append("[^/]+");
            lastEnd = matcher.end();
        }
        regex.append(java.util.regex.Pattern.quote(templatePath.substring(lastEnd)));
        return actualPath.matches(regex.toString());
    }

    /**
     * Emit an OpenTelemetry SERVER span for a served HTTP request when
     * {@link RequestSpans} is enabled. Fail-soft: telemetry must never
     * affect the served response. The span is parented to the inbound
     * W3C trace context when available on the channel.
     */
    private void emitRequestSpan(HttpRequest request, HttpResponse response, Action action,
                                 ChannelHandlerContext ctx, long responseTimeMs) {
        if (!RequestSpans.isEnabled()) {
            return;
        }
        try {
            String method = request.getMethod() != null ? request.getMethod().getValue() : null;
            String path = request.getPath() != null ? request.getPath().getValue() : null;
            Integer statusCode = response != null ? response.getStatusCode() : null;
            String expectationId = action != null ? action.getExpectationId() : null;
            W3CTraceContext parentContext = null;
            if (ctx != null) {
                parentContext = ctx.channel().attr(TraceContextAttributes.TRACE_CONTEXT).get();
            }
            RequestSpans.recordRequest(method, path, statusCode, expectationId, responseTimeMs, parentContext);
        } catch (Exception e) {
            // fail-soft: telemetry must never affect the served response
        }
    }
}
