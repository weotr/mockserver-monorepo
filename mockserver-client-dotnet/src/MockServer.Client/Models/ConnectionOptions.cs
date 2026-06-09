using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Connection-level options for a response action.
/// </summary>
public sealed class ConnectionOptions
{
    [JsonPropertyName("closeSocket")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? CloseSocket { get; set; }

    [JsonPropertyName("contentLengthHeaderOverride")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? ContentLengthHeaderOverride { get; set; }

    [JsonPropertyName("suppressContentLengthHeader")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? SuppressContentLengthHeader { get; set; }

    [JsonPropertyName("suppressConnectionHeader")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? SuppressConnectionHeader { get; set; }

    [JsonPropertyName("keepAliveOverride")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? KeepAliveOverride { get; set; }
}
