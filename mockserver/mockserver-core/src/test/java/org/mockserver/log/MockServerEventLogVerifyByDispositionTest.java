package org.mockserver.log;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.verify.Disposition;
import org.mockserver.verify.Verification;
import org.slf4j.event.Level;

import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.verify.Verification.verification;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * Verifies the verify-by-disposition behaviour: a {@link Verification} carrying a
 * {@link Disposition} counts only the requests handled in that way (FORWARDED vs MOCKED)
 * rather than all received requests.
 */
public class MockServerEventLogVerifyByDispositionTest {

    private final Configuration configuration = configuration();
    private MockServerLogger mockServerLogger;
    private MockServerEventLog mockServerEventLog;

    @Before
    public void setupTestFixture() {
        Scheduler scheduler = mock(Scheduler.class);
        HttpState httpStateHandler = new HttpState(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler);
        mockServerLogger = httpStateHandler.getMockServerLogger();
        mockServerEventLog = httpStateHandler.getMockServerLog();
        configuration.logLevel(Level.INFO);
    }

    private String verify(Verification verification) {
        try {
            return mockServerEventLog.verify(verification).get(60, SECONDS);
        } catch (Exception e) {
            fail(e.getMessage());
            return null;
        }
    }

    private void logRequestHandledAs(LogEntry.LogMessageType type, String path) {
        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(Level.INFO)
                .setType(type)
                .setHttpRequest(request().withPath(path))
        );
    }

    @Test
    public void shouldCountOnlyForwardedRequestsWhenDispositionForwarded() {
        // given - one request mocked, one forwarded, both for /api
        logRequestHandledAs(RECEIVED_REQUEST, "/api");
        logRequestHandledAs(EXPECTATION_RESPONSE, "/api");
        logRequestHandledAs(RECEIVED_REQUEST, "/api");
        logRequestHandledAs(FORWARDED_REQUEST, "/api");

        // when - verify exactly one FORWARDED request
        String forwardedResult = verify(
            verification()
                .withRequest(request().withPath("/api"))
                .withDisposition(Disposition.FORWARDED)
                .withTimes(exactly(1))
        );

        // then - passes (empty failure message)
        assertThat(forwardedResult, is(""));
    }

    @Test
    public void shouldCountOnlyMockedRequestsWhenDispositionMocked() {
        // given
        logRequestHandledAs(EXPECTATION_RESPONSE, "/api");
        logRequestHandledAs(FORWARDED_REQUEST, "/api");

        // when - verify exactly one MOCKED request
        String mockedResult = verify(
            verification()
                .withRequest(request().withPath("/api"))
                .withDisposition(Disposition.MOCKED)
                .withTimes(exactly(1))
        );

        // then
        assertThat(mockedResult, is(""));
    }

    @Test
    public void shouldFailWhenDispositionFilterExcludesMatchingRequests() {
        // given - only a forwarded request for /api
        logRequestHandledAs(FORWARDED_REQUEST, "/api");

        // when - verify a MOCKED request which does not exist
        String result = verify(
            verification()
                .withRequest(request().withPath("/api"))
                .withDisposition(Disposition.MOCKED)
                .withTimes(exactly(1))
        );

        // then - fails because there is no mocked request
        assertThat(result, not(is("")));
        assertThat(result, containsString("Request not found"));
    }

    @Test
    public void shouldDistinguishForwardedFromMockedForSamePath() {
        // given - two forwarded, one mocked
        logRequestHandledAs(FORWARDED_REQUEST, "/api");
        logRequestHandledAs(FORWARDED_REQUEST, "/api");
        logRequestHandledAs(EXPECTATION_RESPONSE, "/api");

        // then - forwarded counts 2
        assertThat(verify(
            verification()
                .withRequest(request().withPath("/api"))
                .withDisposition(Disposition.FORWARDED)
                .withTimes(exactly(2))
        ), is(""));

        // and - mocked counts 1
        assertThat(verify(
            verification()
                .withRequest(request().withPath("/api"))
                .withDisposition(Disposition.MOCKED)
                .withTimes(exactly(1))
        ), is(""));
    }

    @Test
    public void shouldCountAllReceivedRequestsWhenNoDispositionSet() {
        // given - one received, one mocked, one forwarded
        logRequestHandledAs(RECEIVED_REQUEST, "/api");
        logRequestHandledAs(EXPECTATION_RESPONSE, "/api");
        logRequestHandledAs(FORWARDED_REQUEST, "/api");

        // when - no disposition: original behaviour counts only RECEIVED_REQUEST entries
        String result = verify(
            verification()
                .withRequest(request().withPath("/api"))
                .withTimes(exactly(1))
        );

        // then
        assertThat(result, is(""));
    }
}
