package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link BedrockEventStreamEncoder}: encode/decode round-trip,
 * CRC32 validation, header parsing, base64 payload wrapping, and multi-message
 * stream concatenation.
 */
public class BedrockEventStreamEncoderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // --- Single message round-trip ---

    @Test
    public void shouldRoundTripSingleChunk() throws Exception {
        String chunkJson = "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}";

        byte[] encoded = BedrockEventStreamEncoder.encodeChunk(chunkJson);
        List<BedrockEventStreamEncoder.DecodedMessage> messages = BedrockEventStreamEncoder.decode(encoded);

        assertThat(messages, hasSize(1));

        BedrockEventStreamEncoder.DecodedMessage msg = messages.get(0);

        // Verify headers
        assertThat(msg.getHeaders().get(":event-type"), is("chunk"));
        assertThat(msg.getHeaders().get(":content-type"), is("application/json"));
        assertThat(msg.getHeaders().get(":message-type"), is("event"));

        // Verify payload wraps base64-encoded chunk
        JsonNode payloadNode = OBJECT_MAPPER.readTree(msg.getPayloadAsString());
        assertThat(payloadNode.has("bytes"), is(true));
        String base64Bytes = payloadNode.get("bytes").asText();
        String decoded = new String(Base64.getDecoder().decode(base64Bytes), StandardCharsets.UTF_8);
        assertThat(decoded, is(chunkJson));
    }

    @Test
    public void shouldPreserveCrc32IntegrityOnEncode() {
        String chunkJson = "{\"text\":\"test\"}";
        byte[] encoded = BedrockEventStreamEncoder.encodeChunk(chunkJson);
        ByteBuffer buffer = ByteBuffer.wrap(encoded);

        int totalLength = buffer.getInt();
        assertThat("total length must match byte array length", totalLength, is(encoded.length));

        int headersLength = buffer.getInt();

        // Prelude CRC
        CRC32 preludeCrc = new CRC32();
        preludeCrc.update(encoded, 0, 8);
        int preludeCrcValue = buffer.getInt();
        assertThat("prelude CRC must validate",
            preludeCrcValue, is((int) preludeCrc.getValue()));

        // Message CRC
        CRC32 messageCrc = new CRC32();
        messageCrc.update(encoded, 0, totalLength - 4);
        int messageCrcValue = ByteBuffer.wrap(encoded, totalLength - 4, 4).getInt();
        assertThat("message CRC must validate",
            messageCrcValue, is((int) messageCrc.getValue()));
    }

    // --- Multi-message stream ---

    @Test
    public void shouldEncodeAndDecodeMultiMessageStream() throws Exception {
        List<String> chunks = Arrays.asList(
            "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\"}}",
            "{\"type\":\"content_block_delta\",\"delta\":{\"text\":\"Hello\"}}",
            "{\"type\":\"content_block_delta\",\"delta\":{\"text\":\" world\"}}",
            "{\"type\":\"message_stop\"}"
        );

        byte[] stream = BedrockEventStreamEncoder.encodeStream(chunks);
        List<BedrockEventStreamEncoder.DecodedMessage> messages = BedrockEventStreamEncoder.decode(stream);

        assertThat(messages, hasSize(4));

        // Each message should decode back to the original chunk
        for (int i = 0; i < chunks.size(); i++) {
            BedrockEventStreamEncoder.DecodedMessage msg = messages.get(i);
            assertThat(msg.getHeaders().get(":event-type"), is("chunk"));
            assertThat(msg.getHeaders().get(":message-type"), is("event"));

            JsonNode payloadNode = OBJECT_MAPPER.readTree(msg.getPayloadAsString());
            String decoded = new String(
                Base64.getDecoder().decode(payloadNode.get("bytes").asText()),
                StandardCharsets.UTF_8
            );
            assertThat("chunk " + i + " should round-trip", decoded, is(chunks.get(i)));
        }
    }

    @Test
    public void shouldProduceConcatenatedBytesForMultipleChunks() {
        List<String> chunks = Arrays.asList("{\"a\":1}", "{\"b\":2}");
        byte[] stream = BedrockEventStreamEncoder.encodeStream(chunks);

        byte[] chunk1 = BedrockEventStreamEncoder.encodeChunk(chunks.get(0));
        byte[] chunk2 = BedrockEventStreamEncoder.encodeChunk(chunks.get(1));

        assertThat("stream length must equal sum of individual chunks",
            stream.length, is(chunk1.length + chunk2.length));

        // First chunk's bytes match
        byte[] firstPart = Arrays.copyOf(stream, chunk1.length);
        assertThat(firstPart, is(chunk1));

        // Second chunk's bytes match
        byte[] secondPart = Arrays.copyOfRange(stream, chunk1.length, stream.length);
        assertThat(secondPart, is(chunk2));
    }

    // --- CRC corruption detection ---

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectCorruptedPreludeCrc() {
        byte[] encoded = BedrockEventStreamEncoder.encodeChunk("{\"test\":true}");
        // Corrupt the prelude CRC (bytes 8-11)
        encoded[8] = (byte) ~encoded[8];
        BedrockEventStreamEncoder.decode(encoded);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectCorruptedMessageCrc() {
        byte[] encoded = BedrockEventStreamEncoder.encodeChunk("{\"test\":true}");
        // Corrupt the message CRC (last 4 bytes)
        encoded[encoded.length - 1] = (byte) ~encoded[encoded.length - 1];
        BedrockEventStreamEncoder.decode(encoded);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectTruncatedMessage() {
        byte[] encoded = BedrockEventStreamEncoder.encodeChunk("{\"test\":true}");
        // Truncate to just the prelude
        byte[] truncated = Arrays.copyOf(encoded, 8);
        BedrockEventStreamEncoder.decode(truncated);
    }

    // --- Header structure ---

    @Test
    public void shouldEncodeThreeHeaders() throws Exception {
        byte[] encoded = BedrockEventStreamEncoder.encodeChunk("{\"x\":1}");
        List<BedrockEventStreamEncoder.DecodedMessage> messages = BedrockEventStreamEncoder.decode(encoded);

        Map<String, String> headers = messages.get(0).getHeaders();
        assertThat(headers.size(), is(3));
        assertThat(headers, hasKey(":event-type"));
        assertThat(headers, hasKey(":content-type"));
        assertThat(headers, hasKey(":message-type"));
    }

    // --- Payload structure ---

    @Test
    public void shouldProduceValidBase64InPayload() throws Exception {
        String chunkJson = "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_abc\",\"model\":\"claude-3\"}}";
        byte[] encoded = BedrockEventStreamEncoder.encodeChunk(chunkJson);
        List<BedrockEventStreamEncoder.DecodedMessage> messages = BedrockEventStreamEncoder.decode(encoded);

        String payloadStr = messages.get(0).getPayloadAsString();
        JsonNode payloadNode = OBJECT_MAPPER.readTree(payloadStr);

        // Payload must be {"bytes":"..."}
        assertThat(payloadNode.fieldNames().hasNext(), is(true));
        assertThat(payloadNode.has("bytes"), is(true));
        assertThat(payloadNode.size(), is(1));

        // Base64 must be valid and decode to the original chunk JSON
        String base64 = payloadNode.get("bytes").asText();
        byte[] decodedBytes = Base64.getDecoder().decode(base64);
        assertThat(new String(decodedBytes, StandardCharsets.UTF_8), is(chunkJson));
    }

    // --- Empty and edge cases ---

    @Test
    public void shouldHandleEmptyChunkJson() throws Exception {
        byte[] encoded = BedrockEventStreamEncoder.encodeChunk("");
        List<BedrockEventStreamEncoder.DecodedMessage> messages = BedrockEventStreamEncoder.decode(encoded);

        assertThat(messages, hasSize(1));
        JsonNode payloadNode = OBJECT_MAPPER.readTree(messages.get(0).getPayloadAsString());
        String decoded = new String(
            Base64.getDecoder().decode(payloadNode.get("bytes").asText()),
            StandardCharsets.UTF_8
        );
        assertThat(decoded, is(""));
    }

    @Test
    public void shouldHandleUnicodeInChunkJson() throws Exception {
        String chunkJson = "{\"text\":\"Hello \\u4e16\\u754c \\ud83d\\ude00\"}";
        byte[] encoded = BedrockEventStreamEncoder.encodeChunk(chunkJson);
        List<BedrockEventStreamEncoder.DecodedMessage> messages = BedrockEventStreamEncoder.decode(encoded);

        JsonNode payloadNode = OBJECT_MAPPER.readTree(messages.get(0).getPayloadAsString());
        String decoded = new String(
            Base64.getDecoder().decode(payloadNode.get("bytes").asText()),
            StandardCharsets.UTF_8
        );
        assertThat(decoded, is(chunkJson));
    }

    @Test
    public void shouldHandleEmptyStream() {
        byte[] stream = BedrockEventStreamEncoder.encodeStream(List.of());
        assertThat(stream.length, is(0));

        List<BedrockEventStreamEncoder.DecodedMessage> messages = BedrockEventStreamEncoder.decode(stream);
        assertThat(messages, is(empty()));
    }

    // --- Content type constant ---

    @Test
    public void shouldExposeCorrectContentType() {
        assertThat(BedrockEventStreamEncoder.CONTENT_TYPE,
            is("application/vnd.amazon.eventstream"));
    }
}
