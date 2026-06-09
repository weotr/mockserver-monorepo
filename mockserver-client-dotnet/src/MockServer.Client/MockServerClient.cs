using System.Net.Http;
using System.Text;
using System.Text.Json;
using MockServer.Client.Exceptions;
using MockServer.Client.Models;

namespace MockServer.Client;

/// <summary>
/// Synchronous and asynchronous client for the MockServer control-plane REST API.
/// Uses HttpClient and System.Text.Json internally.
/// </summary>
/// <example>
/// <code>
/// using var client = new MockServerClient("localhost", 1080);
/// client.When(
///     HttpRequest.Request().WithMethod("GET").WithPath("/hello")
/// ).Respond(
///     HttpResponse.Response().WithStatusCode(200).WithBody("world")
/// );
/// client.Verify(HttpRequest.Request().WithPath("/hello"), VerificationTimes.AtLeastTimes(1));
/// client.Reset();
/// </code>
/// </example>
public sealed class MockServerClient : IDisposable
{
    private readonly HttpClient _httpClient;
    private readonly string _baseUrl;
    private readonly bool _ownsHttpClient;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    /// <summary>
    /// Creates a new MockServer client.
    /// </summary>
    /// <param name="host">MockServer host (e.g., "localhost").</param>
    /// <param name="port">MockServer port (default 1080).</param>
    /// <param name="contextPath">Optional context path (e.g., "/mockserver-proxy").</param>
    /// <param name="secure">Use HTTPS if true.</param>
    public MockServerClient(string host, int port = 1080, string contextPath = "", bool secure = false)
    {
        var scheme = secure ? "https" : "http";
        var ctxPath = string.IsNullOrEmpty(contextPath)
            ? ""
            : (contextPath.StartsWith("/") ? contextPath : "/" + contextPath);
        _baseUrl = $"{scheme}://{host}:{port}{ctxPath}";
        _httpClient = new HttpClient { Timeout = TimeSpan.FromSeconds(60) };
        _ownsHttpClient = true;
    }

    /// <summary>
    /// Creates a new MockServer client with a pre-configured HttpClient.
    /// </summary>
    /// <param name="baseUrl">The base URL of the MockServer (e.g., "http://localhost:1080").</param>
    /// <param name="httpClient">A pre-configured HttpClient instance.</param>
    public MockServerClient(string baseUrl, HttpClient httpClient)
    {
        _baseUrl = baseUrl.TrimEnd('/');
        _httpClient = httpClient;
        _ownsHttpClient = false;
    }

    // -------------------------------------------------------------------
    // Fluent API
    // -------------------------------------------------------------------

    /// <summary>
    /// Begin building an expectation with the fluent When/Respond API.
    /// </summary>
    public ForwardChainExpectation When(HttpRequest request, Times? times = null, TimeToLive? timeToLive = null, int? priority = null)
    {
        var expectation = new Expectation
        {
            HttpRequest = request,
            Times = times,
            TimeToLive = timeToLive,
            Priority = priority
        };
        return new ForwardChainExpectation(this, expectation);
    }

    // -------------------------------------------------------------------
    // Core API methods
    // -------------------------------------------------------------------

    /// <summary>
    /// Create or update one or more expectations.
    /// </summary>
    public List<Expectation> Upsert(params Expectation[] expectations)
        => UpsertAsync(expectations).GetAwaiter().GetResult();

    /// <summary>
    /// Create or update one or more expectations (async).
    /// </summary>
    public async Task<List<Expectation>> UpsertAsync(params Expectation[] expectations)
    {
        var json = JsonSerializer.Serialize(expectations, JsonOptions);
        var (statusCode, body) = await PutAsync("/mockserver/expectation", json).ConfigureAwait(false);

        if (statusCode == 400)
            throw new MockServerClientException($"Invalid expectation: {body}");
        if (statusCode >= 400)
            throw new MockServerClientException($"Failed to upsert expectations (HTTP {statusCode}): {body}");

        if (!string.IsNullOrEmpty(body))
        {
            var result = JsonSerializer.Deserialize<List<Expectation>>(body, JsonOptions);
            if (result != null) return result;
        }
        return new List<Expectation>(expectations);
    }

