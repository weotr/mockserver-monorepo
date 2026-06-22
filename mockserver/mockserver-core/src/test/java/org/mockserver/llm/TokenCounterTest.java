package org.mockserver.llm;

import org.junit.Test;
import org.mockserver.model.ToolUse;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Unit tests for the approximate {@link TokenCounter}. The counts are an
 * estimate, so the tests assert stability (same input → same count), correct
 * null/empty handling, and that the estimate lands in a sane ballpark — not an
 * exact tokenizer value.
 */
public class TokenCounterTest {

    // ---- null / empty handling ----

    @Test
    public void nullTextIsZeroTokens() {
        assertThat(TokenCounter.estimateTokens(null), is(0));
    }

    @Test
    public void emptyTextIsZeroTokens() {
        assertThat(TokenCounter.estimateTokens(""), is(0));
    }

    @Test
    public void singleCharIsAtLeastOneToken() {
        assertThat(TokenCounter.estimateTokens("a"), is(greaterThanOrEqualTo(1)));
    }

    @Test
    public void whitespaceOnlyIsAtLeastOneToken() {
        // non-empty but no words — still a real (if tiny) cost
        assertThat(TokenCounter.estimateTokens("   "), is(greaterThanOrEqualTo(1)));
    }

    // ---- determinism / stability ----

    @Test
    public void estimateIsStableForKnownString() {
        String text = "The quick brown fox jumps over the lazy dog.";
        int first = TokenCounter.estimateTokens(text);
        int second = TokenCounter.estimateTokens(text);
        assertThat(first, is(second));
        // ballpark: 9 words / ~44 chars → roughly a dozen tokens
        assertThat(first, is(both(greaterThan(7)).and(lessThan(20))));
    }

    @Test
    public void estimateIsStableAcrossManyCalls() {
        String text = "Repeated determinism check across invocations.";
        int expected = TokenCounter.estimateTokens(text);
        for (int i = 0; i < 50; i++) {
            assertThat(TokenCounter.estimateTokens(text), is(expected));
        }
    }

    @Test
    public void longerTextHasMoreTokens() {
        int shortCount = TokenCounter.estimateTokens("hello");
        int longCount = TokenCounter.estimateTokens("hello hello hello hello hello hello hello hello");
        assertThat(longCount, is(greaterThan(shortCount)));
    }

    @Test
    public void roughlyFourCharsPerTokenForProse() {
        // 100 chars of plain prose should land in the same order of magnitude as chars/4
        String text = "Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore.";
        int count = TokenCounter.estimateTokens(text);
        int charsOverFour = text.length() / 4;
        assertThat(count, is(both(greaterThan(charsOverFour / 2)).and(lessThan(charsOverFour * 2 + 10))));
    }

    // ---- prompt-token estimation over a conversation ----

    @Test
    public void promptTokensNullConversationIsZero() {
        assertThat(TokenCounter.estimatePromptTokens(null), is(0));
    }

    @Test
    public void promptTokensEmptyConversationIsZero() {
        assertThat(TokenCounter.estimatePromptTokens(ParsedConversation.empty()), is(0));
    }

    @Test
    public void promptTokensSumsMessagesWithOverhead() {
        ParsedMessage system = new ParsedMessage(ParsedMessage.Role.SYSTEM, "You are a helpful assistant.", null, null);
        ParsedMessage user = new ParsedMessage(ParsedMessage.Role.USER, "What is the capital of France?", null, null);
        ParsedConversation conversation = ParsedConversation.of(Arrays.asList(system, user));

        int total = TokenCounter.estimatePromptTokens(conversation);
        int perMessageSum = TokenCounter.estimateTokens("You are a helpful assistant.")
            + TokenCounter.estimateTokens("What is the capital of France?");
        // includes per-message overhead so the total exceeds the bare text sum
        assertThat(total, is(greaterThan(perMessageSum)));
    }

    @Test
    public void promptTokensIncludeToolCallArgumentsAndResults() {
        ToolUse toolCall = ToolUse.toolUse("get_weather").withArguments("{\"city\":\"Paris\"}");
        ParsedMessage assistant = new ParsedMessage(ParsedMessage.Role.ASSISTANT, "", Collections.singletonList(toolCall), null);

        Map<String, String> results = new LinkedHashMap<>();
        results.put("call_1", "{\"temp\":\"21C\"}");
        ParsedMessage tool = new ParsedMessage(ParsedMessage.Role.TOOL, "", null, results);

        int withToolContent = TokenCounter.estimatePromptTokens(ParsedConversation.of(Arrays.asList(assistant, tool)));
        // two messages of overhead alone would be 6; the tool name/args/results push it higher
        assertThat(withToolContent, is(greaterThan(6)));
    }

    @Test
    public void promptTokensToleratesNullMessageEntries() {
        ParsedConversation conversation = ParsedConversation.of(Arrays.asList(
            null,
            new ParsedMessage(ParsedMessage.Role.USER, "hello world", null, null)));
        assertThat(TokenCounter.estimatePromptTokens(conversation), is(greaterThan(0)));
    }

    // ---- completion-token estimation ----

    @Test
    public void completionTokensTextOnly() {
        int count = TokenCounter.estimateCompletionTokens("The answer is 42.", null);
        assertThat(count, is(greaterThan(0)));
        assertThat(count, is(TokenCounter.estimateTokens("The answer is 42.")));
    }

    @Test
    public void completionTokensIncludeToolCalls() {
        List<ToolUse> toolCalls = Collections.singletonList(
            ToolUse.toolUse("lookup").withArguments("{\"id\":123}"));
        int textOnly = TokenCounter.estimateCompletionTokens("done", null);
        int withTools = TokenCounter.estimateCompletionTokens("done", toolCalls);
        assertThat(withTools, is(greaterThan(textOnly)));
    }

    @Test
    public void completionTokensEmptyOutputIsZero() {
        assertThat(TokenCounter.estimateCompletionTokens(null, null), is(0));
        assertThat(TokenCounter.estimateCompletionTokens("", Collections.emptyList()), is(0));
    }
}
