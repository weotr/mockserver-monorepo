package org.mockserver.llm.codec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Encoder and decoder for the AWS event-stream binary message format
 * ({@code application/vnd.amazon.eventstream}).
 * <p>
 * Each message has the following layout:
 * <pre>
 *   4 bytes  — total byte length (big-endian)
 *   4 bytes  — headers byte length (big-endian)
 *   4 bytes  — prelude CRC32 (of the first 8 bytes)
 *   N bytes  — headers
 *   M bytes  — payload
 *   4 bytes  — message CRC32 (of everything up to this point)
 * </pre>
 * <p>
 * For Bedrock {@code InvokeModelWithResponseStream}, each "chunk" event carries
 * headers {@code :event-type=chunk}, {@code :content-type=application/json},
 * {@code :message-type=event}, and the payload is
 * {@code {"bytes":"<base64(chunkJson)>"}}.
 * <p>
 * This class uses only JDK APIs ({@link java.util.zip.CRC32},
 * {@link java.util.Base64}) and introduces no new Maven dependencies.
 */
public final class BedrockEventStreamEncoder {

    /** The MIME type for AWS event-stream framing. */
    public static final String CONTENT_TYPE = "application/vnd.amazon.eventstream";

    /** Header value type for strings (per the AWS event-stream spec). */
    private static final byte HEADER_VALUE_TYPE_STRING = 7;

    /** Prelude size: 4 (total length) + 4 (headers length) = 8 bytes. */
    private static final int PRELUDE_SIZE = 8;

    /** CRC size: 4 bytes. */
    private static final int CRC_SIZE = 4;

    private BedrockEventStreamEncoder() {
        // utility class
    }

    /**
     * Encode a single model chunk (JSON string) into one AWS event-stream
     * binary message. The chunk JSON is base64-encoded and wrapped in a
     * {@code {"bytes":"<base64>"}} payload, matching Bedrock's
     * {@code InvokeModelWithResponseStream} wire format.
     *
     * @param chunkJson the model's streaming chunk JSON (e.g. an Anthropic SSE data payload)
     * @return the complete binary event-stream message
     */
    public static byte[] encodeChunk(String chunkJson) {
        String base64Payload = Base64.getEncoder().encodeToString(
            chunkJson.getBytes(StandardCharsets.UTF_8)
        );
        String payloadJson = "{\"bytes\":\"" + base64Payload + "\"}";
        byte[] payload = payloadJson.getBytes(StandardCharsets.UTF_8);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(":event-type", "chunk");
        headers.put(":content-type", "application/json");
        headers.put(":message-type", "event");

        return encodeMessage(headers, payload);
    }

    /**
     * Encode a full event-stream binary message with the given headers and payload.
     *
     * @param headers string-typed headers (name to value)
     * @param payload the raw payload bytes
     * @return the complete binary message
     */
    public static byte[] encodeMessage(Map<String, String> headers, byte[] payload) {
        byte[] headerBytes = encodeHeaders(headers);
        int headersLength = headerBytes.length;
        int totalLength = PRELUDE_SIZE + CRC_SIZE + headersLength + payload.length + CRC_SIZE;

        ByteBuffer buffer = ByteBuffer.allocate(totalLength);

        // Prelude: total length + headers length
        buffer.putInt(totalLength);
        buffer.putInt(headersLength);

        // Prelude CRC32
        CRC32 preludeCrc = new CRC32();
        preludeCrc.update(buffer.array(), 0, PRELUDE_SIZE);
        buffer.putInt((int) preludeCrc.getValue());

        // Headers
        buffer.put(headerBytes);

        // Payload
        buffer.put(payload);

        // Message CRC32 (covers everything up to this point)
        CRC32 messageCrc = new CRC32();
        messageCrc.update(buffer.array(), 0, totalLength - CRC_SIZE);
        buffer.putInt((int) messageCrc.getValue());

        return buffer.array();
    }

