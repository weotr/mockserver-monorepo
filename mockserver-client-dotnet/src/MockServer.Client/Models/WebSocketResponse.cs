using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// A single message within an <see cref="HttpWebSocketResponse"/>. A message is
/// either textual (<see cref="Text"/>) or binary (<see cref="Binary"/>, a
/// Base64-encoded payload).
/// </summary>
public sealed class WebSocketMessage
{
    [JsonPropertyName("text")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Text { get; set; }

    /// <summary>
    /// Base64-encoded binary message payload.
    /// </summary>
    [JsonPropertyName("binary")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Binary { get; set; }

    [JsonPropertyName("delay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? Delay { get; set; }

    /// <summary>
    /// Creates a textual WebSocket message.
    /// </summary>
    public static WebSocketMessage OfText(string text) => new() { Text = text };

    /// <summary>
    /// Creates a binary WebSocket message from raw bytes (Base64-encoded for the wire).
    /// </summary>
    public static WebSocketMessage OfBinary(byte[] bytes) => new() { Binary = Convert.ToBase64String(bytes) };

    /// <summary>
    /// Creates a binary WebSocket message from an already-Base64-encoded payload.
    /// </summary>
    public static WebSocketMessage OfBase64(string base64) => new() { Binary = base64 };
}

/// <summary>
/// Represents a WebSocket response action for MockServer. When the matched
/// request is a WebSocket upgrade, the configured messages are sent to the
/// connected client.
/// </summary>
public sealed class HttpWebSocketResponse
{
    [JsonPropertyName("subprotocol")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Subprotocol { get; set; }

    [JsonPropertyName("messages")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<WebSocketMessage>? Messages { get; set; }

    [JsonPropertyName("closeConnection")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? CloseConnection { get; set; }

    [JsonPropertyName("delay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? Delay { get; set; }

    [JsonPropertyName("primary")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? Primary { get; set; }

    /// <summary>
    /// Creates a new WebSocket response builder.
    /// </summary>
    public static WebSocketResponseBuilder Response() => new();
}
