package org.mockserver.mock.action.http;

import org.junit.*;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.crud.CrudDispatcher;
import org.mockserver.model.*;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;
import org.mockserver.time.EpochService;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.util.HashSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.Delay.milliseconds;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural tests for HTTP chaos injection wired through HttpActionHandler.
 * Uses the same Mockito harness as HttpActionHandlerTest.
 */
public class HttpActionHandlerChaosTest {

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

    @BeforeClass
    public static void fixTime() {
        EpochService.fixedTime = true;
    }

    @AfterClass
    public static void stopScheduler() {
        scheduler.shutdown();
    }

    @Before
    public void setupMocks() {
        configuration = configuration().logLevel(Level.INFO);

        mockHttpStateHandler = mock(HttpState.class);
        scheduler = spy(new Scheduler(configuration, mockServerLogger));
        when(mockHttpStateHandler.getScheduler()).thenReturn(scheduler);
        when(mockHttpStateHandler.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer_" + UUIDService.getUUID());
        when(mockHttpStateHandler.getCrudDispatcher()).thenReturn(new CrudDispatcher());
        actionHandler = new HttpActionHandler(configuration, null, mockHttpStateHandler, null, null);

        openMocks(this);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
    }

    @Test
    public void chaosErrorReplacesResponseWhenProbabilityIsOne() {
        // given - an expectation with RESPONSE action and chaos (errorProbability=1.0, errorStatus=503, retryAfter="30")
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(1.0)
                .withRetryAfter("30"));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - a 503 response with Retry-After header is written instead of the normal 200
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getStatusCode(), is(503));
        assertThat(writtenResponse.getFirstHeader("Retry-After"), is("30"));
        assertThat(writtenResponse.getBodyAsString(), is("{\"error\":{\"type\":\"chaos_injected\",\"message\":\"injected HTTP chaos error\"}}"));
    }

    @Test
    public void chaosErrorDoesNotFireWhenProbabilityIsZero() {
        // given - an expectation with chaos but probability=0
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(503)
                .withErrorProbability(0.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the original response is written
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getBodyAsString(), is("normal body"));
    }

    @Test
    public void chaosWithNoErrorStatusDoesNotInjectError() {
        // given - chaos profile with no errorStatus (only latency could apply)
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorProbability(1.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the original response is written (no error injection without errorStatus)
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getBodyAsString(), is("normal body"));
    }

    @Test
    public void noChaosProfilePassesThroughNormally() {
        // given - no chaos
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse);

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the original response is written
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getBodyAsString(), is("normal body"));
    }

    @Test
    public void chaosLatencyReachesSchedulerWhenNoErrorInjected() {
        // given - chaos with latency only (no error injection); use doAnswer to
        // capture delays without actually sleeping
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withLatency(milliseconds(500)));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // capture the Delay[] arg from the inner schedule call (the one with varargs delays)
        java.util.concurrent.atomic.AtomicReference<Delay[]> capturedDelays = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(invocation -> {
            Runnable cmd = invocation.getArgument(0);
            Delay[] delays = invocation.getArguments().length > 2
                ? java.util.Arrays.copyOfRange(invocation.getArguments(), 2, invocation.getArguments().length, Delay[].class)
                : new Delay[0];
            if (delays.length > 0) {
                capturedDelays.set(delays);
            }
            cmd.run(); // run synchronously without sleeping
            return null;
        }).when(scheduler).schedule(any(Runnable.class), eq(true), any(Delay[].class));

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - the chaos latency (500ms) should be among the delays passed to the scheduler
        Delay[] delays = capturedDelays.get();
        assertThat("delays should have been captured", delays != null, is(true));
        boolean foundChaosLatency = false;
        for (Delay d : delays) {
            if (d.getTimeUnit() == java.util.concurrent.TimeUnit.MILLISECONDS && d.getValue() == 500) {
                foundChaosLatency = true;
                break;
            }
        }
        assertThat("chaos latency should be passed to the scheduler", foundChaosLatency, is(true));

        // also verify the normal response was written through
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getBodyAsString(), is("normal body"));
    }

    @Test
    public void chaosErrorWithNoRetryAfterHeaderOmitsIt() {
        // given
        HttpRequest request = request("some_path");
        HttpResponse normalResponse = response("normal body").withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request)
            .thenRespond(normalResponse)
            .withChaos(httpChaosProfile()
                .withErrorStatus(500)
                .withErrorProbability(1.0));

        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class))).thenReturn(normalResponse);

        // when
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);

        // then - a 500 response without Retry-After
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        HttpResponse writtenResponse = responseCaptor.getValue();
        assertThat(writtenResponse.getStatusCode(), is(500));
        assertThat(writtenResponse.getFirstHeader("Retry-After"), is(""));
    }
}
