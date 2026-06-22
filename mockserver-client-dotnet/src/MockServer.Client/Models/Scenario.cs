using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

// ---------------------------------------------------------------------------
// Stateful scenario models: multi-response selection mode, cross-protocol
// triggers and the typed responses of the scenario REST endpoints.
//
// All wire field names match the MockServer REST contract exactly. The
// serializer is configured with WhenWritingNull so unset optional fields are
// omitted.
// ---------------------------------------------------------------------------

/// <summary>
/// How a response is selected from an expectation's <see cref="Expectation.HttpResponses"/> on each match.
/// Serialized as the exact strings <c>SEQUENTIAL</c>, <c>RANDOM</c>, <c>WEIGHTED</c> or <c>SWITCH</c>.
/// </summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum ResponseMode
{
    /// <summary>Serve responses in order, advancing one per match (default).</summary>
    SEQUENTIAL,

    /// <summary>Serve a uniformly random response on each match.</summary>
    RANDOM,

    /// <summary>Serve responses weighted by <see cref="Expectation.ResponseWeights"/>.</summary>
    WEIGHTED,

    /// <summary>Serve each response for <see cref="Expectation.SwitchAfter"/> matches before advancing.</summary>
    SWITCH
}

/// <summary>
/// The kind of event that fires a <see cref="CrossProtocolScenario"/> trigger.
/// Serialized as the exact strings <c>DNS_QUERY</c>, <c>WEBSOCKET_CONNECT</c>,
/// <c>GRPC_REQUEST</c> or <c>HTTP_REQUEST</c>.
/// </summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum CrossProtocolTrigger
{
    /// <summary>A DNS query is observed.</summary>
    DNS_QUERY,

    /// <summary>A WebSocket connection is established.</summary>
    WEBSOCKET_CONNECT,

    /// <summary>A gRPC request is received.</summary>
    GRPC_REQUEST,

    /// <summary>An HTTP request is received.</summary>
    HTTP_REQUEST
}

/// <summary>
/// A cross-protocol trigger: when an event of <see cref="Trigger"/> kind whose identifier contains
/// <see cref="MatchPattern"/> (or any event, when the pattern is unset) is observed, advance the named
/// <see cref="ScenarioName"/> to <see cref="TargetState"/>.
/// </summary>
public sealed class CrossProtocolScenario
{
    /// <summary>The event kind that fires this trigger.</summary>
    [JsonPropertyName("trigger")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public CrossProtocolTrigger? Trigger { get; set; }

    /// <summary>Optional substring filter on the event identifier; omit to match all events of this kind.</summary>
    [JsonPropertyName("matchPattern")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? MatchPattern { get; set; }

    /// <summary>The scenario state-machine to advance.</summary>
    [JsonPropertyName("scenarioName")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? ScenarioName { get; set; }

    /// <summary>The state to transition the scenario to.</summary>
    [JsonPropertyName("targetState")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? TargetState { get; set; }
}

/// <summary>
/// The state of a single named scenario, returned by the scenario REST endpoints.
/// </summary>
public sealed class ScenarioState
{
    /// <summary>The scenario state-machine name.</summary>
    [JsonPropertyName("scenarioName")]
    public string? ScenarioName { get; set; }

    /// <summary>The scenario's current state.</summary>
    [JsonPropertyName("currentState")]
    public string? CurrentState { get; set; }

    /// <summary>The pending next state of a timed transition, if one is scheduled.</summary>
    [JsonPropertyName("nextState")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? NextState { get; set; }

    /// <summary>Milliseconds after which a scheduled timed transition fires, if one is scheduled.</summary>
    [JsonPropertyName("transitionAfterMs")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? TransitionAfterMs { get; set; }
}

/// <summary>
/// The list of all known scenarios, returned by <c>GET /mockserver/scenario</c>.
/// </summary>
public sealed class ScenarioList
{
    /// <summary>All known scenarios and their current states.</summary>
    [JsonPropertyName("scenarios")]
    public List<ScenarioState> Scenarios { get; set; } = new();
}
