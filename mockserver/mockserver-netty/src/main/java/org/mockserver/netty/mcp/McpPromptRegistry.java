package org.mockserver.netty.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registry of MCP prompts exposed by MockServer's own MCP server.
 * <p>
 * Mirrors {@link McpResourceRegistry} and {@link McpToolRegistry}: each prompt is
 * statically configured here with a name, description, an ordered list of
 * {@link PromptArgument}s and a message template. {@code prompts/list} returns the
 * prompt metadata; {@code prompts/get} returns a prompt's messages with the supplied
 * arguments substituted into {@code {{argument}}} placeholders.
 */
public class McpPromptRegistry {

    private final ObjectMapper objectMapper;
    private final Map<String, PromptDefinition> prompts;

    public McpPromptRegistry() {
        this.objectMapper = ObjectMapperFactory.createObjectMapper();
        this.prompts = new LinkedHashMap<>();
        registerAllPrompts();
    }

    public Map<String, PromptDefinition> getPrompts() {
        return prompts;
    }

    private void registerAllPrompts() {
        prompts.put("debug_unmatched_request", new PromptDefinition(
            "debug_unmatched_request",
            "Guides debugging of a request that matched no MockServer expectation",
            List.of(
                new PromptArgument("method", "HTTP method of the request that did not match", true),
                new PromptArgument("path", "Path of the request that did not match", true)
            ),
            "user",
            "A {{method}} request to {{path}} did not match any MockServer expectation. " +
                "Call the 'explain_unmatched_requests' tool, then summarise the closest expectation " +
                "and the field-level differences, and suggest a fix."
        ));

        prompts.put("create_mock_from_description", new PromptDefinition(
            "create_mock_from_description",
            "Turns a natural-language description of an endpoint into a MockServer expectation",
            List.of(
                new PromptArgument("description", "Plain-language description of the endpoint to mock", true)
            ),
            "user",
            "Create a MockServer expectation for the following endpoint using the 'create_expectation' tool: " +
                "{{description}}"
        ));
    }

    /**
     * Build the messages for a named prompt, substituting {@code {{argument}}} placeholders
     * with the supplied argument values. Returns {@code null} when the prompt is unknown.
     */
    public ArrayNode getMessages(String name, JsonNode arguments) {
        PromptDefinition prompt = prompts.get(name);
        if (prompt == null) {
            return null;
        }

        String text = prompt.template;
        if (arguments != null && arguments.isObject()) {
            // Collect substitutions first, then apply them in a single left-to-right pass over the
            // template, so an argument value that happens to contain another argument's {{placeholder}}
            // is never itself re-substituted (no cascade injection).
            Map<String, String> substitutions = new HashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = arguments.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode valueNode = entry.getValue();
                String value = valueNode.isTextual() ? valueNode.asText() : valueNode.toString();
                substitutions.put(entry.getKey(), value);
            }
            text = applySubstitutions(text, substitutions);
        }

        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", prompt.role);
        ObjectNode content = message.putObject("content");
        content.put("type", "text");
        content.put("text", text);
        messages.add(message);
        return messages;
    }

    /** Matches a {@code {{name}}} placeholder where {@code name} is a typical argument identifier. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z0-9_]+)}}");

    /**
     * Replace every {@code {{name}}} placeholder in {@code template} with its value from
     * {@code substitutions} in a single left-to-right pass. Placeholders with no matching argument
     * are left untouched; substituted values are inserted literally and never re-scanned for further
     * placeholders.
     */
    private static String applySubstitutions(String template, Map<String, String> substitutions) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = substitutions.containsKey(key) ? substitutions.get(key) : matcher.group(0);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static class PromptArgument {
        private final String name;
        private final String description;
        private final boolean required;

        public PromptArgument(String name, String description, boolean required) {
            this.name = name;
            this.description = description;
            this.required = required;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public boolean isRequired() {
            return required;
        }
    }

    public static class PromptDefinition {
        private final String name;
        private final String description;
        private final List<PromptArgument> arguments;
        private final String role;
        private final String template;

        public PromptDefinition(String name, String description, List<PromptArgument> arguments, String role, String template) {
            this.name = name;
            this.description = description;
            this.arguments = arguments;
            this.role = role;
            this.template = template;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public List<PromptArgument> getArguments() {
            return arguments;
        }

        public String getRole() {
            return role;
        }

        public String getTemplate() {
            return template;
        }
    }
}
