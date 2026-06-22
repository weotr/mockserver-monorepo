package org.mockserver.mock.action.http;

import org.junit.*;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.crud.CrudDispatcher;
import org.mockserver.model.*;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;
import org.mockserver.time.FixedTime;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.StringContains.containsString;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.Delay.milliseconds;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpOverrideForwardedRequest.forwardOverriddenRequest;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural tests for the LLM cost-budget circuit-breaker enforcement on
 * all forward paths through HttpActionHandler. Verifies that:
 * <ul>
 *   <li>matched FORWARD actions targeting an LLM provider are blocked (429)
 *       when the budget is exceeded</li>
 *   <li>non-LLM forwards are unaffected regardless of budget state</li>
 *   <li>disabled/negative budget never blocks (fail-open)</li>
 *   <li>no double-count: the budget check does not accumulate cost</li>
 *   <li>the trip increments the Prometheus counter</li>
 * </ul>
 */
public class HttpActionHandlerLlmCostBudgetTest {

    private static Scheduler scheduler;
    @Mock
    private HttpResponseActionHandler mockHttpResponseActionHandler;
    @Mock
    private HttpResponseTemplateActionHandler mockHttpResponseTemplateActionHandler;
    @Mock
    private HttpResponseClassCallbackActionHandler mockHttpResponseClassCallbackActionHandler;
    @Mock
    private HttpResponseObjectCallbackActionHandler mockHttpResponseObjectCallbackActionHandler;
    @Mock
    private HttpForwardActionHandler mockHttpForwardActionHandler;
    @Mock
    private HttpForwardTemplateActionHandler mockHttpForwardTemplateActionHandler;
    @Mock
    private HttpForwardClassCallbackActionHandler mockHttpForwardClassCallbackActionHandler;
    @Mock
    private HttpForwardObjectCallbackActionHandler mockHttpForwardObjectCallbackActionHandler;
    @Mock
    private HttpOverrideForwardedRequestActionHandler mockHttpOverrideForwardedRequestActionHandler;
    @Mock
    private HttpForwardValidateActionHandler mockHttpForwardValidateActionHandler;
    @Mock
    private HttpForwardWithFallbackActionHandler mockHttpForwardWithFallbackActionHandler;
    @Mock
    private HttpErrorActionHandler mockHttpErrorActionHandler;
    @Mock
    private ResponseWriter mockResponseWriter;
    @Mock
    private MockServerLogger mockServerLogger;
    @Spy
    private HttpRequestToCurlSerializer httpRequestToCurlSerializer = new HttpRequestToCurlSerializer(mockServerLogger);
    @Mock
    private NettyHttpClient mockNettyHttpClient;
    private HttpState mockHttpStateHandler;
    @InjectMocks
    private HttpActionHandler actionHandler;
    private Configuration configuration;

    private double originalBudget;

    @ClassRule
    public static final FixedTime fixedTime = new FixedTime();

    @AfterClass
    public static void stopScheduler() {
        scheduler.shutdown();
    }

    @Before
    public void setupMocks() {
        originalBudget = ConfigurationProperties.llmCostBudgetUsd();
        Metrics.resetAdditionalMetricsForTesting();
        LlmCostBudgetMonitor.getInstance().reset();
        ServiceChaosRegistry.getInstance().reset();
        configuration = configuration().logLevel(Level.INFO).metricsEnabled(true);

        mockHttpStateHandler = mock(HttpState.class);
        scheduler = spy(new Scheduler(configuration, mockServerLogger));
        when(mockHttpStateHandler.getScheduler()).thenReturn(scheduler);
        when(mockHttpStateHandler.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer_" + UUIDService.getUUID());
        when(mockHttpStateHandler.getCrudDispatcher()).thenReturn(new CrudDispatcher());
        actionHandler = new HttpActionHandler(configuration, null, mockHttpStateHandler, null, null);

        openMocks(this);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
    }

    @After
    public void restoreOriginals() {
        ConfigurationProperties.llmCostBudgetUsd(originalBudget);
        LlmCostBudgetMonitor.getInstance().reset();
    }

    private HttpForwardActionResult completedForwardResult(HttpResponse upstreamResponse) {
        CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        future.complete(upstreamResponse);
        HttpRequest forwardedRequest = mock(HttpRequest.class);
        return new HttpForwardActionResult(forwardedRequest, future, null, new InetSocketAddress(1234));
    }

    // ---- Matched FORWARD: budget enforced on LLM host ----

    @Test
    public void matchedForwardToLlmProviderIsBlockedWhenBudgetExceeded() {
        // given - budget of $0.01 already exceeded
        ConfigurationProperties.llmCostBudgetUsd(0.01);
        LlmCostBudgetMonitor.getInstance().recordCost(0.02);

        HttpRequest request = request("/v1/chat/completions");
        HttpResponse upstreamResponse = response("upstream").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("api.openai.com").withPort(443).withScheme(HttpForward.Scheme.HTTPS);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request).thenForward(forward);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - 429 is written, forward handler is NOT called
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse written = responseCaptor.getValue();
        assertThat(written.getStatusCode(), is(429));
        assertThat(written.getBodyAsString(), containsString("cost_budget_exceeded"));
        verify(mockHttpForwardActionHandler, never()).handle(any(HttpForward.class), any(HttpRequest.class));
    }

