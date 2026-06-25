using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

// ---------------------------------------------------------------------------
// SRE control-plane models: load scenarios, service chaos, SLO verdicts,
// preemption and scheduled chaos experiments.
//
// All wire field names are camelCase to match the MockServer REST contract
// (jekyll-www.mock-server.com/mockserver-openapi.yaml). The serializer is
// configured with WhenWritingNull so unset optional fields are omitted.
// ---------------------------------------------------------------------------

// ===========================================================================
// Load scenarios — load-scenario registry under /mockserver/loadScenario
//
//   PUT    /mockserver/loadScenario           register/load (does not run)
//   GET    /mockserver/loadScenario           list all registered scenarios
//   GET    /mockserver/loadScenario/{name}    fetch one (404 if absent)
//   DELETE /mockserver/loadScenario/{name}    remove one
//   DELETE /mockserver/loadScenario           clear all
//   PUT    /mockserver/loadScenario/start     start one or more by name
//   PUT    /mockserver/loadScenario/stop      stop named, all, or every running
//
// Registration is always allowed (even when loadGenerationEnabled=false);
// only /start requires load generation to be enabled (HTTP 403 otherwise).
// ===========================================================================

/// <summary>The lifecycle state of a registered load scenario.</summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum LoadScenarioState
{
    /// <summary>Registered but never started.</summary>
    LOADED,

    /// <summary>Accepted to start and waiting out its <see cref="LoadScenario.StartDelayMillis"/>.</summary>
    PENDING,

    /// <summary>Actively driving load.</summary>
    RUNNING,

    /// <summary>Finished its profile.</summary>
    COMPLETED,

    /// <summary>Stopped before completing.</summary>
    STOPPED
}

/// <summary>
/// The kind of a <see cref="LoadStage"/>.
/// </summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum LoadStageType
{
    /// <summary>Closed model: hold or ramp the number of concurrent virtual users.</summary>
    VU,

    /// <summary>Open model: hold or ramp a target arrival rate in iterations/second.</summary>
    RATE,

    /// <summary>Drive no load for the stage duration.</summary>
    PAUSE
}

/// <summary>
/// The interpolation curve used to ramp a value across a <see cref="LoadStage"/>.
/// Only meaningful for ramp stages; ignored for holds and pauses.
/// </summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum RampCurve
{
    /// <summary>Constant slope.</summary>
    LINEAR,

    /// <summary>Steeper ease-in.</summary>
    EXPONENTIAL,

    /// <summary>Ease-in (slow then fast).</summary>
    QUADRATIC
}

/// <summary>
/// The template engine used to render per-iteration request paths and bodies in a load scenario.
/// </summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum LoadTemplateType
{
    VELOCITY,
    MUSTACHE
}

/// <summary>
/// A named, declarative load shape (expands server-side into ordinary <see cref="LoadStage"/> stages).
/// </summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum LoadShapeType
{
    /// <summary>Ramp up, hold the peak, ramp back down, with an optional recovery hold.</summary>
    SPIKE,

    /// <summary>A flight of pure-hold steps, each one 'step' higher.</summary>
    STAIRS,

    /// <summary>Ramp from 0 to a target and then hold.</summary>
    RAMP_HOLD
}

/// <summary>What a <see cref="LoadShape"/> drives: concurrent virtual users (closed model) or an arrival rate (open model).</summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum LoadShapeMetric
{
    /// <summary>Concurrent virtual users (closed model).</summary>
    VU,

    /// <summary>Arrival rate in iterations/second (open model).</summary>
    RATE
}

/// <summary>How each iteration of a <see cref="LoadScenario"/> selects which steps to run.</summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum LoadStepSelection
{
    /// <summary>Run ALL steps in declared order each iteration (a multi-step user journey).</summary>
    SEQUENTIAL,

    /// <summary>Run exactly ONE step per iteration, chosen at random proportional to each step's weight.</summary>
    WEIGHTED
}

/// <summary>The per-run metric a <see cref="LoadThreshold"/> evaluates.</summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum LoadThresholdMetric
{
    LATENCY_P50,
    LATENCY_P95,
    LATENCY_P99,
    LATENCY_P999,

    /// <summary>Failed / requests, as a 0.0-1.0 fraction.</summary>
    ERROR_RATE,

    /// <summary>Requests per second over the run's elapsed time.</summary>
    THROUGHPUT_RPS
}

/// <summary>How an observed per-run value is compared to a <see cref="LoadThreshold.Threshold"/>.</summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum LoadThresholdComparator
{
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL
}

/// <summary>How the target iteration cycle of a <see cref="LoadPacing"/> is derived from its value.</summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum LoadPacingMode
{
    /// <summary>No pacing (immediate reschedule).</summary>
    NONE,

    /// <summary>The value is the target iteration cycle in milliseconds.</summary>
    CONSTANT_PACING,

    /// <summary>The value is the target iterations/second per virtual user (cycle = 1000 / value ms).</summary>
    CONSTANT_THROUGHPUT
}

/// <summary>The format of a raw inline <see cref="LoadFeeder.Data"/> dataset.</summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum LoadFeederFormat
{
    CSV,
    JSON
}

/// <summary>How a row is chosen from a <see cref="LoadFeeder"/> each iteration.</summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum LoadFeederStrategy
{
    /// <summary>Cycle rows[globalIteration % size]; never exhausts (default).</summary>
    CIRCULAR,

    /// <summary>Pick a uniformly random row each iteration.</summary>
    RANDOM,

    /// <summary>Use rows[globalIteration] once each in order; COMPLETES the run once exhausted.</summary>
    SEQUENTIAL
}

