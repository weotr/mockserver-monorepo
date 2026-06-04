package org.mockserver.mock;

import com.google.common.annotations.VisibleForTesting;
import org.mockserver.authentication.AuthenticationException;
import org.mockserver.authentication.AuthenticationHandler;
import org.mockserver.closurecallback.websocketregistry.LocalCallbackRegistry;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.cors.CORSHeaders;
import org.mockserver.file.FileStore;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcProtoFileCompiler;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.mock.crud.CrudActionHandler;
import org.mockserver.mock.crud.CrudDataStore;
import org.mockserver.mock.crud.CrudDispatcher;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.matchers.MatchDifference;
import org.mockserver.matchers.MismatchRemediation;
import org.mockserver.memory.MemoryMonitoring;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause;
import org.mockserver.model.*;
import org.mockserver.openapi.OpenAPIConverter;
import org.mockserver.openapi.OpenApiSyncPlanner;
import org.mockserver.persistence.ExpectationFileSystemPersistence;
import org.mockserver.persistence.ExpectationFileWatcher;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.*;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.java.ExpectationToJavaSerializer;
import org.mockserver.serialization.YamlToJsonConverter;
import org.mockserver.server.initialize.ExpectationInitializerLoader;
import org.mockserver.state.InvalidationListener;
import org.mockserver.state.StateBackend;
import org.mockserver.state.StateBackendFactory;
import org.mockserver.time.TimeService;
import org.mockserver.uuid.UUIDService;
import org.mockserver.verify.Verification;
import org.mockserver.verify.VerificationSequence;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.lang3.StringUtils.*;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.log.model.LogEntry.LogMessageType.CLEARED;
import static org.mockserver.log.model.LogEntry.LogMessageType.RETRIEVED;
import static org.mockserver.log.model.LogEntryMessages.RECEIVED_REQUEST_MESSAGE_FORMAT;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.openapi.OpenAPIParser.OPEN_API_LOAD_ERROR;
import static org.slf4j.event.Level.TRACE;

/**
 * @author jamesdbloom
 */
public class HttpState {

    public static final String LOG_SEPARATOR = NEW_LINE + "------------------------------------" + NEW_LINE;
    public static final String PATH_PREFIX = "/mockserver";
    private static final ThreadLocal<Integer> LOCAL_PORT = new ThreadLocal<>();
    private final String uniqueLoopPreventionHeaderValue = "MockServer_" + UUIDService.getUUID();
    private final MockServerEventLog mockServerLog;
    private final Scheduler scheduler;
    private ExpectationFileSystemPersistence expectationFileSystemPersistence;
    private org.mockserver.persistence.RecordedExpectationFileSystemPersistence recordedExpectationFileSystemPersistence;
    private ExpectationFileWatcher expectationFileWatcher;
    // mockserver
    private final RequestMatchers requestMatchers;
    // G10 phase 2a: pluggable state backend (default in-memory, clustered in 2b+)
    private final StateBackend stateBackend;
    private final Configuration configuration;
    // Adds CORS headers to dashboard-facing control-plane responses (e.g. service
    // chaos) so the dashboard works when served from another origin (a dev server),
    // matching the unconditional CORS already applied by the metrics and MCP endpoints.
    private final CORSHeaders corsHeaders;
    private final MockServerLogger mockServerLogger;
    private final WebSocketClientRegistry webSocketClientRegistry;
    // serializers
    private ExpectationIdSerializer expectationIdSerializer;
    private RequestDefinitionSerializer requestDefinitionSerializer;
    private LogEventRequestAndResponseSerializer httpRequestResponseSerializer;
    private ExpectationSerializer expectationSerializer;
    private ExpectationSerializer expectationSerializerThatSerializesBodyDefault;
    private OpenAPIExpectationSerializer openAPIExpectationSerializer;
    private ExpectationToJavaSerializer expectationToJavaSerializer;
    private org.mockserver.serialization.ExpectationExportSerializer expectationExportSerializer;
    private VerificationSerializer verificationSerializer;
    private VerificationSequenceSerializer verificationSequenceSerializer;
    private LogEntrySerializer logEntrySerializer;
    private final MemoryMonitoring memoryMonitoring;
    private OpenAPIConverter openAPIConverter;
    private org.mockserver.serialization.har.HarConverter harConverter;
    private org.mockserver.serialization.curl.HttpRequestToCurlSerializer httpRequestToCurlSerializer;
    private AuthenticationHandler controlPlaneAuthenticationHandler;
    private GrpcProtoDescriptorStore grpcDescriptorStore;
    private final FileStore fileStore = new FileStore();
    private final CrudDispatcher crudDispatcher = new CrudDispatcher();
    // last operating mode explicitly set via PUT /mockserver/mode (so GET round-trips CAPTURE,
    // which shares the proxy-on-no-match flag with SPY); reconciled against the live flag on read
    private volatile MockMode mockMode;
    // optional — set by LifeCycle when a runtime LLM backend is configured
    private volatile org.mockserver.llm.client.LlmCompletionService llmCompletionService;
    private volatile org.mockserver.llm.client.LlmBackend llmBackend;

    public static void setPort(final HttpRequest request) {
        if (request != null && request.getSocketAddress() != null) {
            setPort(request.getSocketAddress().getPort());
            request.withSocketAddress(null);
        }
    }

    public static void setPort(final Integer port) {
        LOCAL_PORT.set(port);
    }

    public static void setPort(final Integer... port) {
        if (port != null && port.length > 0) {
            setPort(port[0]);
        }
    }

    public static void setPort(final List<Integer> port) {
        if (port != null && port.size() > 0) {
            setPort(port.get(0));
        }
    }

    public static Integer getPort() {
        return LOCAL_PORT.get();
    }

