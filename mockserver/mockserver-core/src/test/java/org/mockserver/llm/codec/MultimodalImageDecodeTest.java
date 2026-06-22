package org.mockserver.llm.codec;

import org.junit.Test;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpRequest.request;

/**
 * Behavioural tests that image content parts are recognised on the request side
 * for OpenAI, Anthropic, and Gemini so conversation matchers can assert that a
 * mocked LLM request contains an image (and how many / what media type).
 */
public class MultimodalImageDecodeTest {

    @Test
    public void shouldRecogniseOpenAiImageUrlPart() {
        ParsedConversation parsed = new OpenAiChatCompletionsCodec().decode(request()
            .withBody("{\"model\":\"gpt-4o\",\"messages\":[" +
                "{\"role\":\"user\",\"content\":[" +
                "{\"type\":\"text\",\"text\":\"what is this?\"}," +
                "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AAAA\"}}" +
                "]}]}"));

        assertThat(parsed.getMessages(), hasSize(1));
        ParsedMessage msg = parsed.getMessages().get(0);
        assertThat(msg.hasImage(), is(true));
        assertThat(msg.imageCount(), is(1));
        assertThat(msg.getImages().get(0).getMediaType(), is("image/png"));
        assertThat(msg.getTextContent(), is("what is this?"));
    }

    @Test
    public void shouldRecogniseOpenAiRemoteImageUrlWithoutMediaType() {
        ParsedConversation parsed = new OpenAiChatCompletionsCodec().decode(request()
            .withBody("{\"model\":\"gpt-4o\",\"messages\":[" +
                "{\"role\":\"user\",\"content\":[" +
                "{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.com/cat.jpg\"}}" +
                "]}]}"));

        ParsedMessage msg = parsed.getMessages().get(0);
        assertThat(msg.hasImage(), is(true));
        // remote URL carries no declared media type
        assertThat(msg.getImages().get(0).getMediaType(), is((String) null));
    }

    @Test
    public void shouldRecogniseAnthropicImageBlock() {
        ParsedConversation parsed = new AnthropicCodec().decode(request()
            .withBody("{\"model\":\"claude-3-5-sonnet\",\"messages\":[" +
                "{\"role\":\"user\",\"content\":[" +
                "{\"type\":\"text\",\"text\":\"describe\"}," +
                "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/jpeg\",\"data\":\"AAAA\"}}" +
                "]}]}"));

        ParsedMessage msg = parsed.getMessages().get(0);
        assertThat(msg.hasImage(), is(true));
        assertThat(msg.imageCount(), is(1));
        assertThat(msg.getImages().get(0).getMediaType(), is("image/jpeg"));
    }

    @Test
    public void shouldRecogniseGeminiInlineDataPart() {
        // REST snake_case shape
        ParsedConversation parsed = new GeminiCodec().decode(request()
            .withBody("{\"contents\":[" +
                "{\"role\":\"user\",\"parts\":[" +
                "{\"text\":\"caption this\"}," +
                "{\"inline_data\":{\"mime_type\":\"image/webp\",\"data\":\"AAAA\"}}" +
                "]}]}"));

        ParsedMessage msg = parsed.getMessages().get(0);
        assertThat(msg.hasImage(), is(true));
        assertThat(msg.getImages().get(0).getMediaType(), is("image/webp"));
    }

    @Test
    public void shouldRecogniseGeminiInlineDataCamelCase() {
        // SDK camelCase shape
        ParsedConversation parsed = new GeminiCodec().decode(request()
            .withBody("{\"contents\":[" +
                "{\"role\":\"user\",\"parts\":[" +
                "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"AAAA\"}}" +
                "]}]}"));

        assertThat(parsed.getMessages().get(0).hasImage(), is(true));
        assertThat(parsed.getMessages().get(0).getImages().get(0).getMediaType(), is("image/png"));
    }

    @Test
    public void shouldReportNoImageForTextOnlyMessage() {
        ParsedConversation parsed = new OpenAiChatCompletionsCodec().decode(request()
            .withBody("{\"model\":\"gpt-4o\",\"messages\":[" +
                "{\"role\":\"user\",\"content\":\"just text\"}]}"));

        ParsedMessage msg = parsed.getMessages().get(0);
        assertThat(msg.hasImage(), is(false));
        assertThat(msg.imageCount(), is(0));
    }

    @Test
    public void shouldRecogniseOpenAiInputAudioPart() {
        ParsedConversation parsed = new OpenAiChatCompletionsCodec().decode(request()
            .withBody("{\"model\":\"gpt-4o-audio-preview\",\"messages\":[" +
                "{\"role\":\"user\",\"content\":[" +
                "{\"type\":\"text\",\"text\":\"transcribe this\"}," +
                "{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"AAAA\",\"format\":\"wav\"}}" +
                "]}]}"));

        assertThat(parsed.getMessages(), hasSize(1));
        ParsedMessage msg = parsed.getMessages().get(0);
        assertThat(msg.hasAudio(), is(true));
        assertThat(msg.audioCount(), is(1));
        assertThat(msg.getAudio().get(0).getFormat(), is("wav"));
        assertThat(msg.getTextContent(), is("transcribe this"));
        // audio and image parts are tracked independently
        assertThat(msg.hasImage(), is(false));
    }

    @Test
    public void shouldRecogniseOpenAiInputAudioWithoutFormat() {
        ParsedConversation parsed = new OpenAiChatCompletionsCodec().decode(request()
            .withBody("{\"model\":\"gpt-4o-audio-preview\",\"messages\":[" +
                "{\"role\":\"user\",\"content\":[" +
                "{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"AAAA\"}}" +
                "]}]}"));

        ParsedMessage msg = parsed.getMessages().get(0);
        assertThat(msg.hasAudio(), is(true));
        // a missing format leaves the declared format null
        assertThat(msg.getAudio().get(0).getFormat(), is((String) null));
    }

    @Test
    public void shouldRecogniseBothImageAndAudioInOneMessage() {
        ParsedConversation parsed = new OpenAiChatCompletionsCodec().decode(request()
            .withBody("{\"model\":\"gpt-4o-audio-preview\",\"messages\":[" +
                "{\"role\":\"user\",\"content\":[" +
                "{\"type\":\"text\",\"text\":\"describe both\"}," +
                "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AAAA\"}}," +
                "{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"BBBB\",\"format\":\"mp3\"}}" +
                "]}]}"));

        ParsedMessage msg = parsed.getMessages().get(0);
        assertThat(msg.hasImage(), is(true));
        assertThat(msg.imageCount(), is(1));
        assertThat(msg.getImages().get(0).getMediaType(), is("image/png"));
        assertThat(msg.hasAudio(), is(true));
        assertThat(msg.audioCount(), is(1));
        assertThat(msg.getAudio().get(0).getFormat(), is("mp3"));
    }

    @Test
    public void shouldReportNoAudioForTextOnlyMessage() {
        ParsedConversation parsed = new OpenAiChatCompletionsCodec().decode(request()
            .withBody("{\"model\":\"gpt-4o\",\"messages\":[" +
                "{\"role\":\"user\",\"content\":\"just text\"}]}"));

        ParsedMessage msg = parsed.getMessages().get(0);
        assertThat(msg.hasAudio(), is(false));
        assertThat(msg.audioCount(), is(0));
    }
}
