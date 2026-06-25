package org.mockserver.llm.codec;

import org.junit.Test;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.model.HttpRequest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;

public class AnthropicCodecDecodeTest {

    private final AnthropicCodec codec = new AnthropicCodec();

    @Test
    public void shouldDecodeSimpleTextOnlyConversation() {
        // given
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"model\": \"claude-sonnet-4-20250514\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"user\", \"content\": \"What is the capital of France?\"}\n" +
                "  ]\n" +
                "}");

        // when
        ParsedConversation parsed = codec.decode(request);

        // then
        assertThat(parsed.getMessages(), hasSize(1));
        ParsedMessage msg = parsed.getMessages().get(0);
        assertThat(msg.getRole(), is(ParsedMessage.Role.USER));
        assertThat(msg.getTextContent(), is("What is the capital of France?"));
        assertThat(msg.getToolCalls(), is(empty()));
        assertThat(msg.getToolResults(), is(anEmptyMap()));
    }

    @Test
    public void shouldDecodeMultiTurnConversation() {
        // given
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"model\": \"claude-sonnet-4-20250514\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"user\", \"content\": \"Hello\"},\n" +
                "    {\"role\": \"assistant\", \"content\": [{\"type\": \"text\", \"text\": \"Hi there!\"}]},\n" +
                "    {\"role\": \"user\", \"content\": \"How are you?\"}\n" +
                "  ]\n" +
                "}");

        // when
        ParsedConversation parsed = codec.decode(request);

        // then
        assertThat(parsed.getMessages(), hasSize(3));
        assertThat(parsed.getMessages().get(0).getRole(), is(ParsedMessage.Role.USER));
        assertThat(parsed.getMessages().get(0).getTextContent(), is("Hello"));
        assertThat(parsed.getMessages().get(1).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(parsed.getMessages().get(1).getTextContent(), is("Hi there!"));
        assertThat(parsed.getMessages().get(2).getRole(), is(ParsedMessage.Role.USER));
        assertThat(parsed.getMessages().get(2).getTextContent(), is("How are you?"));
    }

    @Test
    public void shouldDecodeToolUseBlocks() {
        // given
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"model\": \"claude-sonnet-4-20250514\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"user\", \"content\": \"What is the weather?\"},\n" +
                "    {\"role\": \"assistant\", \"content\": [\n" +
                "      {\"type\": \"text\", \"text\": \"Let me check.\"},\n" +
                "      {\"type\": \"tool_use\", \"id\": \"toolu_abc\", \"name\": \"get_weather\", \"input\": {\"city\": \"Paris\"}}\n" +
                "    ]}\n" +
                "  ]\n" +
                "}");

        // when
        ParsedConversation parsed = codec.decode(request);

        // then
        assertThat(parsed.getMessages(), hasSize(2));

        ParsedMessage assistantMsg = parsed.getMessages().get(1);
        assertThat(assistantMsg.getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(assistantMsg.getTextContent(), is("Let me check."));
        assertThat(assistantMsg.getToolCalls(), hasSize(1));
        assertThat(assistantMsg.getToolCalls().get(0).getName(), is("get_weather"));
        assertThat(assistantMsg.getToolCalls().get(0).getId(), is("toolu_abc"));
        assertThat(assistantMsg.getToolCalls().get(0).getArguments(), containsString("Paris"));
    }

    @Test
    public void shouldDecodeToolResultBlocksAsToolRole() {
        // given - Anthropic sends tool_result in a "user" role message
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"model\": \"claude-sonnet-4-20250514\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"user\", \"content\": \"Weather?\"},\n" +
                "    {\"role\": \"assistant\", \"content\": [\n" +
                "      {\"type\": \"tool_use\", \"id\": \"toolu_abc\", \"name\": \"get_weather\", \"input\": {\"city\": \"Paris\"}}\n" +
                "    ]},\n" +
                "    {\"role\": \"user\", \"content\": [\n" +
                "      {\"type\": \"tool_result\", \"tool_use_id\": \"toolu_abc\", \"content\": \"18C and sunny\"}\n" +
                "    ]}\n" +
                "  ]\n" +
                "}");

        // when
        ParsedConversation parsed = codec.decode(request);

        // then
        assertThat(parsed.getMessages(), hasSize(3));

        // The tool_result message should be role=TOOL (even though Anthropic uses "user")
        ParsedMessage toolResultMsg = parsed.getMessages().get(2);
        assertThat(toolResultMsg.getRole(), is(ParsedMessage.Role.TOOL));
        assertThat(toolResultMsg.getToolResults(), hasEntry("toolu_abc", "18C and sunny"));
    }

    @Test
    public void shouldDecodeToolResultWithArrayContent() {
        // given
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"model\": \"claude-sonnet-4-20250514\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"user\", \"content\": [\n" +
                "      {\"type\": \"tool_result\", \"tool_use_id\": \"toolu_xyz\", \"content\": [{\"type\": \"text\", \"text\": \"result data\"}]}\n" +
                "    ]}\n" +
                "  ]\n" +
                "}");

        // when
        ParsedConversation parsed = codec.decode(request);

        // then
        assertThat(parsed.getMessages(), hasSize(1));
        ParsedMessage msg = parsed.getMessages().get(0);
        assertThat(msg.getRole(), is(ParsedMessage.Role.TOOL));
        assertThat(msg.getToolResults(), hasEntry("toolu_xyz", "result data"));
    }

    @Test
    public void shouldReturnEmptyForMalformedJson() {
        // given
        HttpRequest request = request().withBody("this is not json");

        // when
        ParsedConversation parsed = codec.decode(request);

        // then
        assertThat(parsed.getMessages(), is(empty()));
    }

    @Test
    public void shouldReturnEmptyForNullBody() {
        // given
        HttpRequest request = request();

        // when
        ParsedConversation parsed = codec.decode(request);

        // then
        assertThat(parsed.getMessages(), is(empty()));
    }

    @Test
    public void shouldReturnEmptyForEmptyBody() {
        // given
        HttpRequest request = request().withBody("");

        // when
        ParsedConversation parsed = codec.decode(request);

        // then
        assertThat(parsed.getMessages(), is(empty()));
    }

    @Test
    public void shouldReturnEmptyForWrongShapeBody() {
        // given - no "messages" array
        HttpRequest request = request().withBody("{\"model\": \"claude-sonnet-4-20250514\"}");

        // when
        ParsedConversation parsed = codec.decode(request);

        // then
        assertThat(parsed.getMessages(), is(empty()));
    }

    @Test
    public void shouldReturnEmptyForNullRequest() {
        // when
        ParsedConversation parsed = codec.decode(null);

        // then
        assertThat(parsed.getMessages(), is(empty()));
    }

    @Test
    public void shouldHandleContentAsString() {
        // given - content is a plain string, not an array
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"user\", \"content\": \"plain string content\"}\n" +
                "  ]\n" +
                "}");

        // when
        ParsedConversation parsed = codec.decode(request);

        // then
        assertThat(parsed.getMessages(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getTextContent(), is("plain string content"));
    }

    @Test
    public void shouldConcatenateMultipleTextBlocks() {
        // given
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"assistant\", \"content\": [\n" +
                "      {\"type\": \"text\", \"text\": \"Part one. \"},\n" +
                "      {\"type\": \"text\", \"text\": \"Part two.\"}\n" +
                "    ]}\n" +
                "  ]\n" +
                "}");

        // when
        ParsedConversation parsed = codec.decode(request);

        // then
        assertThat(parsed.getMessages(), hasSize(1));
        assertThat(parsed.getMessages().get(0).getTextContent(), is("Part one. Part two."));
    }

    @Test
    public void shouldDecodeFullToolUseToToolResultLoop() {
        // given - full round trip: user asks, assistant uses tool, user sends tool result, assistant answers
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"model\": \"claude-sonnet-4-20250514\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"user\", \"content\": \"What is the weather in Paris?\"},\n" +
                "    {\"role\": \"assistant\", \"content\": [\n" +
                "      {\"type\": \"text\", \"text\": \"Let me check the weather.\"},\n" +
                "      {\"type\": \"tool_use\", \"id\": \"toolu_123\", \"name\": \"get_weather\", \"input\": {\"city\": \"Paris\"}}\n" +
                "    ]},\n" +
                "    {\"role\": \"user\", \"content\": [\n" +
                "      {\"type\": \"tool_result\", \"tool_use_id\": \"toolu_123\", \"content\": \"18C and sunny in Paris\"}\n" +
                "    ]},\n" +
                "    {\"role\": \"assistant\", \"content\": [{\"type\": \"text\", \"text\": \"It is 18C and sunny in Paris.\"}]}\n" +
                "  ]\n" +
                "}");

        // when
        ParsedConversation parsed = codec.decode(request);

        // then
        assertThat(parsed.getMessages(), hasSize(4));

        assertThat(parsed.getMessages().get(0).getRole(), is(ParsedMessage.Role.USER));
        assertThat(parsed.getMessages().get(1).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(parsed.getMessages().get(1).getToolCalls(), hasSize(1));
        assertThat(parsed.getMessages().get(1).getToolCalls().get(0).getName(), is("get_weather"));
        assertThat(parsed.getMessages().get(1).getToolCalls().get(0).getId(), is("toolu_123"));

        assertThat(parsed.getMessages().get(2).getRole(), is(ParsedMessage.Role.TOOL));
        assertThat(parsed.getMessages().get(2).getToolResults(), hasEntry("toolu_123", "18C and sunny in Paris"));

        assertThat(parsed.getMessages().get(3).getRole(), is(ParsedMessage.Role.ASSISTANT));
        assertThat(parsed.getMessages().get(3).getTextContent(), is("It is 18C and sunny in Paris."));
    }

    @Test
    public void shouldMapTopLevelStringSystemPromptToLeadingSystemMessage() {
        // given - Anthropic carries the system prompt in a top-level "system" string field
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"model\": \"claude-sonnet-4-20250514\",\n" +
                "  \"system\": \"You are a helpful assistant.\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"user\", \"content\": \"Hello\"}\n" +
                "  ]\n" +
                "}");

        // when
        ParsedConversation parsed = codec.decode(request);

        // then - SYSTEM message is prepended, mirroring OpenAI-shaped traffic
        assertThat(parsed.getMessages(), hasSize(2));
        assertThat(parsed.getMessages().get(0).getRole(), is(ParsedMessage.Role.SYSTEM));
        assertThat(parsed.getMessages().get(0).getTextContent(), is("You are a helpful assistant."));
        assertThat(parsed.getMessages().get(1).getRole(), is(ParsedMessage.Role.USER));
        assertThat(parsed.getMessages().get(1).getTextContent(), is("Hello"));
    }

    @Test
    public void shouldConcatenateTopLevelSystemPromptContentBlocks() {
        // given - Anthropic also allows "system" as an array of text content blocks (with cache_control)
        HttpRequest request = request()
            .withBody("{\n" +
                "  \"model\": \"claude-sonnet-4-20250514\",\n" +
                "  \"system\": [\n" +
                "    {\"type\": \"text\", \"text\": \"Part one. \"},\n" +
                "    {\"type\": \"text\", \"text\": \"Part two.\", \"cache_control\": {\"type\": \"ephemeral\"}}\n" +
                "  ],\n" +
                "  \"messages\": [\n" +
                "    {\"role\": \"user\", \"content\": \"Hi\"}\n" +
                "  ]\n" +
                "}");

        // when
        ParsedConversation parsed = codec.decode(request);

        // then
        assertThat(parsed.getMessages(), hasSize(2));
        assertThat(parsed.getMessages().get(0).getRole(), is(ParsedMessage.Role.SYSTEM));
        assertThat(parsed.getMessages().get(0).getTextContent(), is("Part one. Part two."));
        assertThat(parsed.getMessages().get(1).getRole(), is(ParsedMessage.Role.USER));
    }

    @Test
    public void shouldNotAddSystemMessageWhenSystemFieldAbsentOrBlank() {
        // given - no system field, plus an explicit empty-string system field
        HttpRequest noSystem = request()
            .withBody("{\"model\": \"claude-sonnet-4-20250514\", \"messages\": [{\"role\": \"user\", \"content\": \"Hi\"}]}");
        HttpRequest blankSystem = request()
            .withBody("{\"model\": \"claude-sonnet-4-20250514\", \"system\": \"\", \"messages\": [{\"role\": \"user\", \"content\": \"Hi\"}]}");

        // when / then - no SYSTEM message is synthesised in either case
        assertThat(codec.decode(noSystem).getMessages(), hasSize(1));
        assertThat(codec.decode(noSystem).getMessages().get(0).getRole(), is(ParsedMessage.Role.USER));
        assertThat(codec.decode(blankSystem).getMessages(), hasSize(1));
        assertThat(codec.decode(blankSystem).getMessages().get(0).getRole(), is(ParsedMessage.Role.USER));
    }
}
