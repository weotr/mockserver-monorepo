using System.Text.Json.Serialization;
using MockServer.Client.Models;

namespace MockServer.Client.Llm;

/// <summary>
/// LLM provider names. Serialized on the wire as the upper-case enum name
/// (mirrors <c>org.mockserver.model.Provider</c>).
/// </summary>
public static class Provider
{
    public const string ANTHROPIC = "ANTHROPIC";
    public const string OPENAI = "OPENAI";
    public const string OPENAI_RESPONSES = "OPENAI_RESPONSES";
    public const string GEMINI = "GEMINI";
    public const string BEDROCK = "BEDROCK";
    public const string AZURE_OPENAI = "AZURE_OPENAI";
    public const string OLLAMA = "OLLAMA";
}

/// <summary>
/// Parsed-message roles (mirrors <c>org.mockserver.llm.ParsedMessage.Role</c>).
/// </summary>
public static class Role
{
    public const string USER = "USER";
    public const string ASSISTANT = "ASSISTANT";
    public const string TOOL = "TOOL";
    public const string SYSTEM = "SYSTEM";
}

/// <summary>
/// A single tool/function call emitted by the assistant
/// (mirrors <c>org.mockserver.model.ToolUse</c>).
/// </summary>
public sealed class ToolUse
{
    [JsonPropertyName("id")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Id { get; set; }

    [JsonPropertyName("name")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Name { get; set; }

    /// <summary>The call arguments as a JSON string.</summary>
    [JsonPropertyName("arguments")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Arguments { get; set; }

    /// <summary>Factory mirroring <c>ToolUse.toolUse(name)</c>.</summary>
    public static ToolUse Of(string name) => new() { Name = name };

    public ToolUse WithId(string id)
    {
        Id = id;
        return this;
    }

    public ToolUse WithName(string name)
    {
        Name = name;
        return this;
    }

    /// <summary>Sets the call arguments as an already-serialized JSON string.</summary>
    public ToolUse WithArguments(string argumentsJson)
    {
        Arguments = argumentsJson;
        return this;
    }
}

/// <summary>
/// Token usage counts for a completion (mirrors <c>org.mockserver.model.Usage</c>).
/// </summary>
public sealed class Usage
{
    [JsonPropertyName("inputTokens")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? InputTokens { get; set; }

    [JsonPropertyName("outputTokens")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? OutputTokens { get; set; }

    public static Usage Create() => new();

    public Usage WithInputTokens(int inputTokens)
    {
        if (inputTokens < 0)
            throw new ArgumentException("inputTokens must be >= 0", nameof(inputTokens));
        InputTokens = inputTokens;
        return this;
    }

    public Usage WithOutputTokens(int outputTokens)
    {
        if (outputTokens < 0)
            throw new ArgumentException("outputTokens must be >= 0", nameof(outputTokens));
        OutputTokens = outputTokens;
        return this;
    }
}

/// <summary>
/// Controls the timing physics of a streamed (SSE) completion
/// (mirrors <c>org.mockserver.model.StreamingPhysics</c>).
/// </summary>
public sealed class StreamingPhysics
{
    [JsonPropertyName("timeToFirstToken")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? TimeToFirstToken { get; set; }

    [JsonPropertyName("tokensPerSecond")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? TokensPerSecond { get; set; }

    [JsonPropertyName("jitter")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? Jitter { get; set; }

    [JsonPropertyName("seed")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? Seed { get; set; }

    public static StreamingPhysics Create() => new();

    public StreamingPhysics WithTimeToFirstToken(Delay delay)
    {
        TimeToFirstToken = delay;
        return this;
    }

    public StreamingPhysics WithTimeToFirstToken(long value, TimeUnit timeUnit = TimeUnit.MILLISECONDS)
    {
        TimeToFirstToken = new Delay { TimeUnit = timeUnit, Value = value };
        return this;
    }

    public StreamingPhysics WithTokensPerSecond(int tokensPerSecond)
    {
        if (tokensPerSecond < 1 || tokensPerSecond > 10000)
            throw new ArgumentException("tokensPerSecond must be between 1 and 10000", nameof(tokensPerSecond));
        TokensPerSecond = tokensPerSecond;
        return this;
    }

    public StreamingPhysics WithJitter(double jitter)
    {
        if (jitter < 0.0 || jitter > 1.0)
            throw new ArgumentException("jitter must be between 0.0 and 1.0", nameof(jitter));
        Jitter = jitter;
        return this;
    }

    public StreamingPhysics WithSeed(long seed)
    {
        Seed = seed;
        return this;
    }
}

/// <summary>
/// A mocked LLM chat/completion response, provider-agnostic
/// (mirrors <c>org.mockserver.model.Completion</c>).
/// </summary>
public sealed class Completion
{
    [JsonPropertyName("text")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Text { get; set; }

    [JsonPropertyName("toolCalls")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<ToolUse>? ToolCalls { get; set; }

    [JsonPropertyName("stopReason")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? StopReason { get; set; }

    [JsonPropertyName("usage")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Usage? Usage { get; set; }

    [JsonPropertyName("streaming")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? Streaming { get; set; }

    [JsonPropertyName("streamingPhysics")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public StreamingPhysics? StreamingPhysics { get; set; }

    /// <summary>An output JSON schema as a JSON string.</summary>
    [JsonPropertyName("outputSchema")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? OutputSchema { get; set; }

    [JsonPropertyName("model")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Model { get; set; }

    public static Completion Create() => new();

    public Completion WithText(string text)
    {
        Text = text;
        return this;
    }

    public Completion WithToolCall(ToolUse toolCall)
    {
        ToolCalls ??= new List<ToolUse>();
        ToolCalls.Add(toolCall);
        return this;
    }

    public Completion WithToolCalls(params ToolUse[] toolCalls)
    {
        ToolCalls = new List<ToolUse>(toolCalls);
        return this;
    }

    public Completion WithStopReason(string stopReason)
    {
        StopReason = stopReason;
        return this;
    }

    public Completion WithUsage(Usage usage)
    {
        Usage = usage;
        return this;
    }

    public Completion WithStreaming(bool streaming = true)
    {
        Streaming = streaming;
        return this;
    }

    /// <summary>Mirror of Java <c>completion().streaming()</c> — enables streaming.</summary>
    public Completion Stream() => WithStreaming(true);

    /// <summary>
    /// Sets the streaming physics. Mirrors Java: does NOT toggle <see cref="Streaming"/> —
    /// enable streaming explicitly via <see cref="WithStreaming"/> / <see cref="Stream"/>.
    /// </summary>
    public Completion WithStreamingPhysics(StreamingPhysics physics)
    {
        StreamingPhysics = physics;
        return this;
    }

    /// <summary>Sets the output JSON schema as an already-serialized JSON string.</summary>
    public Completion WithOutputSchema(string outputSchemaJson)
    {
        OutputSchema = outputSchemaJson;
        return this;
    }

    public Completion WithModel(string model)
    {
        Model = model;
        return this;
    }
}

/// <summary>
/// A mocked embedding response (vector shape, determinism)
/// (mirrors <c>org.mockserver.model.EmbeddingResponse</c>).
/// </summary>
public sealed class EmbeddingResponse
{
    [JsonPropertyName("dimensions")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? Dimensions { get; set; }

    [JsonPropertyName("deterministicFromInput")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? DeterministicFromInput { get; set; }

    [JsonPropertyName("seed")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? Seed { get; set; }

    public static EmbeddingResponse Create() => new();

    public EmbeddingResponse WithDimensions(int dimensions)
    {
        Dimensions = dimensions;
        return this;
    }

    public EmbeddingResponse WithDeterministicFromInput(bool deterministic)
    {
        DeterministicFromInput = deterministic;
        return this;
    }

    public EmbeddingResponse WithSeed(long seed)
    {
        Seed = seed;
        return this;
    }
}

/// <summary>
/// Opt-in prompt normalisation applied before text predicates
/// (mirrors <c>org.mockserver.model.NormalizationOptions</c>).
/// </summary>
public sealed class NormalizationOptions
{
    [JsonPropertyName("collapseWhitespace")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? CollapseWhitespace { get; set; }

    [JsonPropertyName("lowercase")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? Lowercase { get; set; }

    [JsonPropertyName("sortJsonKeys")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? SortJsonKeys { get; set; }

    [JsonPropertyName("dropBuiltInVolatileFields")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? DropBuiltInVolatileFields { get; set; }

    [JsonPropertyName("dropVolatileFields")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<string>? DropVolatileFields { get; set; }

    public static NormalizationOptions Create() => new();

    public NormalizationOptions WithCollapseWhitespace(bool value = true)
    {
        CollapseWhitespace = value;
        return this;
    }

    public NormalizationOptions WithLowercase(bool value = true)
    {
        Lowercase = value;
        return this;
    }

    public NormalizationOptions WithSortJsonKeys(bool value = true)
    {
        SortJsonKeys = value;
        return this;
    }

    public NormalizationOptions WithDropBuiltInVolatileFields(bool value = true)
    {
        DropBuiltInVolatileFields = value;
        return this;
    }

    public NormalizationOptions WithDropVolatileFields(params string[] fields)
    {
        DropVolatileFields = new List<string>(fields);
        return this;
    }
}

/// <summary>
/// Serialisable predicate descriptors for LLM conversation matching
/// (mirrors <c>org.mockserver.model.ConversationPredicates</c>).
/// </summary>
public sealed class ConversationPredicates
{
    [JsonPropertyName("turnIndex")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? TurnIndex { get; set; }

    [JsonPropertyName("latestMessageContains")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? LatestMessageContains { get; set; }

    [JsonPropertyName("latestMessageMatches")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? LatestMessageMatches { get; set; }

    [JsonPropertyName("latestMessageRole")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? LatestMessageRole { get; set; }

    [JsonPropertyName("containsToolResultFor")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? ContainsToolResultFor { get; set; }

    [JsonPropertyName("semanticMatchAgainst")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? SemanticMatchAgainst { get; set; }

    [JsonPropertyName("normalization")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public NormalizationOptions? Normalization { get; set; }

    /// <summary>True if at least one predicate (not normalization) is set.</summary>
    [JsonIgnore]
    public bool HasAnyPredicate =>
        TurnIndex != null
        || LatestMessageContains != null
        || LatestMessageMatches != null
        || LatestMessageRole != null
        || ContainsToolResultFor != null
        || SemanticMatchAgainst != null;
}

/// <summary>
/// The <c>httpLlmResponse</c> action payload of an LLM expectation
/// (mirrors <c>org.mockserver.model.HttpLlmResponse</c>).
/// </summary>
public sealed class HttpLlmResponse
{
    [JsonPropertyName("provider")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Provider { get; set; }

    [JsonPropertyName("model")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Model { get; set; }

    [JsonPropertyName("completion")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Completion? Completion { get; set; }

    [JsonPropertyName("embedding")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public EmbeddingResponse? Embedding { get; set; }

    [JsonPropertyName("conversationPredicates")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public ConversationPredicates? ConversationPredicates { get; set; }

    [JsonPropertyName("chaos")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public object? Chaos { get; set; }

    [JsonPropertyName("delay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? Delay { get; set; }

    public static HttpLlmResponse Create() => new();

    public HttpLlmResponse WithProvider(string provider)
    {
        Provider = provider;
        return this;
    }

    public HttpLlmResponse WithModel(string model)
    {
        Model = model;
        return this;
    }

    public HttpLlmResponse WithCompletion(Completion completion)
    {
        Completion = completion;
        Embedding = null;
        return this;
    }

    public HttpLlmResponse WithEmbedding(EmbeddingResponse embedding)
    {
        Embedding = embedding;
        Completion = null;
        return this;
    }
}

/// <summary>
/// Where to read the per-session isolation key from an inbound request
/// (mirrors <c>org.mockserver.llm.IsolationSource</c>). Encodes as
/// <c>"&lt;kind&gt;:&lt;name&gt;"</c> (e.g. <c>header:x-session-id</c>).
/// </summary>
public sealed class IsolationSource
{
    public string Kind { get; }
    public string Name { get; }

    private IsolationSource(string kind, string name)
    {
        if (string.IsNullOrEmpty(name))
            throw new ArgumentException("name must not be null or empty", nameof(name));
        Kind = kind;
        Name = name;
    }

    public string Encode() => $"{Kind}:{Name}";

    public static IsolationSource Header(string name) => new("header", name);

    public static IsolationSource QueryParameter(string name) => new("query_parameter", name);

    public static IsolationSource Cookie(string name) => new("cookie", name);
}