    public HttpState(Configuration configuration, MockServerLogger mockServerLogger, Scheduler scheduler) {
        this.configuration = configuration;
        this.corsHeaders = new CORSHeaders(configuration);
        this.mockServerLogger = mockServerLogger.setHttpStateHandler(this);
        this.scheduler = scheduler;
        this.webSocketClientRegistry = new WebSocketClientRegistry(configuration, mockServerLogger);
        LocalCallbackRegistry.setMaxWebSocketExpectations(configuration.maxWebSocketExpectations());
        this.mockServerLog = new MockServerEventLog(configuration, mockServerLogger, scheduler, true);
        // G10 phase 2a: create the pluggable state backend (default in-memory, clustered in 2b+).
        this.stateBackend = StateBackendFactory.create(configuration);
        // G10 phase 1: obtain the expectation store via the pluggable factory (default = standard
        // in-memory RequestMatchers; an optional clustered backend can register an alternative).
        this.requestMatchers = ExpectationStoreFactory.create(configuration, mockServerLogger, scheduler, webSocketClientRegistry);
        this.requestMatchers.setStateBackend(stateBackend);
        // G10 phase 2c: wire invalidation listener so remote cluster writes
        // trigger a node-local view rebuild (reconcileFromBackend). For
        // single-node/LOCAL backends the listener fires locally only (no-op
        // because the node-local CPQ is already in sync from the local put).
        stateBackend.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                requestMatchers.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                requestMatchers.reconcileFromBackend();
            }
        });
        // G11: wire chaos registries to the clustered backend for fleet-wide
        // chaos replication. When the backend is not clustered (default), the
        // setStateBackend calls are no-ops and the registries stay node-local.
        org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().setStateBackend(stateBackend);
        org.mockserver.mock.action.http.TcpChaosRegistry.getInstance().setStateBackend(stateBackend);
        org.mockserver.mock.action.http.GrpcChaosRegistry.getInstance().setStateBackend(stateBackend);
        // G11: register a SEPARATE InvalidationListener for chaos reconciliation
        // so that remote writes to chaos stores trigger the node-local rebuild.
        // This is distinct from the expectations reconcile listener above.
        if (stateBackend.isClustered()) {
            stateBackend.addInvalidationListener(new InvalidationListener() {
                @Override
                public void onChanged(String key) {
                    org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().reconcileFromBackend();
                    org.mockserver.mock.action.http.TcpChaosRegistry.getInstance().reconcileFromBackend();
                    org.mockserver.mock.action.http.GrpcChaosRegistry.getInstance().reconcileFromBackend();
                }

                @Override
                public void onCleared() {
                    org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().reconcileFromBackend();
                    org.mockserver.mock.action.http.TcpChaosRegistry.getInstance().reconcileFromBackend();
                    org.mockserver.mock.action.http.GrpcChaosRegistry.getInstance().reconcileFromBackend();
                }
            });
        }
        Metrics.setActiveExpectationsSupplier(() -> requestMatchers.retrieveActiveExpectations(null));
        if (configuration.persistExpectations()) {
            this.expectationFileSystemPersistence = new ExpectationFileSystemPersistence(configuration, mockServerLogger, requestMatchers, stateBackend.blobs());
        }
        if (configuration.persistRecordedExpectations()) {
            this.recordedExpectationFileSystemPersistence = new org.mockserver.persistence.RecordedExpectationFileSystemPersistence(configuration, mockServerLogger, mockServerLog, stateBackend.blobs());
        }
        if (isNotBlank(configuration.initializationJsonPath()) || isNotBlank(configuration.initializationOpenAPIPath()) || isNotBlank(configuration.initializationClass())) {
            ExpectationInitializerLoader expectationInitializerLoader = new ExpectationInitializerLoader(configuration, mockServerLogger, requestMatchers);
            if ((isNotBlank(configuration.initializationJsonPath()) || isNotBlank(configuration.initializationOpenAPIPath())) && configuration.watchInitializationJson()) {
                this.expectationFileWatcher = new ExpectationFileWatcher(configuration, mockServerLogger, requestMatchers, expectationInitializerLoader);
            }
        }
        // G11 follow-up: wire the cross-protocol event bus to the clustered
        // backend for fleet-wide registration replication. When the backend is
        // not clustered (default), setStateBackend is a no-op and the bus stays
        // node-local. Mirrors the chaos registry wiring pattern above.
        CrossProtocolEventBus.getInstance().setStateBackend(stateBackend);
        if (stateBackend.isClustered()) {
            stateBackend.addInvalidationListener(new InvalidationListener() {
                @Override
                public void onChanged(String key) {
                    CrossProtocolEventBus.getInstance().reconcileFromBackend();
                }

                @Override
                public void onCleared() {
                    CrossProtocolEventBus.getInstance().reconcileFromBackend();
                }
            });
        }
        CrossProtocolEventBus.getInstance().setScenarioManager(requestMatchers.getScenarioManager());
        this.memoryMonitoring = new MemoryMonitoring(configuration, this.mockServerLog, this.requestMatchers);
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(TRACE)
                    .setMessageFormat("log ring buffer created, with size " + configuration.ringBufferSize())
            );
        }
        initGrpcDescriptorStore();
    }

    private void initGrpcDescriptorStore() {
        this.grpcDescriptorStore = new GrpcProtoDescriptorStore(mockServerLogger);
        if (configuration.grpcEnabled()) {
            String descriptorDir = configuration.grpcDescriptorDirectory();
            if (isNotBlank(descriptorDir)) {
                grpcDescriptorStore.loadDescriptorDirectory(java.nio.file.Paths.get(descriptorDir));
            }
            String protoDir = configuration.grpcProtoDirectory();
            if (isNotBlank(protoDir)) {
                new GrpcProtoFileCompiler(mockServerLogger, configuration.grpcProtocPath()).compileDirectory(java.nio.file.Paths.get(protoDir), grpcDescriptorStore);
            }
        }
    }

    public GrpcProtoDescriptorStore getGrpcDescriptorStore() {
        return grpcDescriptorStore;
    }

    public FileStore getFileStore() {
        return fileStore;
    }

    public CrudDispatcher getCrudDispatcher() {
        return crudDispatcher;
    }

    public AuthenticationHandler getControlPlaneAuthenticationHandler() {
        return controlPlaneAuthenticationHandler;
    }

    public void setControlPlaneAuthenticationHandler(AuthenticationHandler controlPlaneAuthenticationHandler) {
        this.controlPlaneAuthenticationHandler = controlPlaneAuthenticationHandler;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    /**
     * Install the LLM completion service and default backend for runtime features
     * that call out to an LLM (e.g. AI stub generation). Called by LifeCycle when
     * a backend is configured; null-safe — when not called the stub generation
     * endpoint falls back to template-based stubs.
     */
    public void setLlmCompletionService(org.mockserver.llm.client.LlmCompletionService llmCompletionService,
                                        org.mockserver.llm.client.LlmBackend llmBackend) {
        this.llmCompletionService = llmCompletionService;
        this.llmBackend = llmBackend;
    }

    public MockServerLogger getMockServerLogger() {
        return mockServerLogger;
    }

    public void clear(HttpRequest request) {
        final String logCorrelationId = UUIDService.getUUID();
        RequestDefinition requestDefinition = null;
        ExpectationId expectationId = null;
        if (isNotBlank(request.getBodyAsString())) {
            String body = request.getBodyAsJsonOrXmlString();
            try {
                expectationId = getExpectationIdSerializer().deserialize(body);
            } catch (Throwable throwable) {
                // assume not expectationId
                requestDefinition = getRequestDefinitionSerializer().deserialize(body);
            }
            if (expectationId != null) {
                requestDefinition = resolveExpectationId(expectationId);
            }
        }
        if (requestDefinition != null) {
            requestDefinition.withLogCorrelationId(logCorrelationId);
        }
        try {
            ClearType type = ClearType.valueOf(defaultIfEmpty(request.getFirstQueryStringParameter("type").toUpperCase(), "ALL"));
            switch (type) {
                case LOG:
                    mockServerLog.clear(requestDefinition);
                    break;
                case EXPECTATIONS:
                    if (expectationId != null) {
                        requestMatchers.clear(expectationId, logCorrelationId);
                    } else {
                        requestMatchers.clear(requestDefinition);
                    }
                    break;
                case ALL:
                    mockServerLog.clear(requestDefinition);
                    if (expectationId != null) {
                        requestMatchers.clear(expectationId, logCorrelationId);
                    } else {
                        requestMatchers.clear(requestDefinition);
                    }
                    break;
            }
        } catch (IllegalArgumentException iae) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setCorrelationId(logCorrelationId)
                    .setMessageFormat("exception handling request:{}error:{}")
                    .setArguments(request, iae.getMessage())
                    .setThrowable(iae)
            );
            throw new IllegalArgumentException("\"" + request.getFirstQueryStringParameter("type") + "\" is not a valid value for \"type\" parameter, only the following values are supported " + Arrays.stream(ClearType.values()).map(input -> input.name().toLowerCase()).collect(Collectors.toList()));
        }
    }

    private RequestDefinition resolveExpectationId(ExpectationId expectationId) {
        return requestMatchers
            .retrieveRequestDefinitions(Collections.singletonList(expectationId))
            .findFirst()
            .orElse(null);
    }

    private List<RequestDefinition> resolveExpectationIds(List<ExpectationId> expectationIds) {
        return requestMatchers
            .retrieveRequestDefinitions(expectationIds)
            .collect(Collectors.toList());
    }

    public void reset() {
        requestMatchers.reset();
        requestMatchers.getScenarioManager().cancelAllPendingTransitions();
        CrossProtocolEventBus.getInstance().reset();
        mockServerLog.reset();
        webSocketClientRegistry.reset();
        crudDispatcher.reset();
        fileStore.reset();
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        org.mockserver.mock.action.http.HttpQuotaRegistry.getInstance().reset();
        org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().reset();
        org.mockserver.mock.action.http.TcpChaosRegistry.getInstance().reset();
        org.mockserver.mock.action.http.GrpcChaosRegistry.getInstance().reset();
        org.mockserver.grpc.GrpcHealthRegistry.getInstance().reset();
        org.mockserver.wasm.WasmStore.getInstance().reset();
        org.mockserver.mock.drift.DriftStore.getInstance().clear();
        CassetteRegistry.getInstance().reset();
        org.mockserver.mock.dns.DnsIntentRegistry.getInstance().clear();
        org.mockserver.async.AsyncApiControlPlaneRegistry.getInstance().reset();
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(CLEARED)
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(request())
                    .setMessageFormat("resetting all expectations and request logs")
            );
        }
        new Scheduler.SchedulerThreadFactory("MockServer Memory Metrics").newThread(() -> {
            try {
                SECONDS.sleep(10);
                memoryMonitoring.logMemoryMetrics();
            } catch (InterruptedException ie) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("exception handling reset request:{}")
                        .setArguments(ie.getMessage())
                        .setThrowable(ie)
                );
                ie.printStackTrace();
            }
        });
    }

    public List<Expectation> add(OpenAPIExpectation openAPIExpectation) {
        List<Expectation> newExpectations = getOpenAPIConverter().buildExpectations(
            openAPIExpectation.getSpecUrlOrPayload(),
            openAPIExpectation.getOperationsAndResponses(),
            openAPIExpectation.getContextPathPrefix()
        );

        // Incremental sync: determine the namespace prefixes covered by this
        // import, find stale expectations in those namespaces, and prune them.
        Set<String> newIds = newExpectations.stream()
            .map(Expectation::getId)
            .collect(Collectors.toSet());
        Set<String> namespacePrefixes = newIds.stream()
            .filter(id -> id.startsWith(OpenApiSyncPlanner.OPENAPI_ID_PREFIX))
            .map(id -> {
                // Extract "openapi:<specKey>:" prefix — everything up to and including the second ':'
                int secondColon = id.indexOf(':', OpenApiSyncPlanner.OPENAPI_ID_PREFIX.length());
                return secondColon >= 0 ? id.substring(0, secondColon + 1) : id + ":";
            })
            .collect(Collectors.toSet());
        if (!namespacePrefixes.isEmpty()) {
            List<String> existingIds = requestMatchers.retrieveActiveExpectations(null).stream()
                .map(Expectation::getId)
                .collect(Collectors.toList());
            Set<String> toPrune = OpenApiSyncPlanner.idsToPrune(existingIds, newIds, namespacePrefixes);
            String logCorrelationId = UUIDService.getUUID();
            for (String pruneId : toPrune) {
                requestMatchers.clear(ExpectationId.expectationId(pruneId), logCorrelationId);
            }
        }

        // Upsert the new expectations (add() does upsert-by-id)
        return newExpectations.stream()
            .map(this::add)
            .flatMap(List::stream)
            .collect(Collectors.toList());
    }

    public List<Expectation> add(Expectation... expectations) {
        List<Expectation> upsertedExpectations = new ArrayList<>();
        for (Expectation expectation : expectations) {
            // validate steps if present
            String stepsError = expectation.validateSteps();
            if (stepsError != null) {
                throw new IllegalArgumentException("invalid expectation steps: " + stepsError);
            }
            RequestDefinition requestDefinition = expectation.getHttpRequest();
            if (requestDefinition instanceof HttpRequest) {
                final String hostHeader = ((HttpRequest) requestDefinition).getFirstHeader(HOST.toString());
                if (isNotBlank(hostHeader)) {
                    scheduler.submit(() -> configuration.addSubjectAlternativeName(hostHeader));
                }
            }
            upsertedExpectations.add(requestMatchers.add(expectation, Cause.API));
        }
        return upsertedExpectations;
    }

    public Expectation firstMatchingExpectation(RequestDefinition request) {
        if (requestMatchers.isEmpty()) {
            return null;
        } else {
            return requestMatchers.firstMatchingExpectation(request);
        }
    }

    /**
     * Side-effect-free probe: returns the first matching expectation WITHOUT consuming the
     * match (no Times decrement, no scenario transition, no responseInProgress, no metrics).
     * Note: the underlying matcher evaluation may still emit INFO-level EXPECTATION_MATCHED /
     * EXPECTATION_NOT_MATCHED diagnostic logs; this method avoids the consuming side-effects
     * only. Used by the gRPC bidi router to inspect the action type before committing
     * to a handler — the real consuming match happens separately on the committed path.
     */
    public Expectation peekFirstMatchingExpectation(RequestDefinition request) {
        if (requestMatchers.isEmpty()) {
            return null;
        }
        return requestMatchers.peekFirstMatchingExpectation(request);
    }

    /**
     * Returns the first expectation whose matcher has respondBeforeBody=true, has no body matcher,
     * and matches the supplied headers-only request. Used by the early-response path that runs
     * before the request body is aggregated.
     */
    public Expectation firstMatchingEarlyExpectation(HttpRequest headersOnly) {
        if (requestMatchers.isEmpty()) {
            return null;
        }
        return requestMatchers.firstMatchingEarlyExpectation(headersOnly);
    }

    @VisibleForTesting
    public List<Expectation> allMatchingExpectation(HttpRequest request) {
        if (requestMatchers.isEmpty()) {
            return Collections.emptyList();
        } else {
            // Forward matching ("does each expectation match this concrete request?"),
            // NOT the filter/reverse semantics of retrieveActiveExpectations — the
            // incoming request carries headers/cookies bare stubs lack, so reverse
            // matching would return nothing (this is what silently broke drift analysis).
            return requestMatchers.retrieveExpectationsMatchingRequest(request);
        }
    }

    public void postProcess(Expectation expectation) {
        requestMatchers.postProcess(expectation);
    }

    public java.util.Map<MatchDifference.Field, java.util.List<String>> findClosestMatchDiff(HttpRequest request) {
        if (requestMatchers.isEmpty()) {
            return null;
        }
        return requestMatchers.findClosestMatchDiff(request);
    }

    private static final int DEBUG_MISMATCH_MAX_EXPECTATIONS = 100;

    public HttpResponse debugMismatch(HttpRequest request) {
        final String correlationId = UUIDService.getUUID();
        final String timestamp = java.time.Instant.now().toString();
        try {
            final RequestDefinition requestDefinition = isNotBlank(request.getBodyAsString())
                ? getRequestDefinitionSerializer().deserialize(request.getBodyAsJsonOrXmlString())
                : request();
            if (!(requestDefinition instanceof HttpRequest)) {
                com.fasterxml.jackson.databind.ObjectMapper errorMapper = ObjectMapperFactory.createObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode errorNode = errorMapper.createObjectNode();
                errorNode.put("error", "debugMismatch only supports HttpRequest definitions");
                errorNode.put("correlationId", correlationId);
                errorNode.put("timestamp", timestamp);
                return response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody(errorMapper.writerWithDefaultPrettyPrinter().writeValueAsString(errorNode), MediaType.JSON_UTF_8);
            }
            HttpRequest debugRequest = (HttpRequest) requestDefinition;

            List<HttpRequestMatcher> matchers = requestMatchers.retrieveRequestMatchers(null);
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
            com.fasterxml.jackson.databind.node.ArrayNode expectationResults = objectMapper.createArrayNode();

            int closestMatchFailures = Integer.MAX_VALUE;
            String closestMatchId = null;
            int closestMatchedFields = 0;
            int totalFields = MatchDifference.Field.values().length;
            boolean truncated = matchers.size() > DEBUG_MISMATCH_MAX_EXPECTATIONS;
            int evaluateCount = Math.min(matchers.size(), DEBUG_MISMATCH_MAX_EXPECTATIONS);

            for (int i = 0; i < evaluateCount; i++) {
                HttpRequestMatcher matcher = matchers.get(i);
                com.fasterxml.jackson.databind.node.ObjectNode matchResult = objectMapper.createObjectNode();
                Expectation expectation = matcher.getExpectation();
                if (expectation != null) {
                    matchResult.put("expectationId", expectation.getId());
                    if (expectation.getHttpRequest() instanceof HttpRequest) {
                        HttpRequest expRequest = (HttpRequest) expectation.getHttpRequest();
                        matchResult.put("expectationPath", expRequest.getPath() != null ? expRequest.getPath().getValue() : "");
                        matchResult.put("expectationMethod", expRequest.getMethod() != null ? expRequest.getMethod().getValue() : "");
                    }
                }

                HttpRequest clonedRequest = debugRequest.clone();
                MatchDifference matchDifference = new MatchDifference(true, clonedRequest);
                boolean matches = matcher.matches(matchDifference, clonedRequest);
                matchResult.put("matches", matches);

                if (!matches) {
                    java.util.Map<MatchDifference.Field, List<String>> allDifferences = matchDifference.getAllDifferences();
                    int failures = allDifferences.size();
                    int matchedFields = totalFields - failures;
                    matchResult.put("matchedFieldCount", matchedFields);
                    matchResult.put("totalFieldCount", totalFields);

                    com.fasterxml.jackson.databind.node.ObjectNode differences = objectMapper.createObjectNode();
                    for (java.util.Map.Entry<MatchDifference.Field, List<String>> diffEntry : allDifferences.entrySet()) {
                        com.fasterxml.jackson.databind.node.ArrayNode fieldDiffs = differences.putArray(diffEntry.getKey().getName());
                        for (String diff : diffEntry.getValue()) {
                            fieldDiffs.add(diff);
                        }
                    }
                    matchResult.set("differences", differences);

                    if (failures < closestMatchFailures && expectation != null) {
                        closestMatchFailures = failures;
                        closestMatchId = expectation.getId();
                        closestMatchedFields = matchedFields;
                    }
                } else {
                    matchResult.put("matchedFieldCount", totalFields);
                    matchResult.put("totalFieldCount", totalFields);
                }

                expectationResults.add(matchResult);
            }

            com.fasterxml.jackson.databind.node.ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("correlationId", correlationId);
            resultNode.put("timestamp", timestamp);
            resultNode.put("totalExpectations", matchers.size());
            resultNode.put("evaluatedExpectations", evaluateCount);
            if (truncated) {
                resultNode.put("truncated", true);
                resultNode.put("maxExpectationsEvaluated", DEBUG_MISMATCH_MAX_EXPECTATIONS);
            }
            if (closestMatchId != null) {
                com.fasterxml.jackson.databind.node.ObjectNode closestMatch = objectMapper.createObjectNode();
                closestMatch.put("expectationId", closestMatchId);
                closestMatch.put("matchedFields", closestMatchedFields);
                closestMatch.put("totalFields", totalFields);
                resultNode.set("closestMatch", closestMatch);
            }
            resultNode.set("results", expectationResults);

            return response()
                .withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultNode), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setCorrelationId(correlationId)
                    .setMessageFormat("exception handling debugMismatch request:{}error:{}")
                    .setArguments(request, e.getMessage())
                    .setThrowable(e)
            );
            try {
                com.fasterxml.jackson.databind.ObjectMapper errorMapper = ObjectMapperFactory.createObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode errorNode = errorMapper.createObjectNode();
                errorNode.put("error", "failed to debug request mismatch: " + e.getMessage());
                errorNode.put("correlationId", correlationId);
                errorNode.put("timestamp", timestamp);
                return response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody(errorMapper.writerWithDefaultPrettyPrinter().writeValueAsString(errorNode), MediaType.JSON_UTF_8);
            } catch (Exception jsonError) {
                return response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"failed to debug request mismatch\"}", MediaType.JSON_UTF_8);
            }
        }
    }

    private static final int EXPLAIN_UNMATCHED_MAX_EXPECTATIONS = 50;
    static final int EXPLAIN_UNMATCHED_EVALUATION_BUDGET = 500;

    /**
     * Retrieves recent requests that matched no expectation and, for each, computes
     * ranked closest-expectation diagnostics with remediation hints.
     *
     * @param request the control-plane request (body may contain {@code {"limit":N}})
     * @return a JSON response containing an array of unmatched requests with diagnostics
     */
    public HttpResponse explainUnmatched(HttpRequest request) {
        final String correlationId = UUIDService.getUUID();
        final String timestamp = java.time.Instant.now().toString();
        try {
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

            // parse optional limit from body
            int limit = 10;
            if (isNotBlank(request.getBodyAsString())) {
                try {
                    com.fasterxml.jackson.databind.JsonNode body = objectMapper.readTree(request.getBodyAsJsonOrXmlString());
                    if (body.has("limit")) {
                        limit = body.get("limit").asInt(10);
                    }
                } catch (Exception ignored) {
                    // no valid JSON body -- use default
                }
            }

            CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();

            mockServerLog.retrieveUnmatchedRequests(limit, unmatchedEntries -> {
                try {
                    com.fasterxml.jackson.databind.node.ArrayNode unmatchedArray = objectMapper.createArrayNode();
                    int totalEvaluations = 0;
                    boolean truncated = false;

                    for (LogEntry entry : unmatchedEntries) {
                        if (truncated) {
                            break;
                        }
                        RequestDefinition requestDef = entry.getHttpRequest();
                        if (!(requestDef instanceof HttpRequest)) {
                            continue;
                        }
                        HttpRequest unmatchedRequest = (HttpRequest) requestDef;
                        com.fasterxml.jackson.databind.node.ObjectNode requestNode = objectMapper.createObjectNode();
                        requestNode.put("timestamp", entry.getTimestamp());
                        requestNode.put("method", unmatchedRequest.getMethod() != null ? unmatchedRequest.getMethod().getValue() : "");
                        requestNode.put("path", unmatchedRequest.getPath() != null ? unmatchedRequest.getPath().getValue() : "");

                        // compute per-expectation diffs, ranked by closeness
                        List<HttpRequestMatcher> matchers = requestMatchers.retrieveRequestMatchers(null);
                        int totalFields = MatchDifference.Field.values().length;
                        int evaluateCount = Math.min(matchers.size(), EXPLAIN_UNMATCHED_MAX_EXPECTATIONS);

                        // collect results with their failure count for sorting
                        List<com.fasterxml.jackson.databind.node.ObjectNode> expResults = new ArrayList<>();

                        for (int i = 0; i < evaluateCount; i++) {
                            if (totalEvaluations >= EXPLAIN_UNMATCHED_EVALUATION_BUDGET) {
                                truncated = true;
                                break;
                            }
                            HttpRequestMatcher matcher = matchers.get(i);
                            Expectation expectation = matcher.getExpectation();
                            if (expectation == null) {
                                continue;
                            }

                            HttpRequest clonedRequest = unmatchedRequest.clone();
                            MatchDifference matchDifference = new MatchDifference(true, clonedRequest);
                            boolean matches = matcher.matches(matchDifference, clonedRequest);
                            totalEvaluations++;

                            com.fasterxml.jackson.databind.node.ObjectNode expResult = objectMapper.createObjectNode();
                            expResult.put("expectationId", expectation.getId());
                            if (expectation.getHttpRequest() instanceof HttpRequest) {
                                HttpRequest expReq = (HttpRequest) expectation.getHttpRequest();
                                expResult.put("expectationPath", expReq.getPath() != null ? expReq.getPath().getValue() : "");
                                expResult.put("expectationMethod", expReq.getMethod() != null ? expReq.getMethod().getValue() : "");
                            }
                            expResult.put("matches", matches);

                            java.util.Map<MatchDifference.Field, List<String>> allDifferences = matchDifference.getAllDifferences();
                            int failures = matches ? 0 : allDifferences.size();
                            int matchedFields = totalFields - failures;
                            expResult.put("matchedFieldCount", matchedFields);
                            expResult.put("totalFieldCount", totalFields);
                            expResult.put("differingFieldCount", failures);

                            if (!matches && !allDifferences.isEmpty()) {
                                com.fasterxml.jackson.databind.node.ObjectNode differences = objectMapper.createObjectNode();
                                for (java.util.Map.Entry<MatchDifference.Field, List<String>> diffEntry : allDifferences.entrySet()) {
                                    com.fasterxml.jackson.databind.node.ArrayNode fieldDiffs = differences.putArray(diffEntry.getKey().getName());
                                    for (String diff : diffEntry.getValue()) {
                                        fieldDiffs.add(diff);
                                    }
                                }
                                expResult.set("differences", differences);

                                // add remediation hints
                                java.util.Map<MatchDifference.Field, String> hints = MismatchRemediation.allHints(allDifferences);
                                if (!hints.isEmpty()) {
                                    com.fasterxml.jackson.databind.node.ObjectNode remediationNode = objectMapper.createObjectNode();
                                    for (java.util.Map.Entry<MatchDifference.Field, String> hintEntry : hints.entrySet()) {
                                        remediationNode.put(hintEntry.getKey().getName(), hintEntry.getValue());
                                    }
                                    expResult.set("remediation", remediationNode);
                                }
                            }

                            expResults.add(expResult);
                        }

                        // sort by fewest differing fields first (closest match first)
                        expResults.sort((a, b) -> Integer.compare(
                            a.path("differingFieldCount").asInt(Integer.MAX_VALUE),
                            b.path("differingFieldCount").asInt(Integer.MAX_VALUE)
                        ));

                        com.fasterxml.jackson.databind.node.ArrayNode closestExpectations = objectMapper.createArrayNode();
                        for (com.fasterxml.jackson.databind.node.ObjectNode expResult : expResults) {
                            closestExpectations.add(expResult);
                        }
                        requestNode.set("closestExpectations", closestExpectations);
                        requestNode.put("totalExpectationsEvaluated", expResults.size());

                        unmatchedArray.add(requestNode);
                    }

                    com.fasterxml.jackson.databind.node.ObjectNode resultNode = objectMapper.createObjectNode();
                    resultNode.put("correlationId", correlationId);
                    resultNode.put("timestamp", timestamp);
                    resultNode.put("unmatchedRequestCount", unmatchedArray.size());
                    resultNode.put("truncated", truncated);
                    resultNode.set("unmatchedRequests", unmatchedArray);

                    responseFuture.complete(response()
                        .withStatusCode(OK.code())
                        .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultNode), MediaType.JSON_UTF_8));
                } catch (Exception e) {
                    responseFuture.completeExceptionally(e);
                }
            });

            return responseFuture.get(configuration.maxFutureTimeoutInMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setCorrelationId(correlationId)
                    .setMessageFormat("exception handling explainUnmatched request:{}error:{}")
                    .setArguments(request, e.getMessage())
                    .setThrowable(e)
            );
            try {
                com.fasterxml.jackson.databind.ObjectMapper errorMapper = ObjectMapperFactory.createObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode errorNode = errorMapper.createObjectNode();
                errorNode.put("error", "failed to explain unmatched requests: " + e.getMessage());
                errorNode.put("correlationId", correlationId);
                errorNode.put("timestamp", timestamp);
                return response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody(errorMapper.writerWithDefaultPrettyPrinter().writeValueAsString(errorNode), MediaType.JSON_UTF_8);
            } catch (Exception jsonError) {
                return response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"failed to explain unmatched requests\"}", MediaType.JSON_UTF_8);
            }
        }
    }

    public void log(LogEntry logEntry) {
        if (mockServerLog != null) {
            mockServerLog.add(logEntry);
        }
    }

    public HttpResponse retrieve(HttpRequest request) {
        final String logCorrelationId = UUIDService.getUUID();
        CompletableFuture<HttpResponse> httpResponseFuture = new CompletableFuture<>();
        HttpResponse response = response().withStatusCode(OK.code());
        if (request != null) {
            try {
                final RequestDefinition requestDefinition = isNotBlank(request.getBodyAsString()) ? getRequestDefinitionSerializer().deserialize(request.getBodyAsJsonOrXmlString()) : request();
                requestDefinition.withLogCorrelationId(logCorrelationId);
                Format format = Format.valueOf(defaultIfEmpty(request.getFirstQueryStringParameter("format").toUpperCase(), "JSON"));
                RetrieveType type = RetrieveType.valueOf(defaultIfEmpty(request.getFirstQueryStringParameter("type").toUpperCase(), "REQUESTS"));
                final String correlationIdFilter = request.getFirstQueryStringParameter("correlationId");
                switch (type) {
                    case LOGS: {
                        java.util.function.Consumer<List<LogEntry>> logsConsumer;
                        if (format == Format.LOG_ENTRIES) {
                            logsConsumer = (List<LogEntry> logEntries) -> {
                                response.withBody(
                                    getLogEntrySerializer().serialize(logEntries),
                                    MediaType.JSON_UTF_8
                                );
                                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                    mockServerLogger.logEvent(
                                        new LogEntry()
                                            .setType(RETRIEVED)
                                            .setLogLevel(Level.INFO)
                                            .setCorrelationId(logCorrelationId)
                                            .setHttpRequest(requestDefinition)
                                            .setMessageFormat("retrieved log entries in log_entries format that match:{}")
                                            .setArguments(requestDefinition)
                                    );
                                }
                                httpResponseFuture.complete(response);
                            };
                        } else {
                            logsConsumer = (List<LogEntry> logEntries) -> {
                                StringBuilder stringBuffer = new StringBuilder();
                                for (int i = 0; i < logEntries.size(); i++) {
                                    LogEntry messageLogEntry = logEntries.get(i);
                                    stringBuffer
                                        .append(messageLogEntry.getTimestamp())
                                        .append(" - ")
                                        .append(messageLogEntry.getMessage());
                                    if (i < logEntries.size() - 1) {
                                        stringBuffer.append(LOG_SEPARATOR);
                                    }
                                }
                                stringBuffer.append(NEW_LINE);
                                response.withBody(stringBuffer.toString(), MediaType.PLAIN_TEXT_UTF_8);
                                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                    mockServerLogger.logEvent(
                                        new LogEntry()
                                            .setType(RETRIEVED)
                                            .setLogLevel(Level.INFO)
                                            .setCorrelationId(logCorrelationId)
                                            .setHttpRequest(requestDefinition)
                                            .setMessageFormat("retrieved logs that match:{}")
                                            .setArguments(requestDefinition)
                                    );
                                }
                                httpResponseFuture.complete(response);
                            };
                        }
                        if (isNotBlank(correlationIdFilter)) {
                            mockServerLog.retrieveLogEntriesByCorrelationId(correlationIdFilter, logsConsumer);
                        } else {
                            mockServerLog.retrieveMessageLogEntries(requestDefinition, logsConsumer);
                        }
                        break;
                    }
                    case REQUESTS: {
                        LogEntry logEntry = new LogEntry()
                            .setType(RETRIEVED)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(logCorrelationId)
                            .setHttpRequest(requestDefinition)
                            .setMessageFormat("retrieved requests in " + format.name().toLowerCase() + " that match:{}")
                            .setArguments(requestDefinition);
                        switch (format) {
                            case JAVA:
                                mockServerLog
                                    .retrieveRequests(
                                        requestDefinition,
                                        requests -> {
                                            response.withBody(
                                                getRequestDefinitionSerializer().serialize(requests),
                                                MediaType.create("application", "java").withCharset(UTF_8)
                                            );
                                            mockServerLogger.logEvent(logEntry);
                                            httpResponseFuture.complete(response);
                                        }
                                    );
                                break;
                            case JSON:
                                mockServerLog
                                    .retrieveRequests(
                                        requestDefinition,
                                        requests -> {
                                            response.withBody(
                                                getRequestDefinitionSerializer().serialize(true, requests),
                                                MediaType.JSON_UTF_8
                                            );
                                            mockServerLogger.logEvent(logEntry);
                                            httpResponseFuture.complete(response);
                                        }
                                    );
                                break;
                            case LOG_ENTRIES:
                                mockServerLog
                                    .retrieveRequestLogEntries(
                                        requestDefinition,
                                        logEntries -> {
                                            response.withBody(
                                                getLogEntrySerializer().serialize(logEntries),
                                                MediaType.JSON_UTF_8
                                            );
                                            mockServerLogger.logEvent(logEntry);
                                            httpResponseFuture.complete(response);
                                        }
                                    );
                                break;
                            case OPENAPI:
                                mockServerLog.retrieveRequests(requestDefinition, requests -> {
                                    response.withBody(
                                        getExpectationExportSerializer().serializeRequestsAsOpenApi(requests),
                                        MediaType.JSON_UTF_8
                                    );
                                    mockServerLogger.logEvent(logEntry);
                                    httpResponseFuture.complete(response);
                                });
                                break;
                            case POSTMAN:
                                mockServerLog.retrieveRequests(requestDefinition, requests -> {
                                    response.withBody(
                                        getExpectationExportSerializer().serializeRequestsAsPostman(requests),
                                        MediaType.JSON_UTF_8
                                    );
                                    mockServerLogger.logEvent(logEntry);
                                    httpResponseFuture.complete(response);
                                });
                                break;
                            case BRUNO:
                                mockServerLog.retrieveRequests(requestDefinition, requests -> {
                                    response
                                        .withBody(getExpectationExportSerializer().serializeRequestsAsBruno(requests))
                                        .withHeader(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE.toString(), "application/zip")
                                        .withHeader("content-disposition", "attachment; filename=\"mockserver-requests.bruno.zip\"");
                                    mockServerLogger.logEvent(logEntry);
                                    httpResponseFuture.complete(response);
                                });
                                break;
                            case HAR:
                                mockServerLog.retrieveRequests(requestDefinition, requests -> {
                                    java.util.List<org.mockserver.model.LogEventRequestAndResponse> pairs = new java.util.ArrayList<>(requests.size());
                                    for (org.mockserver.model.RequestDefinition r : requests) {
                                        if (r instanceof org.mockserver.model.HttpRequest) {
                                            pairs.add(new org.mockserver.model.LogEventRequestAndResponse()
                                                .withHttpRequest((org.mockserver.model.HttpRequest) r));
                                        }
                                    }
                                    response.withBody(getHarConverter().serialize(pairs), MediaType.JSON_UTF_8);
                                    mockServerLogger.logEvent(logEntry);
                                    httpResponseFuture.complete(response);
                                });
                                break;
                            case CURL:
                                mockServerLog.retrieveRequests(requestDefinition, requests -> {
                                    List<HttpRequest> httpRequests = new java.util.ArrayList<>(requests.size());
                                    for (RequestDefinition r : requests) {
                                        if (r instanceof HttpRequest) {
                                            httpRequests.add((HttpRequest) r);
                                        }
                                    }
                                    response.withBody(toCurlCommands(httpRequests), MediaType.PLAIN_TEXT_UTF_8);
                                    mockServerLogger.logEvent(logEntry);
                                    httpResponseFuture.complete(response);
                                });
                                break;
                        }
                        break;
                    }
                    case REQUEST_RESPONSES: {
                        LogEntry logEntry = new LogEntry()
                            .setType(RETRIEVED)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(logCorrelationId)
                            .setHttpRequest(requestDefinition)
                            .setMessageFormat("retrieved requests and responses in " + format.name().toLowerCase() + " that match:{}")
                            .setArguments(requestDefinition);
                        switch (format) {
                            case JAVA:
                                response.withBody("JAVA not supported for REQUEST_RESPONSES", MediaType.create("text", "plain").withCharset(UTF_8));
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            case JSON:
                                mockServerLog
                                    .retrieveRequestResponses(
                                        requestDefinition,
                                        httpRequestAndHttpResponses -> {
                                            response.withBody(
                                                getHttpRequestResponseSerializer().serialize(httpRequestAndHttpResponses),
                                                MediaType.JSON_UTF_8
                                            );
                                            mockServerLogger.logEvent(logEntry);
                                            httpResponseFuture.complete(response);
                                        }
                                    );
                                break;
                            case LOG_ENTRIES:
                                mockServerLog
                                    .retrieveRequestResponseMessageLogEntries(
                                        requestDefinition,
                                        logEntries -> {
                                            response.withBody(
                                                getLogEntrySerializer().serialize(logEntries),
                                                MediaType.JSON_UTF_8
                                            );
                                            mockServerLogger.logEvent(logEntry);
                                            httpResponseFuture.complete(response);
                                        }
                                    );
                                break;
                            case HAR:
                                mockServerLog
                                    .retrieveRequestResponses(
                                        requestDefinition,
                                        httpRequestAndHttpResponses -> {
                                            response.withBody(
                                                getHarConverter().serialize(httpRequestAndHttpResponses),
                                                MediaType.JSON_UTF_8
                                            );
                                            mockServerLogger.logEvent(logEntry);
                                            httpResponseFuture.complete(response);
                                        }
                                    );
                                break;
                            case OPENAPI:
                                mockServerLog.retrieveRequestResponses(requestDefinition, pairs -> {
                                    response.withBody(
                                        getExpectationExportSerializer().serializeRequestResponsesAsOpenApi(pairs),
                                        MediaType.JSON_UTF_8
                                    );
                                    mockServerLogger.logEvent(logEntry);
                                    httpResponseFuture.complete(response);
                                });
                                break;
                            case POSTMAN:
                                mockServerLog.retrieveRequestResponses(requestDefinition, pairs -> {
                                    response.withBody(
                                        getExpectationExportSerializer().serializeRequestResponsesAsPostman(pairs),
                                        MediaType.JSON_UTF_8
                                    );
                                    mockServerLogger.logEvent(logEntry);
                                    httpResponseFuture.complete(response);
                                });
                                break;
                            case BRUNO:
                                mockServerLog.retrieveRequestResponses(requestDefinition, pairs -> {
                                    response
                                        .withBody(getExpectationExportSerializer().serializeRequestResponsesAsBruno(pairs))
                                        .withHeader(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE.toString(), "application/zip")
                                        .withHeader("content-disposition", "attachment; filename=\"mockserver-traffic.bruno.zip\"");
                                    mockServerLogger.logEvent(logEntry);
                                    httpResponseFuture.complete(response);
                                });
                                break;
                            case CURL:
                                mockServerLog.retrieveRequestResponses(requestDefinition, pairs -> {
                                    List<HttpRequest> httpRequests = new java.util.ArrayList<>(pairs.size());
                                    for (LogEventRequestAndResponse pair : pairs) {
                                        if (pair.getHttpRequest() instanceof HttpRequest) {
                                            httpRequests.add((HttpRequest) pair.getHttpRequest());
                                        }
                                    }
                                    response.withBody(toCurlCommands(httpRequests), MediaType.PLAIN_TEXT_UTF_8);
                                    mockServerLogger.logEvent(logEntry);
                                    httpResponseFuture.complete(response);
                                });
                                break;
                        }
                        break;
                    }
                    case RECORDED_EXPECTATIONS: {
                        LogEntry logEntry = new LogEntry()
                            .setType(RETRIEVED)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(logCorrelationId)
                            .setHttpRequest(requestDefinition)
                            .setMessageFormat("retrieved recorded expectations in " + format.name().toLowerCase() + " that match:{}")
                            .setArguments(requestDefinition);
                        switch (format) {
                            case JAVA:
                                mockServerLog
                                    .retrieveRecordedExpectations(
                                        requestDefinition,
                                        requests -> {
                                            response.withBody(
                                                getExpectationToJavaSerializer().serialize(requests),
                                                MediaType.create("application", "java").withCharset(UTF_8)
                                            );
                                            mockServerLogger.logEvent(logEntry);
                                            httpResponseFuture.complete(response);
                                        }
                                    );
                                break;
                            case JSON:
                                mockServerLog
                                    .retrieveRecordedExpectations(
                                        requestDefinition,
                                        requests -> {
                                            response.withBody(
                                                getExpectationSerializerThatSerializesBodyDefault().serialize(requests),
                                                MediaType.JSON_UTF_8
                                            );
                                            mockServerLogger.logEvent(logEntry);
                                            httpResponseFuture.complete(response);
                                        }
                                    );
                                break;
                            case LOG_ENTRIES:
                                mockServerLog
                                    .retrieveRecordedExpectationLogEntries(
                                        requestDefinition,
                                        logEntries -> {
                                            response.withBody(
                                                getLogEntrySerializer().serialize(logEntries),
                                                MediaType.JSON_UTF_8
                                            );
                                            mockServerLogger.logEvent(logEntry);
                                            httpResponseFuture.complete(response);
                                        }
                                    );
                                break;
                            case OPENAPI:
                                mockServerLog.retrieveRecordedExpectations(requestDefinition, expectations -> {
                                    response.withBody(
                                        getExpectationExportSerializer().serializeAsOpenApi(expectations),
                                        MediaType.JSON_UTF_8
                                    );
                                    mockServerLogger.logEvent(logEntry);
                                    httpResponseFuture.complete(response);
                                });
                                break;
                            case POSTMAN:
                                mockServerLog.retrieveRecordedExpectations(requestDefinition, expectations -> {
                                    response.withBody(
                                        getExpectationExportSerializer().serializeAsPostmanCollection(expectations),
                                        MediaType.JSON_UTF_8
                                    );
                                    mockServerLogger.logEvent(logEntry);
                                    httpResponseFuture.complete(response);
                                });
                                break;
                            case BRUNO:
                                mockServerLog.retrieveRecordedExpectations(requestDefinition, expectations -> {
                                    response
                                        .withBody(getExpectationExportSerializer().serializeAsBrunoCollection(expectations))
                                        .withHeader(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE.toString(), "application/zip")
                                        .withHeader("content-disposition", "attachment; filename=\"mockserver-recorded.bruno.zip\"");
                                    mockServerLogger.logEvent(logEntry);
                                    httpResponseFuture.complete(response);
                                });
                                break;
                            case HAR:
                                mockServerLog.retrieveRecordedExpectations(requestDefinition, expectations -> {
                                    response.withBody(
                                        getHarConverter().serialize(expectationsToLogEvents(expectations)),
                                        MediaType.JSON_UTF_8
                                    );
                                    mockServerLogger.logEvent(logEntry);
                                    httpResponseFuture.complete(response);
                                });
                                break;
                            case CURL:
                                response.withBody("CURL not supported for RECORDED_EXPECTATIONS", MediaType.create("text", "plain").withCharset(UTF_8));
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                        }
                        break;
                    }
                    case ACTIVE_EXPECTATIONS: {
                        List<Expectation> expectations = requestMatchers.retrieveActiveExpectations(requestDefinition);
                        switch (format) {
                            case JAVA:
                                response.withBody(getExpectationToJavaSerializer().serialize(expectations), MediaType.create("application", "java").withCharset(UTF_8));
                                break;
                            case JSON:
                                response.withBody(getExpectationSerializer().serialize(expectations), MediaType.JSON_UTF_8);
                                break;
                            case LOG_ENTRIES:
                                response.withBody("LOG_ENTRIES not supported for ACTIVE_EXPECTATIONS", MediaType.create("text", "plain").withCharset(UTF_8));
                                break;
                            case OPENAPI:
                                response.withBody(
                                    getExpectationExportSerializer().serializeAsOpenApi(expectations),
                                    MediaType.JSON_UTF_8
                                );
                                break;
                            case POSTMAN:
                                response.withBody(
                                    getExpectationExportSerializer().serializeAsPostmanCollection(expectations),
                                    MediaType.JSON_UTF_8
                                );
                                break;
                            case BRUNO:
                                response
                                    .withBody(getExpectationExportSerializer().serializeAsBrunoCollection(expectations))
                                    .withHeader(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE.toString(), "application/zip")
                                    .withHeader("content-disposition", "attachment; filename=\"mockserver-expectations.bruno.zip\"");
                                break;
                            case HAR:
                                response.withBody(
                                    getHarConverter().serialize(expectationsToLogEvents(expectations)),
                                    MediaType.JSON_UTF_8
                                );
                                break;
                            case CURL:
                                response.withBody("CURL not supported for ACTIVE_EXPECTATIONS", MediaType.create("text", "plain").withCharset(UTF_8));
                                break;
                        }
                        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(RETRIEVED)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(logCorrelationId)
                                    .setHttpRequest(requestDefinition)
                                    .setMessageFormat("retrieved " + expectations.size() + " active expectations in " + format.name().toLowerCase() + " that match:{}")
                                    .setArguments(requestDefinition)
                            );
                        }
                        httpResponseFuture.complete(response);
                        break;
                    }
                    case METRICS: {
                        if (!configuration.metricsEnabled()) {
                            response.withBody("{}", MediaType.JSON_UTF_8);
                        } else {
                            StringBuilder metricsJson = new StringBuilder("{");
                            Metrics.Name[] names = Metrics.Name.values();
                            for (int i = 0; i < names.length; i++) {
                                metricsJson.append("\"").append(names[i].name()).append("\":").append(Metrics.get(names[i]));
                                if (i < names.length - 1) {
                                    metricsJson.append(",");
                                }
                            }
                            metricsJson.append("}");
                            response.withBody(metricsJson.toString(), MediaType.JSON_UTF_8);
                        }
                        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(RETRIEVED)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(logCorrelationId)
                                    .setHttpRequest(requestDefinition)
                                    .setMessageFormat("retrieved metrics")
                            );
                        }
                        httpResponseFuture.complete(response);
                        break;
                    }
                }

                try {
                    return httpResponseFuture.get(configuration.maxFutureTimeoutInMillis(), MILLISECONDS);
                } catch (ExecutionException | InterruptedException | TimeoutException ex) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setCorrelationId(logCorrelationId)
                            .setMessageFormat("exception handling request:{}error:{}")
                            .setArguments(request, ex.getMessage())
                            .setThrowable(ex)
                    );
                    throw new RuntimeException("Exception retrieving state for " + request, ex);
                }
            } catch (IllegalArgumentException iae) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setCorrelationId(logCorrelationId)
                        .setMessageFormat("exception handling request:{}error:{}")
                        .setArguments(request, iae.getMessage())
                        .setThrowable(iae)
                );
                if (iae.getMessage().contains(RetrieveType.class.getSimpleName())) {
                    throw new IllegalArgumentException("\"" + request.getFirstQueryStringParameter("type") + "\" is not a valid value for \"type\" parameter, only the following values are supported " + Arrays.stream(RetrieveType.values()).map(input -> input.name().toLowerCase()).collect(Collectors.toList()));
                }
                if (iae.getMessage().contains(Format.class.getSimpleName())) {
                    throw new IllegalArgumentException("\"" + request.getFirstQueryStringParameter("format") + "\" is not a valid value for \"format\" parameter, only the following values are supported " + Arrays.stream(Format.values()).map(input -> input.name().toLowerCase()).collect(Collectors.toList()));
                }
                throw iae;
            }
        } else {
            return response().withStatusCode(200);
        }
    }

    public Future<String> verify(Verification verification) {
        CompletableFuture<String> result = new CompletableFuture<>();
        verify(verification, result::complete);
        return result;
    }

    public void verify(Verification verification, Consumer<String> resultConsumer) {
        if (verification.getExpectationId() != null) {
            // check valid expectation id and populate for error message
            verification.withRequest(resolveExpectationId(verification.getExpectationId()));
        }
        mockServerLog.verify(verification, resultConsumer);
    }

    public Future<String> verify(VerificationSequence verification) {
        CompletableFuture<String> result = new CompletableFuture<>();
        verify(verification, result::complete);
        return result;
    }

    public void verify(VerificationSequence verificationSequence, Consumer<String> resultConsumer) {
        if (verificationSequence.getExpectationIds() != null && !verificationSequence.getExpectationIds().isEmpty()) {
            verificationSequence.withRequests(resolveExpectationIds(verificationSequence.getExpectationIds()));
        }
        mockServerLog.verify(verificationSequence, resultConsumer);
    }

    public boolean handle(HttpRequest request, ResponseWriter responseWriter, boolean warDeployment) {

        request.withLogCorrelationId(UUIDService.getUUID());
        setPort(request);

        if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.TRACE)
                    .setHttpRequest(request)
                    .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                    .setArguments(request)
            );
        }

        if (request.matches("PUT")) {

            CompletableFuture<Boolean> canHandle = new CompletableFuture<>();

            if (request.matches("PUT", PATH_PREFIX + "/expectation", "/expectation")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    List<Expectation> upsertedExpectations = new ArrayList<>();
                    for (Expectation expectation : getExpectationSerializer().deserializeArray(request.getBodyAsJsonOrXmlString(), false)) {
                        if (!warDeployment || validateSupportedFeatures(expectation, request, responseWriter)) {
                            upsertedExpectations.addAll(add(expectation));
                        }
                    }

                    responseWriter.writeResponse(request, response()
                        .withStatusCode(CREATED.code())
                        .withBody(getExpectationSerializer().serialize(upsertedExpectations), MediaType.JSON_UTF_8), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/openapi", "/openapi")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        List<Expectation> upsertedExpectations = new ArrayList<>();
                        String requestBody = request.getBodyAsJsonOrXmlString();
                        String contentType = request.getFirstHeader(CONTENT_TYPE.toString());
                        if (contentType != null) {
                            String baseType = contentType.split(";")[0].trim().toLowerCase();
                            if ("application/yaml".equals(baseType) || "application/x-yaml".equals(baseType) || "text/yaml".equals(baseType)) {
                                requestBody = YamlToJsonConverter.convertYamlToJson(requestBody);
                            }
                        }
                        for (OpenAPIExpectation openAPIExpectation : getOpenAPIExpectationSerializer().deserializeArray(requestBody, false)) {
                            upsertedExpectations.addAll(add(openAPIExpectation));
                        }
                        responseWriter.writeResponse(request, response()
                            .withStatusCode(CREATED.code())
                            .withBody(getExpectationSerializer().serialize(upsertedExpectations), MediaType.JSON_UTF_8), true);
                    } catch (IllegalArgumentException iae) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for open api expectation:{}error:{}")
                                .setArguments(request, iae.getMessage())
                                .setThrowable(iae)
                        );
                        responseWriter.writeResponse(
                            request,
                            BAD_REQUEST,
                            (!iae.getMessage().startsWith(OPEN_API_LOAD_ERROR) ? OPEN_API_LOAD_ERROR + (isNotBlank(iae.getMessage()) ? ", " : "") : "") + iae.getMessage(),
                            MediaType.create("text", "plain").toString()
                        );
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/wsdl", "/wsdl")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        List<Expectation> upsertedExpectations = add(
                            new org.mockserver.mock.wsdl.WsdlExpectationGenerator()
                                .generate(request.getBodyAsJsonOrXmlString())
                                .toArray(new Expectation[0])
                        );
                        responseWriter.writeResponse(request, response()
                            .withStatusCode(CREATED.code())
                            .withBody(getExpectationSerializer().serialize(upsertedExpectations), MediaType.JSON_UTF_8), true);
                    } catch (IllegalArgumentException iae) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for wsdl expectation:{}error:{}")
                                .setArguments(request, iae.getMessage())
                                .setThrowable(iae)
                        );
                        responseWriter.writeResponse(
                            request,
                            BAD_REQUEST,
                            iae.getMessage(),
                            MediaType.create("text", "plain").toString()
                        );
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/oidc", "/oidc")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        String requestBody = request.getBodyAsJsonOrXmlString();
                        org.mockserver.oidc.OidcProviderConfiguration oidcConfig;
                        if (requestBody == null || requestBody.trim().isEmpty()) {
                            oidcConfig = new org.mockserver.oidc.OidcProviderConfiguration();
                        } else {
                            oidcConfig = ObjectMapperFactory.createObjectMapper()
                                .readValue(requestBody, org.mockserver.oidc.OidcProviderConfiguration.class);
                        }
                        List<Expectation> upsertedExpectations = add(
                            new org.mockserver.oidc.OidcProviderGenerator()
                                .generate(oidcConfig)
                                .toArray(new Expectation[0])
                        );
                        responseWriter.writeResponse(request, response()
                            .withStatusCode(CREATED.code())
                            .withBody(getExpectationSerializer().serialize(upsertedExpectations), MediaType.JSON_UTF_8), true);
                    } catch (IllegalArgumentException iae) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for oidc provider:{}error:{}")
                                .setArguments(request, iae.getMessage())
                                .setThrowable(iae)
                        );
                        responseWriter.writeResponse(
                            request,
                            BAD_REQUEST,
                            iae.getMessage(),
                            MediaType.create("text", "plain").toString()
                        );
                    } catch (Exception e) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for oidc provider:{}error:{}")
                                .setArguments(request, e.getMessage())
                                .setThrowable(e)
                        );
                        responseWriter.writeResponse(
                            request,
                            BAD_REQUEST,
                            e.getMessage(),
                            MediaType.create("text", "plain").toString()
                        );
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/import", "/import")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        String requestBody = request.getBodyAsJsonOrXmlString();
                        if (requestBody == null || requestBody.trim().isEmpty()) {
                            throw new IllegalArgumentException("import request body is required — must be a HAR or Postman collection JSON document");
                        }
                        String formatParam = request.getFirstQueryStringParameter("format");
                        List<Expectation> importedExpectations;
                        if ("har".equalsIgnoreCase(formatParam)) {
                            importedExpectations = new org.mockserver.imports.HarImporter().importExpectations(requestBody);
                        } else if ("postman".equalsIgnoreCase(formatParam)) {
                            importedExpectations = new org.mockserver.imports.PostmanCollectionImporter().importExpectations(requestBody);
                        } else if (formatParam != null && !formatParam.isEmpty()) {
                            throw new IllegalArgumentException("unsupported import format: " + formatParam + " (supported formats: har, postman)");
                        } else {
                            // Auto-detect format from JSON structure
                            com.fasterxml.jackson.databind.JsonNode rootNode = ObjectMapperFactory.createObjectMapper().readTree(requestBody);
                            if (!rootNode.path("log").path("entries").isMissingNode()) {
                                importedExpectations = new org.mockserver.imports.HarImporter().importExpectations(requestBody);
                            } else if (!rootNode.path("info").isMissingNode() && !rootNode.path("item").isMissingNode()) {
                                importedExpectations = new org.mockserver.imports.PostmanCollectionImporter().importExpectations(requestBody);
                            } else {
                                throw new IllegalArgumentException("unable to auto-detect import format — use ?format=har or ?format=postman query parameter");
                            }
                        }
                        List<Expectation> upsertedExpectations = add(
                            importedExpectations.toArray(new Expectation[0])
                        );
                        responseWriter.writeResponse(request, response()
                            .withStatusCode(CREATED.code())
                            .withBody(getExpectationSerializer().serialize(upsertedExpectations), MediaType.JSON_UTF_8), true);
                    } catch (IllegalArgumentException iae) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for import:{}error:{}")
                                .setArguments(request, iae.getMessage())
                                .setThrowable(iae)
                        );
                        responseWriter.writeResponse(
                            request,
                            BAD_REQUEST,
                            iae.getMessage(),
                            MediaType.create("text", "plain").toString()
                        );
                    } catch (Exception e) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for import:{}error:{}")
                                .setArguments(request, e.getMessage())
                                .setThrowable(e)
                        );
                        responseWriter.writeResponse(
                            request,
                            BAD_REQUEST,
                            e.getMessage(),
                            MediaType.create("text", "plain").toString()
                        );
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/pact/verify", "/pact/verify")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, handlePactVerify(request), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/pact", "/pact")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        String consumer = request.getFirstQueryStringParameter("consumer");
                        String provider = request.getFirstQueryStringParameter("provider");
                        String pact = new org.mockserver.mock.pact.PactExporter()
                            .export(requestMatchers.retrieveActiveExpectations(null), consumer, provider);
                        responseWriter.writeResponse(request, response()
                            .withStatusCode(OK.code())
                            .withBody(pact, MediaType.JSON_UTF_8), true);
                    } catch (Exception e) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for pact export:{}error:{}")
                                .setArguments(request, e.getMessage())
                                .setThrowable(e)
                        );
                        responseWriter.writeResponse(
                            request,
                            BAD_REQUEST,
                            e.getMessage(),
                            MediaType.create("text", "plain").toString()
                        );
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/mode", "/mode")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        MockMode mode = MockMode.parse(request.getFirstQueryStringParameter("mode"));
                        mockMode = mode;
                        configuration.attemptToProxyIfNoMatchingExpectation(mode.proxyUnmatchedRequests());
                        responseWriter.writeResponse(request, response()
                            .withStatusCode(OK.code())
                            .withBody("{\"mode\":\"" + mode + "\",\"proxyUnmatchedRequests\":" + mode.proxyUnmatchedRequests() + "}", MediaType.JSON_UTF_8), true);
                    } catch (IllegalArgumentException iae) {
                        responseWriter.writeResponse(request, BAD_REQUEST, iae.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/clear", "/clear")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    clear(request);
                    responseWriter.writeResponse(request, OK);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/reset", "/reset")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    reset();
                    responseWriter.writeResponse(request, OK);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/clock", "/clock")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, handleClockPut(request), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/cassettes", "/cassettes")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleCassettesPut(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/serviceChaos", "/serviceChaos")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleServiceChaosPut(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/tcpChaos", "/tcpChaos")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleTcpChaosPut(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/grpcChaos", "/grpcChaos")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleGrpcChaosPut(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/asyncapi/verify", "/asyncapi/verify")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleAsyncApiVerify(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/asyncapi", "/asyncapi")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleAsyncApiPut(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/debugMismatch", "/debugMismatch")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, debugMismatch(request), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/explainUnmatched", "/explainUnmatched")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, explainUnmatched(request), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/retrieve", "/retrieve")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, retrieve(request), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/verify", "/verify")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    verify(getVerificationSerializer().deserialize(request.getBodyAsJsonOrXmlString()), result -> {
                        if (isEmpty(result)) {
                            responseWriter.writeResponse(request, ACCEPTED);
                        } else {
                            responseWriter.writeResponse(request, NOT_ACCEPTABLE, result, MediaType.create("text", "plain").toString());
                        }
                        canHandle.complete(true);
                    });
                } else {
                    canHandle.complete(true);
                }

            } else if (request.matches("PUT", PATH_PREFIX + "/verifySequence", "/verifySequence")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    verify(getVerificationSequenceSerializer().deserialize(request.getBodyAsJsonOrXmlString()), result -> {
                        if (isEmpty(result)) {
                            responseWriter.writeResponse(request, ACCEPTED);
                        } else {
                            responseWriter.writeResponse(request, NOT_ACCEPTABLE, result, MediaType.create("text", "plain").toString());
                        }
                        canHandle.complete(true);
                    });
                } else {
                    canHandle.complete(true);
                }

            } else if (request.matches("PUT", PATH_PREFIX + "/crud", "/crud")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
                        CrudExpectationsDefinition definition = objectMapper.readValue(request.getBodyAsJsonOrXmlString(), CrudExpectationsDefinition.class);
                        if (definition.getBasePath() == null || definition.getBasePath().isEmpty()) {
                            responseWriter.writeResponse(request, BAD_REQUEST, "basePath is required", MediaType.create("text", "plain").toString());
                        } else {
                            CrudDataStore store = new CrudDataStore(
                                definition.getIdField() != null ? definition.getIdField() : "id",
                                definition.getIdStrategy() != null ? definition.getIdStrategy() : CrudExpectationsDefinition.IdStrategy.AUTO_INCREMENT,
                                definition.getInitialData()
                            );
                            CrudActionHandler handler = new CrudActionHandler(store, definition.getBasePath());
                            crudDispatcher.register(definition.getBasePath(), handler);
                            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setLogLevel(Level.INFO)
                                        .setMessageFormat("registered CRUD resource at base path:{}")
                                        .setArguments(definition.getBasePath())
                                );
                            }
                            com.fasterxml.jackson.databind.node.ObjectNode responseNode = objectMapper.createObjectNode();
                            responseNode.put("basePath", definition.getBasePath());
                            responseNode.put("idField", definition.getIdField() != null ? definition.getIdField() : "id");
                            responseNode.put("idStrategy", (definition.getIdStrategy() != null ? definition.getIdStrategy() : CrudExpectationsDefinition.IdStrategy.AUTO_INCREMENT).name());
                            responseNode.put("itemCount", store.size());
                            responseWriter.writeResponse(request, response()
                                .withStatusCode(CREATED.code())
                                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(responseNode), MediaType.JSON_UTF_8), true);
                        }
                    } catch (Exception e) {
                        responseWriter.writeResponse(request, BAD_REQUEST, "failed to register CRUD resource: " + e.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/grpc/descriptors", "/grpc/descriptors")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        byte[] bodyBytes = request.getBodyAsRawBytes();
                        if (bodyBytes != null && bodyBytes.length > 0) {
                            grpcDescriptorStore.loadDescriptorSet(bodyBytes);
                            responseWriter.writeResponse(request, response()
                                .withStatusCode(CREATED.code())
                                .withBody("{\"status\":\"loaded\"}", MediaType.JSON_UTF_8), true);
                        } else {
                            responseWriter.writeResponse(request, BAD_REQUEST, "descriptor set body is empty", MediaType.create("text", "plain").toString());
                        }
                    } catch (Exception e) {
                        responseWriter.writeResponse(request, BAD_REQUEST, "failed to load gRPC descriptor: " + e.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/grpc/services", "/grpc/services")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
                        com.fasterxml.jackson.databind.node.ArrayNode servicesArray = objectMapper.createArrayNode();
                        for (java.util.Map.Entry<String, com.google.protobuf.Descriptors.ServiceDescriptor> entry : grpcDescriptorStore.getAllServices().entrySet()) {
                            com.fasterxml.jackson.databind.node.ObjectNode serviceNode = objectMapper.createObjectNode();
                            serviceNode.put("name", entry.getKey());
                            com.fasterxml.jackson.databind.node.ArrayNode methodsArray = serviceNode.putArray("methods");
                            for (com.google.protobuf.Descriptors.MethodDescriptor method : entry.getValue().getMethods()) {
                                com.fasterxml.jackson.databind.node.ObjectNode methodNode = objectMapper.createObjectNode();
                                methodNode.put("name", method.getName());
                                methodNode.put("inputType", method.getInputType().getFullName());
                                methodNode.put("outputType", method.getOutputType().getFullName());
                                methodNode.put("clientStreaming", method.isClientStreaming());
                                methodNode.put("serverStreaming", method.isServerStreaming());
                                methodsArray.add(methodNode);
                            }
                            servicesArray.add(serviceNode);
                        }
                        responseWriter.writeResponse(request, response()
                            .withStatusCode(OK.code())
                            .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(servicesArray), MediaType.JSON_UTF_8), true);
                    } catch (Exception e) {
                        responseWriter.writeResponse(request, BAD_REQUEST, "failed to list gRPC services: " + e.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/grpc/health", "/grpc/health")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleGrpcHealthPut(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/grpc/clear", "/grpc/clear")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    grpcDescriptorStore.reset();
                    responseWriter.writeResponse(request, OK);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/wasm/modules", "/wasm/modules")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        String moduleName = request.getFirstQueryStringParameter("name");
                        if (isBlank(moduleName)) {
                            responseWriter.writeResponse(request, BAD_REQUEST, "query parameter 'name' is required", MediaType.create("text", "plain").toString());
                        } else {
                            byte[] bodyBytes = request.getBodyAsRawBytes();
                            if (bodyBytes != null && bodyBytes.length > 0) {
                                org.mockserver.wasm.WasmStore.getInstance().put(moduleName, bodyBytes);
                                responseWriter.writeResponse(request, withDashboardCORS(request, response()
                                    .withStatusCode(CREATED.code())
                                    .withBody("{\"status\":\"loaded\",\"moduleName\":\"" + moduleName + "\"}", MediaType.JSON_UTF_8)), true);
                            } else {
                                responseWriter.writeResponse(request, BAD_REQUEST, "WASM module body is empty", MediaType.create("text", "plain").toString());
                            }
                        }
                    } catch (Exception e) {
                        responseWriter.writeResponse(request, BAD_REQUEST, "failed to load WASM module: " + e.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/files/store", "/files/store")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        String bodyString = request.getBodyAsJsonOrXmlString();
                        if (isNotBlank(bodyString)) {
                            com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
                            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(bodyString);
                            if (node.has("name") && node.has("content")) {
                                String fileName = node.get("name").asText();
                                String content = node.get("content").asText();
                                byte[] fileContent;
                                if (node.has("base64") && node.get("base64").asBoolean()) {
                                    fileContent = java.util.Base64.getDecoder().decode(content);
                                } else {
                                    fileContent = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                }
                                fileStore.store(fileName, fileContent);
                                responseWriter.writeResponse(request, response()
                                    .withStatusCode(CREATED.code())
                                    .withBody("{\"name\":\"" + fileName + "\",\"size\":" + fileContent.length + "}", MediaType.JSON_UTF_8), true);
                            } else {
                                responseWriter.writeResponse(request, BAD_REQUEST, "request body must contain 'name' and 'content' fields", MediaType.create("text", "plain").toString());
                            }
                        } else {
                            responseWriter.writeResponse(request, BAD_REQUEST, "request body is empty", MediaType.create("text", "plain").toString());
                        }
                    } catch (Exception e) {
                        responseWriter.writeResponse(request, BAD_REQUEST, "failed to store file: " + e.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/files/retrieve", "/files/retrieve")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        String bodyString = request.getBodyAsJsonOrXmlString();
                        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
                        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(bodyString);
                        String fileName = node.has("name") ? node.get("name").asText() : null;
                        if (isBlank(fileName)) {
                            responseWriter.writeResponse(request, BAD_REQUEST, "request body must contain 'name' field", MediaType.create("text", "plain").toString());
                        } else {
                            byte[] content = fileStore.retrieve(fileName);
                            if (content != null) {
                                responseWriter.writeResponse(request, response()
                                    .withStatusCode(OK.code())
                                    .withBody(content), true);
                            } else {
                                responseWriter.writeResponse(request, NOT_FOUND, "file not found: " + fileName, MediaType.create("text", "plain").toString());
                            }
                        }
                    } catch (Exception e) {
                        responseWriter.writeResponse(request, BAD_REQUEST, "failed to retrieve file: " + e.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/files/list", "/files/list")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
                        responseWriter.writeResponse(request, response()
                            .withStatusCode(OK.code())
                            .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(fileStore.listFiles()), MediaType.JSON_UTF_8), true);
                    } catch (Exception e) {
                        responseWriter.writeResponse(request, BAD_REQUEST, "failed to list files: " + e.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/files/delete", "/files/delete")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        String bodyString = request.getBodyAsJsonOrXmlString();
                        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
                        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(bodyString);
                        String fileName = node.has("name") ? node.get("name").asText() : null;
                        if (isBlank(fileName)) {
                            responseWriter.writeResponse(request, BAD_REQUEST, "request body must contain 'name' field", MediaType.create("text", "plain").toString());
                        } else if (fileStore.delete(fileName)) {
                            responseWriter.writeResponse(request, OK);
                        } else {
                            responseWriter.writeResponse(request, NOT_FOUND, "file not found: " + fileName, MediaType.create("text", "plain").toString());
                        }
                    } catch (Exception e) {
                        responseWriter.writeResponse(request, BAD_REQUEST, "failed to delete file: " + e.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/generateExpectation", "/generateExpectation")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleGenerateExpectation(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT") && request.getPath() != null
                && request.getPath().getValue() != null
                && (request.getPath().getValue().startsWith(PATH_PREFIX + "/scenario/")
                    || request.getPath().getValue().startsWith("/scenario/"))) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleScenarioPut(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/diff", "/diff")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleDiff(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/drift/clear", "/drift/clear")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    org.mockserver.mock.drift.DriftStore.getInstance().clear();
                    responseWriter.writeResponse(request, withDashboardCORS(request, response()
                        .withStatusCode(OK.code())
                        .withBody("{\"status\":\"cleared\"}", MediaType.JSON_UTF_8)), true);
                }
                canHandle.complete(true);

            } else {

                canHandle.complete(false);

            }

            try {
                return canHandle.get(configuration.maxFutureTimeoutInMillis(), MILLISECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException ex) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("exception handling request:{}error:{}")
                        .setArguments(request, ex.getMessage())
                        .setThrowable(ex)
                );
                return false;
            }

        } else if (request.matches("GET")) {

            if (request.matches("GET", PATH_PREFIX + "/clock", "/clock")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, handleClockGet(), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/cassettes", "/cassettes")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleCassettesGet()), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/serviceChaos", "/serviceChaos")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleServiceChaosGet()), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/tcpChaos", "/tcpChaos")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleTcpChaosGet()), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/grpcChaos", "/grpcChaos")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleGrpcChaosGet()), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/mode", "/mode")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    boolean proxyFlag = configuration.attemptToProxyIfNoMatchingExpectation();
                    // report the last explicitly-set mode when it still agrees with the live flag
                    // (so CAPTURE round-trips), otherwise derive the mode from the flag
                    MockMode mode = (mockMode != null && mockMode.proxyUnmatchedRequests() == proxyFlag)
                        ? mockMode
                        : MockMode.fromProxyFlag(proxyFlag);
                    responseWriter.writeResponse(request, response()
                        .withStatusCode(OK.code())
                        .withBody("{\"mode\":\"" + mode + "\",\"proxyUnmatchedRequests\":" + mode.proxyUnmatchedRequests() + "}", MediaType.JSON_UTF_8), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/wasm/modules", "/wasm/modules")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
                        com.fasterxml.jackson.databind.node.ArrayNode modulesArray = objectMapper.createArrayNode();
                        for (String name : org.mockserver.wasm.WasmStore.getInstance().listNames()) {
                            modulesArray.add(name);
                        }
                        responseWriter.writeResponse(request, withDashboardCORS(request, response()
                            .withStatusCode(OK.code())
                            .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(modulesArray), MediaType.JSON_UTF_8)), true);
                    } catch (Exception e) {
                        responseWriter.writeResponse(request, BAD_REQUEST, "failed to list WASM modules: " + e.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/asyncapi", "/asyncapi")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleAsyncApiGet()), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/drift", "/drift")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleDriftGet(request)), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/grpc/health", "/grpc/health")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleGrpcHealthGet()), true);
                }
                return true;
            }
            if (request.matches("GET") && request.getPath() != null
                && request.getPath().getValue() != null
                && (request.getPath().getValue().startsWith(PATH_PREFIX + "/scenario/")
                    || request.getPath().getValue().startsWith("/scenario/")
                    || request.getPath().getValue().equals(PATH_PREFIX + "/scenario")
                    || request.getPath().getValue().equals("/scenario"))) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleScenarioGet(request)), true);
                }
                return true;
            }
            return false;

        } else if (request.matches("PATCH")) {

            if (request.matches("PATCH", PATH_PREFIX + "/serviceChaos", "/serviceChaos")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleServiceChaosPatch(request)), true);
                }
                return true;
            }
            if (request.matches("PATCH", PATH_PREFIX + "/tcpChaos", "/tcpChaos")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleTcpChaosPatch(request)), true);
                }
                return true;
            }
            if (request.matches("PATCH", PATH_PREFIX + "/grpcChaos", "/grpcChaos")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleGrpcChaosPatch(request)), true);
                }
                return true;
            }
            return false;

        } else if (request.matches("DELETE")) {

            if (request.matches("DELETE", PATH_PREFIX + "/wasm/modules", "/wasm/modules")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    String moduleName = request.getFirstQueryStringParameter("name");
                    if (isBlank(moduleName)) {
                        responseWriter.writeResponse(request, BAD_REQUEST, "query parameter 'name' is required", MediaType.create("text", "plain").toString());
                    } else if (org.mockserver.wasm.WasmStore.getInstance().contains(moduleName)) {
                        org.mockserver.wasm.WasmStore.getInstance().remove(moduleName);
                        responseWriter.writeResponse(request, withDashboardCORS(request, response().withStatusCode(OK.code())), true);
                    } else {
                        responseWriter.writeResponse(request, NOT_FOUND, "WASM module '" + moduleName + "' not found", MediaType.create("text", "plain").toString());
                    }
                }
                return true;
            }
            if (request.matches("DELETE", PATH_PREFIX + "/cassettes", "/cassettes")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleCassettesDelete(request)), true);
                }
                return true;
            }
            return false;

        } else {

            return false;

        }

    }

    private HttpResponse handleClockPut(HttpRequest request) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        objectMapper.createObjectNode().put("error", "request body is required with 'action' field")), MediaType.JSON_UTF_8);
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            String action = node.has("action") ? node.get("action").asText() : null;
            if (isBlank(action)) {
                return response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        objectMapper.createObjectNode().put("error", "'action' field is required, must be one of: freeze, advance, reset")), MediaType.JSON_UTF_8);
            }
            switch (action.toLowerCase()) {
                case "freeze": {
                    java.time.Instant instant = null;
                    if (node.has("instant") && !node.get("instant").isNull()) {
                        try {
                            instant = java.time.Instant.parse(node.get("instant").asText());
                        } catch (Exception e) {
                            return response()
                                .withStatusCode(BAD_REQUEST.code())
                                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                                    objectMapper.createObjectNode().put("error", "invalid 'instant' value, must be ISO-8601 format (e.g. 2024-01-01T00:00:00Z)")), MediaType.JSON_UTF_8);
                        }
                    }
                    TimeService.freeze(instant);
                    break;
                }
                case "advance": {
                    long durationMillis = 0;
                    if (node.has("durationMillis") && !node.get("durationMillis").isNull()) {
                        durationMillis = node.get("durationMillis").asLong(0);
                    }
                    if (durationMillis <= 0) {
                        return response()
                            .withStatusCode(BAD_REQUEST.code())
                            .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                                objectMapper.createObjectNode().put("error", "'durationMillis' must be a positive number")), MediaType.JSON_UTF_8);
                    }
                    TimeService.advance(java.time.Duration.ofMillis(durationMillis));
                    break;
                }
                case "reset": {
                    TimeService.reset();
                    break;
                }
                default: {
                    return response()
                        .withStatusCode(BAD_REQUEST.code())
                        .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                            objectMapper.createObjectNode().put("error", "unknown action '" + action + "', must be one of: freeze, advance, reset")), MediaType.JSON_UTF_8);
                }
            }
            // success response
            java.time.Instant currentInstant = TimeService.now();
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat("clock " + action.toLowerCase() + ", current instant:{}")
                        .setArguments(currentInstant)
                );
            }
            com.fasterxml.jackson.databind.node.ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", action.toLowerCase());
            resultNode.put("currentInstant", currentInstant.toString());
            resultNode.put("currentEpochMillis", currentInstant.toEpochMilli());
            return response()
                .withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultNode), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper errorMapper = ObjectMapperFactory.createObjectMapper();
                return response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody(errorMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        errorMapper.createObjectNode().put("error", "failed to process clock request: " + e.getMessage())), MediaType.JSON_UTF_8);
            } catch (Exception jsonError) {
                return response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"failed to process clock request\"}", MediaType.JSON_UTF_8);
            }
        }
    }

    private HttpResponse handleClockGet() {
        try {
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
            java.time.Instant currentInstant = TimeService.now();
            com.fasterxml.jackson.databind.node.ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("currentInstant", currentInstant.toString());
            resultNode.put("currentEpochMillis", currentInstant.toEpochMilli());
            resultNode.put("frozen", TimeService.isFrozen());
            return response()
                .withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultNode), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response()
                .withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to get clock status\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handleServiceChaosPut(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return serviceChaosError(objectMapper, "request body is required with a 'host' field (and a 'chaos' object), or 'clear':true to clear all");
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            boolean clearAll = node.path("clear").asBoolean(false);
            String host = node.path("host").asText(null);
            org.mockserver.mock.action.http.ServiceChaosRegistry registry = org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance();
            // 'clear' (clear all) and 'host' (single-host operation) are mutually exclusive
            if (clearAll && !isBlank(host)) {
                return serviceChaosError(objectMapper, "cannot specify both 'clear' and 'host'");
            }
            // clear all service-scoped chaos
            if (clearAll) {
                registry.reset();
                logServiceChaos(request, "cleared all service-scoped chaos", null);
                com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
                result.put("status", "cleared");
                return response().withStatusCode(OK.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
            }
            if (isBlank(host)) {
                return serviceChaosError(objectMapper, "'host' field is required");
            }
            // remove the host's chaos when requested or when no chaos object is supplied
            if (node.path("remove").asBoolean(false) || !node.hasNonNull("chaos")) {
                registry.remove(host);
                logServiceChaos(request, "removed service-scoped chaos for host:{}", host);
                com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
                result.put("status", "removed");
                result.put("host", host);
                return response().withStatusCode(OK.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
            }
            // optional time-to-live (auto-revert): the registration auto-expires after this many ms
            long ttlMillis = 0L;
            if (node.hasNonNull("ttlMillis")) {
                ttlMillis = node.path("ttlMillis").asLong(0L);
                if (ttlMillis < 1) {
                    return serviceChaosError(objectMapper, "'ttlMillis' must be >= 1 when supplied");
                }
            }
            // register/replace — deserialize through the DTO so range validation runs
            org.mockserver.serialization.model.HttpChaosProfileDTO dto =
                objectMapper.treeToValue(node.get("chaos"), org.mockserver.serialization.model.HttpChaosProfileDTO.class);
            org.mockserver.model.HttpChaosProfile profile = dto.buildObject();
            registry.put(host, profile, ttlMillis);
            logServiceChaos(request, ttlMillis > 0
                ? "registered service-scoped chaos (ttl " + ttlMillis + "ms) for host:{}"
                : "registered service-scoped chaos for host:{}", host);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "registered");
            result.put("host", host);
            if (ttlMillis > 0) {
                result.put("ttlMillis", ttlMillis);
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            // thrown by HttpChaosProfile validation (e.g. errorStatus out of range)
            return serviceChaosError(objectMapper, "invalid chaos profile: " + e.getMessage());
        } catch (Exception e) {
            return serviceChaosError(objectMapper, "failed to process service chaos request: " + e.getMessage());
        }
    }

    private HttpResponse handleServiceChaosPatch(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return serviceChaosError(objectMapper, "request body is required with 'host' and 'chaos' fields");
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            String host = node.path("host").asText(null);
            if (isBlank(host)) {
                return serviceChaosError(objectMapper, "'host' field is required");
            }
            if (!node.hasNonNull("chaos")) {
                return serviceChaosError(objectMapper, "'chaos' field is required with at least one field to patch");
            }
            org.mockserver.serialization.model.HttpChaosProfileDTO dto =
                objectMapper.treeToValue(node.get("chaos"), org.mockserver.serialization.model.HttpChaosProfileDTO.class);
            org.mockserver.model.HttpChaosProfile partial = dto.buildObject();
            org.mockserver.mock.action.http.ServiceChaosRegistry registry = org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance();
            org.mockserver.model.HttpChaosProfile updated = registry.patch(host, partial);
            logServiceChaos(request, "patched service-scoped chaos for host:{}", host);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "patched");
            result.put("host", host);
            if (updated != null) {
                result.set("chaos", objectMapper.valueToTree(new org.mockserver.serialization.model.HttpChaosProfileDTO(updated)));
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            return serviceChaosError(objectMapper, "invalid chaos profile: " + e.getMessage());
        } catch (Exception e) {
            return serviceChaosError(objectMapper, "failed to process service chaos patch: " + e.getMessage());
        }
    }

    /**
     * Add CORS headers to a dashboard-facing control-plane response unconditionally,
     * so the dashboard works when served from a different origin (e.g. the UI dev
     * server) without requiring {@code enableCORSForAPI} to be set. This mirrors the
     * always-on CORS already applied by the metrics ({@code MetricsHandler}) and MCP
     * endpoints. {@code CORSHeaders.addCORSHeaders} is idempotent
     * ({@code setHeaderIfNotAlreadyExists}), so it composes safely with the
     * conditional CORS that {@code ResponseWriter} may also apply.
     */
    private HttpResponse withDashboardCORS(HttpRequest request, HttpResponse response) {
        corsHeaders.addCORSHeaders(request, response);
        return response;
    }

    private HttpResponse handleServiceChaosGet() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            org.mockserver.mock.action.http.ServiceChaosRegistry registry = org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance();
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode services = result.putObject("services");
            registry.entries().forEach((host, profile) ->
                services.set(host, objectMapper.valueToTree(new org.mockserver.serialization.model.HttpChaosProfileDTO(profile))));
            // remaining time-to-live (ms) for any TTL-bearing registration, so an operator/orchestrator can see the countdown
            java.util.Map<String, Long> ttlRemaining = registry.ttlRemainingMillis();
            if (!ttlRemaining.isEmpty()) {
                com.fasterxml.jackson.databind.node.ObjectNode ttlNode = result.putObject("ttlRemainingMillis");
                ttlRemaining.forEach((h, ms) -> ttlNode.put(h, ms.longValue()));
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to get service chaos\"}", MediaType.JSON_UTF_8);
        }
    }

    private void logServiceChaos(HttpRequest request, String messageFormat, String host) {
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            LogEntry entry = new LogEntry()
                .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(messageFormat);
            if (host != null) {
                entry.setArguments(host);
            }
            mockServerLogger.logEvent(entry);
        }
    }

    private HttpResponse serviceChaosError(com.fasterxml.jackson.databind.ObjectMapper objectMapper, String message) {
        try {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    objectMapper.createObjectNode().put("error", message)), MediaType.JSON_UTF_8);
        } catch (Exception jsonError) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to process service chaos request\"}", MediaType.JSON_UTF_8);
        }
    }

    // --- TCP Chaos endpoint helpers ---

    private HttpResponse handleTcpChaosPut(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return tcpChaosError(objectMapper, "request body is required with a 'host' field (and a 'chaos' object), or 'clear':true to clear all");
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            boolean clearAll = node.path("clear").asBoolean(false);
            String host = node.path("host").asText(null);
            org.mockserver.mock.action.http.TcpChaosRegistry registry = org.mockserver.mock.action.http.TcpChaosRegistry.getInstance();
            if (clearAll && !isBlank(host)) {
                return tcpChaosError(objectMapper, "cannot specify both 'clear' and 'host'");
            }
            if (clearAll) {
                registry.reset();
                logTcpChaos(request, "cleared all TCP-layer chaos", null);
                com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
                result.put("status", "cleared");
                return response().withStatusCode(OK.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
            }
            if (isBlank(host)) {
                return tcpChaosError(objectMapper, "'host' field is required");
            }
            if (node.path("remove").asBoolean(false) || !node.hasNonNull("chaos")) {
                registry.remove(host);
                logTcpChaos(request, "removed TCP-layer chaos for host:{}", host);
                com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
                result.put("status", "removed");
                result.put("host", host);
                return response().withStatusCode(OK.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
            }
            long ttlMillis = 0L;
            if (node.hasNonNull("ttlMillis")) {
                ttlMillis = node.path("ttlMillis").asLong(0L);
                if (ttlMillis < 1) {
                    return tcpChaosError(objectMapper, "'ttlMillis' must be >= 1 when supplied");
                }
            }
            org.mockserver.serialization.model.TcpChaosProfileDTO dto =
                objectMapper.treeToValue(node.get("chaos"), org.mockserver.serialization.model.TcpChaosProfileDTO.class);
            org.mockserver.model.TcpChaosProfile profile = dto.buildObject();
            registry.put(host, profile, ttlMillis);
            logTcpChaos(request, ttlMillis > 0
                ? "registered TCP-layer chaos (ttl " + ttlMillis + "ms) for host:{}"
                : "registered TCP-layer chaos for host:{}", host);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "registered");
            result.put("host", host);
            if (ttlMillis > 0) {
                result.put("ttlMillis", ttlMillis);
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            return tcpChaosError(objectMapper, "invalid TCP chaos profile: " + e.getMessage());
        } catch (Exception e) {
            return tcpChaosError(objectMapper, "failed to process TCP chaos request: " + e.getMessage());
        }
    }

    private HttpResponse handleTcpChaosPatch(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return tcpChaosError(objectMapper, "request body is required with 'host' and 'chaos' fields");
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            String host = node.path("host").asText(null);
            if (isBlank(host)) {
                return tcpChaosError(objectMapper, "'host' field is required");
            }
            if (!node.hasNonNull("chaos")) {
                return tcpChaosError(objectMapper, "'chaos' field is required with at least one field to patch");
            }
            org.mockserver.serialization.model.TcpChaosProfileDTO dto =
                objectMapper.treeToValue(node.get("chaos"), org.mockserver.serialization.model.TcpChaosProfileDTO.class);
            org.mockserver.model.TcpChaosProfile partial = dto.buildObject();
            org.mockserver.mock.action.http.TcpChaosRegistry registry = org.mockserver.mock.action.http.TcpChaosRegistry.getInstance();
            org.mockserver.model.TcpChaosProfile updated = registry.patch(host, partial);
            logTcpChaos(request, "patched TCP-layer chaos for host:{}", host);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "patched");
            result.put("host", host);
            if (updated != null) {
                result.set("chaos", objectMapper.valueToTree(new org.mockserver.serialization.model.TcpChaosProfileDTO(updated)));
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            return tcpChaosError(objectMapper, "invalid TCP chaos profile: " + e.getMessage());
        } catch (Exception e) {
            return tcpChaosError(objectMapper, "failed to process TCP chaos patch: " + e.getMessage());
        }
    }

    private HttpResponse handleTcpChaosGet() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            org.mockserver.mock.action.http.TcpChaosRegistry registry = org.mockserver.mock.action.http.TcpChaosRegistry.getInstance();
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode hosts = result.putObject("hosts");
            registry.entries().forEach((host, profile) ->
                hosts.set(host, objectMapper.valueToTree(new org.mockserver.serialization.model.TcpChaosProfileDTO(profile))));
            java.util.Map<String, Long> ttlRemaining = registry.ttlRemainingMillis();
            if (!ttlRemaining.isEmpty()) {
                com.fasterxml.jackson.databind.node.ObjectNode ttlNode = result.putObject("ttlRemainingMillis");
                ttlRemaining.forEach((h, ms) -> ttlNode.put(h, ms.longValue()));
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to get TCP chaos\"}", MediaType.JSON_UTF_8);
        }
    }

    private void logTcpChaos(HttpRequest request, String messageFormat, String host) {
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            LogEntry entry = new LogEntry()
                .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(messageFormat);
            if (host != null) {
                entry.setArguments(host);
            }
            mockServerLogger.logEvent(entry);
        }
    }

    private HttpResponse tcpChaosError(com.fasterxml.jackson.databind.ObjectMapper objectMapper, String message) {
        try {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    objectMapper.createObjectNode().put("error", message)), MediaType.JSON_UTF_8);
        } catch (Exception jsonError) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to process TCP chaos request\"}", MediaType.JSON_UTF_8);
        }
    }

    // --- gRPC Chaos endpoint helpers ---

    private HttpResponse handleGrpcChaosPut(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return grpcChaosError(objectMapper, "request body is required with a 'service' field (and a 'chaos' object), or 'clear':true to clear all");
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            boolean clearAll = node.path("clear").asBoolean(false);
            String service = node.has("service") ? node.path("service").asText("") : null;
            org.mockserver.mock.action.http.GrpcChaosRegistry registry = org.mockserver.mock.action.http.GrpcChaosRegistry.getInstance();
            if (clearAll && service != null) {
                return grpcChaosError(objectMapper, "cannot specify both 'clear' and 'service'");
            }
            if (clearAll) {
                registry.reset();
                logGrpcChaos(request, "cleared all gRPC chaos", null);
                com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
                result.put("status", "cleared");
                return response().withStatusCode(OK.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
            }
            if (service == null) {
                return grpcChaosError(objectMapper, "'service' field is required");
            }
            if (node.path("remove").asBoolean(false) || !node.hasNonNull("chaos")) {
                registry.remove(service);
                logGrpcChaos(request, "removed gRPC chaos for service:{}", service);
                com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
                result.put("status", "removed");
                result.put("service", service);
                return response().withStatusCode(OK.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
            }
            long ttlMillis = 0L;
            if (node.hasNonNull("ttlMillis")) {
                ttlMillis = node.path("ttlMillis").asLong(0L);
                if (ttlMillis < 1) {
                    return grpcChaosError(objectMapper, "'ttlMillis' must be >= 1 when supplied");
                }
            }
            org.mockserver.serialization.model.GrpcChaosProfileDTO dto =
                objectMapper.treeToValue(node.get("chaos"), org.mockserver.serialization.model.GrpcChaosProfileDTO.class);
            org.mockserver.model.GrpcChaosProfile profile = dto.buildObject();
            registry.put(service, profile, ttlMillis);
            logGrpcChaos(request, ttlMillis > 0
                ? "registered gRPC chaos (ttl " + ttlMillis + "ms) for service:{}"
                : "registered gRPC chaos for service:{}", service);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "registered");
            result.put("service", service);
            if (ttlMillis > 0) {
                result.put("ttlMillis", ttlMillis);
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            return grpcChaosError(objectMapper, "invalid gRPC chaos profile: " + e.getMessage());
        } catch (Exception e) {
            return grpcChaosError(objectMapper, "failed to process gRPC chaos request: " + e.getMessage());
        }
    }

    private HttpResponse handleGrpcChaosPatch(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return grpcChaosError(objectMapper, "request body is required with 'service' and 'chaos' fields");
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            String service = node.has("service") ? node.path("service").asText("") : null;
            if (service == null) {
                return grpcChaosError(objectMapper, "'service' field is required");
            }
            if (!node.hasNonNull("chaos")) {
                return grpcChaosError(objectMapper, "'chaos' field is required with at least one field to patch");
            }
            org.mockserver.serialization.model.GrpcChaosProfileDTO dto =
                objectMapper.treeToValue(node.get("chaos"), org.mockserver.serialization.model.GrpcChaosProfileDTO.class);
            org.mockserver.model.GrpcChaosProfile partial = dto.buildObject();
            org.mockserver.mock.action.http.GrpcChaosRegistry registry = org.mockserver.mock.action.http.GrpcChaosRegistry.getInstance();
            org.mockserver.model.GrpcChaosProfile updated = registry.patch(service, partial);
            logGrpcChaos(request, "patched gRPC chaos for service:{}", service);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "patched");
            result.put("service", service);
            if (updated != null) {
                result.set("chaos", objectMapper.valueToTree(new org.mockserver.serialization.model.GrpcChaosProfileDTO(updated)));
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            return grpcChaosError(objectMapper, "invalid gRPC chaos profile: " + e.getMessage());
        } catch (Exception e) {
            return grpcChaosError(objectMapper, "failed to process gRPC chaos patch: " + e.getMessage());
        }
    }

    private HttpResponse handleGrpcChaosGet() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            org.mockserver.mock.action.http.GrpcChaosRegistry registry = org.mockserver.mock.action.http.GrpcChaosRegistry.getInstance();
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode services = result.putObject("services");
            registry.entries().forEach((service, profile) ->
                services.set(service, objectMapper.valueToTree(new org.mockserver.serialization.model.GrpcChaosProfileDTO(profile))));
            java.util.Map<String, Long> ttlRemaining = registry.ttlRemainingMillis();
            if (!ttlRemaining.isEmpty()) {
                com.fasterxml.jackson.databind.node.ObjectNode ttlNode = result.putObject("ttlRemainingMillis");
                ttlRemaining.forEach((s, ms) -> ttlNode.put(s, ms.longValue()));
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to get gRPC chaos\"}", MediaType.JSON_UTF_8);
        }
    }

    private void logGrpcChaos(HttpRequest request, String messageFormat, String service) {
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            LogEntry entry = new LogEntry()
                .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(messageFormat);
            if (service != null) {
                entry.setArguments(service);
            }
            mockServerLogger.logEvent(entry);
        }
    }

    private HttpResponse grpcChaosError(com.fasterxml.jackson.databind.ObjectMapper objectMapper, String message) {
        try {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    objectMapper.createObjectNode().put("error", message)), MediaType.JSON_UTF_8);
        } catch (Exception jsonError) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to process gRPC chaos request\"}", MediaType.JSON_UTF_8);
        }
    }

    // --- Scenario endpoint helpers ---

    /**
     * Extracts the scenario name from a request path.
     * Handles both {@code /mockserver/scenario/{name}} and {@code /scenario/{name}} prefixes.
     * Returns the full remaining path after the prefix (which may include "/trigger" suffix).
     */
    private String extractScenarioPath(HttpRequest request) {
        String path = request.getPath().getValue();
        String prefixFull = PATH_PREFIX + "/scenario/";
        String prefixShort = "/scenario/";
        if (path.startsWith(prefixFull)) {
            return path.substring(prefixFull.length());
        } else if (path.startsWith(prefixShort)) {
            return path.substring(prefixShort.length());
        }
        return null;
    }

    /**
     * Handles PUT /mockserver/scenario/{name} and PUT /mockserver/scenario/{name}/trigger.
     * <p>
     * PUT /mockserver/scenario/{name}:
     *   Body: {"state": "Running"} — set state immediately
     *   Body: {"state": "Running", "transitionAfterMs": 5000, "nextState": "Finished"} — set state and schedule timed transition
     * <p>
     * PUT /mockserver/scenario/{name}/trigger:
     *   Body: {"newState": "Step3"} — set state to newState immediately
     */
    private HttpResponse handleScenarioPut(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String scenarioPath = extractScenarioPath(request);
            if (isBlank(scenarioPath)) {
                return scenarioError(objectMapper, "scenario name is required in the path");
            }

            boolean isTrigger = scenarioPath.endsWith("/trigger");
            String scenarioName = isTrigger ? scenarioPath.substring(0, scenarioPath.length() - "/trigger".length()) : scenarioPath;

            if (isBlank(scenarioName)) {
                return scenarioError(objectMapper, "scenario name is required in the path");
            }

            ScenarioManager scenarioManager = requestMatchers.getScenarioManager();
            String body = request.getBodyAsJsonOrXmlString();

            if (isTrigger) {
                // PUT /mockserver/scenario/{name}/trigger — external trigger to set state
                if (isBlank(body)) {
                    return scenarioError(objectMapper, "request body is required with 'newState' field");
                }
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
                String newState = node.path("newState").asText(null);
                if (isBlank(newState)) {
                    return scenarioError(objectMapper, "'newState' field is required");
                }
                scenarioManager.setState(scenarioName, newState);
                logScenario(request, "triggered scenario state transition for scenario:{} to state:{}", scenarioName, newState);

                com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
                result.put("scenarioName", scenarioName);
                result.put("currentState", newState);
                return response().withStatusCode(OK.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
            } else {
                // PUT /mockserver/scenario/{name} — set state, optionally schedule transition
                if (isBlank(body)) {
                    return scenarioError(objectMapper, "request body is required with 'state' field");
                }
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
                String state = node.path("state").asText(null);
                if (isBlank(state)) {
                    return scenarioError(objectMapper, "'state' field is required");
                }
                scenarioManager.setState(scenarioName, state);
                logScenario(request, "set scenario state for scenario:{} to state:{}", scenarioName, state);

                // optional timed transition
                Long transitionAfterMs = node.hasNonNull("transitionAfterMs") ? node.get("transitionAfterMs").asLong() : null;
                String nextState = node.path("nextState").asText(null);

                if (transitionAfterMs != null && transitionAfterMs > 0 && isNotBlank(nextState)) {
                    TimedScenarioTransition transition = new TimedScenarioTransition()
                        .withScenarioName(scenarioName)
                        .withCurrentState(state)
                        .withNextState(nextState)
                        .withTransitionAfterMs(transitionAfterMs);
                    scenarioManager.scheduleTransition(transition, scheduler);
                    logScenario(request, "scheduled timed transition for scenario:{} from state:{} to state:{} after {}ms",
                        scenarioName, state, nextState, String.valueOf(transitionAfterMs));
                }

                com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
                result.put("scenarioName", scenarioName);
                result.put("currentState", state);
                if (transitionAfterMs != null && transitionAfterMs > 0 && isNotBlank(nextState)) {
                    result.put("nextState", nextState);
                    result.put("transitionAfterMs", transitionAfterMs);
                }
                return response().withStatusCode(OK.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
            }
        } catch (Exception e) {
            return scenarioError(objectMapper, "failed to process scenario request: " + e.getMessage());
        }
    }

    /**
     * Handles GET /mockserver/scenario/{name} — returns the current state of a scenario.
     * When no name is supplied (GET /mockserver/scenario), returns the list of all known
     * scenarios and their current states (see {@link #handleScenarioList()}).
     */
    private HttpResponse handleScenarioGet(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String scenarioPath = extractScenarioPath(request);
            if (isBlank(scenarioPath)) {
                return handleScenarioList();
            }

            ScenarioManager scenarioManager = requestMatchers.getScenarioManager();
            String currentState = scenarioManager.getState(scenarioPath);

            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("scenarioName", scenarioPath);
            result.put("currentState", currentState);
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return scenarioError(objectMapper, "failed to get scenario state: " + e.getMessage());
        }
    }

    /**
     * Handles GET /mockserver/scenario — returns every known scenario and its current state
     * as {@code { "scenarios": [ { "scenarioName", "currentState" }, ... ] }} so the dashboard
     * can list existing scenarios without the caller having to know their names in advance.
     */
    private HttpResponse handleScenarioList() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            ScenarioManager scenarioManager = requestMatchers.getScenarioManager();
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode scenarios = result.putArray("scenarios");
            for (java.util.Map.Entry<String, String> entry : scenarioManager.getAllStates().entrySet()) {
                com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
                node.put("scenarioName", entry.getKey());
                node.put("currentState", entry.getValue());
                scenarios.add(node);
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return scenarioError(objectMapper, "failed to list scenarios: " + e.getMessage());
        }
    }

    // --- Cassette registry endpoint helpers ---

    /**
     * Handles GET /mockserver/cassettes — lists every cassette tracked server-side as
     * {@code { "cassettes": [ { "path", "filename", "expectationCount", "origin", "lastUsed" } ] }},
     * most-recently-used first. The dashboard merges this with its per-browser list so cassettes
     * recorded/loaded anywhere (or seeded by automation) are visible across reloads and browsers.
     */
    private HttpResponse handleCassettesGet() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode cassettes = result.putArray("cassettes");
            for (CassetteRegistry.Entry entry : CassetteRegistry.getInstance().list()) {
                com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
                node.put("path", entry.path);
                node.put("filename", entry.filename);
                node.put("expectationCount", entry.expectationCount);
                node.put("origin", entry.origin);
                node.put("lastUsed", entry.lastUsedEpochMillis);
                cassettes.add(node);
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return cassetteError(objectMapper, "failed to list cassettes: " + e.getMessage());
        }
    }

    /**
     * Handles PUT /mockserver/cassettes — registers (or updates) a cassette from a JSON body
     * {@code { "path", "filename"?, "expectationCount"?, "origin"? }}. {@code path} is required.
     */
    private HttpResponse handleCassettesPut(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return cassetteError(objectMapper, "request body is required with a 'path' field");
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            String path = node.path("path").asText(null);
            if (isBlank(path)) {
                return cassetteError(objectMapper, "'path' field is required");
            }
            String filename = node.path("filename").asText(null);
            int expectationCount = node.path("expectationCount").asInt(-1);
            String origin = node.path("origin").asText(null);
            CassetteRegistry.Entry entry = CassetteRegistry.getInstance().register(path, filename, expectationCount, origin);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("path", entry.path);
            result.put("filename", entry.filename);
            result.put("expectationCount", entry.expectationCount);
            result.put("origin", entry.origin);
            result.put("lastUsed", entry.lastUsedEpochMillis);
            return response().withStatusCode(CREATED.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return cassetteError(objectMapper, "failed to register cassette: " + e.getMessage());
        }
    }

    /**
     * Handles DELETE /mockserver/cassettes — removes a cassette by path, supplied either as the
     * {@code path} query parameter or a JSON body {@code { "path": "..." }}.
     */
    private HttpResponse handleCassettesDelete(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String path = request.getFirstQueryStringParameter("path");
            if (isBlank(path)) {
                String body = request.getBodyAsJsonOrXmlString();
                if (!isBlank(body)) {
                    path = objectMapper.readTree(body).path("path").asText(null);
                }
            }
            if (isBlank(path)) {
                return cassetteError(objectMapper, "'path' is required (query parameter or body field)");
            }
            boolean removed = CassetteRegistry.getInstance().remove(path);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("removed", removed);
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return cassetteError(objectMapper, "failed to remove cassette: " + e.getMessage());
        }
    }

    private HttpResponse cassetteError(com.fasterxml.jackson.databind.ObjectMapper objectMapper, String message) {
        try {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody(objectMapper.writeValueAsString(objectMapper.createObjectNode().put("error", message)), MediaType.JSON_UTF_8);
        } catch (Exception jsonError) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to process cassette request\"}", MediaType.JSON_UTF_8);
        }
    }

    private void logScenario(HttpRequest request, String messageFormat, String... args) {
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(request)
                    .setMessageFormat(messageFormat)
                    .setArguments((Object[]) args)
            );
        }
    }

    private HttpResponse scenarioError(com.fasterxml.jackson.databind.ObjectMapper objectMapper, String message) {
        try {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    objectMapper.createObjectNode().put("error", message)), MediaType.JSON_UTF_8);
        } catch (Exception jsonError) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to process scenario request\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handleGenerateExpectation(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return generateExpectationError(objectMapper, "request body is required with 'request' field");
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            if (!node.hasNonNull("request")) {
                return generateExpectationError(objectMapper, "'request' field is required (the unmatched HttpRequest)");
            }
            boolean preview = node.path("preview").asBoolean(true);
            int limit = node.path("limit").asInt(1);
            if (limit < 1) {
                limit = 1;
            }
            if (limit > 5) {
                limit = 5;
            }

            // Deserialize the unmatched request
            HttpRequest unmatchedRequest;
            try {
                RequestDefinition rd = getRequestDefinitionSerializer().deserialize(
                    objectMapper.writeValueAsString(node.get("request")));
                if (rd instanceof HttpRequest) {
                    unmatchedRequest = (HttpRequest) rd;
                } else {
                    unmatchedRequest = request().withPath("/");
                }
            } catch (Exception deserializeEx) {
                return generateExpectationError(objectMapper, "failed to parse 'request' field: " + deserializeEx.getMessage());
            }

            // Retrieve context: up to 10 active expectations
            List<Expectation> contextExpectations = requestMatchers.retrieveActiveExpectations(null);
            if (contextExpectations.size() > 10) {
                contextExpectations = contextExpectations.subList(0, 10);
            }

            // Check if LLM is available
            org.mockserver.llm.client.LlmCompletionService service = this.llmCompletionService;
            org.mockserver.llm.client.LlmBackend backend = this.llmBackend;
            if (service == null || backend == null) {
                // Fallback: generate a simple template-based stub without LLM
                Expectation suggestion = generateSimpleStub(unmatchedRequest);
                List<Expectation> suggestions = Collections.singletonList(suggestion);
                if (!preview) {
                    requestMatchers.add(suggestion, Cause.API);
                }
                return buildGenerateExpectationResponse(objectMapper, suggestions, 0.5, preview,
                    "Generated from request pattern (no LLM backend configured)");
            }

            // Build prompt and call LLM
            org.mockserver.llm.StubGenerationPromptBuilder promptBuilder = new org.mockserver.llm.StubGenerationPromptBuilder();
            String prompt = promptBuilder.build(unmatchedRequest, contextExpectations);

            ParsedConversation conversation = ParsedConversation.of(Collections.singletonList(
                new ParsedMessage(ParsedMessage.Role.USER, prompt, null, null)));
            java.util.Optional<org.mockserver.model.Completion> completionOpt = service.complete(backend, conversation);

            if (!completionOpt.isPresent() || isBlank(completionOpt.get().getText())) {
                // LLM call failed or returned empty — fall back to template
                Expectation suggestion = generateSimpleStub(unmatchedRequest);
                List<Expectation> suggestions = Collections.singletonList(suggestion);
                if (!preview) {
                    requestMatchers.add(suggestion, Cause.API);
                }
                return buildGenerateExpectationResponse(objectMapper, suggestions, 0.3, preview,
                    "LLM call returned no result, falling back to template");
            }

            String llmResponse = completionOpt.get().getText();

            // Parse LLM response as Expectation JSON
            List<Expectation> suggestions = new ArrayList<>();
            try {
                String jsonStr = extractJsonFromLlmResponse(llmResponse);
                Expectation[] parsed = getExpectationSerializer().deserializeArray(jsonStr, true);
                for (int i = 0; i < Math.min(parsed.length, limit); i++) {
                    suggestions.add(parsed[i]);
                }
            } catch (Exception parseEx) {
                // fallback to simple stub if LLM response unparseable
                suggestions.add(generateSimpleStub(unmatchedRequest));
            }

            if (!preview && !suggestions.isEmpty()) {
                for (Expectation suggestion : suggestions) {
                    requestMatchers.add(suggestion, Cause.API);
                }
            }

            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat("generated {} expectation suggestion(s) via LLM for path:{}")
                        .setArguments(suggestions.size(),
                            unmatchedRequest.getPath() != null ? unmatchedRequest.getPath().getValue() : "/")
                );
            }

            return buildGenerateExpectationResponse(objectMapper, suggestions, suggestions.isEmpty() ? 0.0 : 0.75, preview, null);
        } catch (Exception e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("failed to generate expectation:{}").setArguments(e.getMessage())
                    .setThrowable(e)
            );
            return generateExpectationError(objectMapper, "failed to generate expectation");
        }
    }

    private HttpResponse buildGenerateExpectationResponse(com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                                          List<Expectation> suggestions, double confidence,
                                                          boolean preview, String explanation) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode suggestionsArray = result.putArray("suggestions");
            for (Expectation suggestion : suggestions) {
                suggestionsArray.add(objectMapper.readTree(getExpectationSerializer().serialize(suggestion)));
            }
            result.put("confidence", confidence);
            result.put("preview", preview);
            if (explanation != null) {
                result.put("explanation", explanation);
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception jsonError) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to serialize response\"}", MediaType.JSON_UTF_8);
        }
    }

    private Expectation generateSimpleStub(HttpRequest unmatchedRequest) {
        String method = unmatchedRequest.getMethod() != null ? unmatchedRequest.getMethod().getValue() : "GET";
        int statusCode = "POST".equalsIgnoreCase(method) ? 201 : "DELETE".equalsIgnoreCase(method) ? 204 : 200;
        return new Expectation(
            HttpRequest.request()
                .withMethod(method)
                .withPath(unmatchedRequest.getPath() != null ? unmatchedRequest.getPath().getValue() : "/")
        ).thenRespond(
            HttpResponse.response()
                .withStatusCode(statusCode)
                .withBody("{\"status\":\"ok\"}", MediaType.JSON_UTF_8)
        );
    }

    private static String extractJsonFromLlmResponse(String text) {
        if (text == null) {
            return "{}";
        }
        String stripped = text.trim();
        // Strip markdown code fences if present
        if (stripped.startsWith("```")) {
            int start = stripped.indexOf('\n');
            int end = stripped.lastIndexOf("```");
            if (start > 0 && end > start) {
                stripped = stripped.substring(start + 1, end).trim();
            }
        }
        return stripped;
    }

    private HttpResponse generateExpectationError(com.fasterxml.jackson.databind.ObjectMapper objectMapper, String message) {
        try {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    objectMapper.createObjectNode().put("error", message)), MediaType.JSON_UTF_8);
        } catch (Exception jsonError) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to generate expectation\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handleGrpcHealthPut(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"request body is required with 'service' and 'status' fields\"}", MediaType.JSON_UTF_8);
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            String service = node.path("service").asText("");
            // The GET response exposes the default (empty-name) override under the "_default"
            // sentinel; map it back so removing/resetting the default row works.
            if ("_default".equals(service)) {
                service = "";
            }
            // A { service, remove: true } request clears that service's override (reverting it to
            // the default; an empty service resets the default itself) — used by the UI Reset button.
            if (node.path("remove").asBoolean(false)) {
                org.mockserver.grpc.GrpcHealthRegistry.getInstance().removeStatus(service);
                com.fasterxml.jackson.databind.node.ObjectNode removed = objectMapper.createObjectNode();
                removed.put("status", "removed");
                removed.put("service", service);
                return response().withStatusCode(OK.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(removed), MediaType.JSON_UTF_8);
            }
            String statusStr = node.path("status").asText(null);
            if (isBlank(statusStr)) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"'status' field is required (UNKNOWN, SERVING, NOT_SERVING, SERVICE_UNKNOWN)\"}", MediaType.JSON_UTF_8);
            }
            org.mockserver.grpc.ServingStatus status;
            try {
                status = org.mockserver.grpc.ServingStatus.valueOf(statusStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"invalid status value, must be one of: UNKNOWN, SERVING, NOT_SERVING, SERVICE_UNKNOWN\"}", MediaType.JSON_UTF_8);
            }
            org.mockserver.grpc.GrpcHealthRegistry.getInstance().setStatus(service, status);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "registered");
            result.put("service", service);
            result.put("servingStatus", status.name());
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to set gRPC health status: " + e.getMessage() + "\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handleGrpcHealthGet() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            org.mockserver.grpc.GrpcHealthRegistry registry = org.mockserver.grpc.GrpcHealthRegistry.getInstance();
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            registry.entries().forEach((service, status) ->
                result.put(service.isEmpty() ? "_default" : service, status.name()));
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to get gRPC health status\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handleDriftGet(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String expectationId = request.getFirstQueryStringParameter("expectationId");
            int limit = 50;
            String limitParam = request.getFirstQueryStringParameter("limit");
            if (limitParam != null && !limitParam.isEmpty()) {
                try {
                    limit = Math.min(500, Integer.parseInt(limitParam));
                } catch (NumberFormatException ignored) {
                    // use default
                }
            }
            org.mockserver.mock.drift.DriftStore store = org.mockserver.mock.drift.DriftStore.getInstance();
            List<org.mockserver.mock.drift.DriftRecord> records = (expectationId != null && !expectationId.isEmpty())
                ? store.getByExpectationId(expectationId)
                : store.getRecent(limit);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("count", records.size());
            result.set("drifts", objectMapper.valueToTree(records));
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to retrieve drift records\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handleDiff(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"request body required with 'expected' and 'actual' fields\"}", MediaType.JSON_UTF_8);
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            if (!node.hasNonNull("expected") || !node.hasNonNull("actual")) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"both 'expected' and 'actual' HttpRequest fields are required\"}", MediaType.JSON_UTF_8);
            }
            RequestDefinition expectedDef = getRequestDefinitionSerializer().deserialize(
                objectMapper.writeValueAsString(node.get("expected")));
            RequestDefinition actualDef = getRequestDefinitionSerializer().deserialize(
                objectMapper.writeValueAsString(node.get("actual")));

            if (!(expectedDef instanceof HttpRequest) || !(actualDef instanceof HttpRequest)) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"both 'expected' and 'actual' must be HttpRequest objects\"}", MediaType.JSON_UTF_8);
            }

            org.mockserver.mock.diff.TrafficDiffEngine diffEngine = new org.mockserver.mock.diff.TrafficDiffEngine();
            java.util.List<org.mockserver.mock.diff.FieldDiff> diffs = diffEngine.diff(
                (HttpRequest) expectedDef, (HttpRequest) actualDef);

            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("diffCount", diffs.size());
            result.put("identical", diffs.isEmpty());
            com.fasterxml.jackson.databind.node.ArrayNode diffsArray = result.putArray("diffs");
            for (org.mockserver.mock.diff.FieldDiff diff : diffs) {
                diffsArray.add(objectMapper.valueToTree(diff));
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to diff requests: " + e.getMessage() + "\"}", MediaType.JSON_UTF_8);
        }
    }

    private boolean controlPlaneRequestAuthenticated(HttpRequest request, ResponseWriter responseWriter) {
        try {
            if (controlPlaneAuthenticationHandler == null || controlPlaneAuthenticationHandler.controlPlaneRequestAuthenticated(request)) {
                return true;
            }
        } catch (AuthenticationException authenticationException) {
            responseWriter.writeResponse(request, UNAUTHORIZED, "Unauthorized for control plane - " + authenticationException.getMessage(), MediaType.create("text", "plain").toString());
            return false;
        }
        responseWriter.writeResponse(request, UNAUTHORIZED, "Unauthorized for control plane", MediaType.create("text", "plain").toString());
        return false;
    }

    @SuppressWarnings("rawtypes")
    private boolean validateSupportedFeatures(Expectation expectation, HttpRequest request, ResponseWriter responseWriter) {
        boolean valid = true;
        Action action = expectation.getAction();
        String NOT_SUPPORTED_MESSAGE = " is not supported by MockServer deployed as a WAR due to limitations in the JEE specification; use mockserver-netty to enable these features";
        if (action instanceof HttpResponse && ((HttpResponse) action).getConnectionOptions() != null) {
            valid = false;
            responseWriter.writeResponse(request, response("ConnectionOptions" + NOT_SUPPORTED_MESSAGE), true);
        } else if (action instanceof HttpObjectCallback) {
            valid = false;
            responseWriter.writeResponse(request, response("HttpObjectCallback" + NOT_SUPPORTED_MESSAGE), true);
        } else if (action instanceof HttpError) {
            valid = false;
            responseWriter.writeResponse(request, response("HttpError" + NOT_SUPPORTED_MESSAGE), true);
        }
        return valid;
    }

    public WebSocketClientRegistry getWebSocketClientRegistry() {
        return webSocketClientRegistry;
    }

    public RequestMatchers getRequestMatchers() {
        return requestMatchers;
    }

    public MockServerEventLog getMockServerLog() {
        return mockServerLog;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public String getUniqueLoopPreventionHeaderName() {
        return "x-forwarded-by";
    }

    public String getUniqueLoopPreventionHeaderValue() {
        return uniqueLoopPreventionHeaderValue;
    }

    public void stop() {
        if (expectationFileSystemPersistence != null) {
            expectationFileSystemPersistence.stop();
        }
        if (recordedExpectationFileSystemPersistence != null) {
            recordedExpectationFileSystemPersistence.stop();
        }
        if (expectationFileWatcher != null) {
            expectationFileWatcher.stop();
        }
        // Stop any active AsyncAPI broker connections (Kafka consumers, MQTT clients)
        // so they are not leaked on shutdown; no-op when the async module is absent
        // or nothing is loaded.
        org.mockserver.async.AsyncApiControlPlaneRegistry.getInstance().reset();
        getMockServerLog().stop();
        // G10 phase 2a: close the state backend (no-op for in-memory)
        if (stateBackend != null) {
            stateBackend.close();
        }
    }

    /**
     * Returns the pluggable state backend (G10 phase 2a). The default
     * implementation is in-memory with zero behaviour change.
     */
    public StateBackend getStateBackend() {
        return stateBackend;
    }

    private ExpectationIdSerializer getExpectationIdSerializer() {
        if (this.expectationIdSerializer == null) {
            this.expectationIdSerializer = new ExpectationIdSerializer(mockServerLogger);
        }
        return expectationIdSerializer;
    }

    private RequestDefinitionSerializer getRequestDefinitionSerializer() {
        if (this.requestDefinitionSerializer == null) {
            this.requestDefinitionSerializer = new RequestDefinitionSerializer(mockServerLogger);
        }
        return requestDefinitionSerializer;
    }

    private LogEventRequestAndResponseSerializer getHttpRequestResponseSerializer() {
        if (this.httpRequestResponseSerializer == null) {
            this.httpRequestResponseSerializer = new LogEventRequestAndResponseSerializer(mockServerLogger);
        }
        return httpRequestResponseSerializer;
    }

    private ExpectationSerializer getExpectationSerializer() {
        if (this.expectationSerializer == null) {
            this.expectationSerializer = new ExpectationSerializer(mockServerLogger);
        }
        return expectationSerializer;
    }

    private ExpectationSerializer getExpectationSerializerThatSerializesBodyDefault() {
        if (this.expectationSerializerThatSerializesBodyDefault == null) {
            this.expectationSerializerThatSerializesBodyDefault = new ExpectationSerializer(mockServerLogger, true);
        }
        return expectationSerializerThatSerializesBodyDefault;
    }

    private OpenAPIExpectationSerializer getOpenAPIExpectationSerializer() {
        if (this.openAPIExpectationSerializer == null) {
            this.openAPIExpectationSerializer = new OpenAPIExpectationSerializer(mockServerLogger);
        }
        return openAPIExpectationSerializer;
    }

    private ExpectationToJavaSerializer getExpectationToJavaSerializer() {
        if (this.expectationToJavaSerializer == null) {
            this.expectationToJavaSerializer = new ExpectationToJavaSerializer();
        }
        return expectationToJavaSerializer;
    }

    private org.mockserver.serialization.ExpectationExportSerializer getExpectationExportSerializer() {
        if (this.expectationExportSerializer == null) {
            this.expectationExportSerializer = new org.mockserver.serialization.ExpectationExportSerializer(mockServerLogger);
        }
        return expectationExportSerializer;
    }

    /**
     * Build a HAR-shaped request/response list from a list of expectations.
     * Used by the OpenAPI/Postman/Bruno/HAR export branches on the
     * ACTIVE_EXPECTATIONS and RECORDED_EXPECTATIONS paths so all formats
     * share one conversion path. Expectations without an httpResponse
     * (forward / template / callback / error / LLM) are still included so
     * that the request side is exported.
     */
    private java.util.List<org.mockserver.model.LogEventRequestAndResponse> expectationsToLogEvents(java.util.List<Expectation> expectations) {
        java.util.List<org.mockserver.model.LogEventRequestAndResponse> result = new java.util.ArrayList<>(expectations.size());
        for (Expectation expectation : expectations) {
            org.mockserver.model.RequestDefinition req = expectation.getHttpRequest();
            if (!(req instanceof org.mockserver.model.HttpRequest)) {
                continue;
            }
            org.mockserver.model.LogEventRequestAndResponse pair = new org.mockserver.model.LogEventRequestAndResponse()
                .withHttpRequest((org.mockserver.model.HttpRequest) req);
            if (expectation.getHttpResponse() != null) {
                pair.withHttpResponse(expectation.getHttpResponse());
            }
            result.add(pair);
        }
        return result;
    }

    private VerificationSerializer getVerificationSerializer() {
        if (this.verificationSerializer == null) {
            this.verificationSerializer = new VerificationSerializer(mockServerLogger);
        }
        return verificationSerializer;
    }

    private VerificationSequenceSerializer getVerificationSequenceSerializer() {
        if (this.verificationSequenceSerializer == null) {
            this.verificationSequenceSerializer = new VerificationSequenceSerializer(mockServerLogger);
        }
        return verificationSequenceSerializer;
    }

    private LogEntrySerializer getLogEntrySerializer() {
        if (this.logEntrySerializer == null) {
            this.logEntrySerializer = new LogEntrySerializer(mockServerLogger);
        }
        return logEntrySerializer;
    }

    private OpenAPIConverter getOpenAPIConverter() {
        if (this.openAPIConverter == null) {
            this.openAPIConverter = new OpenAPIConverter(mockServerLogger);
        }
        return openAPIConverter;
    }

    private org.mockserver.serialization.har.HarConverter getHarConverter() {
        if (this.harConverter == null) {
            this.harConverter = new org.mockserver.serialization.har.HarConverter();
        }
        return harConverter;
    }

    private org.mockserver.serialization.curl.HttpRequestToCurlSerializer getHttpRequestToCurlSerializer() {
        if (this.httpRequestToCurlSerializer == null) {
            this.httpRequestToCurlSerializer = new org.mockserver.serialization.curl.HttpRequestToCurlSerializer(mockServerLogger);
        }
        return httpRequestToCurlSerializer;
    }

    /**
     * Render a list of recorded requests as cURL commands, one per request,
     * separated by a blank line.
     */
    private String toCurlCommands(List<HttpRequest> requests) {
        StringBuilder builder = new StringBuilder();
        for (HttpRequest request : requests) {
            if (builder.length() > 0) {
                builder.append(NEW_LINE).append(NEW_LINE);
            }
            builder.append(getHttpRequestToCurlSerializer().toCurl(request));
        }
        builder.append(NEW_LINE);
        return builder.toString();
    }

    // ---- AsyncAPI control-plane ----

    private HttpResponse handleAsyncApiPut(HttpRequest request) {
        try {
            org.mockserver.async.AsyncApiControlPlaneRegistry registry = org.mockserver.async.AsyncApiControlPlaneRegistry.getInstance();
            if (!registry.isAvailable()) {
                return response().withStatusCode(NOT_IMPLEMENTED.code())
                    .withBody("{\"error\":\"AsyncAPI messaging module is not available — mockserver-async is not on the classpath\"}", MediaType.JSON_UTF_8);
            }
            String body = request.getBodyAsString();
            if (body == null || body.isBlank()) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"request body must contain an AsyncAPI spec (JSON/YAML) or {spec, brokerConfig}\"}", MediaType.JSON_UTF_8);
            }
            com.fasterxml.jackson.databind.JsonNode result = registry.load(body);
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
            return response().withStatusCode(CREATED.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to load AsyncAPI spec: " + message.replace("\"", "'") + "\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handleAsyncApiGet() {
        try {
            org.mockserver.async.AsyncApiControlPlaneRegistry registry = org.mockserver.async.AsyncApiControlPlaneRegistry.getInstance();
            if (!registry.isAvailable()) {
                return response().withStatusCode(NOT_IMPLEMENTED.code())
                    .withBody("{\"error\":\"AsyncAPI messaging module is not available — mockserver-async is not on the classpath\"}", MediaType.JSON_UTF_8);
            }
            com.fasterxml.jackson.databind.JsonNode result = registry.status();
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to get AsyncAPI status: " + message.replace("\"", "'") + "\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handlePactVerify(HttpRequest request) {
        try {
            String body = request.getBodyAsString();
            if (body == null || body.isBlank()) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"Pact contract JSON must not be empty\"}", MediaType.JSON_UTF_8);
            }
            org.mockserver.mock.pact.PactVerifier verifier = new org.mockserver.mock.pact.PactVerifier();
            org.mockserver.mock.pact.PactVerifier.PactVerificationResult result = verifier.verify(body, requestMatchers);
            if (result.isVerified()) {
                return response().withStatusCode(ACCEPTED.code())
                    .withBody(result.toJson(), MediaType.JSON_UTF_8);
            } else {
                return response().withStatusCode(NOT_ACCEPTABLE.code())
                    .withBody(result.toJson(), MediaType.JSON_UTF_8);
            }
        } catch (IllegalArgumentException e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}", MediaType.JSON_UTF_8);
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to verify Pact contract: " + message.replace("\"", "'") + "\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handleAsyncApiVerify(HttpRequest request) {
        try {
            org.mockserver.async.AsyncApiControlPlaneRegistry registry = org.mockserver.async.AsyncApiControlPlaneRegistry.getInstance();
            if (!registry.isAvailable()) {
                return response().withStatusCode(NOT_IMPLEMENTED.code())
                    .withBody("{\"error\":\"AsyncAPI messaging module is not available — mockserver-async is not on the classpath\"}", MediaType.JSON_UTF_8);
            }
            String body = request.getBodyAsString();
            if (body == null || body.isBlank()) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"verification request body must not be empty\"}", MediaType.JSON_UTF_8);
            }
            String result = registry.verify(body);
            if (isEmpty(result)) {
                return response().withStatusCode(ACCEPTED.code());
            } else {
                return response().withStatusCode(NOT_ACCEPTABLE.code())
                    .withBody(result, MediaType.create("text", "plain"));
            }
        } catch (IllegalArgumentException e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}", MediaType.JSON_UTF_8);
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to verify async messages: " + message.replace("\"", "'") + "\"}", MediaType.JSON_UTF_8);
        }
    }
}
