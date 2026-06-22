using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// An OpenAPI expectation. Registers expectations on MockServer from an
/// OpenAPI v3 specification — supplied either as an inline payload (JSON or
/// YAML) or a URL/classpath reference via <see cref="SpecUrlOrPayload"/>.
/// </summary>
/// <example>
/// <code>
/// var openApi = OpenApiExpectation.Of("https://example.com/openapi.json")
///     .WithOperationResponse("showPetById", "200")
///     .WithOperationResponse("createPets", "500");
/// client.OpenApiExpectation(openApi);
/// </code>
/// </example>
public sealed class OpenApiExpectation
{
    /// <summary>
    /// The OpenAPI specification: a URL, classpath location, or an inline
    /// JSON/YAML payload.
    /// </summary>
    [JsonPropertyName("specUrlOrPayload")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? SpecUrlOrPayload { get; set; }

    /// <summary>
    /// Optional map of operationId to the response example to return. When
    /// omitted, MockServer generates expectations for every operation.
    /// </summary>
    [JsonPropertyName("operationsAndResponses")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Dictionary<string, string>? OperationsAndResponses { get; set; }

    /// <summary>
    /// Creates a new OpenAPI expectation builder for the given spec URL or
    /// inline payload.
    /// </summary>
    public static OpenApiExpectation Of(string specUrlOrPayload) => new() { SpecUrlOrPayload = specUrlOrPayload };

    /// <summary>
    /// Maps an operationId to the response example MockServer should return for it.
    /// </summary>
    public OpenApiExpectation WithOperationResponse(string operationId, string response)
    {
        OperationsAndResponses ??= new Dictionary<string, string>();
        OperationsAndResponses[operationId] = response;
        return this;
    }
}
