using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Represents a raw binary response action for MockServer. The
/// <see cref="BinaryData"/> field carries a Base64-encoded payload that is
/// written verbatim to the connection (used for non-HTTP binary protocols).
/// </summary>
public sealed class BinaryResponse
{
    /// <summary>
    /// Base64-encoded response payload.
    /// </summary>
    [JsonPropertyName("binaryData")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? BinaryData { get; set; }

    [JsonPropertyName("delay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? Delay { get; set; }

    [JsonPropertyName("primary")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? Primary { get; set; }

    /// <summary>
    /// Creates a new binary response builder.
    /// </summary>
    public static BinaryResponseBuilder Response() => new();
}
