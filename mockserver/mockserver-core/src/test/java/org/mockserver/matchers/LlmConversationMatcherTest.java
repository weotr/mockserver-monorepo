package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Provider;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.fail;
import static org.mockserver.model.HttpRequest.request;

public class LlmConversationMatcherTest {

    // --- No predicates ---

    @Test
    public void shouldMatchWhenNoPredicatesSet() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC);

        HttpRequest request = request().withBody("{\"messages\": [{\"role\": \"user\", \"content\": \"hello\"}]}");

        assertThat(matcher.matches(request), is(true));
    }

    // --- turnIndex predicate ---

    @Test
    public void shouldMatchTurnIndex() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withTurnIndex(1);

        // One assistant message = turn index 1
        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"hello\"},\n" +
            "    {\"role\": \"assistant\", \"content\": [{\"type\": \"text\", \"text\": \"hi\"}]}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(true));
    }

    @Test
    public void shouldNotMatchTurnIndexWhenDifferent() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withTurnIndex(2);

        // Only one assistant message = turn index 1, but we require 2
        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"hello\"},\n" +
            "    {\"role\": \"assistant\", \"content\": [{\"type\": \"text\", \"text\": \"hi\"}]}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(false));
    }

    @Test
    public void shouldMatchTurnIndexZero() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withTurnIndex(0);

        // No assistant messages = turn index 0
        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"hello\"}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(true));
    }

    // --- latestMessageContains predicate ---

    @Test
    public void shouldMatchLatestMessageContains() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withLatestMessageContains("weather");

        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"What is the weather?\"}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(true));
    }

    @Test
    public void shouldNotMatchLatestMessageContainsWhenAbsent() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withLatestMessageContains("weather");

        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"Hello world\"}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(false));
    }

    // --- latestMessageMatches predicate ---

    @Test
    public void shouldMatchLatestMessageRegex() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withLatestMessageMatches(Pattern.compile("\\d+C"));

        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"It is 18C today\"}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(true));
    }

    @Test
    public void shouldNotMatchLatestMessageRegexWhenNoMatch() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withLatestMessageMatches(Pattern.compile("^exact$"));

        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"not exact at all\"}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(false));
    }

    // --- latestMessageRole predicate ---

    @Test
    public void shouldMatchLatestMessageRole() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withLatestMessageRole(ParsedMessage.Role.USER);

        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"hello\"}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(true));
    }

    @Test
    public void shouldNotMatchLatestMessageRoleWhenDifferent() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withLatestMessageRole(ParsedMessage.Role.ASSISTANT);

        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"hello\"}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(false));
    }

    // --- containsToolResultFor predicate ---

    @Test
    public void shouldMatchContainsToolResultFor() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withContainsToolResultFor("get_weather");

        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"weather?\"},\n" +
            "    {\"role\": \"assistant\", \"content\": [\n" +
            "      {\"type\": \"tool_use\", \"id\": \"toolu_1\", \"name\": \"get_weather\", \"input\": {}}\n" +
            "    ]},\n" +
            "    {\"role\": \"user\", \"content\": [\n" +
            "      {\"type\": \"tool_result\", \"tool_use_id\": \"toolu_1\", \"content\": \"sunny\"}\n" +
            "    ]}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(true));
    }

    @Test
    public void shouldNotMatchContainsToolResultForWhenNoToolResult() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withContainsToolResultFor("get_weather");

        // Has tool_use but no tool_result
        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"weather?\"},\n" +
            "    {\"role\": \"assistant\", \"content\": [\n" +
            "      {\"type\": \"tool_use\", \"id\": \"toolu_1\", \"name\": \"get_weather\", \"input\": {}}\n" +
            "    ]}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(false));
    }

    @Test
    public void shouldNotMatchContainsToolResultForWhenWrongToolName() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withContainsToolResultFor("search");

        // Tool call is get_weather, not search
        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"weather?\"},\n" +
            "    {\"role\": \"assistant\", \"content\": [\n" +
            "      {\"type\": \"tool_use\", \"id\": \"toolu_1\", \"name\": \"get_weather\", \"input\": {}}\n" +
            "    ]},\n" +
            "    {\"role\": \"user\", \"content\": [\n" +
            "      {\"type\": \"tool_result\", \"tool_use_id\": \"toolu_1\", \"content\": \"sunny\"}\n" +
            "    ]}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(false));
    }

    @Test
    public void shouldNotMatchContainsToolResultForWhenResultBelongsToDifferentTool() {
        // Anthropic: tool_use is "get_weather" with id "toolu_1", but tool_result references "toolu_1"
        // and we ask for containsToolResultFor("search") — should NOT match because
        // the result for toolu_1 correlates to get_weather, not search
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withContainsToolResultFor("search");

        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"weather?\"},\n" +
            "    {\"role\": \"assistant\", \"content\": [\n" +
            "      {\"type\": \"tool_use\", \"id\": \"toolu_1\", \"name\": \"get_weather\", \"input\": {}}\n" +
            "    ]},\n" +
            "    {\"role\": \"user\", \"content\": [\n" +
            "      {\"type\": \"tool_result\", \"tool_use_id\": \"toolu_1\", \"content\": \"sunny\"}\n" +
            "    ]}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(false));
    }

    @Test
    public void shouldMatchContainsToolResultForWithCorrectCorrelation() {
        // Two tool calls: search (toolu_1) and get_weather (toolu_2).
        // Only toolu_1 has a result. containsToolResultFor("search") should match.
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withContainsToolResultFor("search");

        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"search and weather\"},\n" +
            "    {\"role\": \"assistant\", \"content\": [\n" +
            "      {\"type\": \"tool_use\", \"id\": \"toolu_1\", \"name\": \"search\", \"input\": {}},\n" +
            "      {\"type\": \"tool_use\", \"id\": \"toolu_2\", \"name\": \"get_weather\", \"input\": {}}\n" +
            "    ]},\n" +
            "    {\"role\": \"user\", \"content\": [\n" +
            "      {\"type\": \"tool_result\", \"tool_use_id\": \"toolu_1\", \"content\": \"found it\"}\n" +
            "    ]}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(true));
    }

    @Test
    public void shouldNotMatchContainsToolResultForWhenResultIsForOtherToolOnly() {
        // Two tool calls: search (toolu_1) and get_weather (toolu_2).
        // Only toolu_2 (get_weather) has a result. containsToolResultFor("search") should NOT match.
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withContainsToolResultFor("search");

        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"search and weather\"},\n" +
            "    {\"role\": \"assistant\", \"content\": [\n" +
            "      {\"type\": \"tool_use\", \"id\": \"toolu_1\", \"name\": \"search\", \"input\": {}},\n" +
            "      {\"type\": \"tool_use\", \"id\": \"toolu_2\", \"name\": \"get_weather\", \"input\": {}}\n" +
            "    ]},\n" +
            "    {\"role\": \"user\", \"content\": [\n" +
            "      {\"type\": \"tool_result\", \"tool_use_id\": \"toolu_2\", \"content\": \"sunny\"}\n" +
            "    ]}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(false));
    }

    // --- containsToolResultFor with OpenAI ---

    @Test
    public void shouldMatchContainsToolResultForOpenAi() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.OPENAI)
            .withContainsToolResultFor("search");

        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"find X\"},\n" +
            "    {\"role\": \"assistant\", \"content\": null, \"tool_calls\": [\n" +
            "      {\"id\": \"call_1\", \"type\": \"function\", \"function\": {\"name\": \"search\", \"arguments\": \"{}\"}}\n" +
            "    ]},\n" +
            "    {\"role\": \"tool\", \"tool_call_id\": \"call_1\", \"content\": \"result\"}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(true));
    }

    @Test
    public void shouldNotMatchContainsToolResultForOpenAiWhenResultBelongsToDifferentTool() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.OPENAI)
            .withContainsToolResultFor("search");

        // Tool call is get_weather with id call_1; result references call_1
        // but we ask for "search" — should not match
        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"find X\"},\n" +
            "    {\"role\": \"assistant\", \"content\": null, \"tool_calls\": [\n" +
            "      {\"id\": \"call_1\", \"type\": \"function\", \"function\": {\"name\": \"get_weather\", \"arguments\": \"{}\"}}\n" +
            "    ]},\n" +
            "    {\"role\": \"tool\", \"tool_call_id\": \"call_1\", \"content\": \"result\"}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(false));
    }

    // --- AND composition ---

    @Test
    public void shouldMatchWithMultiplePredicatesAndComposition() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withTurnIndex(0)
            .withLatestMessageContains("weather");

        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"What is the weather?\"}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(true));
    }

    @Test
    public void shouldFailAndCompositionWhenOneFails() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withTurnIndex(1)  // requires 1 assistant message
            .withLatestMessageContains("weather");

        // No assistant message, so turnIndex=1 fails
        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"What is the weather?\"}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(false));
    }

    // --- Fail-closed: body too large ---

    @Test
    public void shouldNotMatchWhenBodyExceedsMaxSize() {
        // Set a very small max size for this test
        int originalMax = org.mockserver.configuration.ConfigurationProperties.maxLlmConversationBodySize();
        try {
            org.mockserver.configuration.ConfigurationProperties.maxLlmConversationBodySize(16384);

            LlmConversationMatcher matcher = new LlmConversationMatcher()
                .withProvider(Provider.ANTHROPIC)
                .withLatestMessageContains("hello");

            // Create a body larger than 16384 bytes
            StringBuilder largeContent = new StringBuilder();
            for (int i = 0; i < 20000; i++) {
                largeContent.append('x');
            }
            HttpRequest request = request().withBody("{\"messages\": [{\"role\": \"user\", \"content\": \"" + largeContent.toString() + "\"}]}");

            assertThat(matcher.matches(request), is(false));
        } finally {
            org.mockserver.configuration.ConfigurationProperties.maxLlmConversationBodySize(originalMax);
        }
    }

    // --- Fail-closed: malformed body ---

    @Test
    public void shouldNotMatchWithMalformedBody() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withLatestMessageContains("hello");

        HttpRequest request = request().withBody("this is not json");

        assertThat(matcher.matches(request), is(false));
    }

    // --- Fail-closed: no codec registered ---

    @Test
    public void shouldNotMatchWhenNoCodecRegistered() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.GEMINI)  // No Gemini codec registered yet
            .withLatestMessageContains("hello");

        HttpRequest request = request().withBody("{\"messages\": [{\"role\": \"user\", \"content\": \"hello\"}]}");

        assertThat(matcher.matches(request), is(false));
    }

    // --- Fail-closed: no provider set ---

    @Test
    public void shouldNotMatchWhenNoProviderSet() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withLatestMessageContains("hello");

        HttpRequest request = request().withBody("{\"messages\": [{\"role\": \"user\", \"content\": \"hello\"}]}");

        assertThat(matcher.matches(request), is(false));
    }

    // --- Fail-closed: empty parse result ---

    @Test
    public void shouldNotMatchWhenParseReturnsEmptyButPredicatesSet() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withLatestMessageContains("hello");

        // Wrong-shape body (no messages array)
        HttpRequest request = request().withBody("{\"model\": \"claude-sonnet-4-20250514\"}");

        assertThat(matcher.matches(request), is(false));
    }

    // --- Provider inheritance verification ---

    @Test
    public void shouldMatchWithOpenAiProvider() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.OPENAI)
            .withLatestMessageContains("hello");

        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"hello world\"}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(true));
    }

    // --- Never throws ---

    @Test
    public void shouldNotThrowOnNullRequest() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withLatestMessageContains("hello");

        assertThat(matcher.matches(null), is(false));
    }

    // --- Gemini containsToolResultFor ---

    @Test
    public void shouldMatchContainsToolResultForGemini() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.GEMINI)
            .withContainsToolResultFor("get_weather");

        HttpRequest request = request().withBody("{\"contents\":["
            + "{\"role\":\"user\",\"parts\":[{\"text\":\"What is the weather?\"}]},"
            + "{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"get_weather\",\"args\":{\"city\":\"Paris\"}}}]},"
            + "{\"role\":\"user\",\"parts\":[{\"functionResponse\":{\"name\":\"get_weather\",\"response\":\"18C and sunny\"}}]}"
            + "]}");

        assertThat(matcher.matches(request), is(true));
    }

    @Test
    public void shouldMatchContainsToolResultForGeminiWithJsonBody() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.GEMINI)
            .withContainsToolResultFor("get_weather");

        String json = "{\"contents\":["
            + "{\"role\":\"user\",\"parts\":[{\"text\":\"What is the weather?\"}]},"
            + "{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"get_weather\",\"args\":{\"city\":\"Paris\"}}}]},"
            + "{\"role\":\"user\",\"parts\":[{\"functionResponse\":{\"name\":\"get_weather\",\"response\":\"18C and sunny\"}}]}"
            + "]}";

        // Use JsonBody to simulate what MockServer Netty pipeline does
        HttpRequest request = request().withBody(
            new org.mockserver.model.JsonBody(json)
        );

        assertThat(matcher.matches(request), is(true));
    }

    // --- Ollama containsToolResultFor ---

    @Test
    public void shouldMatchContainsToolResultForOllama() {
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.OLLAMA)
            .withContainsToolResultFor("get_weather");

        HttpRequest request = request().withBody("{\"messages\":["
            + "{\"role\":\"user\",\"content\":\"What is the weather?\"},"
            + "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":["
            + "{\"function\":{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Paris\"}}}"
            + "]},"
            + "{\"role\":\"tool\",\"content\":\"18C and sunny\"}"
            + "]}");

        assertThat(matcher.matches(request), is(true));
    }

    @Test
    public void shouldNotMatchOllamaContainsToolResultForWhenMultipleToolsCalled() {
        // Ollama tool results are keyed by empty string (no tool_call_id), so the
        // positional fallback cannot disambiguate between calls. When the assistant
        // turn called BOTH "search" and "calculate", a containsToolResultFor("search")
        // predicate must fail closed because the TOOL message could be the response
        // for either tool.
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.OLLAMA)
            .withContainsToolResultFor("search");

        HttpRequest request = request().withBody("{\"messages\":["
            + "{\"role\":\"user\",\"content\":\"search and compute\"},"
            + "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":["
            + "{\"function\":{\"name\":\"search\",\"arguments\":{\"q\":\"x\"}}},"
            + "{\"function\":{\"name\":\"calculate\",\"arguments\":{\"expr\":\"2+2\"}}}"
            + "]},"
            + "{\"role\":\"tool\",\"content\":\"42\"}"
            + "]}");

        assertThat(matcher.matches(request), is(false));
    }

    // --- Gap 2: Regex compile error at setter time ---

    @Test(expected = PatternSyntaxException.class)
    public void shouldThrowPatternSyntaxExceptionForInvalidRegexViaPatternCompile() {
        // Documenting the contract: Pattern.compile with an invalid regex throws
        // PatternSyntaxException immediately, so users who pass an invalid Pattern
        // to whenLatestMessageContains(Pattern) get a clear error at the call site.
        Pattern.compile("[invalid");
    }

    @Test
    public void shouldRejectInvalidRegexStringEagerlyAtSetterTime() {
        // §2.7: withLatestMessageMatches(String) with an invalid regex must throw
        // IllegalArgumentException at the setter call, not at match time.
        try {
            new LlmConversationMatcher()
                .withLatestMessageMatches("[invalid");
            fail("Expected IllegalArgumentException for invalid regex");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString("[invalid"));
            assertThat(e.getCause() instanceof PatternSyntaxException, is(true));
        }
    }

    @Test
    public void shouldAcceptValidRegexStringAtSetterTime() {
        // Valid regex should not throw and should match at runtime
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withLatestMessageMatches("\\d+C");

        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"It is 18C today\"}\n" +
            "  ]\n" +
            "}");

        assertThat(matcher.matches(request), is(true));
    }

    @Test
    public void shouldAcceptNullRegexString() {
        // Null regex should be accepted (no-op) without throwing
        LlmConversationMatcher matcher = new LlmConversationMatcher()
            .withLatestMessageMatches((String) null);

        assertThat(matcher.getLatestMessageMatchesSource(), is((String) null));
        assertThat(matcher.getLatestMessageMatches(), is((Pattern) null));
    }

    @Test
    public void shouldMatchWithStringRegexSameAsPatternRegex() {
        // Verify string-based and Pattern-based setters produce identical matching
        LlmConversationMatcher stringMatcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withLatestMessageMatches("weather.*paris");

        LlmConversationMatcher patternMatcher = new LlmConversationMatcher()
            .withProvider(Provider.ANTHROPIC)
            .withLatestMessageMatches(Pattern.compile("weather.*paris"));

        HttpRequest request = request().withBody("{\n" +
            "  \"messages\": [\n" +
            "    {\"role\": \"user\", \"content\": \"what is the weather in paris\"}\n" +
            "  ]\n" +
            "}");

        assertThat(stringMatcher.matches(request), is(true));
        assertThat(patternMatcher.matches(request), is(true));
    }
}
