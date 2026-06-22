package org.mockserver.netty.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class McpPromptRegistryTest {

    private McpPromptRegistry promptRegistry;
    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        promptRegistry = new McpPromptRegistry();
        objectMapper = ObjectMapperFactory.createObjectMapper();
    }

    @Test
    public void shouldRegisterPrompts() {
        Map<String, McpPromptRegistry.PromptDefinition> prompts = promptRegistry.getPrompts();
        assertThat(prompts.isEmpty(), is(false));
        assertThat(prompts.containsKey("debug_unmatched_request"), is(true));
        assertThat(prompts.containsKey("create_mock_from_description"), is(true));
    }

    @Test
    public void shouldHavePromptDefinitionsWithMetadata() {
        for (McpPromptRegistry.PromptDefinition prompt : promptRegistry.getPrompts().values()) {
            assertThat(prompt.getName(), notNullValue());
            assertThat(prompt.getDescription(), notNullValue());
            assertThat(prompt.getArguments(), notNullValue());
            assertThat(prompt.getRole(), notNullValue());
            assertThat(prompt.getTemplate(), notNullValue());
        }
    }

    @Test
    public void shouldSubstituteArgumentsIntoMessages() {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("method", "GET");
        arguments.put("path", "/users/1");

        ArrayNode messages = promptRegistry.getMessages("debug_unmatched_request", arguments);

        assertThat(messages, notNullValue());
        assertThat(messages.size(), is(1));
        assertThat(messages.get(0).path("role").asText(), is("user"));
        String text = messages.get(0).path("content").path("text").asText();
        assertThat(text.contains("GET"), is(true));
        assertThat(text.contains("/users/1"), is(true));
        assertThat(text.contains("{{method}}"), is(false));
        assertThat(text.contains("{{path}}"), is(false));
    }

    @Test
    public void shouldLeavePlaceholdersWhenArgumentsMissing() {
        ArrayNode messages = promptRegistry.getMessages("debug_unmatched_request", null);

        assertThat(messages, notNullValue());
        String text = messages.get(0).path("content").path("text").asText();
        assertThat(text.contains("{{method}}"), is(true));
    }

    @Test
    public void shouldNotReSubstitutePlaceholdersIntroducedByArgumentValues() {
        // A value that itself contains another argument's placeholder must be inserted literally,
        // not re-scanned and replaced (no cascade substitution).
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("method", "{{path}}");
        arguments.put("path", "/secret");

        ArrayNode messages = promptRegistry.getMessages("debug_unmatched_request", arguments);

        String text = messages.get(0).path("content").path("text").asText();
        assertThat(text.contains("{{path}}"), is(true));
        assertThat(text.contains("/secret"), is(true));
    }

    @Test
    public void shouldReturnNullMessagesForUnknownPrompt() {
        ArrayNode messages = promptRegistry.getMessages("nonexistent", objectMapper.createObjectNode());
        assertThat(messages, nullValue());
    }

    @Test
    public void shouldNarrowMessageContentToTextType() {
        ArrayNode messages = promptRegistry.getMessages("create_mock_from_description", objectMapper.createObjectNode());
        JsonNode content = messages.get(0).path("content");
        assertThat(content.path("type").asText(), is("text"));
    }
}
