package org.mockserver.mock.action.http;

import org.mockserver.configuration.Configuration;
import org.mockserver.load.IterationContext;
import org.mockserver.load.LoadProfile;
import org.mockserver.load.LoadScenario;
import org.mockserver.load.LoadStage;
import org.mockserver.load.LoadScenarioState;
import org.mockserver.load.LoadStageType;
import org.mockserver.load.LoadStep;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.metrics.MetricLabels;
import org.mockserver.metrics.Metrics;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpTemplate;
import org.mockserver.slo.Scope;
import org.mockserver.slo.SloSampleStore;
import org.mockserver.telemetry.W3CTraceContext;
import org.mockserver.templates.engine.TemplateEngine;
import org.mockserver.templates.engine.mustache.MustacheTemplateEngine;
import org.mockserver.templates.engine.velocity.VelocityTemplateEngine;
import org.mockserver.time.TimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Orchestrates an in-process API-driven load scenario. A scenario drives an ordered list of templated
 * request {@link LoadStep}s through a sequence of {@link LoadStage}s described by a {@link LoadProfile},
 * producing latency/error samples for the SLO verdict feature.
 *
 * <p>Copies the {@link ChaosExperimentOrchestrator} shape: a process-wide singleton with a
 * single-thread daemon {@link ScheduledExecutorService} ("load-scenario-scheduler"). The
 * scheduler thread does <b>no I/O</b> — it advances stages and computes setpoints on a fixed control
 * tick and hands each request to an injected {@code sender} that returns a {@link CompletableFuture}
 * immediately. Step pacing is scheduled (never {@link Delay#applyDelay() Thread.sleep}-ed), so a slow
 * target never blocks a worker thread.
 *
 * <p><b>Two load models, run in sequence:</b>
 * <ul>
 *   <li>{@link LoadStageType#VU} — closed model. The tick maintains a pool of looping virtual users
 *       sized to {@code targetVusAt(elapsedInStage)} (hold or ramp); each VU loops the steps back-to-back.</li>
 *   <li>{@link LoadStageType#RATE} — open model (arrival rate). The tick computes the target rate
 *       {@code r(t)} in iterations/second and starts new <em>one-shot</em> iterations so the cumulative
 *       number started tracks the integral of {@code r(t)} (deficit accounting), auto-scaling a VU pool
 *       up to the stage {@code maxVus} (or the global cap). When the cap blocks the rate, the shortfall
 *       is counted as a {@code rate_limit} throttle.</li>
 *   <li>{@link LoadStageType#PAUSE} — drives no load; VUs drain for the duration.</li>
 * </ul>
 *
 * <p><b>Decoupling:</b> core must not depend on the Netty HTTP client, so the actual request sender is
 * injected via {@link #setSender(Function)} (mirrors {@code HttpState.setReplayHandler}). The Netty
 * runtime wires it from {@code HttpActionHandler.getHttpClient()}; unit tests pass a deterministic
 * synchronous fake sender to {@link #start(LoadScenario, Function)}.
 *
 * <p><b>Self-load guard:</b> off by default ({@code loadGenerationEnabled=false} → the PUT endpoint
 * returns 403). Even when enabled, {@link #validate} enforces hard caps on VUs, rate, stages, duration
 * and step count, and dispatch is bounded live by an in-flight {@link Semaphore} and an RPS token
 * bucket, so a forgotten scenario cannot self-DoS the server.
 *
 * <p>Time is read via a pluggable {@link LongSupplier} clock (defaults to
 * {@link TimeService#currentTimeMillis()}) so tests can drive progression deterministically via
 * {@link #tickNow()} / {@link #advanceNow(long)} without wall-clock sleeps.
 */
public class LoadScenarioOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(LoadScenarioOrchestrator.class);

    /** Control tick interval in milliseconds (stage advance + setpoint recomputation). */
    static final long CONTROL_TICK_MILLIS = 100;

    private static final LoadScenarioOrchestrator INSTANCE = new LoadScenarioOrchestrator(
        TimeService::currentTimeMillis,
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "load-scenario-scheduler");
            t.setDaemon(true);
            return t;
        })
    );

    private final LongSupplier clock;
    private final ScheduledExecutorService scheduler;
    /**
     * Active runs keyed by scenario name. A run is "active" while it is PENDING (waiting out its
     * start delay) or RUNNING. The single scheduler thread ticks every active run each control tick.
     * Re-triggering a name replaces that name's run (and evicts the prior run's metric series).
     */
    private final Map<String, RunningScenario> runs = new ConcurrentHashMap<>();
    /**
     * The terminal (stopped/completed) status of the most recent run for each scenario name, retained
     * for status queries after the run leaves {@link #runs}. Cleared on {@link #reset()} and when the
     * scenario is removed from the registry.
     */
    private final Map<String, LoadScenarioStatus> terminalStatuses = new ConcurrentHashMap<>();
    /** Name of the most-recently-triggered run, backing the single-run convenience accessors. */
    private volatile String lastRunName;
    /** Most recently started run, retained for test assertions on slot accounting after termination. */
    private volatile RunningScenario lastRun;
    /**
     * The single shared scheduled control tick that drives ALL active runs. Created lazily on the
     * first trigger and cancelled when the last run terminates so an idle orchestrator schedules no
     * work (the single-run model previously scheduled one tick per run).
     */
    private volatile ScheduledFuture<?> sharedTick;
    /** Sender installed by the runtime; null in unit tests until start() supplies one. */
    private volatile Function<HttpRequest, CompletableFuture<HttpResponse>> installedSender;
    /** Configuration used to read caps and to build the template engines for rendering. */
    private volatile Configuration configuration = Configuration.configuration();

    LoadScenarioOrchestrator(LongSupplier clock, ScheduledExecutorService scheduler) {
        this.clock = clock;
        this.scheduler = scheduler;
    }

    public static LoadScenarioOrchestrator getInstance() {
        return INSTANCE;
    }

    /**
     * Install the request sender that re-issues a {@link HttpRequest} to its target and returns
     * the upstream response. Called by the Netty runtime, wiring the existing HTTP client so the
     * core never depends on it directly (mirrors {@code HttpState.setReplayHandler}).
     */
    public void setSender(Function<HttpRequest, CompletableFuture<HttpResponse>> sender) {
        this.installedSender = sender;
    }

    /** Install the configuration used for caps and template engines. Called by the runtime. */
    public void setConfiguration(Configuration configuration) {
        if (configuration != null) {
            this.configuration = configuration;
        }
    }

    /**
     * Trigger a scenario to start (run it). Returns a validation error message if the definition is
     * invalid, the caps are exceeded, or the concurrent-scenario cap would be exceeded; or
     * {@code null} on success. Each trigger gets a fresh {@code run_id}. Re-triggering a name that is
     * already active replaces that run (and evicts its prior metric series). A scenario with
     * {@code startDelayMillis > 0} enters {@code PENDING} and begins after the delay; otherwise it
     * begins {@code RUNNING} immediately. When {@code sender} is null the installed runtime sender is
     * used; unit tests pass a deterministic synchronous sender here.
     */
    public String start(LoadScenario scenario, Function<HttpRequest, CompletableFuture<HttpResponse>> sender) {
        String error = validate(scenario);
        if (error != null) {
            return error;
        }
        Function<HttpRequest, CompletableFuture<HttpResponse>> effectiveSender = sender != null ? sender : installedSender;
        if (effectiveSender == null) {
            return "no load sender installed (server runtime not wired)";
        }
        // Concurrency cap: count active (PENDING+RUNNING) runs. Re-triggering an already-active name
        // replaces it (no net increase), so it is exempt from the cap check.
        int maxConcurrent = Math.max(1, configuration.loadGenerationMaxConcurrentScenarios());
        if (!runs.containsKey(scenario.getName()) && runs.size() >= maxConcurrent) {
            return "starting '" + scenario.getName() + "' would exceed the maximum of " + maxConcurrent
                + " concurrently active load scenarios";
        }

        long now = clock.getAsLong();
        RunningScenario running = new RunningScenario(scenario, effectiveSender, now);
        lastRun = running;
        lastRunName = scenario.getName();

        // Replace any existing active run for this name: stop it and evict its series so series
        // accumulation stays bounded to one run per (scenario,run_id).
        RunningScenario previous = runs.put(scenario.getName(), running);
        if (previous != null) {
            previous.stopped.set(true);
            Metrics.evictLoadRun(previous.runId);
            terminalStatuses.remove(scenario.getName());
        } else {
            // No active run for this name, but a prior run may have completed/stopped and its series
            // retained; evict it now that a new run for the same name starts (bounds to <=1 prior run).
            LoadScenarioStatus prior = terminalStatuses.remove(scenario.getName());
            if (prior != null && prior.runId != null && !prior.runId.equals(running.runId)) {
                Metrics.evictLoadRun(prior.runId);
            }
        }

        installGaugeReaders();
        ensureSharedTick();

        if (scenario.getStartDelayMillis() > 0) {
            running.state = LoadScenarioState.PENDING;
            LOG.info("load scenario '{}' pending start delay of {}ms ({} step(s), {} stage(s))",
                scenario.getName(), scenario.getStartDelayMillis(), scenario.getSteps().size(),
                scenario.getProfile().getStages().size());
        } else {
            running.beginRunning(now);
            LOG.info("load scenario '{}' started with {} step(s), {} stage(s)",
                scenario.getName(), scenario.getSteps().size(), scenario.getProfile().getStages().size());
        }

        // Run an immediate first tick so the first stage starts (or the pending-delay is evaluated)
        // without waiting a full control interval.
        tick(running);
        return null;
    }

    /**
     * Stop the most-recently-triggered run (single-run convenience). Idempotent. For multi-run use
     * {@link #stop(String)} or {@link #stopAll()}.
     */
    public void stop() {
        if (lastRunName != null) {
            stop(lastRunName);
        }
    }

    /** Stop a specific scenario's active run by name. Idempotent; no-op if not active. */
    public void stop(String name) {
        RunningScenario run = runs.remove(name);
        if (run != null) {
            terminate(run, LoadScenarioState.STOPPED);
        }
    }

    /** Stop every active run. */
    public void stopAll() {
        for (String name : new ArrayList<>(runs.keySet())) {
            stop(name);
        }
    }

    /** Reset: stop all active runs and clear all terminal status. Called on server reset. */
    public void reset() {
        stopAll();
        terminalStatuses.clear();
        lastRunName = null;
        lastRun = null;
    }

    /**
     * Status of the most-recently-triggered run (active or its retained terminal status), or null if
     * none has ever run. Single-run convenience; see {@link #getStatuses()} for all runs.
     */
    public LoadScenarioStatus getStatus() {
        if (lastRunName == null) {
            return null;
        }
        return statusFor(lastRunName);
    }

    /** Status for a specific scenario name (active run snapshot or retained terminal status), or null. */
    public LoadScenarioStatus statusFor(String name) {
        RunningScenario run = runs.get(name);
        if (run != null) {
            return run.snapshot(run.state, clock.getAsLong());
        }
        return terminalStatuses.get(name);
    }

    /** True if the named scenario currently has an active (PENDING or RUNNING) run. */
    public boolean isActive(String name) {
        return runs.containsKey(name);
    }

    /**
     * Snapshots of every currently-active run (PENDING/RUNNING), keyed by scenario name. The registry
     * (not the orchestrator) is the source of truth for the full scenario list; this only reports the
     * live ones. Includes the retained terminal status for names with no active run is the caller's
     * job via {@link #statusFor(String)}.
     */
    public Map<String, LoadScenarioStatus> getStatuses() {
        Map<String, LoadScenarioStatus> result = new LinkedHashMap<>();
        long now = clock.getAsLong();
        runs.forEach((name, run) -> result.put(name, run.snapshot(run.state, now)));
        return result;
    }

    // -- Internal --

    /**
     * Install the active-VU and in-flight gauge readers. Each reader returns one entry per active run
     * (PENDING runs report zero), so the gauges emit one series per (scenario, run_id). Installed once
     * the first run starts; cleared when the last run terminates.
     */
    private void installGaugeReaders() {
        Metrics.setLoadGaugeReaders(
            () -> {
                if (runs.isEmpty()) {
                    return Collections.emptyMap();
                }
                Map<Metrics.LoadGaugeKey, Integer> out = new LinkedHashMap<>();
                runs.forEach((name, run) -> out.put(run.gaugeKey, Math.max(0, run.activeVUs.get())));
                return out;
            },
            () -> {
                if (runs.isEmpty()) {
                    return Collections.emptyMap();
                }
                Map<Metrics.LoadGaugeKey, Integer> out = new LinkedHashMap<>();
                runs.forEach((name, run) -> out.put(run.gaugeKey, Math.max(0, run.inFlightCount.get())));
                return out;
            });
    }

    /** Lazily start the single shared control tick that drives all active runs. */
    private synchronized void ensureSharedTick() {
        if (sharedTick == null) {
            sharedTick = scheduler.scheduleAtFixedRate(
                this::tickAll, CONTROL_TICK_MILLIS, CONTROL_TICK_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    /** Cancel the shared tick once no runs remain active. */
    private synchronized void stopSharedTickIfIdle() {
        if (runs.isEmpty() && sharedTick != null) {
            sharedTick.cancel(false);
            sharedTick = null;
        }
    }

    /** The shared control tick: advance every active run. Runs on the single scheduler thread. */
    private void tickAll() {
        for (RunningScenario run : new ArrayList<>(runs.values())) {
            tick(run);
        }
    }

    /**
     * True while {@code run} is still the registry's active run for its scenario name. A run that was
     * stopped, completed, or replaced by a re-trigger is no longer current, so late-draining callbacks
     * and ticks must not act on it (this is the multi-run replacement for the old single-run guard).
     */
    private boolean isCurrent(RunningScenario run) {
        return runs.get(run.scenario.getName()) == run;
    }

    /**
     * Terminate a run (already removed from {@link #runs}): mark it stopped, capture its terminal
     * status, clear gauge readers if it was the last run. The run's durable metric series are RETAINED
     * (scrapeable) until the scenario is next triggered or removed from the registry.
     */
    private void terminate(RunningScenario run, LoadScenarioState terminalState) {
        run.stopped.set(true);
        LoadScenarioStatus status = run.snapshot(terminalState, clock.getAsLong());
        terminalStatuses.put(run.scenario.getName(), status);
        if (runs.isEmpty()) {
            Metrics.setLoadGaugeReaders(null, null);
        } else {
            installGaugeReaders();
        }
        stopSharedTickIfIdle();
        LOG.info("load scenario '{}' {} ({} requests sent)",
            run.scenario.getName(), terminalState.name().toLowerCase(), run.requestsSent.get());
    }

    /**
     * Evict the retained metric series for a scenario removed from the registry (its run is no longer
     * referenceable). Called by HttpState when a DELETE removes a registered scenario.
     */
    public void evictTerminalSeries(String name) {
        LoadScenarioStatus status = terminalStatuses.remove(name);
        if (status != null && status.runId != null) {
            Metrics.evictLoadRun(status.runId);
        }
    }

    /**
     * Control tick: advance through the stages by elapsed time, apply the current stage's setpoint
     * (VU pool size or arrival rate), and detect completion (all stages elapsed or maxRequests
     * reached). Runs on the scheduler thread.
     */
    private void tick(RunningScenario run) {
        if (run.stopped.get() || !isCurrent(run)) {
            return;
        }
        long now = clock.getAsLong();
        // PENDING: wait out the start delay (measured against the orchestrator clock so the injected
        // test clock works). Fire NO load until the delay elapses, then transition to RUNNING and start
        // the stage clock from that moment.
        if (run.state == LoadScenarioState.PENDING) {
            if (now - run.triggeredAt < run.scenario.getStartDelayMillis()) {
                return;
            }
            run.beginRunning(now);
            LOG.info("load scenario '{}' start delay elapsed, now running ({} step(s), {} stage(s))",
                run.scenario.getName(), run.scenario.getSteps().size(),
                run.scenario.getProfile().getStages().size());
        }
        if (run.maxRequestsReached()) {
            completeInternal(run);
            return;
        }
        long elapsed = now - run.startedAt;

        // Locate the active stage for the elapsed time. The run ends once elapsed passes the sum of all
        // stage durations.
        List<LoadStage> stages = run.scenario.getProfile().getStages();
        long boundary = 0;
        int stageIndex = -1;
        long elapsedInStage = 0;
        for (int i = 0; i < stages.size(); i++) {
            long stageDuration = Math.max(0, stages.get(i).getDurationMillis());
            if (elapsed < boundary + stageDuration) {
                stageIndex = i;
                elapsedInStage = elapsed - boundary;
                break;
            }
            boundary += stageDuration;
        }
        if (stageIndex < 0) {
            // Past the last stage boundary: the whole sequence is done.
            completeInternal(run);
            return;
        }

        run.stageIndex.set(stageIndex);
        run.elapsedInStageMillis.set(elapsedInStage);
        LoadStage stage = stages.get(stageIndex);

        // On a stage transition (entering any new stage index, including the first), reset the RATE
        // arrival-rate accumulator so a freshly-entered RATE stage integrates ONLY its own elapsed time.
        // This re-arms the driveRateStage "last==0 → first tick initialises, does not integrate" guard
        // and discards iterations still owed from a prior RATE stage, so a RATE stage that follows a
        // VU/PAUSE stage (or another RATE stage after a gap) cannot add targetRate·gap to the deficit on
        // its first tick and burst. Runs exactly once per boundary (guarded by activeStageIndex) on the
        // single scheduler thread, so no extra synchronisation is needed for these run-local fields.
        if (run.activeStageIndex != stageIndex) {
            run.activeStageIndex = stageIndex;
            run.lastRateTickMillis = 0;
            run.rateDeficit = 0;
        }

        switch (stage.getType()) {
            case VU:
                driveVuStage(run, stage, elapsedInStage);
                break;
            case RATE:
                driveRateStage(run, stage, elapsedInStage, now);
                break;
            case PAUSE:
            default:
                // No load: target zero VUs so any looping VUs from a prior VU stage retire at their
                // iteration boundary and the pool drains.
                run.targetVUs.set(0);
                break;
        }
    }

    /**
     * Closed-model VU stage: maintain a pool of <em>looping</em> virtual users sized to the stage's
     * VU setpoint. Growth launches new looping VUs; surplus VUs retire at their iteration boundary.
     */
    private void driveVuStage(RunningScenario run, LoadStage stage, long elapsedInStage) {
        int target = Math.max(0, stage.targetVusAt(elapsedInStage));
        run.targetVUs.set(target);
        int active = run.activeVUs.get();
        if (active < target) {
            int toLaunch = target - active;
            for (int i = 0; i < toLaunch; i++) {
                int vuId = run.vuIdSequence.getAndIncrement();
                run.activeVUs.incrementAndGet();
                launchIteration(run, vuId, 0, true);
            }
        }
    }

    /**
     * Open-model arrival-rate stage: start as many new one-shot iterations as the integral of the
     * target rate since the last tick demands (deficit accounting), bounded by the stage's VU cap.
     *
     * <p>The deficit is a fractional accumulator: each tick adds {@code rate * dtSeconds} iterations
     * owed and starts {@code floor(deficit)} of them, carrying the fractional remainder forward so the
     * achieved long-run rate equals the target rate exactly regardless of tick granularity. Each
     * started iteration occupies one VU slot for its lifetime; the {@code activeVUs} pool auto-scales
     * up to the cap. When the cap is hit the unstarted owed iterations are dropped (deficit clamped)
     * and counted as a {@code rate_limit} throttle so the operator can see the shortfall.
     */
    private void driveRateStage(RunningScenario run, LoadStage stage, long elapsedInStage, long now) {
        double targetRate = stage.targetRateAt(elapsedInStage);
        run.targetRate.set(Double.doubleToRawLongBits(Math.max(0.0, targetRate)));
        // targetVUs for a RATE stage is the live in-use VU pool (informational for the status DTO).
        int stageVuCap = stage.getMaxVus() != null && stage.getMaxVus() > 0
            ? stage.getMaxVus()
            : configuration.loadGenerationMaxVirtualUsers();

        long last = run.lastRateTickMillis;
        if (last == 0) {
            run.lastRateTickMillis = now;
            run.targetVUs.set(run.activeVUs.get());
            return;
        }
        long dtMillis = Math.max(0, now - last);
        run.lastRateTickMillis = now;
        run.rateDeficit += targetRate * (dtMillis / 1000.0);

        int toStart = (int) Math.floor(run.rateDeficit);
        if (toStart > 0) {
            int started = 0;
            for (int i = 0; i < toStart; i++) {
                if (run.activeVUs.get() >= stageVuCap) {
                    break;
                }
                int vuId = run.vuIdSequence.getAndIncrement();
                run.activeVUs.incrementAndGet();
                launchIteration(run, vuId, 0, false);
                started++;
            }
            run.rateDeficit -= started;
            int shortfall = toStart - started;
            if (shortfall > 0) {
                // The VU cap blocked the rate: drop the owed-but-unstartable iterations (so the deficit
                // does not snowball) and record the shortfall as a rate_limit throttle.
                run.rateDeficit -= shortfall;
                if (run.rateDeficit < 0) {
                    run.rateDeficit = 0;
                }
                for (int i = 0; i < shortfall; i++) {
                    Metrics.incrementLoadThrottled(run.scenario.getName(), run.runId, "rate_limit");
                }
            }
        }
        run.targetVUs.set(run.activeVUs.get());
    }

    private void completeInternal(RunningScenario run) {
        // Atomically de-register this run iff it is still the current run for its name (a concurrent
        // re-trigger may have already replaced it). Only the winner records the terminal status.
        if (runs.remove(run.scenario.getName(), run)) {
            terminate(run, LoadScenarioState.COMPLETED);
        }
    }

    /**
     * Run one iteration for a virtual user: render and fire each step in order, chaining the
     * CompletableFutures. After the last step, a {@code looping} VU schedules its next iteration
     * (closed model); a one-shot VU (open/arrival-rate model) ends and releases its slot. No
     * dedicated thread per VU; no blocking.
     */
    private void launchIteration(RunningScenario run, int vuId, long vuIteration, boolean looping) {
        if (run.stopped.get() || !isCurrent(run)) {
            endVu(run);
            return;
        }
        // Closed-model surplus retirement: if the live population currently exceeds the target
        // concurrency, this looping VU's loop ends here. Applied as a single atomic conditional
        // decrement so concurrent surplus VUs never collapse the population below the target. One-shot
        // (rate) VUs never retire this way — they always run exactly one iteration.
        if (looping && vuIteration > 0 && tryRetireSurplus(run)) {
            return;
        }
        fireStep(run, vuId, vuIteration, 0, looping);
    }

    /**
     * Atomically retire this VU iff the live population currently exceeds the target concurrency,
     * decrementing the active-population counter exactly once on success. Returns {@code true} when
     * the VU was retired (its loop must end), {@code false} when it should continue.
     */
    private boolean tryRetireSurplus(RunningScenario run) {
        while (true) {
            int active = run.activeVUs.get();
            if (active <= run.targetVUs.get()) {
                return false;
            }
            if (run.activeVUs.compareAndSet(active, active - 1)) {
                return true;
            }
        }
    }

    /**
     * End a virtual user's loop, releasing the single active-population slot it owned. Called from
     * exactly one of the loop's terminal exit points so the live counter decrements exactly once per
     * launched VU (never zero, never twice).
     */
    private void endVu(RunningScenario run) {
        run.activeVUs.decrementAndGet();
    }

    private void fireStep(RunningScenario run, int vuId, long vuIteration, int stepIndex, boolean looping) {
        if (run.stopped.get() || !isCurrent(run)) {
            endVu(run);
            return;
        }
        // Stop dispatching once the request cap is reached; the control tick then transitions to
        // "completed". Enforcing this here (not only on the tick) bounds the overshoot. This VU's loop
        // ends here, so release its slot exactly once.
        if (run.maxRequestsReached()) {
            endVu(run);
            completeInternal(run);
            return;
        }
        List<LoadStep> steps = run.scenario.getSteps();
        if (stepIndex >= steps.size()) {
            // Iteration finished: account it.
            Metrics.incrementLoadIteration(run.scenario.getName(), run.runId);
            if (!looping) {
                // One-shot (arrival-rate) iteration: end the VU and release its slot.
                endVu(run);
                return;
            }
            // Closed-model VU: schedule the next iteration immediately (re-scheduled, not recursed, to
            // avoid starving the single scheduler thread).
            long nextIteration = vuIteration + 1;
            scheduler.schedule(() -> launchIteration(run, vuId, nextIteration, true), 0, TimeUnit.MILLISECONDS);
            return;
        }

        LoadStep step = steps.get(stepIndex);
        long globalIndex = run.iterationIndex.getAndIncrement();
        long count = run.requestsSent.get();
        long elapsed = clock.getAsLong() - run.startedAt;
        IterationContext iteration = new IterationContext(globalIndex, vuId, vuIteration, elapsed, count);
        final String stepLabel = run.stepLabel(step, stepIndex);
        final Map<String, String> stepCustomLabels = run.customLabelsFor(step);

        HttpRequest rendered;
        try {
            rendered = run.render(step.getRequest(), iteration);
        } catch (Exception e) {
            LOG.warn("load scenario '{}' failed to render step {} (vu {} iteration {}): {}",
                run.scenario.getName(), stepIndex, vuId, vuIteration, e.getMessage());
            run.failed.incrementAndGet();
            run.requestsSent.incrementAndGet();
            Metrics.incrementLoadError(run.scenario.getName(), run.runId, "render");
            scheduleNextStep(run, vuId, vuIteration, stepIndex, step, looping);
            return;
        }

        // Mark every generated request so the server keeps its own load traffic out of the
        // user-facing request event log (a bounded ring buffer). Without this, a running scenario
        // floods the log and evicts real / LLM traffic that the Traffic, Trace and Optimise views
        // depend on. See MockServerEventLog#LOAD_GENERATED_HEADER. Load throughput/latency/SLO are
        // recorded client-side in recordResult(), so suppressing the log entry does not lose metrics.
        if (rendered != null) {
            rendered.withHeader(MockServerEventLog.LOAD_GENERATED_HEADER, run.runId);
        }

        // Self-load guard at dispatch: acquire an in-flight permit and an RPS token. Each skip is a
        // distinct throttle reason so an operator can see why a scenario could not reach its setpoint.
        if (!run.inFlight.tryAcquire()) {
            Metrics.incrementLoadThrottled(run.scenario.getName(), run.runId, "inflight_cap");
            scheduleNextStep(run, vuId, vuIteration, stepIndex, step, looping);
            return;
        }
        if (!run.tryAcquireRpsToken(clock.getAsLong())) {
            run.inFlight.release();
            Metrics.incrementLoadThrottled(run.scenario.getName(), run.runId, "rate_limit");
            scheduleNextStep(run, vuId, vuIteration, stepIndex, step, looping);
            return;
        }

        run.requestsSent.incrementAndGet();
        run.inFlightCount.incrementAndGet();
        final long startNanos = TimeService.nanoTime();
        final String host = hostOf(rendered);
        final String route = run.routeLabel(step, rendered);
        final String method = methodOf(rendered);
        final long requestBytes = bodyBytes(rendered != null ? rendered.getBodyAsRawBytes() : null);
        final String traceId = traceIdOf(rendered);
        CompletableFuture<HttpResponse> future;
        try {
            future = run.sender.apply(rendered);
        } catch (Exception e) {
            run.inFlight.release();
            run.inFlightCount.decrementAndGet();
            recordResult(run, host, stepLabel, route, method, requestBytes, traceId, stepCustomLabels, null, startNanos, true, "connection");
            scheduleNextStep(run, vuId, vuIteration, stepIndex, step, looping);
            return;
        }
        if (future == null) {
            run.inFlight.release();
            run.inFlightCount.decrementAndGet();
            recordResult(run, host, stepLabel, route, method, requestBytes, traceId, stepCustomLabels, null, startNanos, true, "null_response");
            scheduleNextStep(run, vuId, vuIteration, stepIndex, step, looping);
            return;
        }
        future.whenComplete((response, throwable) -> {
            run.inFlight.release();
            run.inFlightCount.decrementAndGet();
            boolean error = throwable != null || response == null
                || (response.getStatusCode() != null && response.getStatusCode() >= 500);
            String errorKind = classifyError(throwable, response);
            recordResult(run, host, stepLabel, route, method, requestBytes, traceId, stepCustomLabels, response, startNanos, error, errorKind);
            scheduleNextStep(run, vuId, vuIteration, stepIndex, step, looping);
        });
    }

    /**
     * Classify the error branch of a completed dispatch into one of the kind labels, or null when
     * the request succeeded.
     */
    private static String classifyError(Throwable throwable, HttpResponse response) {
        if (throwable != null) {
            Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
            String name = cause.getClass().getSimpleName().toLowerCase();
            String message = cause.getMessage() != null ? cause.getMessage().toLowerCase() : "";
            if (name.contains("timeout") || message.contains("timeout") || message.contains("timed out")) {
                return "timeout";
            }
            return "connection";
        }
        if (response == null) {
            return "null_response";
        }
        if (response.getStatusCode() != null && response.getStatusCode() >= 500) {
            return "http_5xx";
        }
        return null;
    }

    private void scheduleNextStep(RunningScenario run, int vuId, long vuIteration, int stepIndex, LoadStep step, boolean looping) {
        if (run.stopped.get() || !isCurrent(run)) {
            // The VU loop ends here (the scenario stopped or was replaced mid-iteration). This is a
            // genuine loop-exit point, so release the slot exactly once.
            endVu(run);
            return;
        }
        long thinkMillis = step.getThinkTime() != null ? Math.max(0, step.getThinkTime().sampleValueMillis()) : 0;
        scheduler.schedule(() -> fireStep(run, vuId, vuIteration, stepIndex + 1, looping), thinkMillis, TimeUnit.MILLISECONDS);
    }

    private void recordResult(RunningScenario run, String host, String stepLabel, String route, String method,
                              long requestBytes, String traceId, Map<String, String> customLabels,
                              HttpResponse response, long startNanos, boolean error, String errorKind) {
        long latencyMillis = Math.max(0, (TimeService.nanoTime() - startNanos) / 1_000_000L);
        if (error) {
            run.failed.incrementAndGet();
        } else {
            run.succeeded.incrementAndGet();
        }
        Integer statusCode = response != null ? response.getStatusCode() : null;
        double latencySeconds = latencyMillis / 1000.0;
        long responseBytes = bodyBytes(response != null ? response.getBodyAsRawBytes() : null);
        // Mirror the forward-path observability so load traffic still shows up on the same metrics.
        Metrics.observeForwardRequest(host, statusCode, latencySeconds);
        // New first-class load family: scenario/run/step/route/method/status-class dimensioned. Only
        // emit while this run is still the current run: a run replaced by a new PUT has already had its
        // load series evicted, so a late-draining in-flight request must NOT resurrect them.
        if (isCurrent(run)) {
            Metrics.observeLoadRequest(run.scenario.getName(), run.runId, stepLabel, route, method, statusCode,
                latencySeconds, requestBytes, responseBytes, traceId, customLabels);
            if (error && errorKind != null) {
                Metrics.incrementLoadError(run.scenario.getName(), run.runId, errorKind);
            }
        }
        // Feed the SLO sample store so the SLO verdict feature can read load-driven SLIs.
        SloSampleStore.getInstance().record(TimeService.currentTimeMillis(), latencyMillis, error, Scope.FORWARD, host);
    }

    private static String hostOf(HttpRequest request) {
        if (request == null) {
            return null;
        }
        String hostHeader = request.getFirstHeader("Host");
        if (hostHeader != null && !hostHeader.isEmpty()) {
            int colon = hostHeader.indexOf(':');
            return colon > 0 ? hostHeader.substring(0, colon) : hostHeader;
        }
        return request.getRemoteAddress();
    }

    private static String methodOf(HttpRequest request) {
        if (request == null || request.getMethod() == null) {
            return "unknown";
        }
        String method = request.getMethod().getValue();
        return method != null && !method.isEmpty() ? method : "unknown";
    }

    private static long bodyBytes(byte[] body) {
        return body != null ? body.length : 0L;
    }

    /**
     * Extract the request's trace id (for the histogram exemplar) from a W3C {@code traceparent}
     * header on the rendered request, or null when absent/invalid.
     */
    private static String traceIdOf(HttpRequest request) {
        if (request == null) {
            return null;
        }
        String traceparent = request.getFirstHeader("traceparent");
        if (traceparent == null || traceparent.isEmpty()) {
            return null;
        }
        try {
            W3CTraceContext context = W3CTraceContext.parse(traceparent, null);
            return context != null && context.isValid() ? context.getTraceId() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Force one control tick immediately on every active run (test hook), bypassing the scheduler
     * interval, so progression can be asserted deterministically. No-op when no scenario is active.
     */
    void tickNow() {
        tickAll();
    }

    /**
     * Test hook for the deterministic clock: callers using a mutable {@link LongSupplier} advance
     * the clock externally; this triggers a tick so the orchestrator observes the new elapsed time.
     */
    void advanceNow(long ignoredMillis) {
        tickNow();
    }

    /**
     * Test hook: the live virtual-user population of the current run, or {@code 0} when no scenario is
     * running.
     */
    int activeVuCount() {
        RunningScenario run = lastRunName != null ? runs.get(lastRunName) : null;
        if (run != null) {
            return run.activeVUs.get();
        }
        return 0;
    }

    /**
     * Test hook: the live virtual-user population of the most recently started run, even after it has
     * stopped or completed. Returns {@code 0} when no scenario has ever run.
     */
    int lastRunActiveVuCount() {
        RunningScenario run = lastRun;
        return run != null ? run.activeVUs.get() : 0;
    }

    public String validate(LoadScenario scenario) {
        if (scenario == null) {
            return "'loadScenario' is required";
        }
        if (scenario.getName() == null || scenario.getName().isBlank()) {
            return "'name' is required";
        }
        List<LoadStep> steps = scenario.getSteps();
        if (steps == null || steps.isEmpty()) {
            return "'steps' must contain at least one step";
        }
        int maxSteps = configuration.loadGenerationMaxSteps();
        if (steps.size() > maxSteps) {
            return "'steps' exceeds the maximum of " + maxSteps;
        }
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) == null || steps.get(i).getRequest() == null) {
                return "step[" + i + "] must have a request";
            }
        }
        LoadProfile profile = scenario.getProfile();
        if (profile == null) {
            return "'profile' is required";
        }
        List<LoadStage> stages = profile.getStages();
        if (stages == null || stages.isEmpty()) {
            return "'profile.stages' must contain at least one stage";
        }
        int maxStages = configuration.loadGenerationMaxStages();
        if (stages.size() > maxStages) {
            return "'profile.stages' exceeds the maximum of " + maxStages;
        }
        int maxVus = configuration.loadGenerationMaxVirtualUsers();
        double maxRate = configuration.loadGenerationMaxRate();
        boolean anyLoad = false;
        for (int i = 0; i < stages.size(); i++) {
            LoadStage stage = stages.get(i);
            if (stage == null) {
                return "stage[" + i + "] is required";
            }
            if (stage.getType() == null) {
                return "stage[" + i + "] must have a type";
            }
            if (stage.getDurationMillis() <= 0) {
                return "stage[" + i + "].durationMillis must be > 0";
            }
            switch (stage.getType()) {
                case VU:
                    if (stage.isVuRamp()) {
                        if (stage.getStartVus() == null || stage.getEndVus() == null) {
                            return "stage[" + i + "] VU ramp requires both startVus and endVus";
                        }
                    } else if (stage.getVus() == null) {
                        return "stage[" + i + "] VU stage requires vus (hold) or startVus+endVus (ramp)";
                    }
                    if (stage.peakVus() <= 0) {
                        return "stage[" + i + "] must request at least one virtual user";
                    }
                    if (stage.peakVus() > maxVus) {
                        return "stage[" + i + "] requests " + stage.peakVus() + " virtual users, exceeding the maximum of " + maxVus;
                    }
                    anyLoad = true;
                    break;
                case RATE:
                    if (stage.isRateRamp()) {
                        if (stage.getStartRate() == null || stage.getEndRate() == null) {
                            return "stage[" + i + "] RATE ramp requires both startRate and endRate";
                        }
                    } else if (stage.getRate() == null) {
                        return "stage[" + i + "] RATE stage requires rate (hold) or startRate+endRate (ramp)";
                    }
                    if (stage.peakRate() <= 0) {
                        return "stage[" + i + "] must request a positive arrival rate";
                    }
                    if (stage.peakRate() > maxRate) {
                        return "stage[" + i + "] requests " + stage.peakRate() + " iterations/sec, exceeding the maximum of " + maxRate;
                    }
                    if (stage.getMaxVus() != null && stage.getMaxVus() > maxVus) {
                        return "stage[" + i + "].maxVus " + stage.getMaxVus() + " exceeds the maximum of " + maxVus;
                    }
                    anyLoad = true;
                    break;
                case PAUSE:
                default:
                    break;
            }
        }
        if (!anyLoad) {
            return "'profile' must contain at least one VU or RATE stage";
        }
        long totalDuration = profile.totalDurationMillis();
        if (totalDuration <= 0) {
            return "'profile' total duration must be > 0";
        }
        long maxDuration = configuration.loadGenerationMaxDurationMillis();
        if (totalDuration > maxDuration) {
            return "'profile' total duration " + totalDuration + " ms exceeds the maximum of " + maxDuration + " ms";
        }
        if (scenario.getTemplateType() == HttpTemplate.TemplateType.JAVASCRIPT) {
            return "templateType JAVASCRIPT is not supported for load steps; use VELOCITY or MUSTACHE";
        }
        return null;
    }

    /** Mutable state for a running scenario. */
    private final class RunningScenario {
        final LoadScenario scenario;
        final Function<HttpRequest, CompletableFuture<HttpResponse>> sender;
        /** Orchestrator-clock time this run was triggered (used to time out a PENDING start delay). */
        final long triggeredAt;
        /**
         * Orchestrator-clock time the stage clock started (the moment the run entered RUNNING). Equal to
         * {@code triggeredAt} when there is no start delay; later for a delayed scenario. Mutable because
         * a PENDING run's stage clock starts only once the delay elapses.
         */
        volatile long startedAt;
        /** Lifecycle state of this run: PENDING (waiting out the start delay) or RUNNING. */
        volatile LoadScenarioState state = LoadScenarioState.LOADED;
        long startedAtEpoch = TimeService.currentTimeMillis();
        /** Stable per-run id (UUID) used as the {@code run_id} metric label and exposed in the status DTO. */
        final String runId = UUID.randomUUID().toString();
        final Metrics.LoadGaugeKey gaugeKey;
        final AtomicBoolean stopped = new AtomicBoolean(false);
        /** Live VU population: incremented once per launch, decremented exactly once when a loop ends. */
        final AtomicInteger activeVUs = new AtomicInteger(0);
        /** Live in-flight (dispatched, not-yet-completed) request count, backing the inflight gauge. */
        final AtomicInteger inFlightCount = new AtomicInteger(0);
        /** Monotonic VU-id allocator, kept separate from the live population counter. */
        final AtomicInteger vuIdSequence = new AtomicInteger(0);
        final AtomicInteger targetVUs = new AtomicInteger(0);
        /** Current stage index, exposed in the status DTO. */
        final AtomicInteger stageIndex = new AtomicInteger(0);
        /** Elapsed millis within the current stage, exposed in the status DTO. */
        final AtomicLong elapsedInStageMillis = new AtomicLong(0);
        /** Current target arrival rate (iterations/sec), stored as raw long bits; 0 for non-RATE stages. */
        final AtomicLong targetRate = new AtomicLong(0);
        final AtomicLong iterationIndex = new AtomicLong(0);
        final AtomicLong requestsSent = new AtomicLong(0);
        final AtomicLong succeeded = new AtomicLong(0);
        final AtomicLong failed = new AtomicLong(0);
        final Semaphore inFlight;
        final int maxRps;

        // Arrival-rate (RATE stage) deficit accounting; only touched on the single scheduler thread.
        double rateDeficit;
        long lastRateTickMillis;
        /**
         * The stage index the controller last drove, used to detect a stage transition on the single
         * scheduler thread so the RATE accumulator can be reset exactly once per boundary. Starts at
         * {@code -1} (no stage entered yet) so the very first stage is treated as a transition.
         */
        int activeStageIndex = -1;

        // RPS token bucket (1-second window). Synchronized on this monitor.
        private long rpsWindowStart;
        private int rpsTokensUsed;

        // Engines built lazily for rendering (Velocity/Mustache only).
        private volatile TemplateEngine velocity;
        private volatile TemplateEngine mustache;

        RunningScenario(LoadScenario scenario, Function<HttpRequest, CompletableFuture<HttpResponse>> sender, long triggeredAt) {
            this.scenario = scenario;
            this.sender = sender;
            this.triggeredAt = triggeredAt;
            this.startedAt = triggeredAt;
            this.gaugeKey = new Metrics.LoadGaugeKey(scenario.getName(), runId);
            this.inFlight = new Semaphore(Math.max(1, configuration.loadGenerationMaxInFlightRequests()));
            this.maxRps = Math.max(1, configuration.loadGenerationMaxRequestsPerSecond());
            this.rpsWindowStart = triggeredAt;
        }

        /**
         * Transition this run to RUNNING and (re)anchor its stage clock and RPS window to {@code now}.
         * Called immediately on trigger when there is no start delay, or by the control tick once a
         * PENDING run's start delay elapses, so the stage progression always measures from t=0.
         */
        void beginRunning(long now) {
            this.startedAt = now;
            this.rpsWindowStart = now;
            this.startedAtEpoch = TimeService.currentTimeMillis();
            this.state = LoadScenarioState.RUNNING;
        }

        boolean maxRequestsReached() {
            Integer max = scenario.getMaxRequests();
            return max != null && max > 0 && requestsSent.get() >= max;
        }

        synchronized boolean tryAcquireRpsToken(long now) {
            if (now - rpsWindowStart >= 1000) {
                rpsWindowStart = now;
                rpsTokensUsed = 0;
            }
            if (rpsTokensUsed < maxRps) {
                rpsTokensUsed++;
                return true;
            }
            return false;
        }

        /** The {@code step} metric label: the step's explicit name if set, else its 0-based index. */
        String stepLabel(LoadStep step, int stepIndex) {
            if (step != null && step.getName() != null && !step.getName().isBlank()) {
                return step.getName();
            }
            return Integer.toString(stepIndex);
        }

        /**
         * The low-cardinality {@code route} label. When the step has an explicit name, the name is
         * used (operator override); otherwise the rendered request path is templatised via
         * {@link MetricLabels#routeOf(String)}.
         */
        String routeLabel(LoadStep step, HttpRequest rendered) {
            if (step != null && step.getName() != null && !step.getName().isBlank()) {
                return step.getName();
            }
            String path = rendered != null && rendered.getPath() != null ? rendered.getPath().getValue() : null;
            return MetricLabels.routeOf(path);
        }

        /**
         * Merge scenario-level and step-level custom labels (step keys win on conflict), or null when
         * neither is present.
         */
        Map<String, String> customLabelsFor(LoadStep step) {
            Map<String, String> scenarioLabels = scenario.getLabels();
            Map<String, String> stepLabels = step != null ? step.getLabels() : null;
            boolean hasScenario = scenarioLabels != null && !scenarioLabels.isEmpty();
            boolean hasStep = stepLabels != null && !stepLabels.isEmpty();
            if (!hasScenario && !hasStep) {
                return null;
            }
            Map<String, String> merged = new LinkedHashMap<>();
            if (hasScenario) {
                merged.putAll(scenarioLabels);
            }
            if (hasStep) {
                merged.putAll(stepLabels);
            }
            return merged;
        }

        HttpRequest render(HttpRequest request, IterationContext iteration) {
            if (request == null) {
                return null;
            }
            TemplateEngine engine = engineFor(scenario.getTemplateType());
            HttpRequest clone = request.clone();
            String path = request.getPath() != null ? request.getPath().getValue() : null;
            if (path != null && containsTemplate(path)) {
                clone.withPath(engine.renderTemplate(path, request, iteration));
            }
            String body = request.getBodyAsString();
            if (body != null && containsTemplate(body)) {
                clone.withBody(engine.renderTemplate(body, request, iteration));
            }
            return clone;
        }

        private boolean containsTemplate(String value) {
            return value != null && (value.contains("$") || value.contains("{{") || value.contains("#"));
        }

        private TemplateEngine engineFor(HttpTemplate.TemplateType type) {
            if (type == HttpTemplate.TemplateType.MUSTACHE) {
                if (mustache == null) {
                    mustache = new MustacheTemplateEngine(new org.mockserver.logging.MockServerLogger(), configuration);
                }
                return mustache;
            }
            if (velocity == null) {
                velocity = new VelocityTemplateEngine(new org.mockserver.logging.MockServerLogger(), configuration);
            }
            return velocity;
        }

        LoadScenarioStatus snapshot(LoadScenarioState state, long now) {
            boolean running = state == LoadScenarioState.RUNNING;
            boolean terminal = state == LoadScenarioState.COMPLETED || state == LoadScenarioState.STOPPED;
            // PENDING reports the trigger time as the reference; RUNNING/terminal report the stage clock.
            long elapsed = running || terminal ? now - startedAt : 0;
            int currentVus = running ? activeVUs.get() : 0;
            int idx = stageIndex.get();
            List<LoadStage> stages = scenario.getProfile().getStages();
            LoadStageType stageType = idx >= 0 && idx < stages.size() ? stages.get(idx).getType() : null;
            double targetRateValue = Double.longBitsToDouble(targetRate.get());
            double currentTarget = running
                ? (stageType == LoadStageType.RATE ? targetRateValue : targetVUs.get())
                : 0.0;
            return new LoadScenarioStatus(
                scenario.getName(),
                state,
                elapsed,
                currentVus,
                requestsSent.get(),
                succeeded.get(),
                failed.get(),
                Metrics.loadLatencyPercentileMillis(scenario.getName(), runId, 50),
                Metrics.loadLatencyPercentileMillis(scenario.getName(), runId, 95),
                Metrics.loadLatencyPercentileMillis(scenario.getName(), runId, 99),
                runId,
                startedAtEpoch,
                terminal ? now : null,
                scenario.getLabels() != null && !scenario.getLabels().isEmpty()
                    ? Collections.unmodifiableMap(new LinkedHashMap<>(scenario.getLabels()))
                    : null,
                scenario,
                running ? idx : -1,
                running && stageType != null ? stageType.name() : null,
                currentTarget,
                scenario.getStartDelayMillis()
            );
        }
    }

    /** Immutable snapshot of a load scenario's progress, serialized by the GET endpoint. */
    public static final class LoadScenarioStatus {
        public final String name;
        /** Lifecycle state of this run (PENDING/RUNNING/COMPLETED/STOPPED). */
        public final LoadScenarioState state;
        public final long elapsedMillis;
        public final int currentVus;
        public final long requestsSent;
        public final long succeeded;
        public final long failed;
        public final long p50Millis;
        public final long p95Millis;
        public final long p99Millis;
        public final String runId;
        public final long startedAtEpochMillis;
        public final Long endedAtEpochMillis;
        /** Scenario-level custom annotation labels (null when none), echoed for dashboards/clients. */
        public final Map<String, String> labels;
        /**
         * The full scenario definition this run was started with (never null for a real run). The GET
         * endpoint serializes it under {@code definition} so any client/dashboard can load the exact
         * {@link LoadScenario} back into an author form and round-trip it as a PUT body.
         */
        public final LoadScenario scenario;
        /** The 0-based index of the currently-running stage, or {@code -1} when not running. */
        public final int stageIndex;
        /** The type of the currently-running stage ({@code VU}/{@code RATE}/{@code PAUSE}), or null. */
        public final String stageType;
        /**
         * The current setpoint for the running stage: target VUs for a VU stage, target arrival rate
         * (iterations/second) for a RATE stage, {@code 0} for PAUSE or when not running.
         */
        public final double currentTarget;
        /** The configured start delay in milliseconds (0 when none). */
        public final long startDelayMillis;

        public LoadScenarioStatus(String name, LoadScenarioState state, long elapsedMillis, int currentVus,
                                  long requestsSent, long succeeded, long failed,
                                  long p50Millis, long p95Millis, long p99Millis,
                                  String runId, long startedAtEpochMillis, Long endedAtEpochMillis,
                                  Map<String, String> labels, LoadScenario scenario,
                                  int stageIndex, String stageType, double currentTarget,
                                  long startDelayMillis) {
            this.name = name;
            this.state = state;
            this.elapsedMillis = elapsedMillis;
            this.currentVus = currentVus;
            this.requestsSent = requestsSent;
            this.succeeded = succeeded;
            this.failed = failed;
            this.p50Millis = p50Millis;
            this.p95Millis = p95Millis;
            this.p99Millis = p99Millis;
            this.runId = runId;
            this.startedAtEpochMillis = startedAtEpochMillis;
            this.endedAtEpochMillis = endedAtEpochMillis;
            this.labels = labels;
            this.scenario = scenario;
            this.stageIndex = stageIndex;
            this.stageType = stageType;
            this.currentTarget = currentTarget;
            this.startDelayMillis = startDelayMillis;
        }
    }
}
