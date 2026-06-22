using MockServer.Client.Models;

namespace MockServer.Client.Llm;

/// <summary>
/// Fluent builder for a single LLM mock expectation (mirrors
/// <c>org.mockserver.client.LlmMockBuilder</c>). Builds one expectation whose
/// request matcher is <c>POST &lt;path&gt;</c> and whose action is an
/// <c>httpLlmResponse</c> carrying either a completion or an embedding.
/// </summary>
public sealed class LlmMockBuilder
{
    private readonly string _path;
    private string? _provider;
    private string? _model;
    private Completion? _completion;
    private EmbeddingResponse? _embedding;

    public LlmMockBuilder(string path)
    {
        _path = path;
    }

    /// <summary>Entry point mirroring <c>LlmMockBuilder.llmMock(path)</c>.</summary>
    public static LlmMockBuilder LlmMock(string path) => new(path);

    public LlmMockBuilder WithProvider(string provider)
    {
        _provider = provider;
        return this;
    }

    public LlmMockBuilder WithModel(string model)
    {
        _model = model;
        return this;
    }

    /// <summary>Responds with a completion (clears any previously set embedding).</summary>
    public LlmMockBuilder RespondingWith(Completion completion)
    {
        _completion = completion;
        _embedding = null;
        return this;
    }

    /// <summary>Responds with an embedding (clears any previously set completion).</summary>
    public LlmMockBuilder RespondingWith(EmbeddingResponse embedding)
    {
        _embedding = embedding;
        _completion = null;
        return this;
    }

    public Expectation Build()
    {
        var action = new HttpLlmResponse { Provider = _provider, Model = _model };
        if (_completion != null)
            action.Completion = _completion;
        if (_embedding != null)
            action.Embedding = _embedding;
        return new Expectation
        {
            HttpRequest = new HttpRequest { Method = "POST", Path = _path },
            HttpLlmResponse = action
        };
    }

    public List<Expectation> ApplyTo(MockServerClient client) => client.Upsert(Build());
}
