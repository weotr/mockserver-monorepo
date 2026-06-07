package org.mockserver.grpc;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

/**
 * Tests for {@link IncrementalGrpcFrameDecoder}: the incremental gRPC length-prefixed
 * frame decoder that accumulates across multiple feed() calls and retains partial frames.
 */
public class IncrementalGrpcFrameDecoderTest {

    /**
     * A single complete frame delivered in one feed should produce exactly one message.
     */
    @Test
    public void shouldDecodeSingleCompleteFrame() {
        IncrementalGrpcFrameDecoder decoder = new IncrementalGrpcFrameDecoder();
        byte[] payload = {0x01, 0x02, 0x03, 0x04, 0x05};
        byte[] frame = GrpcFrameCodec.encode(payload);

        List<byte[]> messages = decoder.feed(frame);

        assertThat("should produce exactly one message", messages.size(), is(1));
        assertThat("message bytes must match original payload", messages.get(0), is(equalTo(payload)));
        assertFalse("no leftover bytes", decoder.hasBufferedBytes());
    }

    /**
     * Two complete frames delivered in a single feed should produce two messages.
     */
    @Test
    public void shouldDecodeTwoMessagesInOneFeed() {
        IncrementalGrpcFrameDecoder decoder = new IncrementalGrpcFrameDecoder();
        byte[] payload1 = "hello".getBytes();
        byte[] payload2 = "world".getBytes();
        byte[] frame1 = GrpcFrameCodec.encode(payload1);
        byte[] frame2 = GrpcFrameCodec.encode(payload2);

        byte[] combined = new byte[frame1.length + frame2.length];
        System.arraycopy(frame1, 0, combined, 0, frame1.length);
        System.arraycopy(frame2, 0, combined, frame1.length, frame2.length);

        List<byte[]> messages = decoder.feed(combined);

        assertThat("should produce two messages", messages.size(), is(2));
        assertThat("first message", messages.get(0), is(equalTo(payload1)));
        assertThat("second message", messages.get(1), is(equalTo(payload2)));
        assertFalse("no leftover bytes", decoder.hasBufferedBytes());
    }

    /**
     * A frame whose 5-byte header is split across two feeds: first feed has 3 bytes
     * of the header, second feed has the remaining 2 header bytes + payload.
     */
    @Test
    public void shouldHandleHeaderSplitAcrossTwoFeeds() {
        IncrementalGrpcFrameDecoder decoder = new IncrementalGrpcFrameDecoder();
        byte[] payload = {0x0A, 0x0B, 0x0C};
        byte[] frame = GrpcFrameCodec.encode(payload);

        // Split at byte 3 (mid-header)
        byte[] part1 = Arrays.copyOfRange(frame, 0, 3);
        byte[] part2 = Arrays.copyOfRange(frame, 3, frame.length);

        List<byte[]> messages1 = decoder.feed(part1);
        assertThat("no complete message yet", messages1, is(empty()));
        assertTrue("should have buffered bytes", decoder.hasBufferedBytes());

        List<byte[]> messages2 = decoder.feed(part2);
        assertThat("should produce one message", messages2.size(), is(1));
        assertThat("message bytes must match", messages2.get(0), is(equalTo(payload)));
        assertFalse("no leftover bytes", decoder.hasBufferedBytes());
    }

    /**
     * A frame whose payload is split across two feeds: first feed has the header + partial
     * payload, second feed has the remaining payload bytes.
     */
    @Test
    public void shouldHandlePayloadSplitAcrossTwoFeeds() {
        IncrementalGrpcFrameDecoder decoder = new IncrementalGrpcFrameDecoder();
        byte[] payload = "split-payload-test".getBytes();
        byte[] frame = GrpcFrameCodec.encode(payload);

        // Split after header + 5 payload bytes
        int splitPoint = 5 + 5;
        byte[] part1 = Arrays.copyOfRange(frame, 0, splitPoint);
        byte[] part2 = Arrays.copyOfRange(frame, splitPoint, frame.length);

        List<byte[]> messages1 = decoder.feed(part1);
        assertThat("no complete message yet", messages1, is(empty()));
        assertTrue("should have buffered bytes", decoder.hasBufferedBytes());

        List<byte[]> messages2 = decoder.feed(part2);
        assertThat("should produce one message", messages2.size(), is(1));
        assertThat("message bytes must match", messages2.get(0), is(equalTo(payload)));
        assertFalse("no leftover bytes", decoder.hasBufferedBytes());
    }

    /**
     * An empty payload (length 0) is a valid gRPC frame.
     */
    @Test
    public void shouldDecodeEmptyPayload() {
        IncrementalGrpcFrameDecoder decoder = new IncrementalGrpcFrameDecoder();
        byte[] payload = new byte[0];
        byte[] frame = GrpcFrameCodec.encode(payload);

        assertThat("frame should be 5 bytes (header only)", frame.length, is(5));

        List<byte[]> messages = decoder.feed(frame);

        assertThat("should produce one message", messages.size(), is(1));
        assertThat("message should be empty", messages.get(0).length, is(0));
        assertFalse("no leftover bytes", decoder.hasBufferedBytes());
    }

    /**
     * Feeding an empty byte array should return an empty list and not alter state.
     */
    @Test
    public void shouldHandleEmptyFeed() {
        IncrementalGrpcFrameDecoder decoder = new IncrementalGrpcFrameDecoder();

        List<byte[]> messages = decoder.feed(new byte[0]);
        assertThat("empty feed should return empty list", messages, is(empty()));
        assertFalse("no buffered bytes", decoder.hasBufferedBytes());
    }

