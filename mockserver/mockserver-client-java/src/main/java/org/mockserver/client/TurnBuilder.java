package org.mockserver.client;

import org.mockserver.llm.ParsedMessage;
import org.mockserver.mock.Expectation;
import org.mockserver.model.Completion;
import org.mockserver.model.NormalizationOptions;

import java.util.regex.Pattern;

/**
 * Sub-builder for configuring a single turn within an LLM conversation mock.
 * <p>
 * Provides fluent predicates for matching inbound requests and a response
 * to return when all predicates match. Returns to the parent
 * {@link LlmConversationBuilder} via {@link #andThen()}.
 */
public class TurnBuilder {

    private final LlmConversationBuilder parent;
    Integer turnIndex;
    String latestMessageContains;
    Pattern latestMessageMatches;
    ParsedMessage.Role latestMessageRole;
    String containsToolResultFor;
    NormalizationOptions normalization;
    Completion completion;

    TurnBuilder(LlmConversationBuilder parent) {
        this.parent = parent;
    }

    /**
     * Match when the conversation has exactly {@code n} assistant turns.
     *
     * @param n the turn index (0-based count of assistant messages)
     * @return this builder
     */
    public TurnBuilder whenTurnIndex(int n) {
        this.turnIndex = n;
        return this;
    }

    /**
     * Match when the latest message's text content contains the given substring.
     *
     * @param text the substring to match
     * @return this builder
     */
    public TurnBuilder whenLatestMessageContains(String text) {
        this.latestMessageContains = text;
        return this;
    }

    /**
     * Match when the latest message's text content matches the given regex.
     *
     * @param regex the regex pattern to match
     * @return this builder
     * @throws IllegalArgumentException if the regex fails to compile
     */
    public TurnBuilder whenLatestMessageMatches(Pattern regex) {
        if (regex == null) {
            throw new IllegalArgumentException("regex must not be null");
        }
        this.latestMessageMatches = regex;
        return this;
    }

    /**
     * @deprecated misleading name — this overload matches by regex, not substring.
     * Use {@link #whenLatestMessageMatches(Pattern)}. Retained for source
     * compatibility; delegates to {@code whenLatestMessageMatches}.
     */
    @Deprecated
    public TurnBuilder whenLatestMessageContains(Pattern regex) {
        return whenLatestMessageMatches(regex);
    }

    /**
     * Match when the latest message has the given role.
     *
     * @param role the role to match
     * @return this builder
     */
    public TurnBuilder whenLatestMessageRole(ParsedMessage.Role role) {
        this.latestMessageRole = role;
        return this;
    }

    /**
     * Match when the conversation contains a tool result for a prior call
     * of the named tool.
     *
     * @param toolName the tool name to look for
     * @return this builder
     */
    public TurnBuilder whenContainsToolResultFor(String toolName) {
        this.containsToolResultFor = toolName;
        return this;
    }

    /**
     * Apply opt-in prompt normalisation before the {@code whenLatestMessage…}
     * text predicates are evaluated, so cosmetic differences (whitespace, JSON
     * key ordering, volatile ids/timestamps) do not block a match. Deterministic
     * — the same prompt always normalises identically.
     *
     * @param normalization the normalisation options
     * @return this builder
     */
    public TurnBuilder withNormalization(NormalizationOptions normalization) {
        this.normalization = normalization;
        return this;
    }

    /**
     * Set the completion to return for this turn.
     *
     * @param completion the completion response
     * @return this builder
     */
    public TurnBuilder respondingWith(Completion completion) {
        this.completion = completion;
        return this;
    }

    /**
     * Return to the parent conversation builder for chaining additional turns.
     *
     * @return the parent LlmConversationBuilder
     */
    public LlmConversationBuilder andThen() {
        return parent;
    }

    /**
     * Shortcut: build and register all turns with the MockServerClient.
     * Delegates to the parent builder's {@code applyTo}.
     *
     * @param client the MockServerClient
     * @return the created expectations
     */
    public Expectation[] applyTo(MockServerClient client) {
        return parent.applyTo(client);
    }
}
