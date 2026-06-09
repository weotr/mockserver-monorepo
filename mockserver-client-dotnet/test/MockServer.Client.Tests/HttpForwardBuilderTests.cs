using System.Text.Json;
using FluentAssertions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

public class HttpForwardBuilderTests
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    [Fact]
    public void Forward_WithHostAndPort_SerializesCorrectly()
    {
        var forward = HttpForward.Forward()
            .WithHost("backend.example.com")
            .WithPort(8080)
            .WithScheme("HTTPS")
            .Build();

        var json = JsonSerializer.Serialize(forward, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.GetProperty("host").GetString().Should().Be("backend.example.com");
        doc.RootElement.GetProperty("port").GetInt32().Should().Be(8080);
        doc.RootElement.GetProperty("scheme").GetString().Should().Be("HTTPS");
    }

    [Fact]
    public void Forward_NullFields_AreOmitted()
    {
        var forward = HttpForward.Forward()
            .WithHost("example.com")
            .Build();

        var json = JsonSerializer.Serialize(forward, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.TryGetProperty("port", out _).Should().BeFalse();
        doc.RootElement.TryGetProperty("scheme", out _).Should().BeFalse();
    }

    [Fact]
    public void Forward_ImplicitConversion_Works()
    {
        HttpForward forward = HttpForward.Forward().WithHost("localhost").WithPort(9090);
        forward.Host.Should().Be("localhost");
        forward.Port.Should().Be(9090);
    }
}
