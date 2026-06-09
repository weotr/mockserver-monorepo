using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Represents a verification request (check that a request was received N times).
/// </summary>
internal sealed class Verification
{
    [JsonPropertyName("httpRequest")]
    public HttpRequest? HttpRequest { get; set; }

    [JsonPropertyName("times")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public VerificationTimes? Times { get; set; }
}
