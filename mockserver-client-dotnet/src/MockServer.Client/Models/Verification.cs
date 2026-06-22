using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Represents a verification request (check that a request was received N times).
/// When HttpResponse is set, verification switches from "request received" to
/// "response received" mode (recorded proxied/forwarded request-response pairs).
/// </summary>
internal sealed class Verification
{
    [JsonPropertyName("httpRequest")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public HttpRequest? HttpRequest { get; set; }

    [JsonPropertyName("httpResponse")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public HttpResponse? HttpResponse { get; set; }

    [JsonPropertyName("times")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public VerificationTimes? Times { get; set; }
}
