using System.Text.Json;
using FluentAssertions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

public class HttpResponseBuilderTests
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    [Fact]
    public void Response_WithStatusCodeAndBody_SerializesCorrectly()
    {
        var response = HttpResponse.Response()
            .WithStatusCode(200)
            .WithBody("hello world")
            .Build();

        var json = JsonSerializer.Serialize(response, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.GetProperty("statusCode").GetInt32().Should().Be(200);
        doc.RootElement.GetProperty("body").GetString().Should().Be("hello world");
    }

    [Fact]
    public void Response_WithHeaders_SerializesCorrectly()
    {
        var response = HttpResponse.Response()
            .WithStatusCode(200)
            .WithHeader("Content-Type", "application/json")
            .Build();

        var json = JsonSerializer.Serialize(response, JsonOptions);
        var doc = JsonDocument.Parse(json);

        var headers = doc.RootElement.GetProperty("headers");
        headers.GetProperty("Content-Type")[0].GetString().Should().Be("application/json");
    }

    [Fact]
    public void Response_WithDelay_SerializesCorrectly()
    {
        var response = HttpResponse.Response()
            .WithStatusCode(200)
            .WithDelay(TimeUnit.MILLISECONDS, 500)
            .Build();

        var json = JsonSerializer.Serialize(response, JsonOptions);
        var doc = JsonDocument.Parse(json);

        var delay = doc.RootElement.GetProperty("delay");
        delay.GetProperty("timeUnit").GetString().Should().Be("MILLISECONDS");
        delay.GetProperty("value").GetInt64().Should().Be(500);
    }

    [Fact]
    public void Response_WithJsonBody_SerializesAsTypedObject()
    {
        var response = HttpResponse.Response()
            .WithStatusCode(200)
            .WithJsonBody("{\"result\":true}")
            .Build();

        var json = JsonSerializer.Serialize(response, JsonOptions);
        var doc = JsonDocument.Parse(json);

        var body = doc.RootElement.GetProperty("body");
        body.GetProperty("type").GetString().Should().Be("JSON");
        body.GetProperty("json").GetString().Should().Be("{\"result\":true}");
    }

    [Fact]
    public void Response_NullFields_AreOmitted()
    {
        var response = HttpResponse.Response()
            .WithStatusCode(200)
            .Build();

        var json = JsonSerializer.Serialize(response, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.TryGetProperty("headers", out _).Should().BeFalse();
        doc.RootElement.TryGetProperty("body", out _).Should().BeFalse();
        doc.RootElement.TryGetProperty("delay", out _).Should().BeFalse();
        doc.RootElement.TryGetProperty("reasonPhrase", out _).Should().BeFalse();
    }

    [Fact]
    public void Response_ImplicitConversion_Works()
    {
        HttpResponse response = HttpResponse.Response().WithStatusCode(404);
        response.StatusCode.Should().Be(404);
    }
}
