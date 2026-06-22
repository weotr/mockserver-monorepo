using System.Linq;
using System.Text.Json;
using FluentAssertions;
using MockServer.Client.Llm;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

/// <summary>
/// Unit tests for the fluent LLM mock builders. Asserts the serialized wire JSON
/// shape (provider casing, <c>httpLlmResponse</c> presence, conversation scenario
/// advancement, and failover coalescing + default error bodies).
/// </summary>
public class LlmBuilderTests
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    private static JsonElement Serialize(object value)
        => JsonDocument.Parse(JsonSerializer.Serialize(value, JsonOptions)).RootElement;

    [Fact]
    public void LlmMock_Completion_SerializesProviderAndHttpLlmResponse()
    {
        var expectation = LlmMockBuilder.LlmMock("/v1/chat/completions")
            .WithProvider(Provider.ANTHROPIC)
            .WithModel("claude-3-5-sonnet")
            .RespondingWith(Completion.Create()
                .WithText("Hello!")
                .WithStopReason("stop")
                .WithUsage(Usage.Create().WithInputTokens(10).WithOutputTokens(5)))
            .Build();

        var root = Serialize(expectation);

        root.GetProperty("httpRequest").GetProperty("method").GetString().Should().Be("POST");
        root.GetProperty("httpRequest").GetProperty("path").GetString().Should().Be("/v1/chat/completions");

        var llm = root.GetProperty("httpLlmResponse");
        llm.GetProperty("provider").GetString().Should().Be("ANTHROPIC");
        llm.GetProperty("model").GetString().Should().Be("claude-3-5-sonnet");
        llm.GetProperty("completion").GetProperty("text").GetString().Should().Be("Hello!");
        llm.GetProperty("completion").GetProperty("stopReason").GetString().Should().Be("stop");
        llm.GetProperty("completion").GetProperty("usage").GetProperty("inputTokens").GetInt32().Should().Be(10);
        llm.GetProperty("completion").GetProperty("usage").GetProperty("outputTokens").GetInt32().Should().Be(5);
        llm.TryGetProperty("embedding", out _).Should().BeFalse();
    }

    [Fact]
    public void LlmMock_Embedding_ClearsCompletion()
    {
        var expectation = LlmMockBuilder.LlmMock("/v1/embeddings")
            .WithProvider(Provider.OPENAI)
            .RespondingWith(Completion.Create().WithText("ignored"))
            .RespondingWith(EmbeddingResponse.Create().WithDimensions(1536).WithDeterministicFromInput(true))
            .Build();

        var llm = Serialize(expectation).GetProperty("httpLlmResponse");
        llm.GetProperty("provider").GetString().Should().Be("OPENAI");
        llm.GetProperty("embedding").GetProperty("dimensions").GetInt32().Should().Be(1536);
        llm.GetProperty("embedding").GetProperty("deterministicFromInput").GetBoolean().Should().BeTrue();
        llm.TryGetProperty("completion", out _).Should().BeFalse();
    }

    [Fact]
    public void Completion_ToolCalls_SerializeAsArray()
    {
        var expectation = LlmMockBuilder.LlmMock("/v1/messages")
            .WithProvider(Provider.ANTHROPIC)
            .RespondingWith(Completion.Create()
                .WithToolCall(ToolUse.Of("get_weather").WithId("call_1").WithArguments("{\"city\":\"London\"}")))
            .Build();

        var toolCalls = Serialize(expectation)
            .GetProperty("httpLlmResponse").GetProperty("completion").GetProperty("toolCalls");
        toolCalls.GetArrayLength().Should().Be(1);
        toolCalls[0].GetProperty("id").GetString().Should().Be("call_1");
        toolCalls[0].GetProperty("name").GetString().Should().Be("get_weather");
        toolCalls[0].GetProperty("arguments").GetString().Should().Be("{\"city\":\"London\"}");
    }

    [Fact]
    public void StreamingPhysics_Validates_AndSerializes()
    {
        var physics = StreamingPhysics.Create()
            .WithTokensPerSecond(50)
            .WithJitter(0.2)
            .WithTimeToFirstToken(300)
            .WithSeed(42);

        var completion = Completion.Create().WithText("stream").Stream().WithStreamingPhysics(physics);
        var expectation = LlmMockBuilder.LlmMock("/v1/chat/completions")
            .WithProvider(Provider.OPENAI).RespondingWith(completion).Build();

        var comp = Serialize(expectation).GetProperty("httpLlmResponse").GetProperty("completion");
        comp.GetProperty("streaming").GetBoolean().Should().BeTrue();
        var sp = comp.GetProperty("streamingPhysics");
        sp.GetProperty("tokensPerSecond").GetInt32().Should().Be(50);
        sp.GetProperty("jitter").GetDouble().Should().Be(0.2);
        sp.GetProperty("timeToFirstToken").GetProperty("value").GetInt64().Should().Be(300);
        sp.GetProperty("timeToFirstToken").GetProperty("timeUnit").GetString().Should().Be("MILLISECONDS");
    }

    [Fact]
    public void StreamingPhysics_RejectsInvalidRanges()
    {
        var act1 = () => StreamingPhysics.Create().WithTokensPerSecond(0);
        act1.Should().Throw<ArgumentException>();
        var act2 = () => StreamingPhysics.Create().WithJitter(1.5);
        act2.Should().Throw<ArgumentException>();
    }

    [Fact]
    public void Usage_RejectsNegativeTokens()
    {
        var act = () => Usage.Create().WithInputTokens(-1);
        act.Should().Throw<ArgumentException>();
    }

    [Fact]
    public void Conversation_AdvancesScenarioState()
    {
        var expectations = LlmConversationBuilder.Conversation()
            .WithPath("/v1/chat/completions")
            .WithProvider(Provider.OPENAI)
            .WithModel("gpt-4o")
            .Turn().RespondingWith(Completion.Create().WithText("turn 0"))
            .Turn().WhenLatestMessageContains("again").RespondingWith(Completion.Create().WithText("turn 1"))
            .Turn().RespondingWith(Completion.Create().WithText("turn 2"))
            .Build();

        expectations.Should().HaveCount(3);

        var first = Serialize(expectations[0]);
        var name = first.GetProperty("scenarioName").GetString();
        name.Should().StartWith("__llm_conv_");
        first.GetProperty("scenarioState").GetString().Should().Be("Started");
        first.GetProperty("newScenarioState").GetString().Should().Be("turn_1");
        // First turn has no predicates.
        first.GetProperty("httpLlmResponse").TryGetProperty("conversationPredicates", out _).Should().BeFalse();

        var second = Serialize(expectations[1]);
        second.GetProperty("scenarioName").GetString().Should().Be(name);
        second.GetProperty("scenarioState").GetString().Should().Be("turn_1");
        second.GetProperty("newScenarioState").GetString().Should().Be("turn_2");
        second.GetProperty("httpLlmResponse").GetProperty("conversationPredicates")
            .GetProperty("latestMessageContains").GetString().Should().Be("again");

        var third = Serialize(expectations[2]);
        third.GetProperty("scenarioState").GetString().Should().Be("turn_2");
        third.GetProperty("newScenarioState").GetString().Should().Be("__done");
    }

    [Fact]
    public void Conversation_IsolateBy_AppendsMarker()
    {
        var expectations = LlmConversationBuilder.Conversation()
            .WithPath("/v1/chat/completions")
            .WithProvider(Provider.ANTHROPIC)
            .IsolateBy(IsolationSource.Header("x-session-id"))
            .Turn().RespondingWith(Completion.Create().WithText("hi"))
            .Build();

        var name = Serialize(expectations[0]).GetProperty("scenarioName").GetString();
        name.Should().StartWith("__llm_conv_");
        name.Should().Contain("__iso=header:x-session-id");
    }

    [Fact]
    public void Conversation_NormalizationAlone_DoesNotCountAsPredicate()
    {
        var expectations = LlmConversationBuilder.Conversation()
            .WithPath("/v1/chat/completions")
            .WithProvider(Provider.OPENAI)
            .Turn()
                .WithNormalization(NormalizationOptions.Create().WithLowercase())
                .RespondingWith(Completion.Create().WithText("hi"))
            .Build();

        // Normalization is a modifier, not a predicate — no conversationPredicates emitted.
        Serialize(expectations[0]).GetProperty("httpLlmResponse")
            .TryGetProperty("conversationPredicates", out _).Should().BeFalse();
    }

    [Fact]
    public void Conversation_RequiresTurnsPathProvider()
    {
        var noTurns = () => LlmConversationBuilder.Conversation()
            .WithPath("/p").WithProvider(Provider.OPENAI).Build();
        noTurns.Should().Throw<InvalidOperationException>();

        var noProvider = () => LlmConversationBuilder.Conversation()
            .WithPath("/p").Turn().RespondingWith(Completion.Create()).Build();
        noProvider.Should().Throw<InvalidOperationException>();
    }

    [Fact]
    public void Failover_CoalescesAndUsesDefaultBodies()
    {
        var expectations = LlmFailoverBuilder.LlmFailover()
            .WithPath("/v1/chat/completions")
            .WithProvider(Provider.OPENAI)
            .WithModel("gpt-4o")
            .FailWith(429, 2)
            .FailWith(503)
            .ThenRespondWith(Completion.Create().WithText("recovered"))
            .Build();

        // 2x429 coalesce into one + 1x503 + success = 3 expectations.
        expectations.Should().HaveCount(3);

        var first = Serialize(expectations[0]);
        first.GetProperty("times").GetProperty("remainingTimes").GetInt32().Should().Be(2);
        first.GetProperty("times").GetProperty("unlimited").GetBoolean().Should().BeFalse();
        first.GetProperty("timeToLive").GetProperty("unlimited").GetBoolean().Should().BeTrue();
        var firstResp = first.GetProperty("httpResponse");
        firstResp.GetProperty("statusCode").GetInt32().Should().Be(429);
        firstResp.GetProperty("headers").GetProperty("Content-Type")[0].GetString().Should().Be("application/json");
        var firstBody = JsonDocument.Parse(firstResp.GetProperty("body").GetString()!).RootElement;
        firstBody.GetProperty("error").GetProperty("type").GetString().Should().Be("rate_limit_error");
        firstBody.GetProperty("error").GetProperty("message").GetString()
            .Should().Be("Rate limit exceeded. Please retry after a brief wait.");

        var second = Serialize(expectations[1]);
        second.GetProperty("times").GetProperty("remainingTimes").GetInt32().Should().Be(1);
        var secondBody = JsonDocument.Parse(second.GetProperty("httpResponse").GetProperty("body").GetString()!).RootElement;
        secondBody.GetProperty("error").GetProperty("type").GetString().Should().Be("service_unavailable");

        var success = Serialize(expectations[2]);
        success.GetProperty("times").GetProperty("unlimited").GetBoolean().Should().BeTrue();
        success.TryGetProperty("httpResponse", out _).Should().BeFalse();
        success.GetProperty("httpLlmResponse").GetProperty("provider").GetString().Should().Be("OPENAI");
        success.GetProperty("httpLlmResponse").GetProperty("completion").GetProperty("text").GetString()
            .Should().Be("recovered");
    }

    [Fact]
    public void Failover_CustomBodyIsNotCoalescedWithDefault()
    {
        var builder = LlmFailoverBuilder.LlmFailover()
            .WithPath("/p")
            .WithProvider(Provider.OPENAI)
            .FailWith(500)
            .FailWith(500, "{\"error\":{\"type\":\"custom\",\"message\":\"x\"}}")
            .ThenRespondWith(Completion.Create().WithText("ok"));

        var expectations = builder.Build();
        // default-500 and custom-500 differ → not coalesced → 2 failures + success.
        expectations.Should().HaveCount(3);
        builder.GetFailureCount().Should().Be(2);
    }

    [Fact]
    public void Failover_DefaultErrorBody_KnownCodes()
    {
        LlmFailoverBuilder.DefaultErrorBody(502).Should().Contain("bad_gateway");
        LlmFailoverBuilder.DefaultErrorBody(500).Should().Contain("internal_server_error");
        LlmFailoverBuilder.DefaultErrorBody(418).Should().Contain("Request failed with status 418");
    }

    [Fact]
    public void Failover_RejectsInvalidStatusCode()
    {
        var act = () => LlmFailoverBuilder.LlmFailover().FailWith(99);
        act.Should().Throw<ArgumentException>();
    }

    [Fact]
    public void IsolationSource_Encodes()
    {
        IsolationSource.Header("h").Encode().Should().Be("header:h");
        IsolationSource.QueryParameter("q").Encode().Should().Be("query_parameter:q");
        IsolationSource.Cookie("c").Encode().Should().Be("cookie:c");
    }
}
