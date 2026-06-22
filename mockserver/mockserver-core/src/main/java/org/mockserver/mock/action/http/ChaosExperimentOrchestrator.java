package org.mockserver.mock.action.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.model.HttpChaosProfile;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.HttpChaosProfileDTO;
import org.mockserver.time.TimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Orchestrates scheduled multi-stage chaos experiments. An experiment is an
 * ordered sequence of stages, each applying a service-scoped chaos profile to
 * one or more hosts for a specified duration, progressing automatically. When
 * the last stage completes, chaos is cleared (or the experiment loops if
 * configured).
 *
 * <p><b>C1 auto-halt integration:</b> If {@link ChaosAutoHaltMonitor} halts
 * chaos mid-experiment (by calling {@link ServiceChaosRegistry#reset()}), the
 * orchestrator detects the empty registry at the next stage advance and stops
 * the experiment. An experiment stopped by auto-halt is reported with status
 * {@code "halted_by_auto_halt"}.
 *
 * <p><b>Safety limits:</b>
 * <ul>
 *   <li>Maximum {@value #MAX_STAGES} stages per experiment</li>
 *   <li>Maximum stage duration: {@value #MAX_STAGE_DURATION_MILLIS} ms (24 hours)</li>
 *   <li>Only one experiment may be active at a time</li>
 *   <li>Stopping an experiment is idempotent</li>
 * </ul>
 *
 * <p>The orchestrator uses a single-thread {@link ScheduledExecutorService}
 * for non-blocking stage advancement. It never blocks the Netty event loop.
 * Time is measured via a pluggable {@link LongSupplier} clock (defaults to
 * {@link TimeService#currentTimeMillis()}) so tests can drive advancement
 * deterministically without wall-clock sleeps.
 *
 * <p><b>Shared-registry exclusivity:</b> A running experiment takes exclusive ownership
 * of {@link ServiceChaosRegistry}. Manual service-chaos registrations are overwritten
 * at the next stage advance (which calls {@code registry.reset()} then re-applies the
 * stage profiles). A manual {@code reset()} of the registry is detected as an auto-halt
 * condition at the next stage boundary (see the {@code entries().isEmpty()} check in
 * {@link #advanceStage(RunningExperiment)}). Users should stop the experiment before
 * making manual service-chaos changes.
 *
 * <p>The singleton instance is shared process-wide, consistent with
 * {@link ServiceChaosRegistry}'s singleton pattern.
 */
public class ChaosExperimentOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(ChaosExperimentOrchestrator.class);

    /** Maximum number of stages in an experiment definition (DoS bound). */
    static final int MAX_STAGES = 50;
    /** Maximum duration for a single stage: 24 hours in milliseconds. */
    static final long MAX_STAGE_DURATION_MILLIS = 24L * 60 * 60 * 1000;
    /** Maximum deferred-start delay: 7 days in milliseconds (runaway-timer bound). */
    static final long MAX_START_DELAY_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private static final ChaosExperimentOrchestrator INSTANCE = new ChaosExperimentOrchestrator(
        TimeService::currentTimeMillis,
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chaos-experiment-scheduler");
            t.setDaemon(true);
            return t;
        })
    );

    private final LongSupplier clock;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<RunningExperiment> current = new AtomicReference<>(null);
    /** Terminal status of the last experiment after it detaches from {@link #current}.
     *  Allows {@link #getStatus()} to report {@code halted_by_auto_halt}, {@code completed},
     *  or {@code stopped} for a short window after the experiment ends (instead of null). */
    private volatile String lastTerminatedStatus;

    ChaosExperimentOrchestrator(LongSupplier clock, ScheduledExecutorService scheduler) {
        this.clock = clock;
        this.scheduler = scheduler;
    }

    public static ChaosExperimentOrchestrator getInstance() {
        return INSTANCE;
    }

    /**
     * Starts an experiment. Returns a validation error message if the definition
     * is invalid, or {@code null} on success. Only one experiment may be active
     * at a time; starting a new one while one is running stops the previous one.
     */
    public String start(ExperimentDefinition definition) {
        // Validate
        String error = validate(definition);
        if (error != null) {
            return error;
        }
        // Stop any existing experiment
        stopInternal(false);

        long now = clock.getAsLong();
        RunningExperiment experiment = new RunningExperiment(definition, now);
        current.set(experiment);

        long startDelay = resolveStartDelayMillis(definition, now);
        if (startDelay > 0) {
            // Deferred start: stage 0 is not applied until the delay elapses.
            // The registry is left untouched until then (back-compat: no schedule = immediate).
            experiment.status = "scheduled";
            scheduleStart(experiment, startDelay);
            LOG.info("chaos experiment '{}' scheduled to start in {} ms ({} stage(s), loop={})",
                definition.name, startDelay, definition.stages.size(), definition.loop);
        } else {
            beginExperiment(experiment);
        }

        return null;
    }

    /**
     * Applies stage 0 and schedules the first stage advance. Shared by the
     * immediate-start path and the deferred-start timer.
     */
    private void beginExperiment(RunningExperiment experiment) {
        // Apply stage 0
        applyStage(experiment, 0);

        // Schedule advancement to stage 1 (if there are more stages)
        scheduleNextStage(experiment, 0);

        LOG.info("chaos experiment '{}' started with {} stage(s), loop={}",
            experiment.definition.name, experiment.definition.stages.size(), experiment.definition.loop);
    }

    /**
     * Computes the delay (in ms) before stage 0 should be applied, from the
     * definition's {@code startDelayMillis} and/or {@code cronSchedule}. Returns
     * {@code 0} (immediate start) when neither is set. When both are set, the
     * later of the two wins so an explicit delay can never start before its cron
     * boundary.
     */
    private long resolveStartDelayMillis(ExperimentDefinition definition, long now) {
        long delay = Math.max(0L, definition.startDelayMillis);
        if (definition.cronSchedule != null && !definition.cronSchedule.isBlank()) {
            long cronDelay = CronSchedule.parse(definition.cronSchedule).millisUntilNext(now);
            delay = Math.max(delay, cronDelay);
        }
        return delay;
    }

    private void scheduleStart(RunningExperiment experiment, long delayMillis) {
        experiment.scheduledStartAtMillis = clock.getAsLong() + delayMillis;
        ScheduledFuture<?> future = scheduler.schedule(() -> startNow(experiment), delayMillis, TimeUnit.MILLISECONDS);
        experiment.pendingAdvance = future;
    }

    private void startNow(RunningExperiment experiment) {
        // Ignore if the experiment was stopped or replaced before the timer fired.
        if (current.get() != experiment || !"scheduled".equals(experiment.status)) {
            return;
        }
        beginExperiment(experiment);
        LOG.info("chaos experiment '{}' deferred start fired", experiment.definition.name);
    }

    /**
     * Forces an immediately-scheduled (deferred-start) experiment to begin,
     * bypassing the scheduler. Used by tests to drive the deferred-start
     * transition deterministically without wall-clock sleeps. No-op unless an
     * experiment is currently in the {@code "scheduled"} state.
     */
    void triggerScheduledStartNow() {
        RunningExperiment exp = current.get();
        if (exp != null && "scheduled".equals(exp.status)) {
            ScheduledFuture<?> pending = exp.pendingAdvance;
            if (pending != null) {
                pending.cancel(false);
            }
            startNow(exp);
        }
    }

    /**
     * Stops the current experiment and clears all chaos from the registry.
     * Idempotent: no-op if no experiment is running.
     */
    public void stop() {
        stopInternal(false);
    }

    /**
     * Resets the orchestrator: stops any running experiment, clears chaos,
     * and clears the terminal status so {@link #getStatus()} returns null.
     * Called on server reset.
     */
    public void reset() {
        stopInternal(false);
        lastTerminatedStatus = null;
    }

    /**
     * Returns the current experiment status. If no experiment is currently running
     * but one recently terminated, returns a status with the terminal status
     * ({@code halted_by_auto_halt}, {@code completed}, or {@code stopped}).
     * Returns {@code null} only when no experiment has ever run (or after reset).
     */
    public ExperimentStatus getStatus() {
        RunningExperiment exp = current.get();
        if (exp == null) {
            String terminated = lastTerminatedStatus;
            if (terminated != null) {
                return new ExperimentStatus(null, terminated, 0, 0, 0, 0, 0, 0, null, 0);
            }
            return null;
        }
        long now = clock.getAsLong();
        if ("scheduled".equals(exp.status)) {
            // Deferred start: no stage has been applied yet, so stage elapsed/remaining
            // are not meaningful. Report startRemainingMillis instead.
            long startRemaining = Math.max(0, exp.scheduledStartAtMillis - now);
            return new ExperimentStatus(
                exp.definition.name, exp.status, 0, exp.definition.stages.size(),
                0, 0, exp.loopIteration, now - exp.startedAtMillis, exp.definition, startRemaining);
        }
        long stageElapsed = now - exp.stageStartedAtMillis;
        Stage currentStage = exp.currentStageIndex < exp.definition.stages.size()
            ? exp.definition.stages.get(exp.currentStageIndex) : null;
        long stageRemaining = currentStage != null
            ? Math.max(0, currentStage.durationMillis - stageElapsed) : 0;
        return new ExperimentStatus(
            exp.definition.name,
            exp.status,
            exp.currentStageIndex,
            exp.definition.stages.size(),
            stageElapsed,
            stageRemaining,
            exp.loopIteration,
            now - exp.startedAtMillis,
            exp.definition,
            0
        );
    }

    // -- Internal methods --

    private void stopInternal(boolean autoHalted) {
        RunningExperiment exp = current.getAndSet(null);
        if (exp != null) {
            ScheduledFuture<?> pending = exp.pendingAdvance;
            if (pending != null) {
                pending.cancel(false);
            }
            exp.status = autoHalted ? "halted_by_auto_halt" : "stopped";
            lastTerminatedStatus = exp.status;
            // Clear chaos from the registry
            ServiceChaosRegistry.getInstance().reset();
            LOG.info("chaos experiment '{}' {}", exp.definition.name, exp.status);
        }
    }

    private void applyStage(RunningExperiment experiment, int stageIndex) {
        Stage stage = experiment.definition.stages.get(stageIndex);
        experiment.currentStageIndex = stageIndex;
        experiment.stageStartedAtMillis = clock.getAsLong();
        experiment.status = "running";

        // Clear previous stage's chaos, then apply new stage's profiles
        ServiceChaosRegistry registry = ServiceChaosRegistry.getInstance();
        registry.reset();

        for (Map.Entry<String, HttpChaosProfile> entry : stage.profiles.entrySet()) {
            registry.put(entry.getKey(), entry.getValue());
        }
    }

    private void scheduleNextStage(RunningExperiment experiment, int currentStageIndex) {
        Stage stage = experiment.definition.stages.get(currentStageIndex);
        long delayMillis = stage.durationMillis;

        ScheduledFuture<?> future = scheduler.schedule(() -> advanceStage(experiment), delayMillis, TimeUnit.MILLISECONDS);
        experiment.pendingAdvance = future;
    }

    private void advanceStage(RunningExperiment experiment) {
        // Check if experiment was stopped or replaced
        if (current.get() != experiment) {
            return;
        }

        // C1 auto-halt integration: if the registry was cleared by auto-halt
        // (or a manual reset) while this stage was running, stop the experiment.
        // Note: a running experiment takes exclusive ownership of ServiceChaosRegistry —
        // manual service-chaos registrations are overwritten at the next stage advance.
        if (ServiceChaosRegistry.getInstance().entries().isEmpty() && experiment.status.equals("running")) {
            LOG.warn("chaos experiment '{}' halted by auto-halt safety circuit-breaker at stage {}",
                experiment.definition.name, experiment.currentStageIndex);
            experiment.status = "halted_by_auto_halt";
            lastTerminatedStatus = experiment.status;
            current.compareAndSet(experiment, null);
            return;
        }

        int nextStageIndex = experiment.currentStageIndex + 1;

        if (nextStageIndex >= experiment.definition.stages.size()) {
            // End of stages
            if (experiment.definition.loop) {
                // Loop back to stage 0
                experiment.loopIteration++;
                applyStage(experiment, 0);
                scheduleNextStage(experiment, 0);
                LOG.info("chaos experiment '{}' looping, iteration {}",
                    experiment.definition.name, experiment.loopIteration);
            } else {
                // Experiment complete
                experiment.status = "completed";
                lastTerminatedStatus = experiment.status;
                ServiceChaosRegistry.getInstance().reset();
                current.compareAndSet(experiment, null);
                LOG.info("chaos experiment '{}' completed after {} stage(s)",
                    experiment.definition.name, experiment.definition.stages.size());
            }
        } else {
            // Advance to next stage
            applyStage(experiment, nextStageIndex);
            scheduleNextStage(experiment, nextStageIndex);
            LOG.info("chaos experiment '{}' advanced to stage {}/{}",
                experiment.definition.name, nextStageIndex + 1, experiment.definition.stages.size());
        }
    }

    /**
     * Forces immediate advancement to the next stage, bypassing the scheduler.
     * Used by tests to drive stage transitions deterministically without
     * wall-clock sleeps. No-op if no experiment is running.
     */
    void advanceNow() {
        RunningExperiment exp = current.get();
        if (exp != null) {
            ScheduledFuture<?> pending = exp.pendingAdvance;
            if (pending != null) {
                pending.cancel(false);
            }
            advanceStage(exp);
        }
    }

    private String validate(ExperimentDefinition definition) {
        if (definition == null) {
            return "'experiment' is required";
        }
        if (definition.name == null || definition.name.isBlank()) {
            return "'name' is required";
        }
        if (definition.stages == null || definition.stages.isEmpty()) {
            return "'stages' must contain at least one stage";
        }
        if (definition.stages.size() > MAX_STAGES) {
            return "'stages' exceeds maximum of " + MAX_STAGES;
        }
        if (definition.startDelayMillis < 0) {
            return "'startDelayMillis' must be >= 0";
        }
        if (definition.startDelayMillis > MAX_START_DELAY_MILLIS) {
            return "'startDelayMillis' exceeds maximum of " + MAX_START_DELAY_MILLIS + " ms (7 days)";
        }
        if (definition.cronSchedule != null && !definition.cronSchedule.isBlank()) {
            CronSchedule cron;
            try {
                cron = CronSchedule.parse(definition.cronSchedule);
            } catch (IllegalArgumentException e) {
                return "'cronSchedule' is invalid: " + e.getMessage();
            }
            // A satisfiable cron always matches some minute within the search horizon, so the
            // next match is at least one minute away (> 0). A return of 0 means the expression
            // can never fire (e.g. an impossible date like "0 0 30 2 *") — reject it rather than
            // silently starting immediately.
            if (cron.millisUntilNext(clock.getAsLong()) == 0L) {
                return "'cronSchedule' never matches a valid time";
            }
        }
        for (int i = 0; i < definition.stages.size(); i++) {
            Stage stage = definition.stages.get(i);
            if (stage.durationMillis <= 0) {
                return "stage[" + i + "].durationMillis must be > 0";
            }
            if (stage.durationMillis > MAX_STAGE_DURATION_MILLIS) {
                return "stage[" + i + "].durationMillis exceeds maximum of " + MAX_STAGE_DURATION_MILLIS + " ms (24h)";
            }
            if (stage.profiles == null || stage.profiles.isEmpty()) {
                return "stage[" + i + "] must have at least one host/profile entry";
            }
        }
        return null;
    }

    // -- Data classes --

    /**
     * An experiment definition: name, ordered stages, whether to loop, and an
     * optional deferred start ({@code startDelayMillis} and/or {@code cronSchedule}).
     * When no scheduling fields are set the experiment starts immediately
     * (back-compatible default).
     */
    public static class ExperimentDefinition {
        public final String name;
        public final List<Stage> stages;
        public final boolean loop;
        /** Fixed delay before stage 0 is applied; {@code 0} = start immediately. */
        public final long startDelayMillis;
        /** Standard 5-field cron expression for the start time; {@code null}/blank = none. */
        public final String cronSchedule;

        public ExperimentDefinition(String name, List<Stage> stages, boolean loop) {
            this(name, stages, loop, 0L, null);
        }

        public ExperimentDefinition(String name, List<Stage> stages, boolean loop,
                                    long startDelayMillis, String cronSchedule) {
            this.name = name;
            this.stages = stages != null ? Collections.unmodifiableList(new ArrayList<>(stages)) : Collections.emptyList();
            this.loop = loop;
            this.startDelayMillis = startDelayMillis;
            this.cronSchedule = cronSchedule;
        }

        /**
         * Deserializes an experiment definition from a JSON node.
         */
        public static ExperimentDefinition fromJson(JsonNode node) {
            ObjectMapper mapper = ObjectMapperFactory.createObjectMapper();
            String name = node.path("name").asText(null);
            boolean loop = node.path("loop").asBoolean(false);
            long startDelayMillis = node.path("startDelayMillis").asLong(0);
            JsonNode cronNode = node.path("cronSchedule");
            String cronSchedule = cronNode.isTextual() ? cronNode.asText() : null;
            List<Stage> stages = new ArrayList<>();
            JsonNode stagesNode = node.path("stages");
            if (stagesNode.isArray()) {
                for (JsonNode stageNode : stagesNode) {
                    long durationMillis = stageNode.path("durationMillis").asLong(0);
                    Map<String, HttpChaosProfile> profiles = new java.util.LinkedHashMap<>();
                    JsonNode profilesNode = stageNode.path("profiles");
                    if (profilesNode.isObject()) {
                        var fields = profilesNode.fields();
                        while (fields.hasNext()) {
                            var entry = fields.next();
                            try {
                                HttpChaosProfileDTO dto = mapper.treeToValue(entry.getValue(), HttpChaosProfileDTO.class);
                                profiles.put(entry.getKey(), dto.buildObject());
                            } catch (Exception e) {
                                throw new IllegalArgumentException(
                                    "invalid chaos profile for host '" + entry.getKey() + "': " + e.getMessage(), e);
                            }
                        }
                    }
                    stages.add(new Stage(durationMillis, profiles));
                }
            }
            return new ExperimentDefinition(name, stages, loop, startDelayMillis, cronSchedule);
        }

        /**
         * Serializes this experiment definition to a JSON node.
         */
        public ObjectNode toJson() {
            ObjectMapper mapper = ObjectMapperFactory.createObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("name", name);
            node.put("loop", loop);
            if (startDelayMillis > 0) {
                node.put("startDelayMillis", startDelayMillis);
            }
            if (cronSchedule != null && !cronSchedule.isBlank()) {
                node.put("cronSchedule", cronSchedule);
            }
            ArrayNode stagesArray = node.putArray("stages");
            for (Stage stage : stages) {
                ObjectNode stageNode = mapper.createObjectNode();
                stageNode.put("durationMillis", stage.durationMillis);
                ObjectNode profilesNode = stageNode.putObject("profiles");
                for (Map.Entry<String, HttpChaosProfile> entry : stage.profiles.entrySet()) {
                    profilesNode.set(entry.getKey(), mapper.valueToTree(new HttpChaosProfileDTO(entry.getValue())));
                }
                stagesArray.add(stageNode);
            }
            return node;
        }
    }

    /**
     * A single stage: profiles to apply to specific hosts for a duration.
     */
    public static class Stage {
        public final long durationMillis;
        public final Map<String, HttpChaosProfile> profiles;

        public Stage(long durationMillis, Map<String, HttpChaosProfile> profiles) {
            this.durationMillis = durationMillis;
            this.profiles = profiles != null
                ? Collections.unmodifiableMap(new java.util.LinkedHashMap<>(profiles))
                : Collections.emptyMap();
        }
    }

    /**
     * Snapshot of the current experiment status.
     */
    public static class ExperimentStatus {
        public final String name;
        public final String status;
        public final int currentStageIndex;
        public final int totalStages;
        public final long stageElapsedMillis;
        public final long stageRemainingMillis;
        public final int loopIteration;
        public final long totalElapsedMillis;
        public final ExperimentDefinition definition;
        /** Milliseconds until a deferred-start experiment begins; {@code 0} unless status is {@code "scheduled"}. */
        public final long startRemainingMillis;

        public ExperimentStatus(String name, String status, int currentStageIndex, int totalStages,
                                long stageElapsedMillis, long stageRemainingMillis,
                                int loopIteration, long totalElapsedMillis,
                                ExperimentDefinition definition, long startRemainingMillis) {
            this.name = name;
            this.status = status;
            this.currentStageIndex = currentStageIndex;
            this.totalStages = totalStages;
            this.stageElapsedMillis = stageElapsedMillis;
            this.stageRemainingMillis = stageRemainingMillis;
            this.loopIteration = loopIteration;
            this.totalElapsedMillis = totalElapsedMillis;
            this.definition = definition;
            this.startRemainingMillis = startRemainingMillis;
        }

        public ObjectNode toJson() {
            ObjectMapper mapper = ObjectMapperFactory.createObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("name", name);
            node.put("status", status);
            node.put("currentStageIndex", currentStageIndex);
            node.put("totalStages", totalStages);
            node.put("stageElapsedMillis", stageElapsedMillis);
            node.put("stageRemainingMillis", stageRemainingMillis);
            node.put("loopIteration", loopIteration);
            node.put("totalElapsedMillis", totalElapsedMillis);
            if ("scheduled".equals(status)) {
                node.put("startRemainingMillis", startRemainingMillis);
            }
            if (definition != null) {
                node.set("experiment", definition.toJson());
            }
            return node;
        }
    }

    /**
     * Mutable state for a running experiment.
     */
    private static final class RunningExperiment {
        final ExperimentDefinition definition;
        final long startedAtMillis;
        volatile int currentStageIndex;
        volatile long stageStartedAtMillis;
        volatile String status;
        volatile int loopIteration;
        volatile ScheduledFuture<?> pendingAdvance;
        /** Absolute clock time (ms) when a deferred-start experiment is due to begin. */
        volatile long scheduledStartAtMillis;

        RunningExperiment(ExperimentDefinition definition, long startedAtMillis) {
            this.definition = definition;
            this.startedAtMillis = startedAtMillis;
            this.status = "starting";
            this.loopIteration = 0;
        }
    }
}
