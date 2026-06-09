using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Represents a verification sequence request (check that requests were received in order).
/// </summary>
internal sealed class VerificationSequence
{
    [JsonPropertyName("httpRequests")]
    public List<HttpRequest>? HttpRequests { get; set; }
}
