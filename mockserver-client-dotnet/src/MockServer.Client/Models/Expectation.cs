using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Represents a MockServer expectation (request matcher + action).
/// </summary>
public sealed class Expectation
{
    [JsonPropertyName("id")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Id { get; set; }

    [JsonPropertyName("priority")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? Priority { get; set; }

    [JsonPropertyName("httpRequest")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public HttpRequest? HttpRequest { get; set; }

    [JsonPropertyName("httpResponse")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public HttpResponse? HttpResponse { get; set; }

    [JsonPropertyName("httpForward")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public HttpForward? HttpForward { get; set; }

    [JsonPropertyName("httpError")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public HttpError? HttpError { get; set; }

    [JsonPropertyName("times")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Times? Times { get; set; }

    [JsonPropertyName("timeToLive")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public TimeToLive? TimeToLive { get; set; }
}
