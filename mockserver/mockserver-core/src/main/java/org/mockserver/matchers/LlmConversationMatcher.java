package org.mockserver.matchers;

import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.ProviderCodecRegistry;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Provider;
import org.mockserver.model.ToolUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Matches inbound HTTP requests against LLM conversation predicates.
 * All predicates compose with AND semantics. Parse failures and oversize
 * bodies are treated as no-match (fail-closed) without throwing.
 */
public class LlmConversationMatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmConversationMatcher.class);

    private Integer turnIndex;
    private String latestMessageContains;
    private String latestMessageMatchesSource; // regex source string
    private Pattern latestMessageMatches;      // lazily compiled from source
    private ParsedMessage.Role latestMessageRole;
    private String containsToolResultFor;
    private Provider provider;

    public LlmConversationMatcher withTurnIndex(Integer turnIndex) {
        this.turnIndex = turnIndex;
        return this;
    }

    public Integer getTurnIndex() {
        return turnIndex;
    }

    public LlmConversationMatcher withLatestMessageContains(String text) {
        this.latestMessageContains = text;
        return this;
    }

    public String getLatestMessageContains() {
        return latestMessageContains;
    }

    /**
     * Set regex from a source string; the Pattern is compiled lazily on first match.
     */
    public LlmConversationMatcher withLatestMessageMatches(String regexSource) {
        this.latestMessageMatchesSource = regexSource;
        this.latestMessageMatches = null; // lazy-compile
        return this;
    }

    public LlmConversationMatcher withLatestMessageMatches(Pattern pattern) {
        this.latestMessageMatches = pattern;
        this.latestMessageMatchesSource = pattern != null ? pattern.pattern() : null;
        return this;
    }

    public Pattern getLatestMessageMatches() {
        if (latestMessageMatches == null && latestMessageMatchesSource != null) {
            latestMessageMatches = Pattern.compile(latestMessageMatchesSource);
        }
        return latestMessageMatches;
    }

    public String getLatestMessageMatchesSource() {
        return latestMessageMatchesSource;
    }

    public LlmConversationMatcher withLatestMessageRole(ParsedMessage.Role role) {
        this.latestMessageRole = role;
        return this;
    }

    public ParsedMessage.Role getLatestMessageRole() {
        return latestMessageRole;
    }

    public LlmConversationMatcher withContainsToolResultFor(String toolName) {
        this.containsToolResultFor = toolName;
        return this;
    }

    public String getContainsToolResultFor() {
        return containsToolResultFor;
    }

    public LlmConversationMatcher withProvider(Provider provider) {
        this.provider = provider;
        return this;
    }

    public Provider getProvider() {
        return provider;
    }

    /**
     * Returns true if any predicate is configured on this matcher.
     */
    public boolean hasPredicates() {
        return turnIndex != null
            || latestMessageContains != null
            || latestMessageMatchesSource != null
            || latestMessageRole != null
            || containsToolResultFor != null;
    }

    /**
     * Matches the given request against all configured predicates.
     * Returns true if no predicates are set. Returns false on parse failure,
     * oversize body, missing codec, or any predicate mismatch.
     * Never throws.
     */
    public boolean matches(HttpRequest request) {
        try {
            // If no predicate is set, return true (nothing to check)
            if (!hasPredicates()) {
                return true;
            }

            // Resolve codec
            if (provider == null) {
                LOGGER.debug("LLM conversation matcher has no provider set, returning no-match");
                return false;
            }
            Optional<ProviderCodec> codecOpt = ProviderCodecRegistry.getInstance().lookup(provider);
            if (!codecOpt.isPresent()) {
                LOGGER.debug("No codec registered for provider {}, returning no-match", provider);
                return false;
            }

            // Body-size cap
            byte[] bodyBytes = request != null ? request.getBodyAsRawBytes() : null;
            if (bodyBytes != null && bodyBytes.length > ConfigurationProperties.maxLlmConversationBodySize()) {
                LOGGER.debug("Request body size {} exceeds maxLlmConversationBodySize {}, skipping LLM conversation matching",
                    bodyBytes.length, ConfigurationProperties.maxLlmConversationBodySize());
                return false;
            }

            // Decode
            ParsedConversation parsed = codecOpt.get().decode(request);
            List<ParsedMessage> messages = parsed.getMessages();

            // If parse returned empty and we have predicates, no match
            if (messages.isEmpty()) {
                String bodySample = "";
                if (request != null) {
                    String bodyStr = request.getBodyAsString();
                    if (bodyStr != null) {
                        bodySample = bodyStr.substring(0, Math.min(bodyStr.length(), 256));
                    }
                }
                LOGGER.debug("LLM conversation parse returned empty for provider {}, body sample: {}", provider, bodySample);
                return false;
            }

            // Apply predicates with AND semantics

            // turnIndex: count of ASSISTANT messages
            if (turnIndex != null) {
                int assistantCount = 0;
                for (ParsedMessage msg : messages) {
                    if (msg.getRole() == ParsedMessage.Role.ASSISTANT) {
                        assistantCount++;
                    }
                }
                if (assistantCount != turnIndex) {
                    return false;
                }
            }

            // latestMessageContains: substring match against last message's text
            if (latestMessageContains != null) {
                ParsedMessage lastMessage = messages.get(messages.size() - 1);
                String text = lastMessage.getTextContent();
                if (text == null || !text.contains(latestMessageContains)) {
                    return false;
                }
            }

            // latestMessageMatches: regex match against last message's text
            if (latestMessageMatchesSource != null) {
                Pattern pattern = getLatestMessageMatches();
                ParsedMessage lastMessage = messages.get(messages.size() - 1);
                String text = lastMessage.getTextContent();
                if (text == null || !pattern.matcher(text).find()) {
                    return false;
                }
            }

            // latestMessageRole: equals last message role
            if (latestMessageRole != null) {
                ParsedMessage lastMessage = messages.get(messages.size() - 1);
                if (lastMessage.getRole() != latestMessageRole) {
                    return false;
                }
            }

            // containsToolResultFor: scan for tool call with matching name, then check for tool result
            if (containsToolResultFor != null) {
                if (!hasToolResultForName(messages, containsToolResultFor)) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            LOGGER.debug("LLM conversation matcher failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if the messages contain a tool result for a tool call with the given name.
     * Correlates by tool call ID: scans assistant messages for tool_calls to build
     * an {@code id -> name} mapping, then checks if any TOOL message has a tool result
     * keyed by an ID that maps to the given tool name.
     */
    private boolean hasToolResultForName(List<ParsedMessage> messages, String toolName) {
        // First pass: build id -> name map from assistant tool calls and collect the
        // distinct tool names called across the conversation. The distinct-name count
        // is needed to disambiguate the positional fallback used by providers that
        // emit tool results without correlatable IDs (Ollama emits empty-string keys).
        Map<String, String> idToName = new HashMap<>();
        Set<String> distinctToolCallNames = new HashSet<>();
        boolean hasToolCallWithName = false;
        for (ParsedMessage msg : messages) {
            if (msg.getRole() == ParsedMessage.Role.ASSISTANT && !msg.getToolCalls().isEmpty()) {
                for (ToolUse tc : msg.getToolCalls()) {
                    if (tc.getId() != null) {
                        idToName.put(tc.getId(), tc.getName());
                    }
                    if (tc.getName() != null) {
                        distinctToolCallNames.add(tc.getName());
                    }
                    if (toolName.equals(tc.getName())) {
                        hasToolCallWithName = true;
                    }
                }
            }
        }

        // Second pass: check TOOL messages for results correlated to the named tool.
        for (ParsedMessage msg : messages) {
            if (msg.getRole() == ParsedMessage.Role.TOOL && !msg.getToolResults().isEmpty()) {
                for (String resultId : msg.getToolResults().keySet()) {
                    // ID-based correlation (Anthropic, OpenAI Chat Completions, OpenAI Responses).
                    if (toolName.equals(idToName.get(resultId))) {
                        return true;
                    }
                    // Name-keyed correlation (Gemini's functionResponse uses the tool name as the
                    // map key — see GeminiCodec.decode).
                    if (toolName.equals(resultId)) {
                        return true;
                    }
                }
                // Positional fallback for providers that emit anonymous tool results
                // (Ollama uses empty-string keys). Only safe when the entire conversation
                // has called exactly one tool name AND that name is the one we're matching;
                // otherwise correlation is ambiguous and we must fail closed.
                if (hasToolCallWithName
                    && idToName.isEmpty()
                    && distinctToolCallNames.size() == 1
                    && distinctToolCallNames.contains(toolName)) {
                    return true;
                }
            }
        }

        return false;
    }
}
