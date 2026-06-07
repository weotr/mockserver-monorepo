package org.mockserver.mock.action.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.junit.*;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.crud.CrudDispatcher;
import org.mockserver.model.*;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;
import org.mockserver.time.FixedTime;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.util.HashSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.Delay.milliseconds;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural tests for GraphQL-semantic chaos injection (graphqlErrors) wired
 * through HttpActionHandler.applyResponseChaos. Uses the same Mockito harness
 * as HttpActionHandlerChaosTest.
 */
public class HttpActionHandlerGraphQLChaosTest {

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

    private static final ObjectMapper JSON = ObjectMapperFactory.createObjectMapper();

    @ClassRule
    public static final FixedTime fixedTime = new FixedTime();

    @AfterClass
    public static void stopScheduler() {
        scheduler.shutdown();
    }

    @Before
    public void setupMocks() {
        Metrics.resetAdditionalMetricsForTesting();
        HttpQuotaRegistry.getInstance().reset();
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

    /**
     * Helper: simulates the matching flow by calling consumeMatch() on the
     * expectation (which increments matchCount) and then dispatches the request.
     * Returns the HttpResponse that was written to the response writer.
     */
    private HttpResponse dispatchAndCapture(HttpRequest request, Expectation expectation, HttpResponse normalResponse) {
        expectation.consumeMatch();
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        reset(mockResponseWriter);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        return responseCaptor.getValue();
    }

    // --- GraphQL error envelope tests ---

    @Test
    public void graphqlErrorsRewritesBodyWithDefaultMessage() throws Exception {
        // given - graphqlErrors=true with default message
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("{\"data\":{\"user\":{\"name\":\"Alice\"}}}").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile().withGraphqlErrors(true));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - HTTP 200 with GraphQL error envelope
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse written = responseCaptor.getValue();
        assertThat(written.getStatusCode(), is(200));
        assertThat(written.getFirstHeader("content-type"), is("application/json"));

        JsonNode envelope = JSON.readTree(written.getBodyAsString());
        assertThat(envelope.get("data").isNull(), is(true));
        assertThat(envelope.get("errors").isArray(), is(true));
        assertThat(envelope.get("errors").size(), is(1));
        assertThat(envelope.get("errors").get(0).get("message").asText(), is("simulated GraphQL error"));
        // no extensions when graphqlErrorCode is not set
        assertThat(envelope.get("errors").get(0).has("extensions"), is(false));
    }

    @Test
    public void graphqlErrorsWithCustomMessageAndCode() throws Exception {
        // given - custom message and code
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withGraphqlErrors(true)
                .withGraphqlErrorMessage("upstream timeout")
                .withGraphqlErrorCode("INTERNAL_SERVER_ERROR"));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse written = responseCaptor.getValue();
        assertThat(written.getStatusCode(), is(200));

