using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Represents a verification sequence request (check that requests were received in order).
/// When HttpResponses is set, the responses are index-aligned with HttpRequests and
/// verification switches to "response received" mode.
/// </summary>
internal sealed class VerificationSequence
{
    [JsonPropertyName("httpRequests")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<HttpRequest>? HttpRequests { get; set; }

    [JsonPropertyName("httpResponses")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<HttpResponse>? HttpResponses { get; set; }
}