    /**
     * Concatenate multiple chunk JSON strings into a single event-stream
     * byte array (one binary message per chunk, concatenated).
     *
     * @param chunks the ordered list of model chunk JSON strings
     * @return concatenated event-stream messages
     */
    public static byte[] encodeStream(List<String> chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String chunk : chunks) {
            byte[] message = encodeChunk(chunk);
            try {
                out.write(message);
            } catch (IOException e) {
                throw new RuntimeException("Failed to write event-stream message", e);
            }
        }
        return out.toByteArray();
    }

    // ---- Decoding (for tests) ----

    /**
     * Decode a concatenated event-stream byte array into individual messages.
     *
     * @param data the raw event-stream bytes
     * @return ordered list of decoded messages
     * @throws IllegalArgumentException if any CRC32 check fails or data is malformed
     */
    public static List<DecodedMessage> decode(byte[] data) {
        List<DecodedMessage> messages = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(data);

        while (buffer.hasRemaining()) {
            int messageStart = buffer.position();
            if (buffer.remaining() < PRELUDE_SIZE + CRC_SIZE) {
                throw new IllegalArgumentException(
                    "Incomplete prelude: need at least " + (PRELUDE_SIZE + CRC_SIZE) +
                        " bytes, have " + buffer.remaining());
            }

            int totalLength = buffer.getInt();
            int headersLength = buffer.getInt();

            // Validate prelude CRC
            CRC32 preludeCrc = new CRC32();
            preludeCrc.update(data, messageStart, PRELUDE_SIZE);
            int expectedPreludeCrc = buffer.getInt();
            if ((int) preludeCrc.getValue() != expectedPreludeCrc) {
                throw new IllegalArgumentException(
                    "Prelude CRC32 mismatch: expected " + Integer.toHexString(expectedPreludeCrc) +
                        " but computed " + Integer.toHexString((int) preludeCrc.getValue()));
            }

            // Read headers
            Map<String, String> headers = decodeHeaders(data, buffer.position(), headersLength);
            buffer.position(buffer.position() + headersLength);

            // Read payload
            int payloadLength = totalLength - PRELUDE_SIZE - CRC_SIZE - headersLength - CRC_SIZE;
            byte[] payload = new byte[payloadLength];
            buffer.get(payload);

            // Validate message CRC
            CRC32 messageCrc = new CRC32();
            messageCrc.update(data, messageStart, totalLength - CRC_SIZE);
            int expectedMessageCrc = buffer.getInt();
            if ((int) messageCrc.getValue() != expectedMessageCrc) {
                throw new IllegalArgumentException(
                    "Message CRC32 mismatch: expected " + Integer.toHexString(expectedMessageCrc) +
                        " but computed " + Integer.toHexString((int) messageCrc.getValue()));
            }

            messages.add(new DecodedMessage(headers, payload));
        }

        return messages;
    }

    /**
     * A decoded event-stream message with parsed headers and raw payload.
     */
    public static final class DecodedMessage {
        private final Map<String, String> headers;
        private final byte[] payload;

        public DecodedMessage(Map<String, String> headers, byte[] payload) {
            this.headers = headers;
            this.payload = payload;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public byte[] getPayload() {
            return payload;
        }

        public String getPayloadAsString() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    // ---- Internal helpers ----

    private static byte[] encodeHeaders(Map<String, String> headers) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            byte[] nameBytes = entry.getKey().getBytes(StandardCharsets.US_ASCII);
            byte[] valueBytes = entry.getValue().getBytes(StandardCharsets.UTF_8);

            // 1 byte: name length
            out.write((byte) nameBytes.length);
            // N bytes: name
            out.write(nameBytes, 0, nameBytes.length);
            // 1 byte: header value type (7 = string)
            out.write(HEADER_VALUE_TYPE_STRING);
            // 2 bytes: value length (big-endian)
            out.write((byte) ((valueBytes.length >> 8) & 0xFF));
            out.write((byte) (valueBytes.length & 0xFF));
            // M bytes: value
            out.write(valueBytes, 0, valueBytes.length);
        }
        return out.toByteArray();
    }

    private static Map<String, String> decodeHeaders(byte[] data, int offset, int length) {
        Map<String, String> headers = new LinkedHashMap<>();
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, length);

        while (buffer.hasRemaining()) {
            int nameLength = buffer.get() & 0xFF;
            byte[] nameBytes = new byte[nameLength];
            buffer.get(nameBytes);
            String name = new String(nameBytes, StandardCharsets.US_ASCII);

            byte valueType = buffer.get();
            if (valueType != HEADER_VALUE_TYPE_STRING) {
                throw new IllegalArgumentException(
                    "Unsupported header value type: " + valueType + " for header '" + name + "'");
            }

            int valueLength = ((buffer.get() & 0xFF) << 8) | (buffer.get() & 0xFF);
            byte[] valueBytes = new byte[valueLength];
            buffer.get(valueBytes);
            String value = new String(valueBytes, StandardCharsets.UTF_8);

            headers.put(name, value);
        }

        return headers;
    }
}
