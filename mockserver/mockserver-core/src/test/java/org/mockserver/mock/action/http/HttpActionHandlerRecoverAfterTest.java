package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.crud.CrudDispatcher;
import org.mockserver.model.*;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.util.HashSet;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.Delay.milliseconds;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.RecoverAfter.recoverAfter;

/**
 * Behavioural tests for the {@link RecoverAfter} fail-then-succeed recovery primitive wired through
 * {@link HttpActionHandler}. Uses the same Mockito harness as {@code HttpActionHandlerChaosTest},
 * except the response-action handler is stubbed to return its first argument so the response object
 * SELECTED by {@code selectRecoveryResponse} (failure vs success) flows through to the writer.
 *
 * <p>State-mutating-singleton test (touches {@link RecoveryAttemptRegistry}): registered in BOTH the
 * parallel {@code <excludes>} AND the sequential {@code <includes>} of mockserver-core's Surefire config.
 */
public class HttpActionHandlerRecoverAfterTest {

    private static Scheduler scheduler;
    @Mock
    private HttpResponseActionHandler mockHttpResponseActionHandler;
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

    @Before
    public void setupMocks() {
        RecoveryAttemptRegistry.getInstance().reset();
        configuration = configuration().logLevel(Level.INFO);

        mockHttpStateHandler = mock(HttpState.class);
        scheduler = spy(new Scheduler(configuration, mockServerLogger));
        when(mockHttpStateHandler.getScheduler()).thenReturn(scheduler);
        when(mockHttpStateHandler.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer_" + UUIDService.getUUID());
        when(mockHttpStateHandler.getCrudDispatcher()).thenReturn(new CrudDispatcher());
        actionHandler = new HttpActionHandler(configuration, null, mockHttpStateHandler, null, null);

        openMocks(this);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        // return the SELECTED response (first arg) unchanged so selection is observable
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class)))
            .thenAnswer(invocation -> ((HttpResponse) invocation.getArgument(0)).clone());
    }

    @After
    public void tearDown() {
        RecoveryAttemptRegistry.getInstance().reset();
        scheduler.shutdown();
    }

    /**
     * Simulates the matching flow: consumeMatch() increments matchCount, then dispatch the request
     * and capture the written response.
     */
    private HttpResponse dispatchAndCapture(HttpRequest request, Expectation expectation) {
        expectation.consumeMatch();
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        reset(mockResponseWriter);
        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, true);
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        return responseCaptor.getValue();
    }

    @Test
    public void failsFirstThreeWithDefault503ThenSucceeds() {
        HttpRequest request = request("some_path");
        HttpResponse success = response("ok").withStatusCode(200).withDelay(milliseconds(0))
            .withRecoverAfter(recoverAfter(3));
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0).thenRespond(success);

        assertThat("attempt #1 default 503", dispatchAndCapture(request, expectation).getStatusCode(), is(503));
        assertThat("attempt #2 default 503", dispatchAndCapture(request, expectation).getStatusCode(), is(503));
        assertThat("attempt #3 default 503", dispatchAndCapture(request, expectation).getStatusCode(), is(503));

        HttpResponse r4 = dispatchAndCapture(request, expectation);
        assertThat("attempt #4 recovers to 200", r4.getStatusCode(), is(200));
        assertThat(r4.getBodyAsString(), is("ok"));
    }

    @Test
    public void honoursExplicitFailResponse() {
        HttpRequest request = request("some_path");
        HttpResponse failResponse = response().withStatusCode(503).withHeader("Retry-After", "1").withBody("down");
        HttpResponse success = response("ok").withStatusCode(200).withDelay(milliseconds(0))
            .withRecoverAfter(new RecoverAfter().withFailTimes(1).withFailResponse(failResponse));
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0).thenRespond(success);

        HttpResponse r1 = dispatchAndCapture(request, expectation);
        assertThat(r1.getStatusCode(), is(503));
        assertThat(r1.getFirstHeader("Retry-After"), is("1"));
        assertThat(r1.getBodyAsString(), is("down"));

        assertThat("attempt #2 recovers", dispatchAndCapture(request, expectation).getStatusCode(), is(200));
    }

    @Test
    public void inertWhenFailTimesNullOrZero() {
        HttpRequest request = request("some_path");
        HttpResponse zero = response("ok").withStatusCode(200).withDelay(milliseconds(0))
            .withRecoverAfter(new RecoverAfter().withFailTimes(0));
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0).thenRespond(zero);

        assertThat("failTimes=0 is inert", dispatchAndCapture(request, expectation).getStatusCode(), is(200));
    }

    @Test
    public void responseWithoutRecoverAfterIsUnchanged() {
        HttpRequest request = request("some_path");
        HttpResponse plain = response("ok").withStatusCode(200).withDelay(milliseconds(0));
        Expectation expectation = new Expectation(request, Times.unlimited(), TimeToLive.unlimited(), 0).thenRespond(plain);

        HttpResponse r1 = dispatchAndCapture(request, expectation);
        assertThat(r1.getStatusCode(), is(200));
        assertThat(r1.getBodyAsString(), is("ok"));
        // and no keyed counter state was created
        assertThat(RecoveryAttemptRegistry.getInstance().nextAttempt("any", "any"), is(1));
    }

    @Test
    public void idempotencyKeyScopesPerValue() {
        HttpResponse success = response("ok").withStatusCode(200).withDelay(milliseconds(0))
            .withRecoverAfter(new RecoverAfter().withFailTimes(2).withIdempotencyHeader("Idempotency-Key"));
        Expectation expectation = new Expectation(request("some_path"), Times.unlimited(), TimeToLive.unlimited(), 0).thenRespond(success);

        HttpRequest keyA1 = request("some_path").withHeader("Idempotency-Key", "A");
        HttpRequest keyB1 = request("some_path").withHeader("Idempotency-Key", "B");

        // key A: attempts 1,2 fail then 3 succeeds; key B has its own independent window
        assertThat("A attempt #1", dispatchAndCapture(keyA1, expectation).getStatusCode(), is(503));
        assertThat("B attempt #1", dispatchAndCapture(keyB1, expectation).getStatusCode(), is(503));
        assertThat("A attempt #2", dispatchAndCapture(keyA1, expectation).getStatusCode(), is(503));
        assertThat("A attempt #3 recovers", dispatchAndCapture(keyA1, expectation).getStatusCode(), is(200));
        assertThat("B attempt #2", dispatchAndCapture(keyB1, expectation).getStatusCode(), is(503));
        assertThat("B attempt #3 recovers", dispatchAndCapture(keyB1, expectation).getStatusCode(), is(200));
    }

    @Test
    public void sameIdempotencyKeyValueSharesWindow() {
        HttpResponse success = response("ok").withStatusCode(200).withDelay(milliseconds(0))
            .withRecoverAfter(new RecoverAfter().withFailTimes(1).withIdempotencyHeader("Idempotency-Key"));
        Expectation expectation = new Expectation(request("some_path"), Times.unlimited(), TimeToLive.unlimited(), 0).thenRespond(success);

        HttpRequest sameKey = request("some_path").withHeader("Idempotency-Key", "same");
        assertThat("attempt #1 fails", dispatchAndCapture(sameKey, expectation).getStatusCode(), is(503));
        assertThat("attempt #2 with same key recovers", dispatchAndCapture(sameKey, expectation).getStatusCode(), is(200));
    }

    @Test
    public void missingIdempotencyHeaderFallsBackToPerExpectationCount() {
        // idempotencyHeader configured, but the request does not carry it -> per-expectation matchCount window
        HttpResponse success = response("ok").withStatusCode(200).withDelay(milliseconds(0))
            .withRecoverAfter(new RecoverAfter().withFailTimes(2).withIdempotencyHeader("Idempotency-Key"));
        Expectation expectation = new Expectation(request("some_path"), Times.unlimited(), TimeToLive.unlimited(), 0).thenRespond(success);

        HttpRequest noKey = request("some_path");
        assertThat("attempt #1", dispatchAndCapture(noKey, expectation).getStatusCode(), is(503));
        assertThat("attempt #2", dispatchAndCapture(noKey, expectation).getStatusCode(), is(503));
        assertThat("attempt #3 recovers", dispatchAndCapture(noKey, expectation).getStatusCode(), is(200));
        // the keyed registry was never touched on the fallback path
        assertThat(RecoveryAttemptRegistry.getInstance().nextAttempt("unused", "unused"), is(1));
    }

    @Test
    public void independentOfTimes() {
        // Times.exactly(5) + failTimes=2 -> 503,503,200,200,200 over five matches; failure attempts
        // do not consume extra Times uses (selection counts off matchCount, independent of Times)
        HttpRequest request = request("some_path");
        HttpResponse success = response("ok").withStatusCode(200).withDelay(milliseconds(0))
            .withRecoverAfter(recoverAfter(2));
        Expectation expectation = new Expectation(request, Times.exactly(5), TimeToLive.unlimited(), 0).thenRespond(success);

        assertThat("match #1", dispatchAndCapture(request, expectation).getStatusCode(), is(503));
        assertThat("match #2", dispatchAndCapture(request, expectation).getStatusCode(), is(503));
        assertThat("match #3", dispatchAndCapture(request, expectation).getStatusCode(), is(200));
        assertThat("match #4", dispatchAndCapture(request, expectation).getStatusCode(), is(200));
        assertThat("match #5", dispatchAndCapture(request, expectation).getStatusCode(), is(200));
    }

    @Test
    public void resetClearsKeyedCountersMidSequence() {
        HttpResponse success = response("ok").withStatusCode(200).withDelay(milliseconds(0))
            .withRecoverAfter(new RecoverAfter().withFailTimes(2).withIdempotencyHeader("Idempotency-Key"));
        Expectation expectation = new Expectation(request("some_path"), Times.unlimited(), TimeToLive.unlimited(), 0).thenRespond(success);

        HttpRequest key = request("some_path").withHeader("Idempotency-Key", "A");
        assertThat("attempt #1", dispatchAndCapture(key, expectation).getStatusCode(), is(503));

        // reset wipes the keyed counter, so the window restarts at attempt #1
        RecoveryAttemptRegistry.getInstance().reset();

        assertThat("post-reset attempt #1 again", dispatchAndCapture(key, expectation).getStatusCode(), is(503));
        assertThat("post-reset attempt #2", dispatchAndCapture(key, expectation).getStatusCode(), is(503));
        assertThat("post-reset attempt #3 recovers", dispatchAndCapture(key, expectation).getStatusCode(), is(200));
    }
}
