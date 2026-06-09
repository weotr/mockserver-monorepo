using System.Text.Json;
using FluentAssertions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

public class ExpectationSerializationTests
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    [Fact]
    public void Expectation_FullShape_SerializesCorrectly()
    {
        var expectation = new Expectation
        {
            Id = "test-id",
            Priority = 5,
            HttpRequest = HttpRequest.Request()
                .WithMethod("GET")
                .WithPath("/hello")
                .WithQueryStringParameter("q", "x")
                .WithHeader("H", "v")
                .Build(),
            HttpResponse = HttpResponse.Response()
                .WithStatusCode(200)
                .WithHeader("Content-Type", "application/json")
                .WithBody("{\"k\":1}")
                .WithDelay(TimeUnit.MILLISECONDS, 0)
                .Build(),
            Times = Times.Exactly(1),
            TimeToLive = TimeToLive.UnlimitedTtl()
        };

        var json = JsonSerializer.Serialize(expectation, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.GetProperty("id").GetString().Should().Be("test-id");
        doc.RootElement.GetProperty("priority").GetInt32().Should().Be(5);

        var req = doc.RootElement.GetProperty("httpRequest");
        req.GetProperty("method").GetString().Should().Be("GET");
        req.GetProperty("path").GetString().Should().Be("/hello");
        req.GetProperty("queryStringParameters").GetProperty("q")[0].GetString().Should().Be("x");
        req.GetProperty("headers").GetProperty("H")[0].GetString().Should().Be("v");

        var resp = doc.RootElement.GetProperty("httpResponse");
        resp.GetProperty("statusCode").GetInt32().Should().Be(200);
        resp.GetProperty("headers").GetProperty("Content-Type")[0].GetString().Should().Be("application/json");
        resp.GetProperty("body").GetString().Should().Be("{\"k\":1}");

        var times = doc.RootElement.GetProperty("times");
        times.GetProperty("remainingTimes").GetInt32().Should().Be(1);
        times.GetProperty("unlimited").GetBoolean().Should().BeFalse();

        var ttl = doc.RootElement.GetProperty("timeToLive");
        ttl.GetProperty("unlimited").GetBoolean().Should().BeTrue();
    }

    [Fact]
    public void Expectation_WithForward_SerializesCorrectly()
    {
        var expectation = new Expectation
        {
            HttpRequest = HttpRequest.Request().WithPath("/proxy").Build(),
            HttpForward = HttpForward.Forward()
                .WithHost("backend.example.com")
                .WithPort(443)
                .WithScheme("HTTPS")
                .Build()
        };

        var json = JsonSerializer.Serialize(expectation, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.TryGetProperty("httpResponse", out _).Should().BeFalse();
        var fwd = doc.RootElement.GetProperty("httpForward");
        fwd.GetProperty("host").GetString().Should().Be("backend.example.com");
        fwd.GetProperty("port").GetInt32().Should().Be(443);
        fwd.GetProperty("scheme").GetString().Should().Be("HTTPS");
    }

    [Fact]
    public void Expectation_WithError_SerializesCorrectly()
    {
        var expectation = new Expectation
        {
            HttpRequest = HttpRequest.Request().WithPath("/error").Build(),
            HttpError = HttpError.Error().WithDropConnection(true).Build()
        };

        var json = JsonSerializer.Serialize(expectation, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.TryGetProperty("httpResponse", out _).Should().BeFalse();
        doc.RootElement.TryGetProperty("httpForward", out _).Should().BeFalse();
        var err = doc.RootElement.GetProperty("httpError");
        err.GetProperty("dropConnection").GetBoolean().Should().BeTrue();
    }

    [Fact]
    public void Expectation_Array_SerializesCorrectly()
    {
        var expectations = new[]
        {
            new Expectation
            {
                HttpRequest = HttpRequest.Request().WithPath("/one").Build(),
                HttpResponse = HttpResponse.Response().WithStatusCode(200).Build()
            },
            new Expectation
            {
                HttpRequest = HttpRequest.Request().WithPath("/two").Build(),
                HttpResponse = HttpResponse.Response().WithStatusCode(201).Build()
            }
        };

        var json = JsonSerializer.Serialize(expectations, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.GetArrayLength().Should().Be(2);
        doc.RootElement[0].GetProperty("httpRequest").GetProperty("path").GetString().Should().Be("/one");
        doc.RootElement[1].GetProperty("httpRequest").GetProperty("path").GetString().Should().Be("/two");
    }

    [Fact]
    public void Times_Helpers_Work()
    {
        var once = Times.Once();
        once.RemainingTimes.Should().Be(1);
        once.IsUnlimited.Should().BeFalse();

        var unlimited = Times.Unlimited();
        unlimited.IsUnlimited.Should().BeTrue();

        var exactly5 = Times.Exactly(5);
        exactly5.RemainingTimes.Should().Be(5);
        exactly5.IsUnlimited.Should().BeFalse();
    }

    [Fact]
    public void TimeToLive_Helpers_Work()
    {
        var unlimited = TimeToLive.UnlimitedTtl();
        unlimited.Unlimited.Should().BeTrue();

        var exactly = TimeToLive.ExactlyTtl(TimeUnit.SECONDS, 30);
        exactly.TimeUnit.Should().Be(TimeUnit.SECONDS);
        exactly.Value.Should().Be(30);
        exactly.Unlimited.Should().BeFalse();
    }
}
