namespace MockServer.Client.Models;

/// <summary>
/// Fluent builder for <see cref="HttpResponse"/>.
/// </summary>
public sealed class HttpResponseBuilder
{
    private readonly HttpResponse _response = new();

    public HttpResponseBuilder WithStatusCode(int statusCode)
    {
        _response.StatusCode = statusCode;
        return this;
    }

    public HttpResponseBuilder WithReasonPhrase(string reasonPhrase)
    {
        _response.ReasonPhrase = reasonPhrase;
        return this;
    }

    public HttpResponseBuilder WithHeader(string name, params string[] values)
    {
        _response.Headers ??= new Dictionary<string, List<string>>();
        _response.Headers[name] = new List<string>(values);
        return this;
    }

    /// <summary>
    /// Sets a plain string response body.
    /// </summary>
    public HttpResponseBuilder WithBody(string body)
    {
        _response.Body = body;
        return this;
    }

    /// <summary>
    /// Sets a typed JSON response body.
    /// </summary>
    public HttpResponseBuilder WithJsonBody(string json)
    {
        _response.Body = new TypedBody { Type = "JSON", Json = json };
        return this;
    }

    public HttpResponseBuilder WithDelay(TimeUnit timeUnit, long value)
    {
        _response.Delay = new Delay { TimeUnit = timeUnit, Value = value };
        return this;
    }

    public HttpResponseBuilder WithConnectionOptions(ConnectionOptions connectionOptions)
    {
        _response.ConnectionOptions = connectionOptions;
        return this;
    }

    public HttpResponse Build() => _response;

    /// <summary>
    /// Implicit conversion to HttpResponse for ergonomic use.
    /// </summary>
    public static implicit operator HttpResponse(HttpResponseBuilder builder) => builder.Build();
}
