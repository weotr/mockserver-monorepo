using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Represents an HTTP error action for MockServer (drops/corrupts connections).
/// </summary>
public sealed class HttpError
{
    [JsonPropertyName("dropConnection")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? DropConnection { get; set; }

    [JsonPropertyName("responseBytes")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? ResponseBytes { get; set; }

    /// <summary>
    /// Creates a new HttpError builder.
    /// </summary>
    public static HttpErrorBuilder Error() => new();
}