    /**
     * Feeding null should return an empty list and not alter state.
     */
    @Test
    public void shouldHandleNullFeed() {
        IncrementalGrpcFrameDecoder decoder = new IncrementalGrpcFrameDecoder();

        List<byte[]> messages = decoder.feed(null);
        assertThat("null feed should return empty list", messages, is(empty()));
        assertFalse("no buffered bytes", decoder.hasBufferedBytes());
    }

    /**
     * Exceeding the buffer cap should throw a GrpcException.
     */
    @Test
    public void shouldThrowWhenCapExceeded() {
        int cap = 32;
        IncrementalGrpcFrameDecoder decoder = new IncrementalGrpcFrameDecoder(cap);

        byte[] largeChunk = new byte[cap + 1];
        Arrays.fill(largeChunk, (byte) 0x42);

        GrpcException exception = assertThrows(GrpcException.class, () -> decoder.feed(largeChunk));
        assertThat("exception message should mention maximum",
            exception.getMessage().contains("exceeded maximum"), is(true));
    }

    /**
     * Exceeding the cap across multiple feeds should also throw.
     * Uses a partial frame header claiming a large payload so the first feed's bytes
     * remain buffered (not consumed as complete frames).
     */
    @Test
    public void shouldThrowWhenCapExceededAcrossMultipleFeeds() {
        int cap = 32;
        IncrementalGrpcFrameDecoder decoder = new IncrementalGrpcFrameDecoder(cap);

        // First feed: 5-byte header claiming 1000 bytes payload + 15 bytes of partial payload = 20 bytes.
        // The frame won't complete (needs 1000 payload bytes), so all 20 bytes stay buffered.
        ByteBuffer header = ByteBuffer.allocate(20);
        header.put((byte) 0); // uncompressed
        header.putInt(1000); // payload length (won't be fulfilled)
        // fill remaining 15 bytes as partial payload
        for (int i = 0; i < 15; i++) {
            header.put((byte) 0x42);
        }
        decoder.feed(header.array()); // should not throw (20 < 32)

        // Second feed: 20 more bytes, total 40 > cap (32)
        byte[] more = new byte[20];
        GrpcException exception = assertThrows(GrpcException.class, () -> decoder.feed(more));
        assertThat("exception message should mention maximum",
            exception.getMessage().contains("exceeded maximum"), is(true));
    }

    /**
     * Two complete messages in one feed, followed by a partial header, then the rest
     * in a subsequent feed. Proves that completed messages are extracted and the
     * partial is retained.
     */
    @Test
    public void shouldExtractCompleteMessagesAndRetainPartial() {
        IncrementalGrpcFrameDecoder decoder = new IncrementalGrpcFrameDecoder();
        byte[] payload1 = "first".getBytes();
        byte[] payload2 = "second".getBytes();
        byte[] payload3 = "third".getBytes();
        byte[] frame1 = GrpcFrameCodec.encode(payload1);
        byte[] frame2 = GrpcFrameCodec.encode(payload2);
        byte[] frame3 = GrpcFrameCodec.encode(payload3);

        // Combine frame1 + frame2 + first 3 bytes of frame3
        byte[] chunk1 = new byte[frame1.length + frame2.length + 3];
        System.arraycopy(frame1, 0, chunk1, 0, frame1.length);
        System.arraycopy(frame2, 0, chunk1, frame1.length, frame2.length);
        System.arraycopy(frame3, 0, chunk1, frame1.length + frame2.length, 3);

        List<byte[]> messages1 = decoder.feed(chunk1);
        assertThat("should extract two complete messages", messages1.size(), is(2));
        assertThat("first message", messages1.get(0), is(equalTo(payload1)));
        assertThat("second message", messages1.get(1), is(equalTo(payload2)));
        assertTrue("should have buffered partial", decoder.hasBufferedBytes());

        // Feed rest of frame3
        byte[] chunk2 = Arrays.copyOfRange(frame3, 3, frame3.length);
        List<byte[]> messages2 = decoder.feed(chunk2);
        assertThat("should produce one message", messages2.size(), is(1));
        assertThat("third message", messages2.get(0), is(equalTo(payload3)));
        assertFalse("no leftover bytes", decoder.hasBufferedBytes());
    }

    /**
     * A frame header with invalid reserved flag bits should throw.
     */
    @Test
    public void shouldThrowOnInvalidCompressedFlag() {
        IncrementalGrpcFrameDecoder decoder = new IncrementalGrpcFrameDecoder();
        // Build a frame with compressed flag = 0x02 (invalid)
        byte[] frame = new byte[]{0x02, 0x00, 0x00, 0x00, 0x01, 0x42};

        GrpcException exception = assertThrows(GrpcException.class, () -> decoder.feed(frame));
        assertThat("exception should mention reserved flag bits",
            exception.getMessage().contains("reserved flag bits"), is(true));
    }

    /**
     * A message whose declared length exceeds MAX_MESSAGE_SIZE should throw.
     */
    @Test
    public void shouldThrowOnOversizedMessageLength() {
        IncrementalGrpcFrameDecoder decoder = new IncrementalGrpcFrameDecoder(16 * 1024 * 1024);
        // Build a header claiming 5 MiB payload (exceeds 4 MiB max)
        int fiveMiB = 5 * 1024 * 1024;
        ByteBuffer header = ByteBuffer.allocate(5);
        header.put((byte) 0);
        header.putInt(fiveMiB);

        GrpcException exception = assertThrows(GrpcException.class, () -> decoder.feed(header.array()));
        assertThat("exception should mention maximum allowed",
            exception.getMessage().contains("exceeds maximum allowed"), is(true));
    }
}
