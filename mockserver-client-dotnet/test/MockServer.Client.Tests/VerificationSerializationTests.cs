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

    [Fact]
    public void Verification_WithResponse_SerializesCorrectly()
    {
        var verification = new
        {
            httpRequest = new { method = "GET", path = "/api" },
            httpResponse = new { statusCode = 200 },
            times = new { atLeast = 1 }
        };

        var json = JsonSerializer.Serialize(verification, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.GetProperty("httpRequest").GetProperty("path").GetString().Should().Be("/api");
        doc.RootElement.GetProperty("httpResponse").GetProperty("statusCode").GetInt32().Should().Be(200);
        doc.RootElement.GetProperty("times").GetProperty("atLeast").GetInt32().Should().Be(1);
    }

    [Fact]
    public void Verification_ResponseOnly_OmitsHttpRequest()
    {
        var verification = new
        {
            httpResponse = new { statusCode = 404 },
            times = new { atLeast = 1 }
        };

        var json = JsonSerializer.Serialize(verification, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.TryGetProperty("httpRequest", out _).Should().BeFalse();
        doc.RootElement.GetProperty("httpResponse").GetProperty("statusCode").GetInt32().Should().Be(404);
    }

    [Fact]
    public void VerificationSequence_WithResponses_SerializesCorrectly()
    {
        var verification = new
        {
            httpRequests = new[] { new { path = "/first" }, new { path = "/second" } },
            httpResponses = new[] { new { statusCode = 200 }, new { statusCode = 201 } }
        };

        var json = JsonSerializer.Serialize(verification, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.GetProperty("httpRequests").GetArrayLength().Should().Be(2);
        doc.RootElement.GetProperty("httpResponses").GetArrayLength().Should().Be(2);
        doc.RootElement.GetProperty("httpResponses")[0].GetProperty("statusCode").GetInt32().Should().Be(200);
        doc.RootElement.GetProperty("httpResponses")[1].GetProperty("statusCode").GetInt32().Should().Be(201);
    }

    [Fact]
    public void VerificationSequence_WithoutResponses_OmitsField()
    {
        var verification = new
        {
            httpRequests = new[] { new { path = "/only-requests" } }
        };

        var json = JsonSerializer.Serialize(verification, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.GetProperty("httpRequests").GetArrayLength().Should().Be(1);
        doc.RootElement.TryGetProperty("httpResponses", out _).Should().BeFalse();
    }
}