    /// <summary>
    /// Verify that a request has been received a specific number of times.
    /// </summary>
    /// <exception cref="VerificationException">If the verification fails (HTTP 406).</exception>
    public void Verify(HttpRequest request, VerificationTimes? times = null)
        => VerifyAsync(request, times).GetAwaiter().GetResult();

    /// <summary>
    /// Verify that a request has been received a specific number of times (async).
    /// </summary>
    public async Task VerifyAsync(HttpRequest request, VerificationTimes? times = null)
    {
        var verification = new Verification { HttpRequest = request, Times = times };
        var json = JsonSerializer.Serialize(verification, JsonOptions);
        var (statusCode, body) = await PutAsync("/mockserver/verify", json).ConfigureAwait(false);

        if (statusCode == 406)
            throw new VerificationException(body ?? "Verification failed");
        if (statusCode >= 400)
            throw new MockServerClientException($"Failed to verify (HTTP {statusCode}): {body}");
    }

    /// <summary>
    /// Verify that requests were received in a specific sequence.
    /// </summary>
    /// <exception cref="VerificationException">If the verification fails (HTTP 406).</exception>
    public void VerifySequence(params HttpRequest[] requests)
        => VerifySequenceAsync(requests).GetAwaiter().GetResult();

    /// <summary>
    /// Verify that requests were received in a specific sequence (async).
    /// </summary>
    public async Task VerifySequenceAsync(params HttpRequest[] requests)
    {
        var verification = new VerificationSequence { HttpRequests = new List<HttpRequest>(requests) };
        var json = JsonSerializer.Serialize(verification, JsonOptions);
        var (statusCode, body) = await PutAsync("/mockserver/verifySequence", json).ConfigureAwait(false);

        if (statusCode == 406)
            throw new VerificationException(body ?? "Verification sequence failed");
        if (statusCode >= 400)
            throw new MockServerClientException($"Failed to verify sequence (HTTP {statusCode}): {body}");
    }

    /// <summary>
    /// Clear expectations and/or logs matching a request.
    /// </summary>
    /// <param name="request">Optional request matcher to clear. Null clears all.</param>
    /// <param name="type">Type to clear: "all", "log", or "expectations".</param>
    public void Clear(HttpRequest? request = null, string? type = null)
        => ClearAsync(request, type).GetAwaiter().GetResult();

    /// <summary>
    /// Clear expectations and/or logs matching a request (async).
    /// </summary>
    public async Task ClearAsync(HttpRequest? request = null, string? type = null)
    {
        var path = "/mockserver/clear";
        if (!string.IsNullOrEmpty(type))
            path += $"?type={Uri.EscapeDataString(type)}";

        var json = request != null ? JsonSerializer.Serialize(request, JsonOptions) : "";
        var (statusCode, body) = await PutAsync(path, json).ConfigureAwait(false);

        if (statusCode >= 400)
            throw new MockServerClientException($"Failed to clear (HTTP {statusCode}): {body}");
    }

    /// <summary>
    /// Clear by expectation ID.
    /// </summary>
    public void ClearById(string expectationId, string? type = null)
        => ClearByIdAsync(expectationId, type).GetAwaiter().GetResult();

    /// <summary>
    /// Clear by expectation ID (async).
    /// </summary>
    public async Task ClearByIdAsync(string expectationId, string? type = null)
    {
        var path = "/mockserver/clear";
        if (!string.IsNullOrEmpty(type))
            path += $"?type={Uri.EscapeDataString(type)}";

        var json = JsonSerializer.Serialize(new { id = expectationId }, JsonOptions);
        var (statusCode, body) = await PutAsync(path, json).ConfigureAwait(false);

        if (statusCode >= 400)
            throw new MockServerClientException($"Failed to clear by id (HTTP {statusCode}): {body}");
    }

    /// <summary>
    /// Reset all expectations and recorded requests.
    /// </summary>
    public void Reset() => ResetAsync().GetAwaiter().GetResult();

