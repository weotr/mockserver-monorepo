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
/// An ordered list of stages run in sequence, describing the load over time for a
/// <see cref="LoadScenario"/>.
/// </summary>
public sealed class LoadProfile
{
    /// <summary>Ordered stages run one after another (required, max 20).</summary>
    [JsonPropertyName("stages")]
    public List<LoadStage> Stages { get; set; } = new();
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

    /// <summary>Ramp profile describing the target concurrency over time (required).</summary>
    [JsonPropertyName("profile")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public LoadProfile? Profile { get; set; }

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
