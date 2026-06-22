using System.Text.Json;
using FluentAssertions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

/// <summary>
/// Serialization tests for OpenAPI import and VerifyZeroInteractions.
/// Wire keys sourced from mockserver-client-node (openAPIExpectation) and
/// mockserver-client-python (open_api_expectation / verify_zero_interactions).
/// </summary>
public class OpenApiAndVerifyZeroTests
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    [Fact]
    public void OpenApiExpectation_WithOperations_SerializesCorrectly()
    {
        var openApi = OpenApiExpectation.Of("https://example.com/petstore.json")
            .WithOperationResponse("showPetById", "200")
            .WithOperationResponse("createPets", "500");

        var json = JsonSerializer.Serialize(openApi, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.GetProperty("specUrlOrPayload").GetString().Should().Be("https://example.com/petstore.json");
        var ops = doc.RootElement.GetProperty("operationsAndResponses");
        ops.GetProperty("showPetById").GetString().Should().Be("200");
        ops.GetProperty("createPets").GetString().Should().Be("500");
    }

    [Fact]
    public void OpenApiExpectation_SpecOnly_OmitsOperations()
    {
        var openApi = OpenApiExpectation.Of("classpath:/openapi.yaml");

        var json = JsonSerializer.Serialize(openApi, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.GetProperty("specUrlOrPayload").GetString().Should().Be("classpath:/openapi.yaml");
        doc.RootElement.TryGetProperty("operationsAndResponses", out _).Should().BeFalse();
    }

    [Fact]
    public void OpenApiExpectation_InlinePayload_SerializesAsString()
    {
        const string payload = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"t\",\"version\":\"1\"}}";
        var openApi = OpenApiExpectation.Of(payload);

        var json = JsonSerializer.Serialize(openApi, JsonOptions);
        var doc = JsonDocument.Parse(json);

        doc.RootElement.GetProperty("specUrlOrPayload").GetString().Should().Be(payload);
    }

    [Fact]
    public void VerifyZeroInteractions_BuildsEmptyRequestWithAtMostZero()
    {
        // Mirrors MockServerClient.VerifyZeroInteractionsAsync: empty request + atMost=0.
        var verification = new Verification
        {
            HttpRequest = HttpRequest.Request().Build(),
            Times = VerificationTimes.AtMostTimes(0)
        };

        var json = JsonSerializer.Serialize(verification, JsonOptions);
        var doc = JsonDocument.Parse(json);

        // Empty request matcher serializes to an empty object (matches any request).
        doc.RootElement.GetProperty("httpRequest").EnumerateObject().Should().BeEmpty();
        doc.RootElement.GetProperty("times").GetProperty("atMost").GetInt32().Should().Be(0);
        doc.RootElement.GetProperty("times").TryGetProperty("atLeast", out _).Should().BeFalse();
    }
}
