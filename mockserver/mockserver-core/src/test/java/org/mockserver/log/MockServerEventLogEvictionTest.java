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
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.fail;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.verify.Verification.verification;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * Covers eviction of the bounded event-log ring (the {@code CircularConcurrentLinkedDeque} backing
 * {@link MockServerEventLog}) when more entries than {@code maxLogEntries} are added:
 * <ul>
 *   <li>the deque size stays bounded by {@code maxLogEntries};</li>
 *   <li>the oldest entries are dropped (FIFO eviction) and the most-recent window is retained;</li>
 *   <li>retained entries are still retrievable in order;</li>
 *   <li>verification counts reflect only the retained window (evicted entries no longer count).</li>
 * </ul>
 *
 * <p>Determinism: {@link MockServerEventLog#add} publishes through the same FIFO ring buffer as
 * {@code retrieve}/{@code verify}, so a subsequent retrieve/verify (which runs as a RUNNABLE on the
 * single consumer thread, after every prior add has been processed) observes a settled deque — no
 * sleeps needed. This is the same synchronisation the existing event-log tests rely on. Each test
 * constructs its own isolated {@link MockServerEventLog} (own {@link Configuration} and
 * {@link Scheduler}); no JVM-global static state is mutated, so these tests are parallel-safe and do
 * not need registering in the sequential Surefire phase.
 */
public class MockServerEventLogEvictionTest {

    private Configuration configuration;
    private Scheduler scheduler;
    private MockServerEventLog mockServerEventLog;

    @Before
    public void setupTestFixture() {
        configuration = configuration();
        scheduler = new Scheduler(configuration, new MockServerLogger());
    }

    /**
     * The backing deque reads {@code maxLogEntries} once at construction, so each test sets the
     * bound first and then builds the event log.
     *
     * <p>Synchronous event processing ({@code asynchronousEventProcessing=false}) is used so each
     * {@code add} (and the resulting FIFO eviction) is applied inline on the calling thread before
     * the next statement runs. This makes the size/window assertions deterministic — with async
     * processing a {@code retrieve} could snapshot the deque before the final {@code add} had been
     * drained from the ring buffer, which is flaky under parallel load. Eviction behaviour is
     * identical in both modes.
     */
    private void buildEventLogWithMaxLogEntries(int maxLogEntries) {
        configuration.maxLogEntries(maxLogEntries);
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

    private List<RequestDefinition> retrieveAllReceivedRequests() {
        CompletableFuture<List<RequestDefinition>> result = new CompletableFuture<>();
        mockServerEventLog.retrieveRequests((RequestDefinition) null, result::complete);
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

    private void addReceivedRequest(String path) {
        mockServerEventLog.add(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setHttpRequest(request(path))
        );
    }

    @Test
    public void shouldBoundSizeAndEvictOldestWhenMoreThanMaxLogEntriesAdded() {
        // given - a small bounded log
        buildEventLogWithMaxLogEntries(3);

        // when - add more entries than the bound
        addReceivedRequest("/0");
        addReceivedRequest("/1");
        addReceivedRequest("/2");
        addReceivedRequest("/3");
        addReceivedRequest("/4");

        // force all prior adds to be processed by the single consumer thread
        List<RequestDefinition> retained = retrieveAllReceivedRequests();

        // then - size is bounded to maxLogEntries
        assertThat(mockServerEventLog.size(), is(3));
        assertThat(mockServerEventLog.size(), lessThanOrEqualTo(configuration.maxLogEntries()));

        // then - the three most-recent entries are retained in order, oldest dropped
        assertThat(retained, contains(
            request("/2"),
            request("/3"),
            request("/4")
        ));
    }

    @Test
    public void shouldNotCountEvictedEntriesInVerification() {
        // given - a log that holds only the two most-recent entries
        buildEventLogWithMaxLogEntries(2);

        // when - the same path is received three times, but only two entries can be retained
        addReceivedRequest("/repeated");
        addReceivedRequest("/repeated");
        addReceivedRequest("/repeated");

        // force processing
        retrieveAllReceivedRequests();

        // then - exactly two remain in the deque
        assertThat(mockServerEventLog.size(), is(2));

        // then - verification counts only the retained window (2), not the 3 originally added
        assertThat(verify(
            verification()
                .withRequest(request("/repeated"))
                .withTimes(exactly(2))
        ), is(""));

        // and verifying for 3 now fails because the oldest was evicted
        assertThat(verify(
            verification()
                .withRequest(request("/repeated"))
                .withTimes(exactly(3))
        ), is(not("")));
    }

    @Test
    public void shouldOnlyRetainMostRecentWindowOfDistinctEntries() {
        // given
        buildEventLogWithMaxLogEntries(4);

        // when - add ten distinct entries
        for (int i = 0; i < 10; i++) {
            addReceivedRequest("/" + i);
        }
        List<RequestDefinition> retained = retrieveAllReceivedRequests();

        // then - only the last four survive, in FIFO order
        assertThat(mockServerEventLog.size(), is(4));
        assertThat(retained, contains(
            request("/6"),
            request("/7"),
            request("/8"),
            request("/9")
        ));

        // and an evicted path is no longer retrievable
        CompletableFuture<List<RequestDefinition>> evicted = new CompletableFuture<>();
        mockServerEventLog.retrieveRequests((RequestDefinition) request("/0"), evicted::complete);
        try {
            assertThat(evicted.get(30, SECONDS).isEmpty(), is(true));
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }
}
