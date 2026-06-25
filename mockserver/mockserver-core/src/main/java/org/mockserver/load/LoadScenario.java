package org.mockserver.load;

import org.mockserver.model.HttpTemplate;
import org.mockserver.model.ObjectWithJsonToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An API-driven load scenario: an ordered list of templated request {@link LoadStep}s
 * driven at a target concurrency described by a {@link LoadProfile}, with optional
 * per-iteration data variation via the scenario's {@link HttpTemplate.TemplateType}.
 *
 * <p>Modelled on {@link org.mockserver.verify.VerificationSequence} (an ordered list of
 * request steps) but oriented at <em>producing</em> traffic rather than asserting over it.
 * It is a pure SLI producer: it records latency/error samples (into the metrics histograms
 * and the SLO sample store) but contains no verdict logic — the SLO verify feature consumes
 * those samples.
 *
 * <p>Off by default; {@code PUT /mockserver/loadScenario} returns 403 unless
 * {@code loadGenerationEnabled} is set.
 */
public class LoadScenario extends ObjectWithJsonToString {

    /**
     * How each iteration selects which steps to run from {@link #getSteps()}.
     */
    public enum StepSelection {
        /**
         * Default: every iteration runs ALL steps in declared order (a multi-step user journey).
         * Per-step {@link LoadStep#getWeight() weights} are ignored.
         */
        SEQUENTIAL,
        /**
         * Each iteration runs exactly ONE step, chosen at random with probability proportional to the
         * steps' {@link LoadStep#getWeight() weights} (mixed-workload modelling, e.g. 70% browse /
         * 20% search / 10% checkout). Because a WEIGHTED iteration runs a single step, cross-step
         * captures have no later step to consume them and so are meaningful only under SEQUENTIAL;
         * feeder data and pacing apply to both modes.
         */
        WEIGHTED
    }

    private String name;
    private List<LoadStep> steps = new ArrayList<>();
    private LoadProfile profile;
    private HttpTemplate.TemplateType templateType = HttpTemplate.TemplateType.VELOCITY;
    private Integer maxRequests;
    /**
     * Delay in milliseconds, applied after this scenario is <em>triggered</em> to start, before its
     * iterations begin. A scenario with {@code startDelayMillis > 0} sits in the {@code PENDING}
     * lifecycle state until the delay elapses (measured against the orchestrator's clock), then
     * transitions to {@code RUNNING}. {@code 0} (the default) means start immediately on trigger.
     * Lets several preloaded scenarios be triggered together yet begin at staggered offsets.
     */
    private long startDelayMillis;
    /**
     * Scenario-level custom annotation labels. Attached as OpenTelemetry attributes on every
     * load measurement (arbitrary keys — this is the flexible, k6-beating annotation surface) and
     * surfaced in the status DTO. For the Prometheus side, only the keys named in the
     * {@code mockserver.loadGenerationMetricLabels} allowlist are added as extra fixed labels
     * (Prometheus requires a fixed label-name set). Step labels (see {@link LoadStep#getLabels()})
     * are merged on top of these for a given step, with step keys winning on conflict.
     */
    private Map<String, String> labels;
    /**
     * In-run pass/fail thresholds (nullable/empty = none). Evaluated on every control tick from this
     * run's per-run latency histogram and counters; the run carries a {@code PASS} verdict iff ALL
     * thresholds hold, {@code FAIL} otherwise (and a null verdict until the first request completes,
     * or always-null when there are no thresholds). See {@link LoadThreshold}.
     */
    private List<LoadThreshold> thresholds;
    /**
     * When true, a {@code FAIL} verdict aborts the run early — the run transitions to the terminal
     * {@code STOPPED} state and is marked aborted-by-threshold. Default {@code false} (the run always
     * finishes its stages and carries a final verdict). See {@link #abortGraceMillis}.
     */
    private boolean abortOnFail;
    /**
     * Suppress {@code abortOnFail} for the first {@code N} milliseconds of the run, so noisy
     * startup samples cannot trigger a premature abort (analogous to k6's {@code delayAbortEval}).
     * Thresholds are still evaluated and a verdict still reported during the grace window — only the
     * abort action is deferred until {@code elapsedMillis >= abortGraceMillis}. Default {@code 0}.
     */
    private long abortGraceMillis;
    /**
     * Adaptive iteration pacing (think-time): a target per-VU iteration cycle time. Nullable (or
     * {@link LoadPacing.Mode#NONE}) means no pacing — the closed-model VU loop reschedules each next
     * iteration immediately. When set, a closed-model VU starts an iteration at most once per target
     * cycle (waiting out any remainder, starting immediately on overrun). Applies only to the
     * closed-model VU loop; open-model RATE iterations ignore it. See {@link LoadPacing}.
     */
    private LoadPacing pacing;
    /**
     * Parameterized test data ("data feeder"): an inline dataset from which the orchestrator selects
     * one row per iteration and exposes it to that iteration's templated request fields (path, body and
     * headers) as {@code $iteration.data.<column>} / {@code {{iteration.data.<column>}}}. Nullable
     * (default) means no feeder — {@code $iteration.data} resolves to an empty map and behaviour is
     * unchanged. With {@link LoadFeeder.Strategy#SEQUENTIAL SEQUENTIAL} selection the run completes once
     * the dataset is exhausted (each row used exactly once). See {@link LoadFeeder}.
     */
    private LoadFeeder feeder;
    /**
     * Per-iteration step selection mode (nullable = {@link StepSelection#SEQUENTIAL SEQUENTIAL}). Under
     * SEQUENTIAL (the default and the original behaviour) every iteration runs all steps in order; under
     * {@link StepSelection#WEIGHTED WEIGHTED} each iteration runs a single step chosen by
     * {@link LoadStep#getWeight() weight}. See {@link StepSelection}.
     */
    private StepSelection stepSelection;