/// <summary>Where a <see cref="LoadCapture"/> extracts a value from a step's response.</summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum LoadCaptureSource
{
    /// <summary>A JSONPath over the response body.</summary>
    BODY_JSONPATH,

    /// <summary>A response header value.</summary>
    HEADER,

    /// <summary>A regex over the response body string (capture group 1).</summary>
    BODY_REGEX
}

/// <summary>The in-run threshold verdict of a load scenario run.</summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum LoadVerdict
{
    /// <summary>All thresholds satisfied.</summary>
    PASS,

    /// <summary>At least one threshold breached. Clients should map a terminal FAIL to a non-zero CI exit code.</summary>
    FAIL
}

/// <summary>The mode controlling how recorded requests become steps when generating from a recording.</summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum LoadRecordingMode
{
    /// <summary>One step per recorded request, in recorded order (default).</summary>
    VERBATIM,

    /// <summary>One step per unique (method, templatised-path) route, ordered by descending frequency.</summary>
    TEMPLATIZED
}

/// <summary>
/// One stage of a <see cref="LoadProfile"/>, run in sequence: it holds or ramps a setpoint for its
/// <see cref="DurationMillis"/>. A stage is one of three kinds (<see cref="LoadStageType"/>):
/// VU (closed model — hold <see cref="Vus"/> or ramp <see cref="StartVus"/> to <see cref="EndVus"/>),
/// RATE (open model — hold <see cref="Rate"/> or ramp <see cref="StartRate"/> to <see cref="EndRate"/>
/// in iterations/second, optionally capped at <see cref="MaxVus"/>), or PAUSE (no load).
/// </summary>
/// <remarks>
/// The numeric setpoints are nullable so a meaningful zero (e.g. <c>StartVus = 0</c> at the bottom of a
/// ramp, or <c>Rate = 0</c>) is still emitted; <see cref="JsonIgnoreCondition.WhenWritingNull"/> drops a
/// field only when it is null.
/// </remarks>
public sealed class LoadStage
{
    /// <summary>The stage kind: VU, RATE or PAUSE (required).</summary>
    [JsonPropertyName("type")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadStageType? Type { get; set; }

    /// <summary>How long this stage runs in milliseconds (&gt; 0, required); the sum across stages is the total run length (max 3600000 = 1h).</summary>
    [JsonPropertyName("durationMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? DurationMillis { get; set; }

    /// <summary>Ramp interpolation curve; only meaningful for ramp stages.</summary>
    [JsonPropertyName("curve")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public RampCurve? Curve { get; set; }

    /// <summary>VU hold: number of virtual users to hold (max 50).</summary>
    [JsonPropertyName("vus")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? Vus { get; set; }

    /// <summary>VU ramp: virtual users at the start of the ramp.</summary>
    [JsonPropertyName("startVus")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? StartVus { get; set; }

    /// <summary>VU ramp: virtual users at the end of the ramp (max 50).</summary>
    [JsonPropertyName("endVus")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? EndVus { get; set; }

    /// <summary>RATE hold: arrival rate to hold, in iterations/second (max 5000).</summary>
    [JsonPropertyName("rate")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? Rate { get; set; }

    /// <summary>RATE ramp: arrival rate at the start of the ramp, in iterations/second.</summary>
    [JsonPropertyName("startRate")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? StartRate { get; set; }

    /// <summary>RATE ramp: arrival rate at the end of the ramp, in iterations/second (max 5000).</summary>
    [JsonPropertyName("endRate")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? EndRate { get; set; }

    /// <summary>RATE stage only: optional cap on the auto-scaling virtual-user pool (defaults to the global VU cap).</summary>
    [JsonPropertyName("maxVus")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? MaxVus { get; set; }

    /// <summary>Build a VU hold stage holding <paramref name="vus"/> virtual users for <paramref name="durationMillis"/>.</summary>
    public static LoadStage ConstantVus(int vus, long durationMillis)
        => new() { Type = LoadStageType.VU, Vus = vus, DurationMillis = durationMillis };

    /// <summary>Build a VU ramp stage ramping from <paramref name="startVus"/> to <paramref name="endVus"/> over <paramref name="durationMillis"/>.</summary>
    public static LoadStage RampVus(int startVus, int endVus, long durationMillis, RampCurve curve = RampCurve.LINEAR)
        => new() { Type = LoadStageType.VU, StartVus = startVus, EndVus = endVus, DurationMillis = durationMillis, Curve = curve };

    /// <summary>Build a RATE hold stage holding <paramref name="rate"/> iterations/second for <paramref name="durationMillis"/>.</summary>
    public static LoadStage ConstantRate(double rate, long durationMillis)
        => new() { Type = LoadStageType.RATE, Rate = rate, DurationMillis = durationMillis };

    /// <summary>Build a RATE ramp stage ramping from <paramref name="startRate"/> to <paramref name="endRate"/> (iterations/second) over <paramref name="durationMillis"/>.</summary>
    public static LoadStage RampRate(double startRate, double endRate, long durationMillis, RampCurve curve = RampCurve.LINEAR)
        => new() { Type = LoadStageType.RATE, StartRate = startRate, EndRate = endRate, DurationMillis = durationMillis, Curve = curve };

    /// <summary>Build a PAUSE stage driving no load for <paramref name="durationMillis"/>.</summary>
    public static LoadStage Pause(long durationMillis)
        => new() { Type = LoadStageType.PAUSE, DurationMillis = durationMillis };
}

/// <summary>
/// A declarative named load shape that expands server-side into ordinary <see cref="LoadStage"/>
/// stages. Only the parameters its <see cref="Type"/> needs are read; the rest are ignored. Use a
/// shape OR an explicit <see cref="LoadProfile.Stages"/> list, not both.
/// </summary>
public sealed class LoadShape
{
    /// <summary>The named shape (required): SPIKE, STAIRS or RAMP_HOLD.</summary>
    [JsonPropertyName("type")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadShapeType? Type { get; set; }

    /// <summary>What the shape drives: VU (closed model) or RATE (open model).</summary>
    [JsonPropertyName("metric")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadShapeMetric? Metric { get; set; }

    /// <summary>Ramp interpolation curve.</summary>
    [JsonPropertyName("curve")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public RampCurve? Curve { get; set; }

    /// <summary>SPIKE: the level held before and after the spike.</summary>
    [JsonPropertyName("baseline")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? Baseline { get; set; }

    /// <summary>SPIKE: the level held at the top of the spike.</summary>
    [JsonPropertyName("peak")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? Peak { get; set; }

    /// <summary>SPIKE: duration of the baseline-to-peak ramp, in milliseconds.</summary>
    [JsonPropertyName("rampUpMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? RampUpMillis { get; set; }

    /// <summary>SPIKE: duration to hold at the peak; RAMP_HOLD: duration to hold at the target (ms).</summary>
    [JsonPropertyName("holdMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? HoldMillis { get; set; }

    /// <summary>SPIKE: duration of the peak-to-baseline ramp, in milliseconds.</summary>
    [JsonPropertyName("rampDownMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? RampDownMillis { get; set; }

    /// <summary>SPIKE (optional): duration to hold at baseline after the down ramp, in milliseconds.</summary>
    [JsonPropertyName("recoveryHoldMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? RecoveryHoldMillis { get; set; }

    /// <summary>STAIRS: the level of the first step.</summary>
    [JsonPropertyName("start")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? Start { get; set; }

    /// <summary>STAIRS: how much each step rises above the previous one.</summary>
    [JsonPropertyName("step")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? Step { get; set; }

    /// <summary>STAIRS: the number of steps.</summary>
    [JsonPropertyName("steps")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? Steps { get; set; }

    /// <summary>STAIRS: how long each step holds at its level, in milliseconds.</summary>
    [JsonPropertyName("stepDurationMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? StepDurationMillis { get; set; }

    /// <summary>RAMP_HOLD: the level ramped up to (from 0) and then held.</summary>
    [JsonPropertyName("target")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? Target { get; set; }

    /// <summary>RAMP_HOLD: duration of the 0-to-target ramp, in milliseconds.</summary>
    [JsonPropertyName("rampMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? RampMillis { get; set; }
}

/// <summary>
/// Describes the load over time for a <see cref="LoadScenario"/>, as EITHER an ordered list of
/// <see cref="Stages"/> OR a single named <see cref="Shape"/> (which expands into stages). Set one,
/// not both; if both are set the explicit stages win.
/// </summary>
public sealed class LoadProfile
{
    /// <summary>Ordered stages run one after another (max 20).</summary>
    [JsonPropertyName("stages")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<LoadStage>? Stages { get; set; }

    /// <summary>A single named load shape that expands into stages; use instead of <see cref="Stages"/>.</summary>
    [JsonPropertyName("shape")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadShape? Shape { get; set; }
}

/// <summary>
/// A single templated request step in a <see cref="LoadScenario"/>.
/// </summary>
public sealed class LoadStep
{
    /// <summary>The templated request fired for this step (required).</summary>
    [JsonPropertyName("request")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public HttpRequest? Request { get; set; }

    /// <summary>Optional inter-step pause.</summary>
    [JsonPropertyName("thinkTime")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? ThinkTime { get; set; }

    /// <summary>Optional human label for this step (used as the 'step' metric label).</summary>
    [JsonPropertyName("name")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Name { get; set; }

    /// <summary>Optional step-level annotation labels, merged over the scenario labels (step keys win).</summary>
    [JsonPropertyName("labels")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Dictionary<string, string>? Labels { get; set; }

    /// <summary>
    /// Optional cross-step capture rules applied to this step's response; each binds an extracted value
    /// to a variable visible to subsequent steps in the same iteration. Meaningful only under
    /// SEQUENTIAL <see cref="LoadScenario.StepSelection"/>.
    /// </summary>
    [JsonPropertyName("captures")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<LoadCapture>? Captures { get; set; }

    /// <summary>
    /// Relative selection weight, used only when the scenario's <see cref="LoadScenario.StepSelection"/>
    /// is WEIGHTED (must be &gt; 0 then; omitted means 1.0). Ignored under SEQUENTIAL.
    /// </summary>
    [JsonPropertyName("weight")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? Weight { get; set; }
}

/// <summary>
/// A declarative cross-step capture / correlation rule: extracts a value from a step's response and
/// binds it to a variable name a later step in the same iteration can reference from its templated
/// request fields. Best-effort: on no match it falls back to <see cref="DefaultValue"/> (when set) or
/// leaves the variable unset, never failing the run.
/// </summary>
public sealed class LoadCapture
{
    /// <summary>The variable name later steps reference (required, e.g. "token" for $iteration.captured.token).</summary>
    [JsonPropertyName("name")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Name { get; set; }

    /// <summary>Where to extract from (required): BODY_JSONPATH, HEADER or BODY_REGEX.</summary>
    [JsonPropertyName("source")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadCaptureSource? Source { get; set; }

    /// <summary>The JSONPath, header name, or regex driving the extraction (required).</summary>
    [JsonPropertyName("expression")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Expression { get; set; }

    /// <summary>Optional fallback value bound to the variable when extraction yields nothing.</summary>
    [JsonPropertyName("defaultValue")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? DefaultValue { get; set; }
}

/// <summary>
/// An in-run pass/fail threshold for a load scenario: a per-run metric compared against a value. All
/// thresholds must hold for the run verdict to be PASS (logical AND); any breach makes it FAIL.
/// </summary>
public sealed class LoadThreshold
{
    /// <summary>The per-run metric to evaluate (required).</summary>
    [JsonPropertyName("metric")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadThresholdMetric? Metric { get; set; }

    /// <summary>How the observed per-run value is compared to the threshold (required).</summary>
    [JsonPropertyName("comparator")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadThresholdComparator? Comparator { get; set; }

    /// <summary>The threshold value (ms for latency, 0.0-1.0 fraction for ERROR_RATE, rps for THROUGHPUT_RPS) (required).</summary>
    [JsonPropertyName("threshold")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? Threshold { get; set; }
}

/// <summary>
/// Adaptive iteration pacing (think-time) for a load scenario: a target per-virtual-user iteration
/// cycle time. Applies only to the closed-model VU loop; open-model RATE iterations ignore it.
/// </summary>
public sealed class LoadPacing
{
    /// <summary>How the target iteration cycle is derived from <see cref="Value"/> (required).</summary>
    [JsonPropertyName("mode")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadPacingMode? Mode { get; set; }

    /// <summary>
    /// For CONSTANT_PACING the target cycle in milliseconds; for CONSTANT_THROUGHPUT the target
    /// iterations/second per VU. Must be &gt; 0 when mode is not NONE; ignored when mode is NONE.
    /// </summary>
    [JsonPropertyName("value")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? Value { get; set; }
}

/// <summary>
/// Parameterized test data (a data feeder) for a load scenario: an inline dataset from which one row
/// is selected per iteration and exposed to that iteration's templated fields. Always inline. Supply
/// EITHER <see cref="Rows"/> (the primary form) OR <see cref="Data"/> + <see cref="Format"/>; rows wins.
/// </summary>
public sealed class LoadFeeder
{
    /// <summary>Inline dataset: a list of column-name-to-value maps, one per row (the primary mechanism).</summary>
    [JsonPropertyName("rows")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<Dictionary<string, string>>? Rows { get; set; }

    /// <summary>Optional raw inline dataset parsed server-side into rows per <see cref="Format"/>. Ignored when <see cref="Rows"/> is set.</summary>
    [JsonPropertyName("data")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Data { get; set; }

    /// <summary>The format of <see cref="Data"/> (required when data is set): CSV or JSON.</summary>
    [JsonPropertyName("format")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadFeederFormat? Format { get; set; }

    /// <summary>How a row is chosen each iteration (default CIRCULAR).</summary>
    [JsonPropertyName("strategy")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadFeederStrategy? Strategy { get; set; }
}

/// <summary>
/// The evaluated result of a single <see cref="LoadThreshold"/>, present in a run's status when
/// thresholds were evaluated (behind the run <c>verdict</c>).
/// </summary>
public sealed class LoadThresholdResult
{
    [JsonPropertyName("metric")]
    public string? Metric { get; set; }

    [JsonPropertyName("comparator")]
    public string? Comparator { get; set; }

    [JsonPropertyName("threshold")]
    public double? Threshold { get; set; }

    /// <summary>The observed per-run value at evaluation time.</summary>
    [JsonPropertyName("observed")]
    public double? Observed { get; set; }

    [JsonPropertyName("satisfied")]
    public bool? Satisfied { get; set; }
}

/// <summary>
/// An API-driven load scenario: ordered templated steps driven at a target concurrency.
/// Registered with <c>PUT /mockserver/loadScenario</c> under its unique <see cref="Name"/>;
/// registration does not start it and is always allowed. Starting it (via
/// <c>PUT /mockserver/loadScenario/start</c>) requires the server to be started with
/// <c>loadGenerationEnabled=true</c>, otherwise the server returns HTTP 403.
/// </summary>
public sealed class LoadScenario
{
    /// <summary>Unique scenario name, used as the registry key (required).</summary>
    [JsonPropertyName("name")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Name { get; set; }

    /// <summary>Template engine used to render per-iteration request path and body (default VELOCITY).</summary>
    [JsonPropertyName("templateType")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadTemplateType? TemplateType { get; set; }

    /// <summary>Optional delay (in milliseconds) between the scenario being started and load beginning; honoured by <c>PUT /mockserver/loadScenario/start</c>.</summary>
    [JsonPropertyName("startDelayMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? StartDelayMillis { get; set; }

    /// <summary>Optional hard cap on the total number of requests dispatched.</summary>
    [JsonPropertyName("maxRequests")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? MaxRequests { get; set; }

    /// <summary>Optional scenario-level annotation labels.</summary>
    [JsonPropertyName("labels")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Dictionary<string, string>? Labels { get; set; }

    /// <summary>
    /// Optional in-run pass/fail thresholds; the run carries a PASS verdict iff all hold, FAIL
    /// otherwise. Empty/omitted means no verdict is computed.
    /// </summary>
    [JsonPropertyName("thresholds")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<LoadThreshold>? Thresholds { get; set; }

    /// <summary>When true, a FAIL verdict aborts the run early (default false).</summary>
    [JsonPropertyName("abortOnFail")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? AbortOnFail { get; set; }

    /// <summary>Suppress <see cref="AbortOnFail"/> for the first N milliseconds so noisy startup samples cannot trigger a premature abort.</summary>
    [JsonPropertyName("abortGraceMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? AbortGraceMillis { get; set; }

    /// <summary>Optional adaptive iteration pacing (think-time) for the closed-model VU loop.</summary>
    [JsonPropertyName("pacing")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadPacing? Pacing { get; set; }

    /// <summary>Optional inline data feeder exposing one row per iteration to templated request fields.</summary>
    [JsonPropertyName("feeder")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadFeeder? Feeder { get; set; }

    /// <summary>Ramp profile describing the target concurrency over time (required).</summary>
    [JsonPropertyName("profile")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadProfile? Profile { get; set; }

    /// <summary>How each iteration selects which steps to run (default SEQUENTIAL).</summary>
    [JsonPropertyName("stepSelection")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadStepSelection? StepSelection { get; set; }

    /// <summary>Ordered list of request steps fired in sequence each iteration (max 50).</summary>
    [JsonPropertyName("steps")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<LoadStep>? Steps { get; set; }
}

/// <summary>
/// Live runtime metrics for a registered load scenario. The server emits these
/// fields FLAT on a registry-listing entry (siblings of name/state/definition),
/// present only once the scenario has run; <see cref="LoadScenarioEntry"/> inherits
/// this type so the flat JSON populates them directly.
/// </summary>
public class LoadScenarioStatus
{
    /// <summary>The registry key (unique scenario name).</summary>
    [JsonPropertyName("name")]
    public string? Name { get; set; }

    /// <summary>Lifecycle state (see <see cref="LoadScenarioState"/>).</summary>
    [JsonPropertyName("state")]
    public LoadScenarioState? State { get; set; }

    [JsonPropertyName("elapsedMillis")]
    public long? ElapsedMillis { get; set; }

    [JsonPropertyName("currentVus")]
    public int? CurrentVus { get; set; }

    /// <summary>0-based index of the currently-running stage (present only while running).</summary>
    [JsonPropertyName("stageIndex")]
    public int? StageIndex { get; set; }

    /// <summary>Type of the currently-running stage (present only while running).</summary>
    [JsonPropertyName("stageType")]
    public string? StageType { get; set; }

    /// <summary>Current setpoint for the running stage (target VUs, target rate, or 0 for PAUSE).</summary>
    [JsonPropertyName("currentTarget")]
    public double? CurrentTarget { get; set; }

    [JsonPropertyName("requestsSent")]
    public long? RequestsSent { get; set; }

    [JsonPropertyName("succeeded")]
    public long? Succeeded { get; set; }

    [JsonPropertyName("failed")]
    public long? Failed { get; set; }

    [JsonPropertyName("p50Millis")]
    public double? P50Millis { get; set; }

    [JsonPropertyName("p95Millis")]
    public double? P95Millis { get; set; }

    [JsonPropertyName("p99Millis")]
    public double? P99Millis { get; set; }

    /// <summary>99.9th-percentile coordinated-omission-corrected latency (ms), from the per-run HDR histogram.</summary>
    [JsonPropertyName("p999Millis")]
    public double? P999Millis { get; set; }

    /// <summary>Iterations that were due but never dispatched because a safety cap was hit (rate-limit + inflight-cap throttles).</summary>
    [JsonPropertyName("droppedIterations")]
    public long? DroppedIterations { get; set; }

    /// <summary>In-run threshold verdict: PASS (all thresholds satisfied) or FAIL (any breached); absent when no thresholds or none evaluated yet.</summary>
    [JsonPropertyName("verdict")]
    public LoadVerdict? Verdict { get; set; }

    /// <summary>True when this run was terminated early by an abortOnFail threshold breach; absent means false.</summary>
    [JsonPropertyName("abortedByThreshold")]
    public bool? AbortedByThreshold { get; set; }

    /// <summary>Per-threshold results behind the <see cref="Verdict"/> (present when thresholds were evaluated).</summary>
    [JsonPropertyName("thresholdResults")]
    public List<LoadThresholdResult>? ThresholdResults { get; set; }

    [JsonPropertyName("runId")]
    public string? RunId { get; set; }

    [JsonPropertyName("startedAt")]
    public long? StartedAt { get; set; }

    [JsonPropertyName("endedAt")]
    public long? EndedAt { get; set; }

    /// <summary>Scenario-level annotation labels carried through to the run.</summary>
    [JsonPropertyName("labels")]
    public Dictionary<string, string>? Labels { get; set; }
}

/// <summary>
/// One registered scenario as returned by the registry endpoints
/// (<c>GET /mockserver/loadScenario</c> and <c>GET /mockserver/loadScenario/{name}</c>):
/// the scenario name, its lifecycle state, the registered definition, and — once it has
/// run — its live status fields, which the server emits FLAT on the entry. This type
/// inherits <see cref="LoadScenarioStatus"/> so those flat fields populate directly.
/// </summary>
public sealed class LoadScenarioEntry : LoadScenarioStatus
{
    /// <summary>Optional delay (ms) before the scenario begins driving load once started.</summary>
    [JsonPropertyName("startDelayMillis")]
    public long? StartDelayMillis { get; set; }

    /// <summary>The full registered scenario definition.</summary>
    [JsonPropertyName("definition")]
    public LoadScenario? Definition { get; set; }
}

/// <summary>
/// The full registry listing returned by <c>GET /mockserver/loadScenario</c>.
/// </summary>
public sealed class LoadScenarioList
{
    /// <summary>Every registered scenario, in registration order.</summary>
    [JsonPropertyName("scenarios")]
    public List<LoadScenarioEntry> Scenarios { get; set; } = new();
}

/// <summary>A name/state pair echoed back from a register or start operation.</summary>
public sealed class LoadScenarioRef
{
    [JsonPropertyName("name")]
    public string? Name { get; set; }

    [JsonPropertyName("state")]
    public LoadScenarioState? State { get; set; }
}

/// <summary>
/// The result of <c>PUT /mockserver/loadScenario/start</c>: the scenarios accepted to start
/// (each with its resulting state) and an overall status string.
/// </summary>
public sealed class LoadScenarioStartResult
{
    /// <summary>The scenarios accepted to start, each with its resulting state.</summary>
    [JsonPropertyName("started")]
    public List<LoadScenarioRef> Started { get; set; } = new();

    /// <summary>Overall status string returned by the server.</summary>
    [JsonPropertyName("status")]
    public string? Status { get; set; }
}

/// <summary>
/// The result of <c>PUT /mockserver/loadScenario/stop</c>: the scenarios stopped (each with its
/// resulting state) and an overall status string.
/// </summary>
public sealed class LoadScenarioStopResult
{
    /// <summary>The scenarios that were stopped, each with its resulting state.</summary>
    [JsonPropertyName("stopped")]
    public List<LoadScenarioRef> Stopped { get; set; } = new();

    /// <summary>Overall status string returned by the server.</summary>
    [JsonPropertyName("status")]
    public string? Status { get; set; }
}

/// <summary>The network scheme of a <see cref="LoadGenerateTarget"/>.</summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum LoadTargetScheme
{
    http,
    https
}

/// <summary>
/// An explicit network target applied to every generated load step (overrides each request's own
/// Host/secure routing), used by the generate-from-OpenAPI / generate-from-recording operations.
/// </summary>
public sealed class LoadGenerateTarget
{
    [JsonPropertyName("host")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Host { get; set; }

    [JsonPropertyName("port")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? Port { get; set; }

    [JsonPropertyName("scheme")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadTargetScheme? Scheme { get; set; }
}

/// <summary>The request body for <c>PUT /mockserver/loadScenario/generateFromOpenAPI</c>.</summary>
public sealed class GenerateFromOpenApiRequest
{
    /// <summary>The generated scenario name (the unique registry key) (required).</summary>
    [JsonPropertyName("name")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Name { get; set; }

    /// <summary>The OpenAPI spec as an inline JSON/YAML payload, a URL, or a file/classpath reference (required).</summary>
    [JsonPropertyName("specUrlOrPayload")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? SpecUrlOrPayload { get; set; }

    /// <summary>Optional explicit network target for every generated step (overrides the spec's servers[0]).</summary>
    [JsonPropertyName("target")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadGenerateTarget? Target { get; set; }

    /// <summary>Optional load profile; when omitted a conservative default is applied server-side.</summary>
    [JsonPropertyName("profile")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadProfile? Profile { get; set; }
}

/// <summary>The request body for <c>PUT /mockserver/loadScenario/generateFromRecording</c>.</summary>
public sealed class GenerateFromRecordingRequest
{
    /// <summary>The generated scenario name (the unique registry key) (required).</summary>
    [JsonPropertyName("name")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Name { get; set; }

    /// <summary>Optional generation mode: VERBATIM (default) or TEMPLATIZED.</summary>
    [JsonPropertyName("mode")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadRecordingMode? Mode { get; set; }

    /// <summary>Optional matcher selecting which recorded requests to include; absent means all.</summary>
    [JsonPropertyName("requestFilter")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public HttpRequest? RequestFilter { get; set; }

    /// <summary>Optional cap on the number of VERBATIM steps (keeps the first N recorded requests).</summary>
    [JsonPropertyName("maxSteps")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? MaxSteps { get; set; }

    /// <summary>Optional explicit network target applied to every generated step.</summary>
    [JsonPropertyName("target")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadGenerateTarget? Target { get; set; }

    /// <summary>Optional load profile; when omitted a conservative default is applied server-side.</summary>
    [JsonPropertyName("profile")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadProfile? Profile { get; set; }
}

/// <summary>
/// The result of a load-scenario generate operation (<c>generateFromOpenAPI</c> /
/// <c>generateFromRecording</c>): the loaded scenario's name, its lifecycle state, and the generated
/// definition so a client/UI can show and edit it before triggering a run.
/// </summary>
public sealed class LoadScenarioGenerateResult
{
    /// <summary>Overall status string returned by the server (e.g. "loaded").</summary>
    [JsonPropertyName("status")]
    public string? Status { get; set; }

    /// <summary>The generated scenario name (the unique registry key).</summary>
    [JsonPropertyName("name")]
    public string? Name { get; set; }

    /// <summary>The lifecycle state of the generated scenario (LOADED).</summary>
    [JsonPropertyName("state")]
    public LoadScenarioState? State { get; set; }

    /// <summary>The generated scenario definition.</summary>
    [JsonPropertyName("scenario")]
    public LoadScenario? Scenario { get; set; }
}

// ===========================================================================
// Service chaos — PUT /mockserver/serviceChaos
// ===========================================================================

/// <summary>
/// An HTTP chaos / fault-injection profile registered for a downstream host
/// (the <c>chaos</c> object of a service-chaos request). All fields are optional;
/// unset fields are omitted on the wire.
/// </summary>
public sealed class ServiceChaosProfile
{
    /// <summary>HTTP error status code to return instead of the real response.</summary>
    [JsonPropertyName("errorStatus")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? ErrorStatus { get; set; }

    /// <summary>Probability (0.0 to 1.0) that a request triggers the error.</summary>
    [JsonPropertyName("errorProbability")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? ErrorProbability { get; set; }

    /// <summary>Probability (0.0 to 1.0) that the TCP connection is dropped without responding.</summary>
    [JsonPropertyName("dropConnectionProbability")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? DropConnectionProbability { get; set; }

    /// <summary>Literal Retry-After header value to return on injected errors.</summary>
    [JsonPropertyName("retryAfter")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? RetryAfter { get; set; }

    /// <summary>Additional latency injected before the response.</summary>
    [JsonPropertyName("latency")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? Latency { get; set; }

    /// <summary>Fixed seed for deterministic probabilistic outcomes.</summary>
    [JsonPropertyName("seed")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? Seed { get; set; }

    /// <summary>Allow this many requests to succeed before faults begin.</summary>
    [JsonPropertyName("succeedFirst")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? SucceedFirst { get; set; }

    /// <summary>Number of requests to fail (after <see cref="SucceedFirst"/>) before recovering.</summary>
    [JsonPropertyName("failRequestCount")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? FailRequestCount { get; set; }

    /// <summary>Begin a simulated outage after this many milliseconds.</summary>
    [JsonPropertyName("outageAfterMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? OutageAfterMillis { get; set; }

    /// <summary>How long a simulated outage lasts, in milliseconds.</summary>
    [JsonPropertyName("outageDurationMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? OutageDurationMillis { get; set; }

    /// <summary>Truncate the response body at this fraction (0.0 to 1.0) of its length.</summary>
    [JsonPropertyName("truncateBodyAtFraction")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? TruncateBodyAtFraction { get; set; }

    /// <summary>When true, returns a malformed body.</summary>
    [JsonPropertyName("malformedBody")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? MalformedBody { get; set; }

    /// <summary>Chunk size in bytes for a slow trickled response.</summary>
    [JsonPropertyName("slowResponseChunkSize")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? SlowResponseChunkSize { get; set; }

    /// <summary>Delay between successive chunks of a slow trickled response.</summary>
    [JsonPropertyName("slowResponseChunkDelay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? SlowResponseChunkDelay { get; set; }

    /// <summary>Shared quota counter key.</summary>
    [JsonPropertyName("quotaName")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? QuotaName { get; set; }

    /// <summary>Maximum requests allowed per quota window.</summary>
    [JsonPropertyName("quotaLimit")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? QuotaLimit { get; set; }

    /// <summary>Quota window length in milliseconds.</summary>
    [JsonPropertyName("quotaWindowMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? QuotaWindowMillis { get; set; }

    /// <summary>Status returned when over quota.</summary>
    [JsonPropertyName("quotaErrorStatus")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? QuotaErrorStatus { get; set; }

    /// <summary>Ramp the chaos severity in over this many milliseconds.</summary>
    [JsonPropertyName("degradationRampMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? DegradationRampMillis { get; set; }
}

// ===========================================================================
// SLO verdict — PUT /mockserver/verifySLO
// ===========================================================================

/// <summary>The service-level indicator evaluated by an <see cref="SloObjective"/>.</summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum Sli
{
    LATENCY_P50,
    LATENCY_P95,
    LATENCY_P99,
    ERROR_RATE
}

/// <summary>How an observed SLI value is compared to its threshold.</summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum SloComparator
{
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL
}

/// <summary>Which recorded traffic an objective evaluates.</summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum SloScope
{
    FORWARD,
    INBOUND
}

/// <summary>The kind of time window an <see cref="SloCriteria"/> evaluates over.</summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum SloWindowType
{
    LOOKBACK,
    EXPLICIT
}

/// <summary>The overall verdict of an SLO evaluation.</summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum SloResult
{
    PASS,
    FAIL,
    INCONCLUSIVE
}

/// <summary>The time window an <see cref="SloCriteria"/> is evaluated over.</summary>
public sealed class SloWindow
{
    [JsonPropertyName("type")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public SloWindowType? Type { get; set; }

    /// <summary>LOOKBACK: window length ending now.</summary>
    [JsonPropertyName("lookbackMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? LookbackMillis { get; set; }

    /// <summary>EXPLICIT: window start in epoch milliseconds.</summary>
    [JsonPropertyName("fromEpochMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? FromEpochMillis { get; set; }

    /// <summary>EXPLICIT: window end in epoch milliseconds.</summary>
    [JsonPropertyName("toEpochMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? ToEpochMillis { get; set; }

    /// <summary>Build a trailing lookback window of the given length.</summary>
    public static SloWindow Lookback(long lookbackMillis)
        => new() { Type = SloWindowType.LOOKBACK, LookbackMillis = lookbackMillis };

    /// <summary>Build an explicit absolute window.</summary>
    public static SloWindow Explicit(long fromEpochMillis, long toEpochMillis)
        => new() { Type = SloWindowType.EXPLICIT, FromEpochMillis = fromEpochMillis, ToEpochMillis = toEpochMillis };
}

/// <summary>A single service-level objective over the recorded SLI samples.</summary>
public sealed class SloObjective
{
    [JsonPropertyName("sli")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Sli? Sli { get; set; }

    [JsonPropertyName("comparator")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public SloComparator? Comparator { get; set; }

    [JsonPropertyName("threshold")]
    public double Threshold { get; set; }

    [JsonPropertyName("scope")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public SloScope? Scope { get; set; }
}

/// <summary>
/// A named set of service-level objectives over a time window, submitted to
/// <c>PUT /mockserver/verifySLO</c>.
/// </summary>
public sealed class SloCriteria
{
    /// <summary>Human-readable criteria name, echoed back in the verdict.</summary>
    [JsonPropertyName("name")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Name { get; set; }

    /// <summary>The time window to evaluate over.</summary>
    [JsonPropertyName("window")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public SloWindow? Window { get; set; }

    /// <summary>Minimum samples required in the window; below this the verdict is INCONCLUSIVE.</summary>
    [JsonPropertyName("minimumSampleCount")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? MinimumSampleCount { get; set; }

    /// <summary>Optional list of upstream hosts to restrict the evaluation to.</summary>
    [JsonPropertyName("upstreamHosts")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<string>? UpstreamHosts { get; set; }

    /// <summary>The objectives that must all hold (logical AND) for the criteria to PASS.</summary>
    [JsonPropertyName("objectives")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<SloObjective>? Objectives { get; set; }
}

/// <summary>The evaluated result of a single <see cref="SloObjective"/>.</summary>
public sealed class SloObjectiveResult
{
    [JsonPropertyName("sli")]
    public string? Sli { get; set; }

    [JsonPropertyName("comparator")]
    public string? Comparator { get; set; }

    [JsonPropertyName("threshold")]
    public double Threshold { get; set; }

    [JsonPropertyName("observedValue")]
    public double? ObservedValue { get; set; }

    [JsonPropertyName("result")]
    public SloResult? Result { get; set; }

    [JsonPropertyName("detail")]
    public string? Detail { get; set; }
}

/// <summary>
/// The overall verdict of an SLO evaluation (the AND of all objective results),
/// returned by <c>PUT /mockserver/verifySLO</c>.
/// </summary>
public sealed class SloVerdict
{
    [JsonPropertyName("name")]
    public string? Name { get; set; }

    [JsonPropertyName("result")]
    public SloResult? Result { get; set; }

    [JsonPropertyName("windowFromEpochMillis")]
    public long? WindowFromEpochMillis { get; set; }

    [JsonPropertyName("windowToEpochMillis")]
    public long? WindowToEpochMillis { get; set; }

    [JsonPropertyName("sampleCount")]
    public int? SampleCount { get; set; }

    [JsonPropertyName("objectiveResults")]
    public List<SloObjectiveResult>? ObjectiveResults { get; set; }
}

// ===========================================================================
// Preemption — PUT/GET/DELETE /mockserver/preemption
// ===========================================================================

/// <summary>How draining is signalled during a preemption simulation.</summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum PreemptionMode
{
    /// <summary>503 + Connection: close.</summary>
    reject503,

    /// <summary>HTTP/2 GOAWAY frame.</summary>
    goaway,

    /// <summary>Both a 503 close and a GOAWAY frame.</summary>
    both
}

/// <summary>
/// Preemption simulation parameters (all fields optional), submitted to
/// <c>PUT /mockserver/preemption</c>.
/// </summary>
public sealed class PreemptionRequest
{
    /// <summary>How draining is signalled (default <see cref="PreemptionMode.both"/>).</summary>
    [JsonPropertyName("mode")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public PreemptionMode? Mode { get; set; }

    /// <summary>How long in-flight requests are allowed to drain, in milliseconds.</summary>
    [JsonPropertyName("drainMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? DrainMillis { get; set; }

    /// <summary>Auto-uncordon after this many milliseconds (dead-man's switch); 0 means no auto-uncordon.</summary>
    [JsonPropertyName("ttlMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? TtlMillis { get; set; }

    /// <summary>HTTP/2 GOAWAY last_stream_id to advertise; -1 lets the server choose.</summary>
    [JsonPropertyName("lastStreamId")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? LastStreamId { get; set; }
}

/// <summary>
/// The current cordon/drain status of the server, returned by the preemption endpoints.
/// </summary>
public sealed class PreemptionStatus
{
    /// <summary>"inactive", "draining" or "drained".</summary>
    [JsonPropertyName("state")]
    public string? State { get; set; }

    /// <summary>Number of requests currently in flight.</summary>
    [JsonPropertyName("inFlight")]
    public int? InFlight { get; set; }

    /// <summary>Milliseconds left in the drain window.</summary>
    [JsonPropertyName("drainRemainingMillis")]
    public long? DrainRemainingMillis { get; set; }

    /// <summary>Active signalling mode (omitted when inactive).</summary>
    [JsonPropertyName("mode")]
    public string? Mode { get; set; }
}

// ===========================================================================
// Chaos experiment — PUT /mockserver/chaosExperiment
// ===========================================================================

/// <summary>A single stage of a <see cref="ChaosExperiment"/>.</summary>
public sealed class ChaosExperimentStage
{
    /// <summary>How long this stage runs before advancing, in milliseconds (max 86400000 = 24h).</summary>
    [JsonPropertyName("durationMillis")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? DurationMillis { get; set; }

    /// <summary>Map of host -&gt; chaos profile to apply during this stage.</summary>
    [JsonPropertyName("profiles")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Dictionary<string, ServiceChaosProfile>? Profiles { get; set; }
}

/// <summary>
/// A scheduled multi-stage chaos experiment definition, submitted to
/// <c>PUT /mockserver/chaosExperiment</c>. Stages progress automatically; only one
/// experiment may be active at a time.
/// </summary>
public sealed class ChaosExperiment
{
    /// <summary>Human-readable experiment name.</summary>
    [JsonPropertyName("name")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Name { get; set; }

    /// <summary>Whether to loop back to stage 0 after the last stage completes (default false).</summary>
    [JsonPropertyName("loop")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? Loop { get; set; }

    /// <summary>Ordered sequence of stages (required).</summary>
    [JsonPropertyName("stages")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<ChaosExperimentStage>? Stages { get; set; }
}
