using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Represents a delay to apply before returning a response.
/// </summary>
public sealed class Delay
{
    [JsonPropertyName("timeUnit")]
    public TimeUnit TimeUnit { get; set; } = TimeUnit.MILLISECONDS;

    [JsonPropertyName("value")]
    public long Value { get; set; }
}

/// <summary>
/// Time unit for delays (matches MockServer's Java TimeUnit names).
/// </summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum TimeUnit
{
    MILLISECONDS,
    SECONDS,
    MINUTES,
    HOURS,
    DAYS
}