        JsonNode envelope = JSON.readTree(written.getBodyAsString());
        assertThat(envelope.get("data").isNull(), is(true));
        assertThat(envelope.get("errors").get(0).get("message").asText(), is("upstream timeout"));
        assertThat(envelope.get("errors").get(0).get("extensions").get("code").asText(), is("INTERNAL_SERVER_ERROR"));
    }

    @Test
    public void graphqlNullifyDataFalsePreservesOriginalJson() throws Exception {
        // given - graphqlNullifyData=false with a JSON body
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("{\"user\":{\"name\":\"Alice\"}}").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withGraphqlErrors(true)
                .withGraphqlNullifyData(false));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - data preserves the original JSON
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        JsonNode envelope = JSON.readTree(responseCaptor.getValue().getBodyAsString());
        assertThat(envelope.get("data").get("user").get("name").asText(), is("Alice"));
        assertThat(envelope.get("errors").get(0).get("message").asText(), is("simulated GraphQL error"));
    }

    @Test
    public void graphqlNullifyDataFalseWithNonJsonFallsBackToNull() throws Exception {
        // given - graphqlNullifyData=false but the original body is not valid JSON
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("not valid json").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withGraphqlErrors(true)
                .withGraphqlNullifyData(false));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - data falls back to null
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        JsonNode envelope = JSON.readTree(responseCaptor.getValue().getBodyAsString());
        assertThat(envelope.get("data").isNull(), is(true));
    }

    @Test
    public void graphqlNullifyDataFalseWithEmptyBodyFallsBackToNull() throws Exception {
        // given - graphqlNullifyData=false but the body is empty
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response().withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withGraphqlErrors(true)
                .withGraphqlNullifyData(false));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - data falls back to null
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        JsonNode envelope = JSON.readTree(responseCaptor.getValue().getBodyAsString());
        assertThat(envelope.get("data").isNull(), is(true));
    }

    @Test
    public void graphqlErrorsSetsContentTypeAndRemovesContentLength() throws Exception {
        // given - response with explicit content-length
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("some body")
            .withDelay(milliseconds(0))
            .withHeader("content-length", "9")
            .withHeader("content-type", "text/plain");
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile().withGraphqlErrors(true));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - content-type is application/json, content-length is removed
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse written = responseCaptor.getValue();
        assertThat(written.getFirstHeader("content-type"), is("application/json"));
        assertThat(written.getFirstHeader("content-length"), is(""));
    }

    @Test
    public void graphqlErrorsFalseDoesNotRewrite() {
        // given - graphqlErrors=false should be a no-op
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile().withGraphqlErrors(false));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - original body is passed through
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getBodyAsString(), is("normal body"));
    }

    @Test
    public void graphqlErrorsNullDoesNotRewrite() {
        // given - graphqlErrors=null should be a no-op
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile());

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - original body is passed through
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getBodyAsString(), is("normal body"));
    }

    @Test
    public void graphqlErrorsGatedByCountWindow() throws Exception {
        // succeedFirst=1, failRequestCount=1, graphqlErrors=true
        // -> match #1 normal, match #2 GraphQL error, match #3 normal again
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withGraphqlErrors(true)
                .withSucceedFirst(1)
                .withFailRequestCount(1));

        HttpResponse r1 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #1 outside window keeps normal body", r1.getBodyAsString(), is("normal body"));

        HttpResponse r2 = dispatchAndCapture(request, expectation, normalResponse);
        JsonNode envelope = JSON.readTree(r2.getBodyAsString());
        assertThat("match #2 inside window is GraphQL error", envelope.has("errors"), is(true));
        assertThat(r2.getStatusCode(), is(200));

        HttpResponse r3 = dispatchAndCapture(request, expectation, normalResponse);
        assertThat("match #3 past window keeps normal body", r3.getBodyAsString(), is("normal body"));
    }

    @Test
    public void graphqlErrorsIncrementsGraphqlMetric() {
        // given - graphqlErrors fires
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile().withGraphqlErrors(true));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "graphql"), is(1.0));
    }

    @Test
    public void graphqlErrorsTakesPrecedenceOverTruncateAndMalformed() throws Exception {
        // given - graphqlErrors=true AND truncate + malformed set; graphql should win
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withGraphqlErrors(true)
                .withTruncateBodyAtFraction(0.5)
                .withMalformedBody(true));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - GraphQL envelope, not truncated/malformed
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse written = responseCaptor.getValue();
        assertThat(written.getStatusCode(), is(200));
        JsonNode envelope = JSON.readTree(written.getBodyAsString());
        assertThat(envelope.has("errors"), is(true));
        assertThat(envelope.get("data").isNull(), is(true));

        // graphql metric fired, truncate/malformed did NOT
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "graphql"), is(1.0));
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "truncate"), is(0.0));
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "malformed"), is(0.0));
    }

    @Test
    public void graphqlErrorsNotAppliedWhenErrorInjected() throws Exception {
        // given - error injection (probability=1.0) AND graphqlErrors=true; error injection
        // replaces the response before applyResponseChaos is called, so the error response
        // is what gets written (not a GraphQL envelope of the error)
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withGraphqlErrors(true)
                .withErrorStatus(503)
                .withErrorProbability(1.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the error response is written (503), not a GraphQL envelope
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse written = responseCaptor.getValue();
        assertThat(written.getStatusCode(), is(503));
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "error"), is(1.0));
    }

    @Test
    public void graphqlErrorsMessageWithSpecialCharactersIsEscaped() throws Exception {
        // given - message with quotes and backslash that must be JSON-escaped
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withGraphqlErrors(true)
                .withGraphqlErrorMessage("error with \"quotes\" and \\backslash"));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the body is valid JSON and the message is correctly escaped
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        JsonNode envelope = JSON.readTree(responseCaptor.getValue().getBodyAsString());
        assertThat(envelope.get("errors").get(0).get("message").asText(), is("error with \"quotes\" and \\backslash"));
    }

    // --- Minor: GraphQL envelope shape tests ---

    @Test
    public void graphqlEnvelopeHasNoExtensionsWhenErrorCodeUnset() throws Exception {
        // given - graphqlErrors=true, no graphqlErrorCode set
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withGraphqlErrors(true)
                .withGraphqlErrorMessage("some error"));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - no "extensions" key on the error object at all (not even empty object)
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        JsonNode envelope = JSON.readTree(responseCaptor.getValue().getBodyAsString());
        assertThat("error should have message", envelope.get("errors").get(0).get("message").asText(), is("some error"));
        assertThat("no extensions key when graphqlErrorCode is unset",
            envelope.get("errors").get(0).has("extensions"), is(false));
    }

    // --- Prometheus metric helpers ---

    private static double scrapeCounterValue(String name, String labelName, String labelValue) {
        MetricSnapshots snapshots = PrometheusRegistry.defaultRegistry.scrape();
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetadata().getName().equals(name) && snapshot instanceof CounterSnapshot counterSnapshot) {
                for (CounterSnapshot.CounterDataPointSnapshot dataPoint : counterSnapshot.getDataPoints()) {
                    if (labelValue.equals(dataPoint.getLabels().get(labelName))) {
                        return dataPoint.getValue();
                    }
                }
            }
        }
        return 0.0;
    }
}
