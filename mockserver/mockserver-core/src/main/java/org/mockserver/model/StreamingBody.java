package org.mockserver.model;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoop;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A chunk sink for streaming response bodies. When MockServer proxies a streaming response
 * (Server-Sent Events or chunked without Content-Length), the response head is delivered
 * immediately while chunks flow through this sink. The sink captures bytes into a bounded
 * buffer (up to {@code maxCaptureBytes}) for the event log / dashboard while forwarding
 * every chunk to the subscribed consumer.
 * <p>
 * Thread-safety: {@link #addCompletionListener} may be called from a scheduler thread
 * (e.g. {@code HttpActionHandler.writeStreamingForwardActionResponse}) while
 * {@link #complete()} and {@link #error(Throwable)} are called from the Netty event loop.
 * All mutation of the {@code completed} flag and {@code completionListeners} list is
 * guarded by {@code synchronized(lock)} to ensure a listener is always fired exactly once —
 * immediately if already completed, otherwise when completion/error occurs.
 * <p>
 * The chunk path ({@link #subscribe}, {@link #addChunk}, {@link #complete}, {@link #error})
 * is serialised on the upstream channel's event loop to avoid races between chunk arrival
 * and subscription. Any chunks that arrive before {@code subscribe()} are buffered as
 * {@code byte[]} arrays in a pending list and drained in order when the subscriber connects.
 */
public class StreamingBody {

    private final Object lock = new Object();
    private final int maxCaptureBytes;
    private final ByteArrayOutputStream captureBuffer;
    private volatile boolean truncated;
    private boolean completed;
    private Throwable error;

    private volatile Consumer<ByteBuf> onChunk;
    private volatile Runnable onComplete;
    private volatile Consumer<Throwable> onError;
    private List<Runnable> completionListeners;
    private volatile Runnable requestMoreCallback;

    /** The upstream channel's event loop, used to serialise subscribe/addChunk/complete/error. */
    private volatile EventLoop eventLoop;

    /** Chunks that arrived before subscribe() was called. Each entry is a copy of the chunk bytes. */
    private List<byte[]> pendingChunks;

    /**
     * Per-chunk arrival timestamps in nanoseconds (monotonic clock). The first entry
     * records the baseline; subsequent entries allow computing inter-chunk delays.
     * Populated only when {@code captureChunkTimestamps} is true.
     */
    private List<Long> chunkTimestampsNanos;

    /** Whether to record per-chunk monotonic timestamps on {@link #addChunk}. */
    private final boolean captureChunkTimestamps;

    public StreamingBody(int maxCaptureBytes) {
        this(maxCaptureBytes, false);
    }

    /**
     * @param maxCaptureBytes       maximum bytes to capture for the event log
     * @param captureChunkTimestamps when true, records a {@code System.nanoTime()} per chunk
     *                              for later per-chunk replay timing
     */
    public StreamingBody(int maxCaptureBytes, boolean captureChunkTimestamps) {
        this.maxCaptureBytes = Math.max(0, maxCaptureBytes);
        this.captureBuffer = new ByteArrayOutputStream(Math.min(this.maxCaptureBytes, 8192));
        this.pendingChunks = new ArrayList<>();
        this.captureChunkTimestamps = captureChunkTimestamps;
        this.chunkTimestampsNanos = captureChunkTimestamps ? new ArrayList<>() : null;
    }

    /**
     * Set the upstream channel's event loop. Must be called before any chunks arrive
     * (typically by the handler that creates the body). All chunk-path operations
     * ({@link #subscribe}, {@link #addChunk}, {@link #complete}, {@link #error}) are
     * serialised on this event loop.
     *
     * @param eventLoop the upstream channel's event loop
     */
    public void setEventLoop(EventLoop eventLoop) {
        this.eventLoop = eventLoop;
    }

    /**
     * Subscribe to receive streaming chunks. If an event loop has been set via
     * {@link #setEventLoop(EventLoop)} and the caller is not on that event loop, the
     * subscribe body is marshalled onto the event loop to serialise with addChunk/complete/error.
     * Any chunks that arrived before subscription are drained in order and then the first
     * upstream read is triggered via {@link #requestMore()}.
     *
     * @param onChunk    called for each {@link ByteBuf} chunk; the consumer must NOT release the buffer
     * @param onComplete called when the last chunk has been received
     * @param onError    called if the stream is interrupted (channel close, timeout, etc.)
     */
    public void subscribe(Consumer<ByteBuf> onChunk, Runnable onComplete, Consumer<Throwable> onError) {
        if (eventLoop != null && !eventLoop.inEventLoop()) {
            eventLoop.execute(() -> subscribeOnEventLoop(onChunk, onComplete, onError));
        } else {
            subscribeOnEventLoop(onChunk, onComplete, onError);
        }
    }

    /**
     * Runs on the upstream event loop (or inline when no event loop is set). Sets subscriber
     * callbacks, drains any pending pre-subscribe chunks, replays terminal signals, and
     * triggers the first upstream read.
     */
    private void subscribeOnEventLoop(Consumer<ByteBuf> onChunk, Runnable onComplete, Consumer<Throwable> onError) {
        this.onChunk = onChunk;
        this.onComplete = onComplete;
        this.onError = onError;

        // Drain any chunks that arrived before subscribe was called
        if (pendingChunks != null && !pendingChunks.isEmpty()) {
            List<byte[]> pending = pendingChunks;
            pendingChunks = null;
            for (byte[] chunkBytes : pending) {
                ByteBuf buffered = Unpooled.wrappedBuffer(chunkBytes);
                try {
                    onChunk.accept(buffered);
                } finally {
                    buffered.release();
                }
            }
        } else {
            pendingChunks = null;
        }

        // Replay terminal signal if the stream completed/errored before subscribe
        if (completed) {
            if (error != null && onError != null) {
                onError.accept(error);
            } else if (error == null && onComplete != null) {
                onComplete.run();
            }
            return;
        }

        // Trigger the first upstream read now that the subscriber is ready
        requestMore();
    }

    /**
     * Feed a chunk into the sink. The chunk bytes are appended to the bounded capture
     * buffer and forwarded to the subscriber. If no subscriber has connected yet, the
     * full chunk bytes are copied into a pending list so they can be drained when
     * {@link #subscribe} runs.
     *
     * @param chunk the chunk content (caller retains ownership of the buffer)
     */
    public void addChunk(ByteBuf chunk) {
        if (completed) {
            return;
        }
        // record monotonic timestamp for per-chunk replay timing (stop once truncated —
        // a truncated recording falls back to a static/fixed response so per-chunk timing
        // is moot, and continuing to record timestamps would grow the list without bound
        // on long-lived streams)
        if (chunkTimestampsNanos != null && !truncated) {
            chunkTimestampsNanos.add(System.nanoTime());
        }
        // capture into bounded buffer
        int readable = chunk.readableBytes();
        if (!truncated && readable > 0) {
            int remaining = maxCaptureBytes - captureBuffer.size();
            if (remaining > 0) {
                int toCapture = Math.min(readable, remaining);
                byte[] bytes = new byte[toCapture];
                chunk.getBytes(chunk.readerIndex(), bytes);
                captureBuffer.write(bytes, 0, toCapture);
                if (captureBuffer.size() >= maxCaptureBytes) {
                    truncated = true;
                    // discard timestamps — truncated recordings use fixed-delay fallback
                    chunkTimestampsNanos = null;
                }
            } else {
                truncated = true;
                // discard timestamps — truncated recordings use fixed-delay fallback
                chunkTimestampsNanos = null;
            }
        }
        // forward to subscriber or buffer for later
        if (onChunk != null) {
            onChunk.accept(chunk);
        } else if (pendingChunks != null) {
            // No subscriber yet — copy the full chunk bytes into the pending list
            byte[] copy = new byte[readable];
            chunk.getBytes(chunk.readerIndex(), copy);
            pendingChunks.add(copy);
        }
    }

    /**
     * Signal that the stream is complete (last chunk received).
     */
    public void complete() {
        List<Runnable> listenersToFire;
        synchronized (lock) {
            if (completed) {
                return;
            }
            completed = true;
            listenersToFire = completionListeners;
            completionListeners = null;
        }
        if (onComplete != null) {
            onComplete.run();
        }
        fireListeners(listenersToFire);
    }

    /**
     * Signal that the stream was interrupted by an error.
     *
     * @param cause the cause of the interruption
     */
    public void error(Throwable cause) {
        List<Runnable> listenersToFire;
        synchronized (lock) {
            if (completed) {
                return;
            }
            completed = true;
            error = cause;
            truncated = true;
            chunkTimestampsNanos = null;
            listenersToFire = completionListeners;
            completionListeners = null;
        }
        if (onError != null) {
            onError.accept(cause);
        }
        fireListeners(listenersToFire);
    }

    /**
     * Add a listener that is called when the stream completes (either successfully or
     * with an error). If the stream has already completed, the listener is called
     * immediately. This method is safe to call from any thread.
     *
     * @param listener the completion listener
     */
    public void addCompletionListener(Runnable listener) {
        boolean runNow;
        synchronized (lock) {
            if (completed) {
                runNow = true;
            } else {
                runNow = false;
                if (completionListeners == null) {
                    completionListeners = new ArrayList<>();
                }
                completionListeners.add(listener);
            }
        }
        if (runNow) {
            listener.run();
        }
    }

    private void fireListeners(List<Runnable> listeners) {
        if (listeners != null) {
            for (Runnable listener : listeners) {
                try {
                    listener.run();
                } catch (Exception ignored) {
                    // don't let a listener failure propagate
                }
            }
        }
    }

    /**
     * Set a callback that is invoked to request the next chunk from the upstream channel.
     * Used for backpressure: the downstream writer calls {@link #requestMore()} after
     * each chunk write completes, which triggers an upstream {@code ctx.read()}.
     *
     * @param callback the callback to request the next upstream chunk
     */
    public void setRequestMoreCallback(Runnable callback) {
        this.requestMoreCallback = callback;
    }

    /**
     * Request the next chunk from the upstream channel. Called by the downstream writer
     * after a chunk write completes to implement backpressure.
     */
    public void requestMore() {
        if (requestMoreCallback != null) {
            requestMoreCallback.run();
        }
    }

    /**
     * @return true if the capture buffer was capped and does not contain the full body
     */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * @return true if the stream has completed (successfully or with an error)
     */
    public boolean isCompleted() {
        synchronized (lock) {
            return completed;
        }
    }

    /**
     * @return the error that interrupted the stream, or null if it completed normally
     */
    public Throwable getError() {
        synchronized (lock) {
            return error;
        }
    }

    /**
     * @return the captured bytes (may be fewer than the full body if truncated)
     */
    public byte[] capturedBytes() {
        return captureBuffer.toByteArray();
    }

    /**
     * Compute inter-chunk delays in milliseconds from the captured monotonic timestamps.
     * The first element is always 0 (no delay before the first chunk). Subsequent elements
     * represent the wall-clock gap between consecutive chunk arrivals.
     *
     * @return a list of inter-chunk delays in milliseconds, or {@code null} if timestamps
     *         were not captured (i.e. {@code captureChunkTimestamps} was false)
     */
    public List<Long> interChunkDelaysMillis() {
        if (chunkTimestampsNanos == null || chunkTimestampsNanos.isEmpty()) {
            return null;
        }
        List<Long> delays = new ArrayList<>(chunkTimestampsNanos.size());
        delays.add(0L); // first chunk has no preceding delay
        for (int i = 1; i < chunkTimestampsNanos.size(); i++) {
            long deltaNanos = chunkTimestampsNanos.get(i) - chunkTimestampsNanos.get(i - 1);
            delays.add(Math.max(0, deltaNanos / 1_000_000L)); // nanos to millis, floor at 0
        }
        return delays;
    }
}
