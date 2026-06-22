using MockServer.Client.Models;

namespace MockServer.Client.Llm;

/// <summary>
/// Builder for multi-turn LLM conversation mocks with MockServer scenario-state
/// advancement (mirrors <c>org.mockserver.client.LlmConversationBuilder</c>).
/// </summary>
public sealed class LlmConversationBuilder
{
    internal const string ScenarioPrefix = "__llm_conv_";
    internal const string IsolationMarker = "__iso=";
    internal const string DoneState = "__done";

    private string? _path;
    private string? _provider;
    private string? _model;
    private IsolationSource? _isolationSource;
    private readonly List<TurnBuilder> _turns = new();

    /// <summary>Entry point mirroring <c>LlmConversationBuilder.conversation()</c>.</summary>
    public static LlmConversationBuilder Conversation() => new();

    public LlmConversationBuilder WithPath(string path)
    {
        _path = path;
        return this;
    }

    public LlmConversationBuilder WithProvider(string provider)
    {
        _provider = provider;
        return this;
    }

    public LlmConversationBuilder WithModel(string model)
    {
        _model = model;
        return this;
    }

    public LlmConversationBuilder IsolateBy(IsolationSource source)
    {
        _isolationSource = source;
        return this;
    }

    /// <summary>Begins a new turn and returns its sub-builder.</summary>
    public TurnBuilder Turn()
    {
        var turnBuilder = new TurnBuilder(this);
        _turns.Add(turnBuilder);
        return turnBuilder;
    }

    public List<Expectation> Build()
    {
        if (_turns.Count == 0)
            throw new InvalidOperationException("At least one turn must be defined");
        if (string.IsNullOrEmpty(_path))
            throw new InvalidOperationException("Path must be set");
        if (string.IsNullOrEmpty(_provider))
            throw new InvalidOperationException("Provider must be set");

        var conversationId = ScenarioPrefix + Guid.NewGuid();
        var scenarioName = conversationId;
        if (_isolationSource != null)
            scenarioName = conversationId + IsolationMarker + _isolationSource.Encode();

        var expectations = new List<Expectation>();
        var n = _turns.Count;
        for (var i = 0; i < n; i++)
        {
            var turn = _turns[i];
            var nextState = i < n - 1 ? "turn_" + (i + 1) : DoneState;

            var action = new HttpLlmResponse { Provider = _provider, Model = _model };
            if (turn.Completion != null)
                action.Completion = turn.Completion;
            if (turn.Chaos != null)
                action.Chaos = turn.Chaos;

            var predicates = turn.BuildPredicates();
            if (predicates.HasAnyPredicate)
                action.ConversationPredicates = predicates;

            expectations.Add(new Expectation
            {
                HttpRequest = new HttpRequest { Method = "POST", Path = _path },
                ScenarioName = scenarioName,
                ScenarioState = i == 0 ? "Started" : "turn_" + i,
                NewScenarioState = nextState,
                HttpLlmResponse = action
            });
        }
        return expectations;
    }

    public List<Expectation> ApplyTo(MockServerClient client) => client.Upsert(Build().ToArray());
}

/// <summary>
/// Sub-builder configuring one turn of a conversation mock
/// (mirrors <c>org.mockserver.client.TurnBuilder</c>).
/// </summary>
public sealed class TurnBuilder
{
    private readonly LlmConversationBuilder _parent;

    internal int? TurnIndex { get; private set; }
    internal string? LatestMessageContains { get; private set; }
    internal string? LatestMessageMatches { get; private set; }
    internal string? LatestMessageRole { get; private set; }
    internal string? ContainsToolResultFor { get; private set; }
    internal string? SemanticMatchAgainst { get; private set; }
    internal NormalizationOptions? Normalization { get; private set; }
    internal object? Chaos { get; private set; }
    internal Completion? Completion { get; private set; }

    internal TurnBuilder(LlmConversationBuilder parent)
    {
        _parent = parent;
    }

    public TurnBuilder WhenTurnIndex(int index)
    {
        TurnIndex = index;
        return this;
    }

    public TurnBuilder WhenLatestMessageContains(string text)
    {
        LatestMessageContains = text;
        return this;
    }

    public TurnBuilder WhenLatestMessageMatches(string regex)
    {
        LatestMessageMatches = regex ?? throw new ArgumentNullException(nameof(regex));
        return this;
    }

    public TurnBuilder WhenLatestMessageRole(string role)
    {
        LatestMessageRole = role;
        return this;
    }

    public TurnBuilder WhenContainsToolResultFor(string toolName)
    {
        ContainsToolResultFor = toolName;
        return this;
    }

    public TurnBuilder WhenSemanticMatch(string expectedMeaning)
    {
        SemanticMatchAgainst = expectedMeaning;
        return this;
    }

    public TurnBuilder WithNormalization(NormalizationOptions normalization)
    {
        Normalization = normalization;
        return this;
    }

    public TurnBuilder WithChaos(object chaos)
    {
        Chaos = chaos;
        return this;
    }

    public TurnBuilder RespondingWith(Completion completion)
    {
        Completion = completion;
        return this;
    }

    /// <summary>Begins a new turn on the parent conversation.</summary>
    public TurnBuilder Turn() => _parent.Turn();

    /// <summary>Returns to the parent conversation builder.</summary>
    public LlmConversationBuilder AndThen() => _parent;

    public List<Expectation> Build() => _parent.Build();

    public List<Expectation> ApplyTo(MockServerClient client) => _parent.ApplyTo(client);

    internal ConversationPredicates BuildPredicates() => new()
    {
        TurnIndex = TurnIndex,
        LatestMessageContains = LatestMessageContains,
        LatestMessageMatches = LatestMessageMatches,
        LatestMessageRole = LatestMessageRole,
        ContainsToolResultFor = ContainsToolResultFor,
        SemanticMatchAgainst = SemanticMatchAgainst,
        Normalization = Normalization
    };
}
