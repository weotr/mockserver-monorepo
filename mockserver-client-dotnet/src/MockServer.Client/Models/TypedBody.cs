using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// A typed body for JSON body matchers (type=JSON).
/// </summary>
public sealed class TypedBody
{
    [JsonPropertyName("type")]
    public string Type { get; set; } = "JSON";

    [JsonPropertyName("json")]
    public string? Json { get; set; }
}
