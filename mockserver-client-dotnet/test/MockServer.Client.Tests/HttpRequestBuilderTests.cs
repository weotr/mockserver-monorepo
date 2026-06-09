using System.Text.Json;
using FluentAssertions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

public class HttpRequestBuilderTests
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    [Fact]
    public void Request_WithMethodAndPath_SerializesCorrectly()
    {
        var request = HttpRequest.Request()
            .WithMethod("GET")
            .WithPath("/hello")
            .Build();

        var json = JsonSerializer.Serialize(request, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.GetProperty("method").GetString().Should().Be("GET");
        doc.RootElement.GetProperty("path").GetString().Should().Be("/hello");
    }

    [Fact]
    public void Request_WithQueryStringParameters_SerializesCorrectly()
    {
        var request = HttpRequest.Request()
            .WithMethod("GET")
            .WithPath("/search")
            .WithQueryStringParameter("q", "mockserver")
            .WithQueryStringParameter("page", "1", "2")
            .Build();

        var json = JsonSerializer.Serialize(request, JsonOptions);
        var doc = JsonDocument.Parse(json);

        var qsp = doc.RootElement.GetProperty("queryStringParameters");
        qsp.GetProperty("q")[0].GetString().Should().Be("mockserver");
        qsp.GetProperty("page")[0].GetString().Should().Be("1");
        qsp.GetProperty("page")[1].GetString().Should().Be("2");
    }

    [Fact]
    public void Request_WithHeaders_SerializesCorrectly()
    {
        var request = HttpRequest.Request()
            .WithPath("/api")
            .WithHeader("Content-Type", "application/json")
            .WithHeader("Accept", "text/html", "application/json")
            .Build();

        var json = JsonSerializer.Serialize(request, JsonOptions);
        var doc = JsonDocument.Parse(json);

        var headers = doc.RootElement.GetProperty("headers");
        headers.GetProperty("Content-Type")[0].GetString().Should().Be("application/json");
        headers.GetProperty("Accept")[0].GetString().Should().Be("text/html");
        headers.GetProperty("Accept")[1].GetString().Should().Be("application/json");
    }

    [Fact]
    public void Request_WithStringBody_SerializesAsString()
    {
        var request = HttpRequest.Request()
            .WithPath("/api")
            .WithBody("hello world")
            .Build();

        var json = JsonSerializer.Serialize(request, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.GetProperty("body").GetString().Should().Be("hello world");
    }

    [Fact]
    public void Request_WithJsonBody_SerializesAsTypedObject()
    {
        var request = HttpRequest.Request()
            .WithPath("/api")
            .WithJsonBody("{\"key\":\"value\"}")
            .Build();

        var json = JsonSerializer.Serialize(request, JsonOptions);
        var doc = JsonDocument.Parse(json);

        var body = doc.RootElement.GetProperty("body");
        body.GetProperty("type").GetString().Should().Be("JSON");
        body.GetProperty("json").GetString().Should().Be("{\"key\":\"value\"}");
    }

    [Fact]
    public void Request_NullFields_AreOmitted()
    {
        var request = HttpRequest.Request()
            .WithPath("/hello")
            .Build();

        var json = JsonSerializer.Serialize(request, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.TryGetProperty("method", out _).Should().BeFalse();
        doc.RootElement.TryGetProperty("headers", out _).Should().BeFalse();
        doc.RootElement.TryGetProperty("queryStringParameters", out _).Should().BeFalse();
        doc.RootElement.TryGetProperty("body", out _).Should().BeFalse();
    }

    [Fact]
    public void Request_ImplicitConversion_Works()
    {
        HttpRequest request = HttpRequest.Request().WithPath("/hello");
        request.Path.Should().Be("/hello");
    }
}
