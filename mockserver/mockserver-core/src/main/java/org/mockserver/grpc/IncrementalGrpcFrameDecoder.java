package org.mockserver.grpc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Incremental gRPC length-prefixed frame decoder that accumulates across multiple
 * {@code feed()} calls, retaining any trailing partial bytes for the next call.
 * <p>
 * Unlike {@link GrpcFrameCodec#decode(byte[])}, which operates on a complete buffer
 * and discards trailing partial frames (line ~49), this decoder preserves partial frames
 * across feeds so that a gRPC message split across multiple HTTP/2 DATA frames is
 * reassembled correctly.
 * <p>
 * Each gRPC frame has a 5-byte header: {@code [1-byte compressed flag][4-byte big-endian length][payload]}.
 * <p>
 * Thread-safety: NOT thread-safe. Expected to be used by a single Netty I/O thread
 * (per-stream handler, NOT {@code @Sharable}).
 */
public class IncrementalGrpcFrameDecoder {

    private static final int HEADER_LENGTH = 5;
    private static final int MAX_MESSAGE_SIZE = 4 * 1024 * 1024;
    private static final int DEFAULT_MAX_BUFFER_SIZE = 8 * 1024 * 1024;

    private final int maxBufferSize;
    private byte[] buffer;
    private int position;

    /**
     * Creates a decoder with the default max buffer size (8 MiB).
     */
    public IncrementalGrpcFrameDecoder() {
        this(DEFAULT_MAX_BUFFER_SIZE);
    }

    /**
     * Creates a decoder with a custom max buffer size.
     *
     * @param maxBufferSize maximum number of bytes that may be buffered; if exceeded a
     *                      {@link GrpcException} is thrown from {@link #feed(byte[])}
     */
    public IncrementalGrpcFrameDecoder(int maxBufferSize) {
        if (maxBufferSize < HEADER_LENGTH) {
            throw new IllegalArgumentException("maxBufferSize must be at least " + HEADER_LENGTH);
        }
        this.maxBufferSize = maxBufferSize;
        this.buffer = new byte[256];
        this.position = 0;
    }

    /**
     * Feeds a chunk of bytes into the decoder and returns a list of complete gRPC
     * message payloads (after stripping the 5-byte header and decompressing if needed).
     * <p>
     * Any trailing partial bytes are retained internally for the next feed.
     *
     * @param chunk bytes to append (may be empty or {@code null})
     * @return list of complete message payloads (never {@code null}; may be empty)
     * @throws GrpcException if the total buffered bytes exceed the configured cap,
     *                       or if a frame header has invalid flag bits or exceeds
     *                       {@code MAX_MESSAGE_SIZE}
     */
    public List<byte[]> feed(byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return Collections.emptyList();
        }

        // use a long to compute the new total so a near-Integer.MAX_VALUE sum cannot overflow
        // negative and slip past the cap check
        long newTotalLong = (long) position + chunk.length;
        if (newTotalLong > maxBufferSize) {
            throw new GrpcException("gRPC frame decoder buffer exceeded maximum of " + maxBufferSize + " bytes");
        }
        int newTotal = (int) newTotalLong;

        ensureCapacity(newTotal);
        System.arraycopy(chunk, 0, buffer, position, chunk.length);
        position = newTotal;

        List<byte[]> messages = new ArrayList<>();
        int offset = 0;

        while (offset + HEADER_LENGTH <= position) {
            byte compressedFlag = buffer[offset];
            if ((compressedFlag & ~1) != 0) {
                throw new GrpcException("gRPC frame has reserved flag bits set: " + compressedFlag);
            }

            int length = ((buffer[offset + 1] & 0xFF) << 24)
                | ((buffer[offset + 2] & 0xFF) << 16)
                | ((buffer[offset + 3] & 0xFF) << 8)
                | (buffer[offset + 4] & 0xFF);

            if (length < 0 || length > MAX_MESSAGE_SIZE) {
                throw new GrpcException("gRPC message size " + length + " exceeds maximum allowed " + MAX_MESSAGE_SIZE);
            }

            if (offset + HEADER_LENGTH + length > position) {
                break;
            }

            byte[] payload = new byte[length];
            System.arraycopy(buffer, offset + HEADER_LENGTH, payload, 0, length);

            if (compressedFlag == 1) {
                payload = decompress(payload);
            }

            messages.add(payload);
            offset += HEADER_LENGTH + length;
        }

        // Compact: move any leftover bytes to the front of the buffer
        int remaining = position - offset;
        if (remaining > 0 && offset > 0) {
            System.arraycopy(buffer, offset, buffer, 0, remaining);
        }
        position = remaining;

        return messages;
    }

    /**
     * Returns {@code true} if there are buffered bytes awaiting more data to form a
     * complete frame.
     */
    public boolean hasBufferedBytes() {
        return position > 0;
    }

    private void ensureCapacity(int needed) {
        if (needed > buffer.length) {
            int newSize = Math.max(buffer.length * 2, needed);
            if (newSize > maxBufferSize) {
                newSize = maxBufferSize;
            }
            byte[] newBuffer = new byte[newSize];
            System.arraycopy(buffer, 0, newBuffer, 0, position);
            buffer = newBuffer;
        }
    }

    private static byte[] decompress(byte[] data) {
        try {
            java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(data);
            try (java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(bis)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                while ((len = gis.read(buf)) != -1) {
                    bos.write(buf, 0, len);
                }
                return bos.toByteArray();
            }
        } catch (java.io.IOException e) {
            throw new GrpcException("Failed to gzip decompress gRPC message", e);
        }
    }
}
