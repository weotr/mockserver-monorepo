using MockServer.Client.Models;

namespace MockServer.Client;

/// <summary>
/// A handle to a single named scenario state-machine, obtained from
/// <see cref="MockServerClient.Scenario"/>. Wraps the scenario REST endpoints:
/// <c>GET/PUT /mockserver/scenario/{name}</c> and <c>PUT /mockserver/scenario/{name}/trigger</c>.
/// </summary>
/// <example>
/// <code>
/// client.Scenario("Deploy").Set("Deploying");
/// client.Scenario("Deploy").Set("Deploying", 5000, "Deployed");
/// client.Scenario("Deploy").Trigger("Failed");
/// var state = client.Scenario("Deploy").State();
/// </code>
/// </example>
public sealed class ScenarioHandle
{
    private readonly MockServerClient _client;
    private readonly string _name;

    internal ScenarioHandle(MockServerClient client, string name)
    {
        _client = client;
        _name = name;
    }

    /// <summary>The scenario name this handle refers to.</summary>
    public string Name => _name;

    /// <summary>
    /// Get the scenario's current state (GET /mockserver/scenario/{name}).
    /// </summary>
    public ScenarioState State() => StateAsync().GetAwaiter().GetResult();

    /// <summary>
    /// Get the scenario's current state (async).
    /// </summary>
    public Task<ScenarioState> StateAsync() => _client.ScenarioStateAsync(_name);

    /// <summary>
    /// Set the scenario's state immediately (PUT /mockserver/scenario/{name}).
    /// </summary>
    /// <param name="state">The state to set.</param>
    public ScenarioState Set(string state) => SetAsync(state).GetAwaiter().GetResult();

    /// <summary>
    /// Set the scenario's state immediately (async).
    /// </summary>
    public Task<ScenarioState> SetAsync(string state)
    {
        if (string.IsNullOrEmpty(state)) throw new ArgumentException("state is required", nameof(state));
        return _client.ScenarioSetAsync(_name, state, null, null);
    }

    /// <summary>
    /// Set the scenario's state, then transition to <paramref name="nextState"/> after
    /// <paramref name="transitionAfterMs"/> milliseconds (PUT /mockserver/scenario/{name}).
    /// </summary>
    /// <param name="state">The state to set now.</param>
    /// <param name="transitionAfterMs">Milliseconds after which to transition to <paramref name="nextState"/>.</param>
    /// <param name="nextState">The state to transition to after the delay.</param>
    public ScenarioState Set(string state, long transitionAfterMs, string nextState)
        => SetAsync(state, transitionAfterMs, nextState).GetAwaiter().GetResult();

    /// <summary>
    /// Set the scenario's state with a timed transition (async).
    /// </summary>
    public Task<ScenarioState> SetAsync(string state, long transitionAfterMs, string nextState)
    {
        if (string.IsNullOrEmpty(state)) throw new ArgumentException("state is required", nameof(state));
        if (string.IsNullOrEmpty(nextState)) throw new ArgumentException("nextState is required", nameof(nextState));
        return _client.ScenarioSetAsync(_name, state, transitionAfterMs, nextState);
    }

    /// <summary>
    /// Trigger a transition to <paramref name="newState"/> (PUT /mockserver/scenario/{name}/trigger).
    /// </summary>
    /// <param name="newState">The state to transition to.</param>
    public ScenarioState Trigger(string newState) => TriggerAsync(newState).GetAwaiter().GetResult();

    /// <summary>
    /// Trigger a transition (async).
    /// </summary>
    public Task<ScenarioState> TriggerAsync(string newState)
    {
        if (string.IsNullOrEmpty(newState)) throw new ArgumentException("newState is required", nameof(newState));
        return _client.ScenarioTriggerAsync(_name, newState);
    }
}
