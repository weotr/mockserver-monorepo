package org.mockserver.log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.verify.Verification;

import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.verify.Verification.verification;
import static org.mockserver.verify.VerificationTimes.atMost;
import static org.mockserver.verify.VerificationTimes.between;

/**
 * Exercises the {@code atMost} and {@code between} {@link org.mockserver.verify.VerificationTimes}
 * boundaries through {@link MockServerEventLog#verify}. The existing event-log verification tests
 * only cover {@code exactly} and {@code atLeast}, so the upper-bound ({@code atMost}) and the
 * two-sided range ({@code between}) count checks were untested at this layer.
 *
 * <p>Each test owns an isolated {@link MockServerEventLog}; no JVM-global static state is mutated.
 */
public class MockServerEventLogVerificationTimesBoundaryTest {

    private Configuration configuration;
    private Scheduler scheduler;
    private MockServerEventLog mockServerEventLog;

    @Before
    public void setupTestFixture() {
        configuration = configuration();
        configuration.detailedVerificationFailures(false);
        scheduler = new Scheduler(configuration, new MockServerLogger());
        mockServerEventLog = new MockServerEventLog(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler, true);
    }

    @After
    public void stop() {
        if (mockServerEventLog != null) {
            mockServerEventLog.stop();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    private String verify(Verification verification) {
        CompletableFuture<String> result = new CompletableFuture<>();
        mockServerEventLog.verify(verification, result::complete);
        try {
            return result.get(30, SECONDS);
        } catch (Exception e) {
            fail(e.getMessage());
            return null;
        }
    }

    private void recordSamePath(int count) {
        HttpRequest httpRequest = request("/some_path");
        for (int i = 0; i < count; i++) {
            mockServerEventLog.add(new LogEntry().setType(RECEIVED_REQUEST).setHttpRequest(httpRequest));
        }
    }

    // ---- atMost ----

    @Test
    public void shouldPassAtMostWhenCountBelowUpperBound() {
        recordSamePath(2);
        assertThat(verify(verification().withRequest(request("/some_path")).withTimes(atMost(3))), is(""));
    }

    @Test
    public void shouldPassAtMostWhenCountEqualsUpperBound() {
        recordSamePath(3);
        assertThat(verify(verification().withRequest(request("/some_path")).withTimes(atMost(3))), is(""));
    }

    @Test
    public void shouldPassAtMostZeroWhenNoMatchingRequests() {
        recordSamePath(0);
        assertThat(verify(verification().withRequest(request("/some_path")).withTimes(atMost(0))), is(""));
    }

    @Test
    public void shouldFailAtMostWhenCountAboveUpperBound() {
        recordSamePath(4);
        String failure = verify(verification().withRequest(request("/some_path")).withTimes(atMost(3)));
        assertThat(failure, is(not("")));
        assertThat(failure, containsString("at most 3 times"));
    }

    // ---- between ----

    @Test
    public void shouldPassBetweenWhenCountAtLowerBound() {
        recordSamePath(2);
        assertThat(verify(verification().withRequest(request("/some_path")).withTimes(between(2, 4))), is(""));
    }

    @Test
    public void shouldPassBetweenWhenCountWithinRange() {
        recordSamePath(3);
        assertThat(verify(verification().withRequest(request("/some_path")).withTimes(between(2, 4))), is(""));
    }

    @Test
    public void shouldPassBetweenWhenCountAtUpperBound() {
        recordSamePath(4);
        assertThat(verify(verification().withRequest(request("/some_path")).withTimes(between(2, 4))), is(""));
    }

    @Test
    public void shouldFailBetweenWhenCountBelowLowerBound() {
        recordSamePath(1);
        String failure = verify(verification().withRequest(request("/some_path")).withTimes(between(2, 4)));
        assertThat(failure, is(not("")));
        assertThat(failure, containsString("between 2 and 4 times"));
    }

    @Test
    public void shouldFailBetweenWhenCountAboveUpperBound() {
        recordSamePath(5);
        String failure = verify(verification().withRequest(request("/some_path")).withTimes(between(2, 4)));
        assertThat(failure, is(not("")));
        assertThat(failure, containsString("between 2 and 4 times"));
    }
}
