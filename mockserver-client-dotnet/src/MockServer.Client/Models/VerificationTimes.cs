using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Specifies the number of times a request should have been received for verification.
/// </summary>
public sealed class VerificationTimes
{
    [JsonPropertyName("atLeast")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? AtLeast { get; set; }

    [JsonPropertyName("atMost")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? AtMost { get; set; }

    /// <summary>
    /// Verifies the request was received at least <paramref name="count"/> times.
    /// </summary>
    public static VerificationTimes AtLeastTimes(int count) => new() { AtLeast = count };

    /// <summary>
    /// Verifies the request was received at most <paramref name="count"/> times.
    /// </summary>
    public static VerificationTimes AtMostTimes(int count) => new() { AtMost = count };

    /// <summary>
    /// Verifies the request was received exactly <paramref name="count"/> times.
    /// </summary>
    public static VerificationTimes ExactlyTimes(int count) => new() { AtLeast = count, AtMost = count };

    /// <summary>
    /// Verifies the request was received exactly once.
    /// </summary>
    public static VerificationTimes Once() => ExactlyTimes(1);
}
