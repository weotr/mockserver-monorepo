using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Breakpoint interception phase.
/// </summary>
public static class BreakpointPhase
{
    public const string Request = "REQUEST";
    public const string Response = "RESPONSE";
    public const string ResponseStream = "RESPONSE_STREAM";
    public const string InboundStream = "INBOUND_STREAM";
}

/// <summary>
/// Registration request for a breakpoint matcher.
/// </summary>
public sealed class BreakpointMatcherRegistration
{
    [JsonPropertyName("httpRequest")]
    public HttpRequest? HttpRequest { get; set; }

    [JsonPropertyName("phases")]
    public List<string>? Phases { get; set; }

    [JsonPropertyName("clientId")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? ClientId { get; set; }
}

/// <summary>
/// Response from registering a breakpoint matcher.
/// </summary>
public sealed class BreakpointMatcherResponse
{
    [JsonPropertyName("id")]
    public string? Id { get; set; }

    [JsonPropertyName("phases")]
    public List<string>? Phases { get; set; }
}

/// <summary>
/// An entry in the list of registered breakpoint matchers.
/// </summary>
public sealed class BreakpointMatcherEntry
{
    [JsonPropertyName("id")]
    public string? Id { get; set; }

    [JsonPropertyName("httpRequest")]
    public object? HttpRequest { get; set; }

    [JsonPropertyName("phases")]
    public List<string>? Phases { get; set; }

    [JsonPropertyName("clientId")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? ClientId { get; set; }
}

/// <summary>
/// Response from listing breakpoint matchers.
/// </summary>
public sealed class BreakpointMatcherList
{
    [JsonPropertyName("matchers")]
    public List<BreakpointMatcherEntry>? Matchers { get; set; }
}

/// <summary>
/// A paused stream frame pushed by the server over the callback WebSocket.
/// </summary>
public sealed class PausedStreamFrame
{
    [JsonPropertyName("correlationId")]
    public string? CorrelationId { get; set; }

    [JsonPropertyName("streamId")]
    public string? StreamId { get; set; }

    [JsonPropertyName("sequenceNumber")]
    public int SequenceNumber { get; set; }

    [JsonPropertyName("direction")]
    public string? Direction { get; set; }

    [JsonPropertyName("phase")]
    public string? Phase { get; set; }

    [JsonPropertyName("body")]
    public string? Body { get; set; }

    [JsonPropertyName("requestMethod")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? RequestMethod { get; set; }

    [JsonPropertyName("requestPath")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? RequestPath { get; set; }

    [JsonPropertyName("breakpointId")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? BreakpointId { get; set; }

    /// <summary>
    /// Decodes the Base64-encoded body to bytes.
    /// </summary>
    public byte[] BodyBytes() => string.IsNullOrEmpty(Body) ? Array.Empty<byte>() : Convert.FromBase64String(Body);
}

/// <summary>
/// Client-to-server reply for a stream frame decision.
/// </summary>
public sealed class StreamFrameDecision
{
    [JsonPropertyName("correlationId")]
    public string? CorrelationId { get; set; }

    [JsonPropertyName("action")]
    public string? Action { get; set; }

    [JsonPropertyName("body")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Body { get; set; }

    /// <summary>Creates a CONTINUE decision.</summary>
    public static StreamFrameDecision Continue(string correlationId) =>
        new() { CorrelationId = correlationId, Action = "CONTINUE" };

    /// <summary>Creates a MODIFY decision with replacement bytes.</summary>
    public static StreamFrameDecision Modify(string correlationId, byte[] body) =>
        new() { CorrelationId = correlationId, Action = "MODIFY", Body = Convert.ToBase64String(body) };

    /// <summary>Creates a DROP decision.</summary>
    public static StreamFrameDecision Drop(string correlationId) =>
        new() { CorrelationId = correlationId, Action = "DROP" };

    /// <summary>Creates an INJECT decision.</summary>
    public static StreamFrameDecision Inject(string correlationId, byte[] extraBody) =>
        new() { CorrelationId = correlationId, Action = "INJECT", Body = Convert.ToBase64String(extraBody) };

    /// <summary>Creates a CLOSE decision.</summary>
    public static StreamFrameDecision Close(string correlationId) =>
        new() { CorrelationId = correlationId, Action = "CLOSE" };
}
