namespace MockServer.Client.Models;

/// <summary>
/// Fluent builder for <see cref="HttpTemplate"/>.
/// </summary>
public sealed class HttpTemplateBuilder
{
    private readonly HttpTemplate _template = new();

    internal HttpTemplateBuilder(TemplateType type)
    {
        _template.TemplateType = type;
    }

    /// <summary>
    /// Sets the inline template content.
    /// </summary>
    public HttpTemplateBuilder WithTemplate(string template)
    {
        _template.Template = template;
        return this;
    }

    /// <summary>
    /// Sets the path to a file containing the template (classpath or filesystem).
    /// The inline template, when present, takes precedence over the file.
    /// </summary>
    public HttpTemplateBuilder WithTemplateFile(string templateFile)
    {
        _template.TemplateFile = templateFile;
        return this;
    }

    public HttpTemplate Build() => _template;

    /// <summary>
    /// Implicit conversion to HttpTemplate for ergonomic use.
    /// </summary>
    public static implicit operator HttpTemplate(HttpTemplateBuilder builder) => builder.Build();
}
