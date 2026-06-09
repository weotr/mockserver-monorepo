using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Specifies how long an expectation remains active.
/// </summary>
public sealed class TimeToLive
{
    [JsonPropertyName("timeUnit")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    [JsonConverter(typeof(JsonStringEnumConverter))]
    public TimeUnit? TimeUnit { get; set; }

    [JsonPropertyName("timeToLive")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? Value { get; set; }

    [JsonPropertyName("unlimited")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? Unlimited { get; set; }

    /// <summary>
    /// Creates a TimeToLive that never expires.
    /// </summary>
    public static TimeToLive UnlimitedTtl() => new() { Unlimited = true };

    /// <summary>
    /// Creates a TimeToLive that expires after the given duration.
    /// </summary>
    public static TimeToLive ExactlyTtl(TimeUnit timeUnit, long value)
        => new() { TimeUnit = timeUnit, Value = value, Unlimited = false };
}
