using MockServer.Client.Models;

namespace MockServer.Client.Llm;

/// <summary>
/// Builder for provider failover/retry scenarios (mirrors
/// <c>org.mockserver.client.LlmFailoverBuilder</c>).
///
/// Produces an ordered list of expectations: limited-<c>times</c> failure
/// expectations first (consumed before the success), then a single
/// unlimited-<c>times</c> success expectation carrying an <c>httpLlmResponse</c>.
/// Consecutive identical failures are coalesced into one expectation with
/// <c>times.remainingTimes = count</c>.
/// </summary>
public sealed class LlmFailoverBuilder
{
    private sealed class FailureSpec
    {
        public FailureSpec(int statusCode, string? errorBody)
        {
            StatusCode = statusCode;
            ErrorBody = errorBody;
        }

        public int StatusCode { get; }
        public string? ErrorBody { get; }
    }

    private string? _path;
    private string? _provider;
    private string? _model;
    private readonly List<FailureSpec> _failures = new();
    private Completion? _successCompletion;

    /// <summary>Entry point mirroring <c>LlmFailoverBuilder.llmFailover()</c>.</summary>
    public static LlmFailoverBuilder LlmFailover() => new();

    public LlmFailoverBuilder WithPath(string path)
    {
        _path = path;
        return this;
    }

    public LlmFailoverBuilder WithProvider(string provider)
    {
        _provider = provider;
        return this;
    }

    public LlmFailoverBuilder WithModel(string model)
    {
        _model = model;
        return this;
    }

    /// <summary>Adds one failure attempt with the default error body for the status code.</summary>
    public LlmFailoverBuilder FailWith(int statusCode)
    {
        ValidateStatusCode(statusCode);
        _failures.Add(new FailureSpec(statusCode, null));
        return this;
    }

    /// <summary>Adds one failure attempt with a custom JSON error body.</summary>
    public LlmFailoverBuilder FailWith(int statusCode, string errorBody)
    {
        ValidateStatusCode(statusCode);
        _failures.Add(new FailureSpec(statusCode, errorBody));
        return this;
    }

    /// <summary>Adds <paramref name="count"/> failure attempts with the default error body.</summary>
    public LlmFailoverBuilder FailWith(int statusCode, int count)
    {
        ValidateStatusCode(statusCode);
        if (count < 1)
            throw new ArgumentException($"count must be >= 1, got {count}", nameof(count));
        for (var i = 0; i < count; i++)
            _failures.Add(new FailureSpec(statusCode, null));
        return this;
    }

    public LlmFailoverBuilder ThenRespondWith(Completion completion)
    {
        _successCompletion = completion;
        return this;
    }

    public int GetFailureCount() => _failures.Count;

    public List<Expectation> Build()
    {
        if (string.IsNullOrEmpty(_path))
            throw new InvalidOperationException("Path must be set");
        if (string.IsNullOrEmpty(_provider))
            throw new InvalidOperationException("Provider must be set");
        if (_failures.Count == 0)
            throw new InvalidOperationException("At least one failure must be defined");
        if (_successCompletion == null)
            throw new InvalidOperationException("Success completion must be set via ThenRespondWith()");

        var expectations = new List<Expectation>();
        foreach (var (statusCode, errorBody, count) in Coalesce())
        {
            var body = errorBody ?? DefaultErrorBody(statusCode);
            expectations.Add(new Expectation
            {
                HttpRequest = new HttpRequest { Method = "POST", Path = _path },
                Times = Times.Exactly(count),
                TimeToLive = TimeToLive.UnlimitedTtl(),
                HttpResponse = new HttpResponse
                {
                    StatusCode = statusCode,
                    Headers = new Dictionary<string, List<string>>
                    {
                        ["Content-Type"] = new() { "application/json" }
                    },
                    Body = body
                }
            });
        }

        expectations.Add(new Expectation
        {
            HttpRequest = new HttpRequest { Method = "POST", Path = _path },
            Times = Times.Unlimited(),
            TimeToLive = TimeToLive.UnlimitedTtl(),
            HttpLlmResponse = new HttpLlmResponse
            {
                Provider = _provider,
                Model = _model,
                Completion = _successCompletion
            }
        });

        return expectations;
    }

    public List<Expectation> ApplyTo(MockServerClient client) => client.Upsert(Build().ToArray());

    private static void ValidateStatusCode(int statusCode)
    {
        if (statusCode < 100 || statusCode > 599)
            throw new ArgumentException($"statusCode must be between 100 and 599, got {statusCode}", nameof(statusCode));
    }

    private IEnumerable<(int StatusCode, string? ErrorBody, int Count)> Coalesce()
    {
        var result = new List<(int StatusCode, string? ErrorBody, int Count)>();
        foreach (var spec in _failures)
        {
            if (result.Count > 0)
            {
                var last = result[result.Count - 1];
                if (last.StatusCode == spec.StatusCode && last.ErrorBody == spec.ErrorBody)
                {
                    result[result.Count - 1] = (last.StatusCode, last.ErrorBody, last.Count + 1);
                    continue;
                }
            }
            result.Add((spec.StatusCode, spec.ErrorBody, 1));
        }
        return result;
    }

    /// <summary>
    /// Default JSON error body for a status code (exposed for testing / parity).
    /// Shape: <c>{"error":{"type":&lt;type&gt;,"message":&lt;message&gt;}}</c>.
    /// </summary>
    public static string DefaultErrorBody(int statusCode)
    {
        var (type, message) = statusCode switch
        {
            429 => ("rate_limit_error", "Rate limit exceeded. Please retry after a brief wait."),
            500 => ("internal_server_error", "An internal error occurred. Please retry your request."),
            502 => ("bad_gateway", "Bad gateway. The upstream server returned an invalid response."),
            503 => ("service_unavailable", "The service is temporarily overloaded. Please retry later."),
            _ => ("error", $"Request failed with status {statusCode}")
        };
        return "{\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}";
    }
}
