using MockServer.Client.Models;

namespace MockServer.Client;

/// <summary>
/// Fluent chain: after When(...), call Respond(...), Forward(...), or Error(...) to complete the expectation.
/// </summary>
public sealed class ForwardChainExpectation
{
    private readonly MockServerClient _client;
    private readonly Expectation _expectation;

    internal ForwardChainExpectation(MockServerClient client, Expectation expectation)
    {
        _client = client;
        _expectation = expectation;
    }

    /// <summary>
    /// Set the expectation ID.
    /// </summary>
    public ForwardChainExpectation WithId(string id)
    {
        _expectation.Id = id;
        return this;
    }

    /// <summary>
    /// Set the expectation priority.
    /// </summary>
    public ForwardChainExpectation WithPriority(int priority)
    {
        _expectation.Priority = priority;
        return this;
    }

    /// <summary>
    /// Complete the expectation with a response action.
    /// </summary>
    public List<Expectation> Respond(HttpResponse response)
    {
        _expectation.HttpResponse = response;
        return _client.UpsertExpectation(_expectation);
    }

    /// <summary>
    /// Complete the expectation with a response action (async).
    /// </summary>
    public Task<List<Expectation>> RespondAsync(HttpResponse response)
    {
        _expectation.HttpResponse = response;
        return _client.UpsertExpectationAsync(_expectation);
    }

    /// <summary>
    /// Complete the expectation with a forward action.
    /// </summary>
    public List<Expectation> Forward(HttpForward forward)
    {
        _expectation.HttpForward = forward;
        return _client.UpsertExpectation(_expectation);
    }

    /// <summary>
    /// Complete the expectation with a forward action (async).
    /// </summary>
    public Task<List<Expectation>> ForwardAsync(HttpForward forward)
    {
        _expectation.HttpForward = forward;
        return _client.UpsertExpectationAsync(_expectation);
    }

    /// <summary>
    /// Complete the expectation with an error action (drops/corrupts the connection).
    /// </summary>
    public List<Expectation> Error(HttpError error)
    {
        _expectation.HttpError = error;
        return _client.UpsertExpectation(_expectation);
    }

    /// <summary>
    /// Complete the expectation with an error action (async).
    /// </summary>
    public Task<List<Expectation>> ErrorAsync(HttpError error)
    {
        _expectation.HttpError = error;
        return _client.UpsertExpectationAsync(_expectation);
    }
}
