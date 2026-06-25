package org.mockserver.log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.RequestDefinition;
import org.mockserver.scheduler.Scheduler;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;

/**
 * Verifies that {@link MockServerEventLog} keeps the server's own load-generation traffic out of the
 * bounded event log: any entry whose request carries the in-process load-generation marker
 * ({@link org.mockserver.model.HttpRequest#setLoadGenerated(boolean)}, set by
 * {@link org.mockserver.mock.action.http.LoadScenarioOrchestrator}) is dropped on {@code add}, so a
 * running load scenario cannot flood the log and evict real / LLM traffic. The marker is in-process
 * only, never a wire header, so it cannot reach an upstream target. Uses the synchronous processing
 * path so {@code size()} and retrieval are deterministic without a drain.
 */
public class MockServerEventLogLoadGeneratedSuppressionTest {

    private Configuration configuration;
    private Scheduler scheduler;
    private MockServerEventLog mockServerEventLog;

    @Before
    public void setupTestFixture() {
        configuration = configuration();
        scheduler = new Scheduler(configuration, new MockServerLogger());
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

    @Test
    public void shouldNotLogLoadGeneratedRequests() {
        // when - a normal request and a load-generated request are added
        mockServerEventLog.add(new LogEntry().setType(RECEIVED_REQUEST).setHttpRequest(request("/real")));
        mockServerEventLog.add(new LogEntry().setType(RECEIVED_REQUEST).setHttpRequest(
            request("/load").setLoadGenerated(true)));

        // then - only the real request was recorded; the load-generated one was suppressed
        assertThat(mockServerEventLog.size(), is(1));
        assertThat(retrieveRequests(null), contains(request("/real")));
    }

    @Test
    public void shouldSuppressEveryLogTypeForLoadGeneratedRequests() {
        // when - both a received-request and an expectation-response entry reference a load request
        mockServerEventLog.add(new LogEntry().setType(RECEIVED_REQUEST).setHttpRequest(
            request("/load").setLoadGenerated(true)));
        mockServerEventLog.add(new LogEntry().setType(EXPECTATION_RESPONSE).setHttpRequest(
            request("/load").setLoadGenerated(true)));

        // then - nothing was recorded
        assertThat(mockServerEventLog.size(), is(0));
    }

    @Test
    public void shouldStillLogNormalTrafficAlongsideLoad() {
        // when - interleaved real and load traffic
        mockServerEventLog.add(new LogEntry().setType(RECEIVED_REQUEST).setHttpRequest(request("/a")));
        mockServerEventLog.add(new LogEntry().setType(RECEIVED_REQUEST).setHttpRequest(
            request("/load").setLoadGenerated(true)));
        mockServerEventLog.add(new LogEntry().setType(RECEIVED_REQUEST).setHttpRequest(request("/b")));

        // then - only the two real requests survive, in order
        assertThat(mockServerEventLog.size(), is(2));
        assertThat(retrieveRequests(null), contains(request("/a"), request("/b")));
    }
}
