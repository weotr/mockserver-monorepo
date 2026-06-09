using System.Text.Json;
using FluentAssertions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

public class VerificationSerializationTests
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    [Fact]
    public void VerificationTimes_AtLeast_SerializesCorrectly()
    {
        var times = VerificationTimes.AtLeastTimes(2);

        var json = JsonSerializer.Serialize(times, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.GetProperty("atLeast").GetInt32().Should().Be(2);
        doc.RootElement.TryGetProperty("atMost", out _).Should().BeFalse();
    }

    [Fact]
    public void VerificationTimes_AtMost_SerializesCorrectly()
    {
        var times = VerificationTimes.AtMostTimes(5);

        var json = JsonSerializer.Serialize(times, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.TryGetProperty("atLeast", out _).Should().BeFalse();
        doc.RootElement.GetProperty("atMost").GetInt32().Should().Be(5);
    }

    [Fact]
    public void VerificationTimes_Exactly_SerializesCorrectly()
    {
        var times = VerificationTimes.ExactlyTimes(3);

        var json = JsonSerializer.Serialize(times, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.GetProperty("atLeast").GetInt32().Should().Be(3);
        doc.RootElement.GetProperty("atMost").GetInt32().Should().Be(3);
    }

    [Fact]
    public void VerificationTimes_Once_SerializesCorrectly()
    {
        var times = VerificationTimes.Once();

        var json = JsonSerializer.Serialize(times, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.GetProperty("atLeast").GetInt32().Should().Be(1);
        doc.RootElement.GetProperty("atMost").GetInt32().Should().Be(1);
    }

    [Fact]
    public void Verification_SerializesCorrectly()
    {
        var verification = new { httpRequest = new { method = "GET", path = "/hello" }, times = new { atLeast = 1, atMost = 1 } };

        var json = JsonSerializer.Serialize(verification, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.GetProperty("httpRequest").GetProperty("method").GetString().Should().Be("GET");
        doc.RootElement.GetProperty("httpRequest").GetProperty("path").GetString().Should().Be("/hello");
        doc.RootElement.GetProperty("times").GetProperty("atLeast").GetInt32().Should().Be(1);
    }
}