    @Test
    public void matchedForwardReplaceToLlmProviderIsBlockedWhenBudgetExceeded() {
        // given - budget of $0.01 already exceeded
        ConfigurationProperties.llmCostBudgetUsd(0.01);
        LlmCostBudgetMonitor.getInstance().recordCost(0.02);

        HttpRequest request = request("/v1/messages");
        HttpResponse upstreamResponse = response("upstream").withStatusCode(200).withDelay(milliseconds(0));
        HttpOverrideForwardedRequest forwardReplace = forwardOverriddenRequest(
            request().withHeader("Host", "api.anthropic.com:443")
        );
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request).thenForward(forwardReplace);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpOverrideForwardedRequestActionHandler.handle(any(HttpOverrideForwardedRequest.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - 429 is written, forward handler is NOT called
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getStatusCode(), is(429));
        verify(mockHttpOverrideForwardedRequestActionHandler, never()).handle(any(HttpOverrideForwardedRequest.class), any(HttpRequest.class));
    }

    @Test
    public void matchedForwardValidateToLlmProviderIsBlockedWhenBudgetExceeded() {
        // given - budget of $0.01 already exceeded
        ConfigurationProperties.llmCostBudgetUsd(0.01);
        LlmCostBudgetMonitor.getInstance().recordCost(0.02);

        HttpRequest request = request("/v1/chat/completions");
        HttpResponse upstreamResponse = response("upstream").withStatusCode(200).withDelay(milliseconds(0));
        HttpForwardValidateAction forwardValidate = HttpForwardValidateAction.forwardValidate()
            .withHost("api.openai.com").withPort(443).withScheme(HttpForward.Scheme.HTTPS)
            .withSpecUrlOrPayload("{}");
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request).thenForwardValidate(forwardValidate);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardValidateActionHandler.handle(any(HttpForwardValidateAction.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - 429 is written, forward handler is NOT called
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getStatusCode(), is(429));
        verify(mockHttpForwardValidateActionHandler, never()).handle(any(HttpForwardValidateAction.class), any(HttpRequest.class));
    }

    @Test
    public void matchedForwardWithFallbackToLlmProviderIsBlockedWhenBudgetExceeded() {
        // given - budget exceeded
        ConfigurationProperties.llmCostBudgetUsd(0.01);
        LlmCostBudgetMonitor.getInstance().recordCost(0.02);

        HttpRequest request = request("/v1/chat/completions");
        HttpResponse upstreamResponse = response("upstream").withStatusCode(200).withDelay(milliseconds(0));
        HttpForwardWithFallback forwardWithFallback = HttpForwardWithFallback.forwardWithFallback()
            .withForward(forward().withHost("api.openai.com").withPort(443).withScheme(HttpForward.Scheme.HTTPS))
            .withFallback(response("fallback").withStatusCode(200));
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request).thenForwardWithFallback(forwardWithFallback);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardWithFallbackActionHandler.handle(any(HttpForwardWithFallback.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - 429 is written
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getStatusCode(), is(429));
        verify(mockHttpForwardWithFallbackActionHandler, never()).handle(any(HttpForwardWithFallback.class), any(HttpRequest.class));
    }

    // ---- Non-LLM forwards are unaffected ----