    /// <summary>
    /// Reset all expectations and recorded requests (async).
    /// </summary>
    public async Task ResetAsync()
    {
        var (statusCode, body) = await PutAsync("/mockserver/reset", "").ConfigureAwait(false);

        if (statusCode >= 400)
            throw new MockServerClientException($"Failed to reset (HTTP {statusCode}): {body}");
    }

    /// <summary>
    /// Retrieve recorded requests matching an optional filter.
    /// </summary>
    public List<HttpRequest> RetrieveRecordedRequests(HttpRequest? filter = null)
        => RetrieveRecordedRequestsAsync(filter).GetAwaiter().GetResult();

    /// <summary>
    /// Retrieve recorded requests matching an optional filter (async).
    /// </summary>
    public async Task<List<HttpRequest>> RetrieveRecordedRequestsAsync(HttpRequest? filter = null)
    {
        var json = filter != null ? JsonSerializer.Serialize(filter, JsonOptions) : "";
        var (statusCode, body) = await PutAsync("/mockserver/retrieve?type=requests&format=json", json).ConfigureAwait(false);

        if (statusCode >= 400)
            throw new MockServerClientException($"Failed to retrieve recorded requests (HTTP {statusCode}): {body}");

        if (!string.IsNullOrEmpty(body))
        {
            var result = JsonSerializer.Deserialize<List<HttpRequest>>(body, JsonOptions);
            if (result != null) return result;
        }
        return new List<HttpRequest>();
    }

    /// <summary>
    /// Retrieve active expectations matching an optional filter.
    /// </summary>
    public List<Expectation> RetrieveActiveExpectations(HttpRequest? filter = null)
        => RetrieveActiveExpectationsAsync(filter).GetAwaiter().GetResult();

    /// <summary>
    /// Retrieve active expectations matching an optional filter (async).
    /// </summary>
    public async Task<List<Expectation>> RetrieveActiveExpectationsAsync(HttpRequest? filter = null)
    {
        var json = filter != null ? JsonSerializer.Serialize(filter, JsonOptions) : "";
        var (statusCode, body) = await PutAsync("/mockserver/retrieve?type=active_expectations&format=json", json).ConfigureAwait(false);

        if (statusCode >= 400)
            throw new MockServerClientException($"Failed to retrieve active expectations (HTTP {statusCode}): {body}");

        if (!string.IsNullOrEmpty(body))
        {
            var result = JsonSerializer.Deserialize<List<Expectation>>(body, JsonOptions);
            if (result != null) return result;
        }
        return new List<Expectation>();
    }

    /// <summary>
    /// Retrieve recorded expectations matching an optional filter.
    /// </summary>
    public List<Expectation> RetrieveRecordedExpectations(HttpRequest? filter = null)
        => RetrieveRecordedExpectationsAsync(filter).GetAwaiter().GetResult();

    /// <summary>
    /// Retrieve recorded expectations matching an optional filter (async).
    /// </summary>
    public async Task<List<Expectation>> RetrieveRecordedExpectationsAsync(HttpRequest? filter = null)
    {
        var json = filter != null ? JsonSerializer.Serialize(filter, JsonOptions) : "";
        var (statusCode, body) = await PutAsync("/mockserver/retrieve?type=recorded_expectations&format=json", json).ConfigureAwait(false);

        if (statusCode >= 400)
            throw new MockServerClientException($"Failed to retrieve recorded expectations (HTTP {statusCode}): {body}");

        if (!string.IsNullOrEmpty(body))
        {
            var result = JsonSerializer.Deserialize<List<Expectation>>(body, JsonOptions);
            if (result != null) return result;
        }
        return new List<Expectation>();
    }

    /// <summary>
    /// Retrieve log messages matching an optional filter.
    /// </summary>
    public List<string> RetrieveLogMessages(HttpRequest? filter = null)
        => RetrieveLogMessagesAsync(filter).GetAwaiter().GetResult();

