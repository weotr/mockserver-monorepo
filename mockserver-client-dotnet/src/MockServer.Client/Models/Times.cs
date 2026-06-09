using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Specifies how many times an expectation should be used before it is discarded.
/// </summary>
public sealed class Times
{
    [JsonPropertyName("remainingTimes")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? RemainingTimes { get; set; }

    [JsonPropertyName("unlimited")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? IsUnlimited { get; set; }

    /// <summary>
    /// Creates a Times that matches exactly <paramref name="count"/> times.
    /// </summary>
    public static Times Exactly(int count) => new() { RemainingTimes = count, IsUnlimited = false };

    /// <summary>
    /// Creates a Times that matches unlimited times.
    /// </summary>
    public static Times Unlimited() => new() { IsUnlimited = true };

    /// <summary>
    /// Creates a Times that matches exactly once.
    /// </summary>
    public static Times Once() => Exactly(1);
}