    @Test
    public void matchedForwardToNonLlmHostIsNotBlockedEvenWhenBudgetExceeded() {
        // given - budget exceeded but forward target is not an LLM provider
        ConfigurationProperties.llmCostBudgetUsd(0.01);
        LlmCostBudgetMonitor.getInstance().recordCost(0.02);

        HttpRequest request = request("/api/users");
        HttpResponse upstreamResponse = response("users").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("internal-service.example.com").withPort(8080);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request).thenForward(forward);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the upstream response passes through, forward handler IS called
        verify(mockHttpForwardActionHandler).handle(any(HttpForward.class), any(HttpRequest.class));
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getStatusCode(), is(200));
    }

    // ---- Fail-open when budget disabled ----

    @Test
    public void matchedForwardToLlmProviderProceedsWhenBudgetDisabled() {
        // given - no budget configured (disabled)
        ConfigurationProperties.llmCostBudgetUsd(-1.0);
        LlmCostBudgetMonitor.getInstance().recordCost(100.0);

        HttpRequest request = request("/v1/chat/completions");
        HttpResponse upstreamResponse = response("completion").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("api.openai.com").withPort(443).withScheme(HttpForward.Scheme.HTTPS);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request).thenForward(forward);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - forward handler IS called (fail-open)
        verify(mockHttpForwardActionHandler).handle(any(HttpForward.class), any(HttpRequest.class));
    }

    @Test
    public void matchedForwardToLlmProviderProceedsWhenBudgetNotExceeded() {
        // given - budget configured but not exceeded
        ConfigurationProperties.llmCostBudgetUsd(10.0);
        LlmCostBudgetMonitor.getInstance().recordCost(0.01);

        HttpRequest request = request("/v1/chat/completions");
        HttpResponse upstreamResponse = response("completion").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("api.openai.com").withPort(443).withScheme(HttpForward.Scheme.HTTPS);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request).thenForward(forward);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - forward handler IS called (budget not exceeded)
        verify(mockHttpForwardActionHandler).handle(any(HttpForward.class), any(HttpRequest.class));
    }

    // ---- No double-count ----

    @Test
    public void budgetCheckDoesNotAccumulateCost() {
        // given - budget of $1.00 with $0.90 already recorded
        ConfigurationProperties.llmCostBudgetUsd(1.0);
        LlmCostBudgetMonitor.getInstance().recordCost(0.90);
        double costBefore = LlmCostBudgetMonitor.getInstance().getCumulativeCostUsd();

        HttpRequest request = request("/v1/chat/completions");
        HttpResponse upstreamResponse = response("completion").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("api.openai.com").withPort(443).withScheme(HttpForward.Scheme.HTTPS);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request).thenForward(forward);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when - the budget check runs but budget is not exceeded, so the forward proceeds
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - cumulative cost has not changed (no double-count)
        double costAfter = LlmCostBudgetMonitor.getInstance().getCumulativeCostUsd();
        assertThat("budget check must not accumulate cost", costAfter, is(costBefore));
    }

    // ---- Trip emits metric ----

    @Test
    public void budgetTripIncrementsPrometheusCounter() {
        // given - budget exceeded
        ConfigurationProperties.llmCostBudgetUsd(0.01);
        LlmCostBudgetMonitor.getInstance().recordCost(0.02);
        long tripsBefore = LlmCostBudgetMonitor.getInstance().getTripCount();

        HttpRequest request = request("/v1/chat/completions");
        HttpForward forward = forward().withHost("api.openai.com").withPort(443).withScheme(HttpForward.Scheme.HTTPS);

        Expectation expectation = new Expectation(request).thenForward(forward);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - trip count incremented
        assertThat(LlmCostBudgetMonitor.getInstance().getTripCount(), is(tripsBefore + 1));
        // and the Prometheus counter is incremented
        assertThat(Metrics.getLlmCostBudgetTrippedCount(), is(tripsBefore + 1));
    }

    // ---- Template and class-callback forwards (use request host for sniffing) ----

    @Test
    public void matchedForwardTemplateToLlmProviderIsBlockedWhenBudgetExceeded() {
        // given - budget exceeded, request host header targets LLM
        ConfigurationProperties.llmCostBudgetUsd(0.01);
        LlmCostBudgetMonitor.getInstance().recordCost(0.02);

        HttpRequest request = request("/v1/chat/completions")
            .withHeader("Host", "api.openai.com:443");
        HttpResponse upstreamResponse = response("upstream").withStatusCode(200).withDelay(milliseconds(0));
        HttpTemplate forwardTemplate = HttpTemplate.template(HttpTemplate.TemplateType.JAVASCRIPT, "return {}");
        forwardTemplate.withActionType(Action.Type.FORWARD_TEMPLATE);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request).thenForward(forwardTemplate);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardTemplateActionHandler.handle(any(HttpTemplate.class), any(HttpRequest.class))).thenReturn(forwardResult);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - 429 is written, forward handler is NOT called
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getStatusCode(), is(429));
        verify(mockHttpForwardTemplateActionHandler, never()).handle(any(HttpTemplate.class), any(HttpRequest.class));
    }

    // ---- Reset clears budget ----

    @Test
    public void resetClearsBudgetAllowingForwardsToResume() {
        // given - budget exceeded
        ConfigurationProperties.llmCostBudgetUsd(0.01);
        LlmCostBudgetMonitor.getInstance().recordCost(0.02);
        assertThat(LlmCostBudgetMonitor.getInstance().isBudgetExceeded(), is(true));

        // when - reset
        LlmCostBudgetMonitor.getInstance().reset();

        // then - budget is no longer exceeded
        assertThat(LlmCostBudgetMonitor.getInstance().isBudgetExceeded(), is(false));

        // and forwards proceed normally
        HttpRequest request = request("/v1/chat/completions");
        HttpResponse upstreamResponse = response("completion").withStatusCode(200).withDelay(milliseconds(0));
        HttpForward forward = forward().withHost("api.openai.com").withPort(443).withScheme(HttpForward.Scheme.HTTPS);
        HttpForwardActionResult forwardResult = completedForwardResult(upstreamResponse);

        Expectation expectation = new Expectation(request).thenForward(forward);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(forwardResult);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);
        verify(mockHttpForwardActionHandler).handle(any(HttpForward.class), any(HttpRequest.class));
    }

    // ---- Proxy-pass mapping: budget enforced ----

    @Test
    public void proxyPassMappingToLlmHostIsBlockedWhenBudgetExceeded() {
        // given - budget exceeded, proxy-pass mapping targets an LLM host
        ConfigurationProperties.llmCostBudgetUsd(0.01);
        LlmCostBudgetMonitor.getInstance().recordCost(0.02);

        configuration.proxyPassMappings(List.of(
            ProxyPassMapping.proxyPass("/llm/", "https://api.openai.com/v1/")
        ));

        HttpRequest request = request("/llm/chat/completions");
        // no matched expectation — falls through to proxy-pass
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(null);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - 429 is written, upstream send is NOT called
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getStatusCode(), is(429));
        assertThat(responseCaptor.getValue().getBodyAsString(), containsString("cost_budget_exceeded"));
        verify(mockNettyHttpClient, never()).sendRequest(any(HttpRequest.class), any(InetSocketAddress.class));
    }

    @Test
    public void proxyPassMappingToNonLlmHostIsNotBlockedEvenWhenBudgetExceeded() {
        // given - budget exceeded, but proxy-pass mapping targets a non-LLM host
        ConfigurationProperties.llmCostBudgetUsd(0.01);
        LlmCostBudgetMonitor.getInstance().recordCost(0.02);

        configuration.proxyPassMappings(List.of(
            ProxyPassMapping.proxyPass("/api/", "https://internal-service.example.com:8443/api/")
        ));

        HttpRequest request = request("/api/users");
        // no matched expectation — falls through to proxy-pass
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(null);

        CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        future.complete(response("users-response").withStatusCode(200));
        when(mockNettyHttpClient.sendRequest(any(HttpRequest.class), any(InetSocketAddress.class))).thenReturn(future);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - upstream send IS called (non-LLM host not blocked)
        verify(mockNettyHttpClient).sendRequest(any(HttpRequest.class), any(InetSocketAddress.class));
    }

    // ---- IPv6 host parsing (stripPortFromHost) ----

    @Test
    public void stripPortFromHostHandlesIpv4WithPort() {
        assertThat(HttpActionHandler.stripPortFromHost("api.openai.com:443"), is("api.openai.com"));
    }

    @Test
    public void stripPortFromHostHandlesIpv4WithoutPort() {
        assertThat(HttpActionHandler.stripPortFromHost("api.openai.com"), is("api.openai.com"));
    }

    @Test
    public void stripPortFromHostHandlesBracketedIpv6WithPort() {
        assertThat(HttpActionHandler.stripPortFromHost("[::1]:8080"), is("::1"));
    }

    @Test
    public void stripPortFromHostHandlesBracketedIpv6WithoutPort() {
        assertThat(HttpActionHandler.stripPortFromHost("[2001:db8::1]"), is("2001:db8::1"));
    }

    @Test
    public void stripPortFromHostHandlesBareIpv6WithoutTruncation() {
        // A bare IPv6 address (no brackets) has multiple colons — must not truncate
        assertThat(HttpActionHandler.stripPortFromHost("2001:db8::1"), is("2001:db8::1"));
    }

    @Test
    public void stripPortFromHostHandlesEmptyBrackets() {
        // Malformed edge case — fail-open
        assertThat(HttpActionHandler.stripPortFromHost("[]"), is(""));
    }
}
