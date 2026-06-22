package org.mockserver.log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.RequestDefinition;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.verify.Verification;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.verify.Verification.verification;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * Exercises {@link MockServerEventLog} with {@code asynchronousEventProcessing = false} — the
 * synchronous path where {@link MockServerEventLog#add} calls {@code processLogEntry} directly on the
 * calling thread instead of publishing to the disruptor ring buffer. Every other event-log test runs
 * with async processing enabled, so this path was previously untested.
 *
 * <p>Note: even with synchronous {@code add}, the retrieve/verify reads still publish a RUNNABLE
 * through the disruptor (the disruptor is always started). The reads complete via their
 * {@code CompletableFuture}, so the assertions remain deterministic. Each test owns an isolated
 * {@link MockServerEventLog}; no JVM-global static state is mutated.
 */
public class MockServerEventLogSynchronousProcessingTest {

    private Configuration configuration;
    private Scheduler scheduler;
    private MockServerEventLog mockServerEventLog;

    @Before
    public void setupTestFixture() {
        configuration = configuration();
        scheduler = new Scheduler(configuration, new MockServerLogger());
        // asynchronousEventProcessing = false => synchronous processLogEntry on the add() thread
        mockServerEventLog = new MockServerEventLog(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler, false);
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

    private List<RequestDefinition> retrieveRequests(RequestDefinition requestDefinition) {
        CompletableFuture<List<RequestDefinition>> result = new CompletableFuture<>();
        mockServerEventLog.retrieveRequests(requestDefinition, result::complete);
        try {
            return result.get(30, SECONDS);
        } catch (Exception e) {
            fail(e.getMessage());
            return null;
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

    @Test
    public void shouldAddAndRetrieveOnSynchronousPath() {
        // when
        mockServerEventLog.add(new LogEntry().setType(RECEIVED_REQUEST).setHttpRequest(request("/one")));
        mockServerEventLog.add(new LogEntry().setType(RECEIVED_REQUEST).setHttpRequest(request("/two")));

        // then - synchronous add stored both entries immediately (size reflects them without a drain)
        assertThat(mockServerEventLog.size(), is(2));

        // and they are retrievable in order
        assertThat(retrieveRequests(null), contains(request("/one"), request("/two")));

        // and filtered retrieval works on the synchronous path
        assertThat(retrieveRequests(request("/two")), contains(request("/two")));
    }

    @Test
    public void shouldVerifyOnSynchronousPath() {
        // when
        mockServerEventLog.add(new LogEntry().setType(RECEIVED_REQUEST).setHttpRequest(request("/some_path")));
        mockServerEventLog.add(new LogEntry().setType(RECEIVED_REQUEST).setHttpRequest(request("/some_path")));

        // then
        assertThat(verify(verification().withRequest(request("/some_path")).withTimes(exactly(2))), is(""));
    }
}
