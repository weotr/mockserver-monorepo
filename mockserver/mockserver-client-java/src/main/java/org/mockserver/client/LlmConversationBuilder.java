package org.mockserver.client;

import org.mockserver.llm.IsolationSource;
import org.mockserver.llm.LlmScenarioNames;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.matchers.LlmConversationMatcher;
import org.mockserver.mock.Expectation;
import org.mockserver.model.Completion;
import org.mockserver.model.ConversationPredicates;
import org.mockserver.model.HttpLlmResponse;
import org.mockserver.model.Provider;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.mockserver.model.HttpLlmResponse.llmResponse;
import static org.mockserver.model.HttpRequest.request;

/**
 * Builder for multi-turn LLM conversation mocks with optional per-session isolation.
 * <p>
 * Each {@link #turn()} block produces one {@link Expectation} with scenario-based
 * state advancement. On {@link #applyTo(MockServerClient)}, all turn expectations
 * are registered as a group sharing a single auto-generated scenario name.
 * <p>
 * Example:
 * <pre>
 * conversation()
 *     .withPath("/v1/messages")
 *     .withProvider(Provider.ANTHROPIC)
 *     .isolateBy(IsolationSource.header("x-session-id"))
 *     .turn()
 *         .whenTurnIndex(0)
 *         .respondingWith(completion().withToolCall(toolUse("search").withArguments("{}")))
 *     .andThen()
 *     .turn()
 *         .whenContainsToolResultFor("search")
 *         .respondingWith(completion().withText("The answer is 42."))
 *     .applyTo(mockServerClient);
 * </pre>
 */
public class LlmConversationBuilder {

    private static final String SCENARIO_PREFIX = "__llm_conv_";
    private static final String DONE_STATE = "__done";

    private String path;
    private Provider provider;
    private String model;
    private IsolationSource isolationSource;
    private final List<TurnBuilder> turns = new ArrayList<>();

    private LlmConversationBuilder() {
    }

    /**
     * Entry point for building a multi-turn LLM conversation mock.
     *
     * @return a new LlmConversationBuilder
     */
    public static LlmConversationBuilder conversation() {
        return new LlmConversationBuilder();
    }

    public LlmConversationBuilder withPath(String path) {
        this.path = path;
        return this;
    }

    public LlmConversationBuilder withProvider(Provider provider) {
        this.provider = provider;
        return this;
    }

    public LlmConversationBuilder withModel(String model) {
        this.model = model;
        return this;
    }

    /**
     * Set isolation source for per-session state. When set, the isolation key
     * is extracted from each inbound request (header, query parameter, or cookie)
     * and used to maintain independent scenario state per value.
     *
     * @param source the isolation source descriptor
     * @return this builder
     */
    public LlmConversationBuilder isolateBy(IsolationSource source) {
        this.isolationSource = source;
        return this;
    }

    /**
     * Start defining a new turn in the conversation.
     *
     * @return a TurnBuilder for configuring this turn's match predicates and response
     */
    public TurnBuilder turn() {
        TurnBuilder turnBuilder = new TurnBuilder(this);
        turns.add(turnBuilder);
        return turnBuilder;
    }

    IsolationSource getIsolationSource() {
        return isolationSource;
    }

    Provider getProvider() {
        return provider;
    }

    String getModel() {
        return model;
    }

    String getPath() {
        return path;
    }

    List<TurnBuilder> getTurns() {
        return turns;
    }

    /**
     * Build all turn expectations and register them with the MockServerClient.
     *
     * @param client the MockServerClient to register with
     * @return the created expectations
     */
    public Expectation[] applyTo(MockServerClient client) {
        Expectation[] expectations = build();
        return client.upsert(expectations);
    }

    /**
     * Build all turn expectations without registering them.
     *
     * @return the array of expectations, one per turn
     */
    public Expectation[] build() {
        if (turns.isEmpty()) {
            throw new IllegalStateException("At least one turn must be defined");
        }
        if (path == null || path.isEmpty()) {
            throw new IllegalStateException("Path must be set");
        }
        if (provider == null) {
            throw new IllegalStateException("Provider must be set");
        }

        String conversationId = SCENARIO_PREFIX + UUID.randomUUID().toString();
        String scenarioName = conversationId;

        // Encode isolation source into scenario name if present
        if (isolationSource != null) {
            scenarioName = conversationId + LlmScenarioNames.ISOLATION_MARKER + isolationSource.encode();
        }

        Expectation[] expectations = new Expectation[turns.size()];
        for (int i = 0; i < turns.size(); i++) {
            TurnBuilder turnBuilder = turns.get(i);
            String turnState = "turn_" + i;
            String nextState = (i < turns.size() - 1) ? "turn_" + (i + 1) : DONE_STATE;

            // Build serialisable conversation predicates
            ConversationPredicates predicates = ConversationPredicates.conversationPredicates();
            if (turnBuilder.turnIndex != null) {
                predicates.withTurnIndex(turnBuilder.turnIndex);
            }
            if (turnBuilder.latestMessageContains != null) {
                predicates.withLatestMessageContains(turnBuilder.latestMessageContains);
            }
            if (turnBuilder.latestMessageMatches != null) {
                predicates.withLatestMessageMatches(turnBuilder.latestMessageMatches.pattern());
            }
            if (turnBuilder.latestMessageRole != null) {
                predicates.withLatestMessageRole(turnBuilder.latestMessageRole);
            }
            if (turnBuilder.containsToolResultFor != null) {
                predicates.withContainsToolResultFor(turnBuilder.containsToolResultFor);
            }
            if (turnBuilder.normalization != null) {
                predicates.withNormalization(turnBuilder.normalization);
            }

            // Build response
            HttpLlmResponse llmResponse = llmResponse()
                .withProvider(provider)
                .withModel(model);

            if (turnBuilder.completion != null) {
                llmResponse = llmResponse.withCompletion(turnBuilder.completion);
            }

            // Store the predicates on the response (survives JSON serialisation)
            if (predicates.hasAnyPredicate()) {
                llmResponse.withConversationPredicates(predicates);
            }

            // Build expectation with scenario state
            Expectation expectation = Expectation.when(
                request().withMethod("POST").withPath(path)
            )
                .withScenarioName(scenarioName)
                .withScenarioState(i == 0 ? "Started" : turnState)
                .withNewScenarioState(nextState)
                .thenRespondWithLlm(llmResponse);

            expectations[i] = expectation;
        }

        return expectations;
    }

    /**
     * Decode the isolation source from a scenario name that contains the isolation marker.
     * Delegates to {@link LlmScenarioNames#decodeIsolationSource(String)}.
     */
    public static IsolationSource decodeIsolationSource(String scenarioName) {
        return LlmScenarioNames.decodeIsolationSource(scenarioName);
    }

    /**
     * Extract the base scenario name (without isolation marker suffix) from a full scenario name.
     * Delegates to {@link LlmScenarioNames#baseScenarioName(String)}.
     */
    public static String baseScenarioName(String scenarioName) {
        return LlmScenarioNames.baseScenarioName(scenarioName);
    }
}
