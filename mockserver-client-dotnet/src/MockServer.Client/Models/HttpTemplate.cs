using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Represents a template action for MockServer (response template or forward template).
/// The template engine processes the template content against the incoming request.
/// </summary>
public sealed class HttpTemplate
{
    [JsonPropertyName("templateType")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    [JsonConverter(typeof(JsonStringEnumConverter))]
    public TemplateType? TemplateType { get; set; }

    [JsonPropertyName("template")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Template { get; set; }

    [JsonPropertyName("templateFile")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? TemplateFile { get; set; }

    /// <summary>
    /// Creates a new HttpTemplate builder with the specified template type.
    /// </summary>
    public static HttpTemplateBuilder OfType(TemplateType type) => new(type);
}

/// <summary>
/// Template engine types supported by MockServer.
/// </summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum TemplateType
{
    JAVASCRIPT,
    VELOCITY,
    MUSTACHE
}