    public static LoadScenario loadScenario() {
        return new LoadScenario();
    }

    public static LoadScenario loadScenario(String name) {
        return new LoadScenario().withName(name);
    }

    public String getName() {
        return name;
    }

    public LoadScenario withName(String name) {
        this.name = name;
        return this;
    }

    public List<LoadStep> getSteps() {
        return steps;
    }

    public LoadScenario withSteps(LoadStep... steps) {
        Collections.addAll(this.steps, steps);
        return this;
    }

    public LoadScenario withSteps(List<LoadStep> steps) {
        this.steps = steps;
        return this;
    }

    public LoadProfile getProfile() {
        return profile;
    }

    public LoadScenario withProfile(LoadProfile profile) {
        this.profile = profile;
        return this;
    }

    public HttpTemplate.TemplateType getTemplateType() {
        return templateType;
    }

    public LoadScenario withTemplateType(HttpTemplate.TemplateType templateType) {
        this.templateType = templateType;
        return this;
    }

    public Integer getMaxRequests() {
        return maxRequests;
    }

    public LoadScenario withMaxRequests(Integer maxRequests) {
        this.maxRequests = maxRequests;
        return this;
    }

    /**
     * The start delay in milliseconds applied after a start trigger before this scenario's
     * iterations begin (default {@code 0}). See {@link #startDelayMillis}.
     */
    public long getStartDelayMillis() {
        return startDelayMillis;
    }

    public LoadScenario withStartDelayMillis(long startDelayMillis) {
        this.startDelayMillis = startDelayMillis;
        return this;
    }

    /**
     * Scenario-level custom labels (may be null/empty). See {@link #labels}.
     */
    public Map<String, String> getLabels() {
        return labels;
    }

    public LoadScenario withLabels(Map<String, String> labels) {
        this.labels = labels != null ? new LinkedHashMap<>(labels) : null;
        return this;
    }

    public LoadScenario withLabel(String name, String value) {
        if (this.labels == null) {
            this.labels = new LinkedHashMap<>();
        }
        this.labels.put(name, value);
        return this;
    }

    /**
     * In-run pass/fail thresholds (may be null/empty). See {@link #thresholds}.
     */
    public List<LoadThreshold> getThresholds() {
        return thresholds;
    }

    public LoadScenario withThresholds(LoadThreshold... thresholds) {
        if (this.thresholds == null) {
            this.thresholds = new ArrayList<>();
        }
        Collections.addAll(this.thresholds, thresholds);
        return this;
    }

    public LoadScenario withThresholds(List<LoadThreshold> thresholds) {
        this.thresholds = thresholds;
        return this;
    }

    /**
     * Whether a {@code FAIL} verdict aborts the run early (default {@code false}). See {@link #abortOnFail}.
     */
    public boolean isAbortOnFail() {
        return abortOnFail;
    }

    public LoadScenario withAbortOnFail(boolean abortOnFail) {
        this.abortOnFail = abortOnFail;
        return this;
    }

    /**
     * The abort grace window in milliseconds (default {@code 0}). See {@link #abortGraceMillis}.
     */
    public long getAbortGraceMillis() {
        return abortGraceMillis;
    }

    public LoadScenario withAbortGraceMillis(long abortGraceMillis) {
        this.abortGraceMillis = abortGraceMillis;
        return this;
    }

    /**
     * Adaptive iteration pacing (may be null = no pacing). See {@link #pacing}.
     */
    public LoadPacing getPacing() {
        return pacing;
    }

    public LoadScenario withPacing(LoadPacing pacing) {
        this.pacing = pacing;
        return this;
    }

    /**
     * The data feeder (may be null = no feeder). See {@link #feeder}.
     */
    public LoadFeeder getFeeder() {
        return feeder;
    }

    public LoadScenario withFeeder(LoadFeeder feeder) {
        this.feeder = feeder;
        return this;
    }

    /**
     * The per-iteration step selection mode (may be null = {@link StepSelection#SEQUENTIAL}). See
     * {@link #stepSelection}.
     */
    public StepSelection getStepSelection() {
        return stepSelection;
    }

    public LoadScenario withStepSelection(StepSelection stepSelection) {
        this.stepSelection = stepSelection;
        return this;
    }
}
