package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.load.LoadProfile;
import org.mockserver.load.LoadScenario;
import org.mockserver.load.LoadStage;
import org.mockserver.load.LoadStep;
import org.mockserver.load.RampCurve;
import org.mockserver.metrics.Metrics;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpTemplate;
import org.mockserver.slo.SloSampleStore;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests for {@link LoadScenarioOrchestrator}. Uses a deterministic mutable clock and a
 * synchronous fake sender (every request returns an already-completed future), so assertions
 * about request volume and templated variation are deterministic without wall-clock sleeps.
 * Where a count must be reached, a bounded poll on the sender's recorded queue is used.
 */
public class LoadScenarioOrchestratorTest {

    private AtomicLong clock;
    private ScheduledExecutorService scheduler;
    private LoadScenarioOrchestrator orchestrator;

    private boolean originalEnabled;
    private int originalMaxVus;
    private long originalMaxDuration;
    private int originalMaxSteps;
    private int originalMaxConcurrent;
    private boolean originalSloTracking;

    @Before
    public void setUp() {
        clock = new AtomicLong(10_000L);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-load-scenario-scheduler");
            t.setDaemon(true);
            return t;
        });
        orchestrator = new LoadScenarioOrchestrator(clock::get, scheduler);
        Metrics.resetAdditionalMetricsForTesting();
        SloSampleStore.getInstance().reset();

        originalEnabled = ConfigurationProperties.loadGenerationEnabled();
        originalMaxVus = ConfigurationProperties.loadGenerationMaxVirtualUsers();
        originalMaxDuration = ConfigurationProperties.loadGenerationMaxDurationMillis();
        originalMaxSteps = ConfigurationProperties.loadGenerationMaxSteps();
        originalMaxConcurrent = ConfigurationProperties.loadGenerationMaxConcurrentScenarios();
        originalSloTracking = ConfigurationProperties.sloTrackingEnabled();
    }

    @After
    public void tearDown() {
        orchestrator.reset();
        scheduler.shutdownNow();
        Metrics.resetAdditionalMetricsForTesting();
        SloSampleStore.getInstance().reset();
        ConfigurationProperties.loadGenerationEnabled(originalEnabled);
        ConfigurationProperties.loadGenerationMaxVirtualUsers(originalMaxVus);
        ConfigurationProperties.loadGenerationMaxDurationMillis(originalMaxDuration);
        ConfigurationProperties.loadGenerationMaxSteps(originalMaxSteps);
        ConfigurationProperties.loadGenerationMaxConcurrentScenarios(originalMaxConcurrent);
        ConfigurationProperties.sloTrackingEnabled(originalSloTracking);
    }

    /** A synchronous fake sender that records every request and returns a 200 immediately. */
    private static final class RecordingSender implements Function<HttpRequest, CompletableFuture<HttpResponse>> {
        final ConcurrentLinkedQueue<HttpRequest> sent = new ConcurrentLinkedQueue<>();

        @Override
        public CompletableFuture<HttpResponse> apply(HttpRequest httpRequest) {
            sent.add(httpRequest);
            return CompletableFuture.completedFuture(response().withStatusCode(200));
        }
    }

    private boolean awaitAtLeast(RecordingSender sender, int n, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (sender.sent.size() >= n) {
                return true;
            }
            Thread.sleep(5);
        }
        return sender.sent.size() >= n;
    }

    /** Poll until the most recent run's live active-VU count reaches the expected value (or timeout). */
    private boolean awaitLastRunActiveVus(int expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (orchestrator.lastRunActiveVuCount() == expected) {
                return true;
            }
            Thread.sleep(5);
        }
        return orchestrator.lastRunActiveVuCount() == expected;
    }

    @Test
    public void constantProfileDrivesRequestsUntilMaxRequests() throws Exception {
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("constant")
            .withMaxRequests(25)
            .withProfile(LoadProfile.constant(3, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        String error = orchestrator.start(scenario, sender);
        assertThat(error, is(nullValue()));

        assertThat("scenario should reach maxRequests", awaitAtLeast(sender, 25, 5_000L), is(true));
        // The cap is enforced at dispatch time, so the count overshoots only by roughly the number
        // of in-flight VUs (3), not by a full control interval of firing — it must not run forever.
        long deadline = System.currentTimeMillis() + 500L;
        while (orchestrator.getStatus() != null && org.mockserver.load.LoadScenarioState.RUNNING == orchestrator.getStatus().state
            && System.currentTimeMillis() < deadline) {
            orchestrator.tickNow();
            Thread.sleep(5);
        }
        assertThat(sender.sent.size(), greaterThanOrEqualTo(25));
        assertThat(sender.sent.size(), lessThan(60));
    }

    @Test
    public void linearProfileRampsTargetVusOverTime() throws Exception {
        // Pure ramp-shape assertion via the stage (deterministic, no traffic needed).
        LoadStage linear = LoadStage.rampVus(0, 10, 1000L, RampCurve.LINEAR);
        assertThat(linear.targetVusAt(0), is(0));
        assertThat(linear.targetVusAt(500), is(5));
        assertThat(linear.targetVusAt(1000), is(10));
        assertThat(linear.targetVusAt(2000), is(10)); // clamped at end

        // And via the orchestrator's live active-VU count driven by tickNow + the mutable clock.
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("linear")
            .withProfile(LoadProfile.linear(0, 8, 1000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));
        assertThat(orchestrator.start(scenario, sender), is(nullValue()));

        clock.set(10_000L + 500L);
        orchestrator.tickNow();
        assertThat("active VUs ramp up to the t=500 target of 4",
            awaitLastRunActiveVus(4, 5_000L), is(true));

        // Just before the duration boundary the ramp is essentially at endVus.
        clock.set(10_000L + 999L);
        orchestrator.tickNow();
        assertThat("active VUs ramp up to the near-end target of 8",
            awaitLastRunActiveVus(8, 5_000L), is(true));
    }

    @Test
    public void stagesRunInSequenceVuThenPauseThenRate() throws Exception {
        // VU(2 VUs, 400ms) -> PAUSE(200ms) -> RATE(20/s, 500ms). Assert the stage index advances in
        // order, the pause drains VUs, and the run terminates after the last stage.
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("sequenced")
            .withProfile(LoadProfile.of(
                LoadStage.constantVus(2, 400L),
                LoadStage.pause(200L),
                LoadStage.constantRate(20.0, 500L)))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));

        // Stage 0 (VU): two VUs spin up.
        assertThat("VU stage spins up 2 VUs", awaitLastRunActiveVus(2, 5_000L), is(true));
        assertThat(orchestrator.getStatus().stageIndex, is(0));
        assertThat(orchestrator.getStatus().stageType, is("VU"));

        // Advance into the PAUSE stage (after 400ms): VUs drain to zero.
        clock.set(10_000L + 500L);
        orchestrator.tickNow();
        assertThat("pause stage drains VUs", awaitLastRunActiveVus(0, 5_000L), is(true));
        assertThat(orchestrator.getStatus().stageIndex, is(1));
        assertThat(orchestrator.getStatus().stageType, is("PAUSE"));

        // Advance into the RATE stage (after 600ms): the open-model scheduler reports a RATE stage.
        clock.set(10_000L + 700L);
        orchestrator.tickNow();
        assertThat(orchestrator.getStatus().stageIndex, is(2));
        assertThat(orchestrator.getStatus().stageType, is("RATE"));

        // Advance past the last stage (total 1100ms): the run terminates.
        clock.set(10_000L + 1_200L);
        orchestrator.tickNow();
        long deadline = System.currentTimeMillis() + 2_000L;
        while (orchestrator.getStatus() != null && org.mockserver.load.LoadScenarioState.RUNNING == orchestrator.getStatus().state
            && System.currentTimeMillis() < deadline) {
            orchestrator.tickNow();
            Thread.sleep(5);
        }
        assertThat(orchestrator.getStatus().state, is(org.mockserver.load.LoadScenarioState.COMPLETED));
    }

    @Test
    public void rateStageAchievesTargetArrivalRate() throws Exception {
        // A constant 50 iterations/sec rate over ~1s should start ~50 iterations (one request per
        // iteration here), within tolerance. Drive the clock forward in fixed steps and tick so the
        // deficit accumulator integrates the rate deterministically.
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("rate")
            .withProfile(LoadProfile.constantRate(50.0, 2_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        // Step the clock in 100ms increments over 1 second, ticking each time (50/s * 1s = ~50).
        for (int i = 1; i <= 10; i++) {
            clock.set(10_000L + i * 100L);
            orchestrator.tickNow();
            Thread.sleep(20); // let one-shot iterations dispatch their single step
        }
        long sent = sender.sent.size();
        assertThat("≈50 iterations started over 1s at 50/s", sent, greaterThanOrEqualTo(40L));
        assertThat(sent, lessThanOrEqualTo(60L));
    }

    @Test
    public void rateStageAfterPauseDoesNotBurstOnFirstTick() throws Exception {
        // RATE 10/s -> PAUSE 5s -> RATE 10/s. The accumulator state is per-run; before the fix the
        // second RATE stage's first integrating tick computed dt across the ENTIRE pause and added
        // rate*gap iterations at once (~50+), bursting. After the fix each RATE stage integrates only
        // its own elapsed time, so the second stage's FIRST tick starts ~rate*tick/1000 (~1 at 10/s,
        // 100ms tick), and the whole second stage totals ~rate*duration with no surplus.
        RecordingSender sender = new RecordingSender();
        long base = 10_000L;
        LoadScenario scenario = new LoadScenario()
            .withName("rate-pause-rate")
            .withProfile(LoadProfile.of(
                LoadStage.constantRate(10.0, 1_000L),   // stage 0: 0..1000ms
                LoadStage.pause(5_000L),                // stage 1: 1000..6000ms
                LoadStage.constantRate(10.0, 1_000L)))  // stage 2: 6000..7000ms
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));

        // Drive stage 0 (first RATE): 0..1000ms in 100ms ticks. ~10 iterations.
        for (int i = 1; i <= 10; i++) {
            clock.set(base + i * 100L);
            orchestrator.tickNow();
            Thread.sleep(20);
        }
        long afterFirstRate = sender.sent.size();
        assertThat("first RATE stage achieves ~10 iterations over 1s",
            afterFirstRate, allOf(greaterThanOrEqualTo(7L), lessThanOrEqualTo(13L)));

        // Drive the PAUSE stage: 1000..6000ms. No load.
        for (int i = 11; i <= 60; i++) {
            clock.set(base + i * 100L);
            orchestrator.tickNow();
        }
        Thread.sleep(20);
        long afterPause = sender.sent.size();
        assertThat("pause stage drives no load", afterPause - afterFirstRate, is(0L));

        // First tick of stage 2 (second RATE), at elapsed 6100ms. This is the "first integrating tick"
        // of the second RATE stage: after the reset it re-initialises (no integration) on the boundary
        // tick at 6000ms and integrates only one 100ms step here, so it must start ~1 iteration, NOT
        // rate*gap (~50). The boundary tick (6000ms) is the initialising tick.
        clock.set(base + 6_000L);
        orchestrator.tickNow();
        Thread.sleep(20);
        long atSecondRateBoundary = sender.sent.size();
        assertThat("the boundary tick of the second RATE stage initialises and does not integrate",
            atSecondRateBoundary - afterPause, is(0L));

        clock.set(base + 6_100L);
        orchestrator.tickNow();
        Thread.sleep(20);
        long afterFirstIntegratingTick = sender.sent.size();
        long firstTickIterations = afterFirstIntegratingTick - atSecondRateBoundary;
        assertThat("second RATE stage's first integrating tick starts ~1 iteration (rate*tick), not a burst",
            firstTickIterations, allOf(greaterThanOrEqualTo(0L), lessThanOrEqualTo(3L)));

        // Drive the remainder of stage 2: 6200..7000ms. Total over the second RATE stage ~= 10.
        for (int i = 62; i <= 70; i++) {
            clock.set(base + i * 100L);
            orchestrator.tickNow();
            Thread.sleep(20);
        }
        long secondRateTotal = sender.sent.size() - afterPause;
        assertThat("second RATE stage totals ~rate*duration with no burst surplus",
            secondRateTotal, allOf(greaterThanOrEqualTo(7L), lessThanOrEqualTo(13L)));
    }

    @Test
    public void rateStageAfterVuStageDoesNotBurstOnFirstTick() throws Exception {
        // RATE 10/s -> VU(2, 3s) -> RATE 10/s. Same no-burst property across a VU stage in between:
        // before the fix the second RATE stage's first integrating tick spanned the whole VU stage and
        // bursted; after the fix it integrates only its own elapsed time.
        //
        // The step carries a very large think time so the closed-loop VUs fire roughly one request then
        // park for far longer than the whole test (they never re-loop and flood the recorder). Think time
        // is inter-step pacing applied AFTER the request is sent, so it does not delay or reduce the RATE
        // one-shots' single request — only the VU loops are silenced, keeping the stage-2 RATE count a
        // clean signal instead of being swamped by unbounded zero-think VU looping.
        RecordingSender sender = new RecordingSender();
        long base = 10_000L;
        LoadScenario scenario = new LoadScenario()
            .withName("rate-vu-rate")
            .withProfile(LoadProfile.of(
                LoadStage.constantRate(10.0, 1_000L),   // stage 0: 0..1000ms
                LoadStage.constantVus(2, 3_000L),       // stage 1: 1000..4000ms
                LoadStage.constantRate(10.0, 1_000L)))  // stage 2: 4000..5000ms
            .withSteps(new LoadStep()
                .withRequest(request().withPath("/api").withHeader("Host", "target"))
                .withThinkTime(org.mockserver.model.Delay.seconds(60)));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));

        // Stage 0 (first RATE): 0..1000ms.
        for (int i = 1; i <= 10; i++) {
            clock.set(base + i * 100L);
            orchestrator.tickNow();
            Thread.sleep(20);
        }
        // Move through the VU stage (1100..3900ms). Each VU fires ~one request then parks (60s think).
        for (int i = 11; i <= 39; i++) {
            clock.set(base + i * 100L);
            orchestrator.tickNow();
            Thread.sleep(5);
        }

        // Boundary tick of stage 2 (4000ms): the RATE accumulator was reset on entry, so this tick
        // initialises and does not integrate.
        clock.set(base + 4_000L);
        orchestrator.tickNow();
        Thread.sleep(20);
        long atBoundary = sender.sent.size();

        // First integrating tick of stage 2 (4100ms): integrates only one 100ms step (~1 iteration),
        // NOT rate * the 3s VU stage (~30). This is the regression: pre-fix dtMillis spanned the whole
        // intervening VU stage and added rate*3s to the deficit on this one tick.
        clock.set(base + 4_100L);
        orchestrator.tickNow();
        Thread.sleep(20);
        long firstTickIterations = sender.sent.size() - atBoundary;
        assertThat("second RATE stage's first integrating tick after a VU stage starts ~1 iteration, not a burst",
            firstTickIterations, allOf(greaterThanOrEqualTo(0L), lessThanOrEqualTo(3L)));

        // Remainder of stage 2: 4200..5000ms. The RATE stage contribution totals ~rate*duration (~10).
        for (int i = 42; i <= 50; i++) {
            clock.set(base + i * 100L);
            orchestrator.tickNow();
            Thread.sleep(20);
        }
        long secondRateContribution = sender.sent.size() - atBoundary;
        assertThat("the second RATE stage contributes ~rate*duration with no burst surplus",
            secondRateContribution, allOf(greaterThanOrEqualTo(7L), lessThanOrEqualTo(14L)));
    }

    @Test
    public void rampRateStageIncreasesOverTime() throws Exception {
        // A 0->100/s linear ramp over 1s: more iterations are started in the second half than the
        // first half (the rate genuinely rises).
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("ramp-rate")
            .withProfile(LoadProfile.of(LoadStage.rampRate(0.0, 100.0, 1_000L, RampCurve.LINEAR)))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        // First half (0..500ms).
        for (int i = 1; i <= 5; i++) {
            clock.set(10_000L + i * 100L);
            orchestrator.tickNow();
            Thread.sleep(20);
        }
        long firstHalf = sender.sent.size();
        // Second half (500..1000ms).
        for (int i = 6; i <= 10; i++) {
            clock.set(10_000L + i * 100L);
            orchestrator.tickNow();
            Thread.sleep(20);
        }
        long secondHalf = sender.sent.size() - firstHalf;
        assertThat("the ramping rate starts more iterations in the second half than the first",
            secondHalf, greaterThan(firstHalf));
    }

    @Test
    public void pauseStageProducesNoRequests() throws Exception {
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("pause-only")
            .withProfile(LoadProfile.of(LoadStage.pause(500L), LoadStage.constantVus(1, 500L)))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        // Tick within the first (PAUSE) stage only: no requests should be produced.
        clock.set(10_000L + 200L);
        orchestrator.tickNow();
        Thread.sleep(100);
        clock.set(10_000L + 400L);
        orchestrator.tickNow();
        Thread.sleep(100);
        assertThat("pause stage drives no load", sender.sent.size(), is(0));
    }

    @Test
    public void rejectsRateStageExceedingMaxRate() {
        LoadScenario scenario = new LoadScenario()
            .withName("too-fast")
            .withProfile(LoadProfile.constantRate(1_000_000.0, 1_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api")));

        String error = orchestrator.start(scenario, new RecordingSender());
        assertThat(error, containsString("exceeding the maximum of"));
    }

    @Test
    public void rejectsRampStageMissingEndpoints() {
        LoadScenario scenario = new LoadScenario()
            .withName("bad-ramp")
            .withProfile(LoadProfile.of(new LoadStage().withType(org.mockserver.load.LoadStageType.VU)
                .withDurationMillis(1_000L).withStartVus(1))) // endVus missing -> treated as a hold without vus
            .withSteps(new LoadStep().withRequest(request().withPath("/api")));

        String error = orchestrator.start(scenario, new RecordingSender());
        assertThat(error, containsString("VU stage requires"));
    }

    @Test
    public void rejectsStageWithZeroDuration() {
        LoadScenario scenario = new LoadScenario()
            .withName("zero-duration")
            .withProfile(LoadProfile.of(LoadStage.constantVus(1, 0L)))
            .withSteps(new LoadStep().withRequest(request().withPath("/api")));

        String error = orchestrator.start(scenario, new RecordingSender());
        assertThat(error, containsString("durationMillis must be > 0"));
    }

    @Test
    public void perIterationTemplatingProducesDistinctPaths() throws Exception {
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("templated")
            .withTemplateType(HttpTemplate.TemplateType.VELOCITY)
            .withMaxRequests(5)
            .withProfile(LoadProfile.constant(1, 60_000L))
            .withSteps(new LoadStep().withRequest(
                request().withPath("/item/$iteration.index").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        assertThat(awaitAtLeast(sender, 5, 5_000L), is(true));

        Set<String> distinctPaths = sender.sent.stream()
            .map(r -> r.getPath().getValue())
            .collect(java.util.stream.Collectors.toSet());
        // $iteration.index varies per global iteration, so paths must differ.
        assertThat(distinctPaths.size(), greaterThan(1));
        assertThat(distinctPaths, hasItem("/item/0"));
    }

    @Test
    public void recordsSamplesIntoSloStoreWhenTrackingEnabled() throws Exception {
        ConfigurationProperties.sloTrackingEnabled(true);
        SloSampleStore.getInstance().reset();
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("slo-feed")
            .withMaxRequests(10)
            .withProfile(LoadProfile.constant(2, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        assertThat(awaitAtLeast(sender, 10, 5_000L), is(true));
        // give the whenComplete callbacks a moment to flush into the store
        long deadline = System.currentTimeMillis() + 2_000L;
        while (SloSampleStore.getInstance().size() < 10 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(SloSampleStore.getInstance().size(), greaterThanOrEqualTo(10));
    }

    @Test
    public void rejectsScenarioExceedingVuCap() {
        ConfigurationProperties.loadGenerationMaxVirtualUsers(5);
        orchestrator.setConfiguration(org.mockserver.configuration.Configuration.configuration());
        LoadScenario scenario = new LoadScenario()
            .withName("too-many-vus")
            .withProfile(LoadProfile.constant(50, 1000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api")));

        String error = orchestrator.start(scenario, new RecordingSender());
        assertThat(error, containsString("exceeding the maximum of 5"));
    }

    @Test
    public void rejectsScenarioExceedingStepCap() {
        ConfigurationProperties.loadGenerationMaxSteps(2);
        orchestrator.setConfiguration(org.mockserver.configuration.Configuration.configuration());
        LoadScenario scenario = new LoadScenario()
            .withName("too-many-steps")
            .withProfile(LoadProfile.constant(1, 1000L))
            .withSteps(
                new LoadStep().withRequest(request().withPath("/a")),
                new LoadStep().withRequest(request().withPath("/b")),
                new LoadStep().withRequest(request().withPath("/c")));

        String error = orchestrator.start(scenario, new RecordingSender());
        assertThat(error, containsString("exceeds the maximum of 2"));
    }

    @Test
    public void rejectsMissingProfileAndSteps() {
        assertThat(orchestrator.start(new LoadScenario().withName("x").withProfile(LoadProfile.constant(1, 1000L)),
            new RecordingSender()), containsString("steps"));
        assertThat(orchestrator.start(new LoadScenario().withName("x")
                .withSteps(new LoadStep().withRequest(request().withPath("/a"))),
            new RecordingSender()), containsString("profile"));
    }

    @Test
    public void activeVuCountReturnsToZeroAfterStop() throws Exception {
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("drain-on-stop")
            .withProfile(LoadProfile.constant(4, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        // Let the VUs spin up and issue some traffic so loops are genuinely in flight.
        assertThat("scenario should produce traffic", awaitAtLeast(sender, 20, 5_000L), is(true));
        assertThat("active VUs should have ramped up", orchestrator.activeVuCount(), is(greaterThan(0)));

        orchestrator.stop();

        // Every launched VU loop must release its slot exactly once as it observes the stopped state,
        // so the live active-VU count drains back to zero (no leak, no double-count to a negative).
        assertThat("active VU count should drain to zero after stop",
            awaitLastRunActiveVus(0, 5_000L), is(true));
        assertThat(orchestrator.lastRunActiveVuCount(), is(0));
    }

    @Test
    public void activeVuCountReturnsToZeroAfterCompletion() throws Exception {
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("drain-on-complete")
            .withMaxRequests(30)
            .withProfile(LoadProfile.constant(4, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        assertThat("scenario should reach maxRequests", awaitAtLeast(sender, 30, 5_000L), is(true));

        // Drive ticks so completion is observed, then the in-flight VU loops drain their slots.
        long deadline = System.currentTimeMillis() + 2_000L;
        while (orchestrator.getStatus() != null && org.mockserver.load.LoadScenarioState.RUNNING == orchestrator.getStatus().state
            && System.currentTimeMillis() < deadline) {
            orchestrator.tickNow();
            Thread.sleep(5);
        }
        assertThat(orchestrator.getStatus().state, is(org.mockserver.load.LoadScenarioState.COMPLETED));
        assertThat("active VU count should drain to zero after completion",
            awaitLastRunActiveVus(0, 5_000L), is(true));
        assertThat(orchestrator.lastRunActiveVuCount(), is(0));
    }

    @Test
    public void activeVuCountRampsDownToTargetWithoutUnderflow() throws Exception {
        // Ramp up to a high concurrency, then drop the target and assert the live population settles
        // exactly at the new target (surplus VUs retire exactly once each; concurrent retirement
        // never collapses below the target).
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("ramp-down")
            .withProfile(LoadProfile.linear(6, 2, 1_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        // At t=0 the linear profile starts at 6 VUs.
        assertThat("active VUs should ramp up to the start target",
            awaitLastRunActiveVus(6, 5_000L), is(true));

        // Advance the clock so the linear ramp's target falls to 2, then tick to apply the setpoint
        // and let surplus VUs retire at their iteration boundaries.
        clock.set(10_000L + 1_000L - 1L);
        orchestrator.tickNow();
        assertThat("surplus VUs should retire down to exactly the target",
            awaitLastRunActiveVus(2, 5_000L), is(true));
        assertThat("retirement must not underflow below the target",
            orchestrator.lastRunActiveVuCount(), is(2));
    }

    @Test
    public void getStatusReportsTerminalStateAfterStop() throws Exception {
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("stoppable")
            .withProfile(LoadProfile.constant(1, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));
        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        orchestrator.stop();
        assertThat(orchestrator.getStatus(), is(notNullValue()));
        assertThat(orchestrator.getStatus().state, is(org.mockserver.load.LoadScenarioState.STOPPED));
    }

    @Test
    public void startDelayKeepsScenarioPendingUntilDelayElapses() throws Exception {
        // A scenario with startDelayMillis>0 must sit PENDING and fire NO requests until the delay
        // elapses (advance the test clock), then RUN.
        RecordingSender sender = new RecordingSender();
        LoadScenario scenario = new LoadScenario()
            .withName("delayed")
            .withStartDelayMillis(500L)
            .withProfile(LoadProfile.constant(2, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/api").withHeader("Host", "target")));

        assertThat(orchestrator.start(scenario, sender), is(nullValue()));
        // Immediately after trigger: PENDING, no traffic.
        assertThat(orchestrator.getStatus().state, is(org.mockserver.load.LoadScenarioState.PENDING));

        // Tick while still inside the delay window (t=300ms < 500ms delay): still PENDING, no requests.
        clock.set(10_000L + 300L);
        orchestrator.tickNow();
        Thread.sleep(50);
        assertThat("no requests fire during the start delay", sender.sent.size(), is(0));
        assertThat(orchestrator.getStatus().state, is(org.mockserver.load.LoadScenarioState.PENDING));

        // Advance past the delay: transitions to RUNNING and begins firing.
        clock.set(10_000L + 600L);
        orchestrator.tickNow();
        assertThat("scenario runs after the delay elapses", awaitAtLeast(sender, 2, 5_000L), is(true));
        assertThat(orchestrator.getStatus().state, is(org.mockserver.load.LoadScenarioState.RUNNING));
    }

    @Test
    public void triggeringMultipleScenariosRunsThemConcurrently() throws Exception {
        // Two scenarios triggered at once both run; each records its own metrics under its own run_id.
        org.mockserver.configuration.Configuration config =
            org.mockserver.configuration.Configuration.configuration().metricsEnabled(true);
        new Metrics(config);
        orchestrator.setConfiguration(config);

        RecordingSender senderA = new RecordingSender();
        RecordingSender senderB = new RecordingSender();
        // No maxRequests so each runs the full 60s stage and stays active during the assertions; a
        // 5ms per-step think time paces the loops so the instant sender does not flood unbounded.
        LoadScenario a = new LoadScenario().withName("concurrent-a")
            .withProfile(LoadProfile.constant(2, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/a").withHeader("Host", "target"))
                .withThinkTime(org.mockserver.model.Delay.milliseconds(5)));
        LoadScenario b = new LoadScenario().withName("concurrent-b")
            .withProfile(LoadProfile.constant(2, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/b").withHeader("Host", "target"))
                .withThinkTime(org.mockserver.model.Delay.milliseconds(5)));

        try {
            assertThat(orchestrator.start(a, senderA), is(nullValue()));
            String runIdA = orchestrator.statusFor("concurrent-a").runId;
            assertThat(orchestrator.start(b, senderB), is(nullValue()));
            String runIdB = orchestrator.statusFor("concurrent-b").runId;

            assertThat("both runs are active concurrently", orchestrator.getStatuses().size(), is(2));
            assertThat(runIdA, is(not(runIdB)));

            assertThat(awaitAtLeast(senderA, 10, 5_000L), is(true));
            assertThat(awaitAtLeast(senderB, 10, 5_000L), is(true));
            Thread.sleep(100);

            // Each run records under its own scenario/run_id — no cross-contamination.
            long countA = Metrics.getLoadRequestCount("concurrent-a", runIdA, "0", "/a", "unknown", "2xx");
            long countB = Metrics.getLoadRequestCount("concurrent-b", runIdB, "0", "/b", "unknown", "2xx");
            assertThat(countA, greaterThanOrEqualTo(10L));
            assertThat(countB, greaterThanOrEqualTo(10L));
            // Run A's series carries only A's path; B's run_id never appears under A's name.
            assertThat(Metrics.getLoadRequestCount("concurrent-a", runIdB, "0", "/a", "unknown", "2xx"), is(0L));
        } finally {
            orchestrator.stop("concurrent-a");
            orchestrator.stop("concurrent-b");
        }
    }

    @Test
    public void rejectsTriggerExceedingMaxConcurrentScenarios() {
        ConfigurationProperties.loadGenerationMaxConcurrentScenarios(1);
        orchestrator.setConfiguration(org.mockserver.configuration.Configuration.configuration());
        RecordingSender sender = new RecordingSender();
        LoadScenario a = new LoadScenario().withName("cap-a")
            .withProfile(LoadProfile.constant(1, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/a").withHeader("Host", "target")));
        LoadScenario b = new LoadScenario().withName("cap-b")
            .withProfile(LoadProfile.constant(1, 60_000L))
            .withSteps(new LoadStep().withRequest(request().withPath("/b").withHeader("Host", "target")));

        assertThat(orchestrator.start(a, sender), is(nullValue()));
        String error = orchestrator.start(b, sender);
        assertThat(error, containsString("maximum of 1"));
        // Re-triggering an already-active name is exempt from the cap (it replaces, no net increase).
        assertThat(orchestrator.start(a, sender), is(nullValue()));
    }
}