    /// <summary>
    /// Retrieve log messages matching an optional filter (async).
    /// </summary>
    public async Task<List<string>> RetrieveLogMessagesAsync(HttpRequest? filter = null)
    {
        var json = filter != null ? JsonSerializer.Serialize(filter, JsonOptions) : "";
        var (statusCode, body) = await PutAsync("/mockserver/retrieve?type=logs", json).ConfigureAwait(false);

        if (statusCode >= 400)
            throw new MockServerClientException($"Failed to retrieve log messages (HTTP {statusCode}): {body}");

        if (!string.IsNullOrEmpty(body))
        {
            try
            {
                var result = JsonSerializer.Deserialize<List<string>>(body, JsonOptions);
                if (result != null) return result;
            }
            catch (JsonException)
            {
                // Log messages may come as separator-delimited text
                return new List<string>(body.Split(new[] { "------------------------------------\n" }, StringSplitOptions.RemoveEmptyEntries));
            }
        }
        return new List<string>();
    }

    /// <summary>
    /// Check MockServer status (returns bound ports).
    /// </summary>
    public Ports Status() => StatusAsync().GetAwaiter().GetResult();

    /// <summary>
    /// Check MockServer status (async).
    /// </summary>
    public async Task<Ports> StatusAsync()
    {
        var (statusCode, body) = await PutAsync("/mockserver/status", "").ConfigureAwait(false);

        if (statusCode >= 400)
            throw new MockServerClientException($"Failed to get status (HTTP {statusCode}): {body}");

        if (!string.IsNullOrEmpty(body))
        {
            var result = JsonSerializer.Deserialize<Ports>(body, JsonOptions);
            if (result != null) return result;
        }
        return new Ports();
    }

    /// <summary>
    /// Bind additional ports to the MockServer.
    /// </summary>
    public Ports Bind(params int[] ports) => BindAsync(ports).GetAwaiter().GetResult();

    /// <summary>
    /// Bind additional ports (async).
    /// </summary>
    public async Task<Ports> BindAsync(params int[] ports)
    {
        var payload = new Ports { PortList = new List<int>(ports) };
        var json = JsonSerializer.Serialize(payload, JsonOptions);
        var (statusCode, body) = await PutAsync("/mockserver/bind", json).ConfigureAwait(false);

        if (statusCode >= 400)
            throw new MockServerClientException($"Failed to bind ports (HTTP {statusCode}): {body}");

        if (!string.IsNullOrEmpty(body))
        {
            var result = JsonSerializer.Deserialize<Ports>(body, JsonOptions);
            if (result != null) return result;
        }
        return new Ports();
    }

    /// <summary>
    /// Check if MockServer has started (poll with retries).
    /// </summary>
    public bool HasStarted(int attempts = 10, TimeSpan? delay = null)
        => HasStartedAsync(attempts, delay).GetAwaiter().GetResult();

    /// <summary>
    /// Check if MockServer has started (async, with retries).
    /// </summary>
    public async Task<bool> HasStartedAsync(int attempts = 10, TimeSpan? delay = null)
    {
        var wait = delay ?? TimeSpan.FromMilliseconds(500);
        for (int i = 0; i < attempts; i++)
        {
            try
            {
                var (statusCode, _) = await PutAsync("/mockserver/status", "").ConfigureAwait(false);
                if (statusCode == 200) return true;
            }
            catch (HttpRequestException)
            {
                // Server not yet available
            }
            catch (TaskCanceledException)
            {
                // Timeout
            }

            if (i < attempts - 1)
                await Task.Delay(wait).ConfigureAwait(false);
        }
        return false;
    }

    // -------------------------------------------------------------------
    // Internal: called by ForwardChainExpectation
    // -------------------------------------------------------------------

    internal List<Expectation> UpsertExpectation(Expectation expectation)
        => Upsert(expectation);

    internal Task<List<Expectation>> UpsertExpectationAsync(Expectation expectation)
        => UpsertAsync(expectation);

    // -------------------------------------------------------------------
    // HTTP transport
    // -------------------------------------------------------------------

    private async Task<(int StatusCode, string Body)> PutAsync(string path, string jsonBody)
    {
        var url = _baseUrl + path;
        using var content = new StringContent(jsonBody ?? "", Encoding.UTF8, "application/json");
        using var response = await _httpClient.PutAsync(url, content).ConfigureAwait(false);
        var responseBody = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
        return ((int)response.StatusCode, responseBody);
    }

    public void Dispose()
    {
        if (_ownsHttpClient)
            _httpClient.Dispose();
    }
}
