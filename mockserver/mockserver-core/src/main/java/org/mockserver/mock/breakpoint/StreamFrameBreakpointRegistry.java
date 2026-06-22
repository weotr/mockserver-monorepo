package org.mockserver.mock.breakpoint;

import org.mockserver.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide registry of streaming response frame breakpoints. Holds paused
 * (breakpointed) frames from forwarded streaming responses until they are resolved
 * via the control-plane REST API or auto-continued by the timeout rail.
 *
 * <p>Thread-safe: uses a {@link ConcurrentHashMap} internally. Designed to be called
 * from Netty event-loop threads (the chunk callback) and from scheduler/control-plane
 * threads (the resolve methods). NEVER blocks the calling thread — all operations are
 * non-blocking.
 *
 * <p><b>Frame ordering:</b> frames within a single stream (identified by {@code streamId})
 * are assigned monotonically increasing sequence numbers. The registry enforces that
 * frames are resolved in order — attempting to resolve a frame whose predecessor is still
 * held returns false.
 *
 * <p><b>DoS rail:</b> the registry enforces a hard cap on concurrently held frames
 * (shared with the request/response breakpoint cap via
 * {@link Configuration#breakpointMaxHeld()}, default 50). When the cap is reached,
 * new frame breakpoints are skipped (the frame is written immediately).
 *
 * <p><b>Timeout rail:</b> each paused frame auto-continues if not resolved within
 * {@link Configuration#breakpointTimeoutMillis()} (default 30 000 ms).
 *
 * <p><b>Stream-close eviction:</b> when a stream is evicted (channel closed, error, or
 * explicit close), all held frames for that stream are auto-continued and released.
 */
public class StreamFrameBreakpointRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(StreamFrameBreakpointRegistry.class);

    private static final StreamFrameBreakpointRegistry INSTANCE = new StreamFrameBreakpointRegistry();

    private static final ScheduledExecutorService TIMEOUT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MockServer-stream-frame-breakpoint-timeout");
        t.setDaemon(true);
        return t;
    });

    /** All held frames, keyed by frameId. */
    private final ConcurrentHashMap<String, PausedStreamFrame> heldFrames = new ConcurrentHashMap<>();

    /** Per-stream sequence counter for assigning monotonic frame sequence numbers. */
    private final ConcurrentHashMap<String, AtomicInteger> streamSequences = new ConcurrentHashMap<>();

    /** Per-stream tracking: the next sequence number that may be resumed (for ordering enforcement). */
    private final ConcurrentHashMap<String, AtomicInteger> nextResumable = new ConcurrentHashMap<>();

    /** Per-stream list of frame IDs, in order, for iteration and eviction. */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> streamFrameIds = new ConcurrentHashMap<>();

    public static StreamFrameBreakpointRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Park a streaming response frame at a breakpoint (OUTBOUND direction).
     *
     * <p>If the held-frame cap is already reached, returns {@code null}
     * (the caller should write the frame immediately).
     *
     * @param streamId      the stream correlation id (unique per forwarded streaming response)
     * @param chunkBytes    a COPY of the frame payload bytes (caller retains the ByteBuf)
     * @param requestMethod the request method (for context/logging)
     * @param requestPath   the request path (for context/logging)
     * @param configuration the active server configuration
     * @return the registered {@link PausedStreamFrame}, or {@code null} if the cap is reached
     */
    public PausedStreamFrame pauseFrame(String streamId, byte[] chunkBytes,
                                        String requestMethod, String requestPath,
                                        Configuration configuration) {
        return pauseFrame(streamId, chunkBytes, requestMethod, requestPath, configuration, PausedStreamFrame.Direction.OUTBOUND);
    }

    /**
     * Park a streaming frame at a breakpoint with an explicit direction.
     *
     * <p>If the held-frame cap is already reached, returns {@code null}
     * (the caller should write/process the frame immediately).
     *
     * @param streamId      the stream correlation id
     * @param chunkBytes    a COPY of the frame payload bytes (caller retains the ByteBuf)
     * @param requestMethod the request method (for context/logging)
     * @param requestPath   the request path (for context/logging)
     * @param configuration the active server configuration
     * @param direction     OUTBOUND (server-to-client) or INBOUND (client-to-server)
     * @return the registered {@link PausedStreamFrame}, or {@code null} if the cap is reached
     */
    public PausedStreamFrame pauseFrame(String streamId, byte[] chunkBytes,
                                        String requestMethod, String requestPath,
                                        Configuration configuration,
                                        PausedStreamFrame.Direction direction) {
        int maxHeld = configuration.breakpointMaxHeld();
        if (heldFrames.size() >= maxHeld) {
            LOG.info("stream frame breakpoint cap reached ({}/{}), skipping for stream={}", heldFrames.size(), maxHeld, streamId);
            return null;
        }

        AtomicInteger seqCounter = streamSequences.computeIfAbsent(streamId, k -> new AtomicInteger(0));
        int seq = seqCounter.getAndIncrement();
        String frameId = streamId + "-frame-" + seq;

        // Ensure the resumable counter exists for this stream; capture the reference
        // so the whenComplete callback only advances THIS counter instance (not a
        // replacement created by a subsequent reset() + re-creation of the same streamId).
        AtomicInteger resumableRef = nextResumable.computeIfAbsent(streamId, k -> new AtomicInteger(0));

        PausedStreamFrame frame = new PausedStreamFrame(frameId, streamId, seq, chunkBytes, requestMethod, requestPath, direction);
        heldFrames.put(frameId, frame);

        // Track in per-stream list; capture reference for the same identity-check reason.
        CopyOnWriteArrayList<String> frameIdsRef = streamFrameIds.computeIfAbsent(streamId, k -> new CopyOnWriteArrayList<>());
        frameIdsRef.add(frameId);

        // Schedule timeout auto-continue
        long timeoutMillis = configuration.breakpointTimeoutMillis();
        ScheduledFuture<?> timeoutHandle = TIMEOUT_SCHEDULER.schedule(() -> {
            if (frame.getDecisionFuture().complete(StreamFrameDecision.continueFrame())) {
                LOG.info("stream frame auto-continued (timeout {}ms) for frame={}", timeoutMillis, frameId);
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);

        // When the decision is resolved (by API call or timeout), clean up.
        // All captured references (frame, frameIdsRef, resumableRef) are identity-pinned
        // to the exact objects created during THIS pauseFrame() call. If reset() clears
        // the maps and a new pauseFrame() re-creates entries for the same streamId,
        // these callbacks operate on the OLD objects (which are no longer in the maps)
        // and have no effect on the new entries.
        frame.getDecisionFuture().whenComplete((decision, throwable) -> {
            timeoutHandle.cancel(false);
            heldFrames.remove(frameId, frame);
            frameIdsRef.remove(frameId);
            if (frameIdsRef.isEmpty()) {
                streamFrameIds.remove(streamId, frameIdsRef);
            }
            // Advance the resumable counter only if this is still the active instance
            resumableRef.compareAndSet(seq, seq + 1);
        });

        return frame;
    }

    /**
     * Resolve a paused frame as CONTINUE (write the original frame body).
     *
     * @return true if the frame was found and resolved
     */
    public boolean resolveContinue(String frameId) {
        PausedStreamFrame frame = heldFrames.get(frameId);
        if (frame == null) {
            return false;
        }
        if (!isNextResumable(frame)) {
            LOG.info("cannot continue frame {} — predecessor frame(s) still held (seq={}, nextResumable={})",
                frameId, frame.getSequenceNumber(), getNextResumableSeq(frame.getStreamId()));
            return false;
        }
        return frame.getDecisionFuture().complete(StreamFrameDecision.continueFrame());
    }

    /**
     * Resolve a paused frame as MODIFY (write a replacement body).
     *
     * @return true if the frame was found and resolved
     */
    public boolean resolveModify(String frameId, byte[] replacementBody) {
        PausedStreamFrame frame = heldFrames.get(frameId);
        if (frame == null) {
            return false;
        }
        if (!isNextResumable(frame)) {
            LOG.info("cannot modify frame {} — predecessor frame(s) still held", frameId);
            return false;
        }
        return frame.getDecisionFuture().complete(StreamFrameDecision.modify(replacementBody));
    }

    /**
     * Resolve a paused frame as DROP (discard without writing to client).
     *
     * @return true if the frame was found and resolved
     */
    public boolean resolveDrop(String frameId) {
        PausedStreamFrame frame = heldFrames.get(frameId);
        if (frame == null) {
            return false;
        }
        if (!isNextResumable(frame)) {
            LOG.info("cannot drop frame {} — predecessor frame(s) still held", frameId);
            return false;
        }
        return frame.getDecisionFuture().complete(StreamFrameDecision.drop());
    }

    /**
     * Resolve a paused frame as INJECT (write original, then also write an extra frame).
     *
     * @return true if the frame was found and resolved
     */
    public boolean resolveInject(String frameId, byte[] injectedBody) {
        PausedStreamFrame frame = heldFrames.get(frameId);
        if (frame == null) {
            return false;
        }
        if (!isNextResumable(frame)) {
            LOG.info("cannot inject after frame {} — predecessor frame(s) still held", frameId);
            return false;
        }
        return frame.getDecisionFuture().complete(StreamFrameDecision.inject(injectedBody));
    }

    /**
     * Resolve a paused frame as CLOSE (end the stream: drop frame, send LastHttpContent).
     *
     * @return true if the frame was found and resolved
     */
    public boolean resolveClose(String frameId) {
        PausedStreamFrame frame = heldFrames.get(frameId);
        if (frame == null) {
            return false;
        }
        if (!isNextResumable(frame)) {
            LOG.info("cannot close stream at frame {} — predecessor frame(s) still held", frameId);
            return false;
        }
        // First close this frame, then evict all remaining frames for the stream
        boolean resolved = frame.getDecisionFuture().complete(StreamFrameDecision.close());
        if (resolved) {
            evictStream(frame.getStreamId());
        }
        return resolved;
    }

    /**
     * Evict all held frames for a given stream. Called when the channel closes,
     * the stream ends, or on an explicit CLOSE action. Each held frame is
     * resolved with DROP so any awaiting callbacks fire but do NOT attempt
     * to write to the (possibly closing) channel. This prevents resource
     * leaks, hanging futures, and out-of-order writes after LastHttpContent.
     *
     * @param streamId the stream to evict
     */
    public void evictStream(String streamId) {
        CopyOnWriteArrayList<String> frameIds = streamFrameIds.get(streamId);
        if (frameIds != null) {
            for (String fid : frameIds) {
                PausedStreamFrame f = heldFrames.get(fid);
                if (f != null) {
                    f.getDecisionFuture().complete(StreamFrameDecision.drop());
                }
            }
        }
        // Clean up stream tracking
        streamSequences.remove(streamId);
        nextResumable.remove(streamId);
        // streamFrameIds entries are removed by the whenComplete callbacks
    }

    /**
     * Returns the next monotonic sequence number for the given stream without parking
     * a frame. Used by the WS-callback dispatch path which manages its own futures
     * but needs to share the per-stream sequence counter for consistency with the
     * REST-park path.
     *
     * @param streamId the stream to get the next sequence number for
     * @return the next sequence number (0-based, monotonically increasing)
     */
    public int nextSequenceNumber(String streamId) {
        AtomicInteger seqCounter = streamSequences.computeIfAbsent(streamId, k -> new AtomicInteger(0));
        return seqCounter.getAndIncrement();
    }

    /**
     * Returns a snapshot of all currently held frames.
     */
    public Map<String, PausedStreamFrame> entries() {
        return new LinkedHashMap<>(heldFrames);
    }

    /**
     * Returns a snapshot of all currently held frames for a specific stream.
     */
    public List<PausedStreamFrame> framesForStream(String streamId) {
        CopyOnWriteArrayList<String> frameIds = streamFrameIds.get(streamId);
        if (frameIds == null) {
            return Collections.emptyList();
        }
        List<PausedStreamFrame> result = new ArrayList<>();
        for (String fid : frameIds) {
            PausedStreamFrame f = heldFrames.get(fid);
            if (f != null) {
                result.add(f);
            }
        }
        return result;
    }

    /**
     * Returns all active stream IDs that have held frames.
     */
    public Set<String> activeStreamIds() {
        return new LinkedHashSet<>(streamFrameIds.keySet());
    }

    /**
     * Number of currently held frames across all streams.
     */
    public int size() {
        return heldFrames.size();
    }

    /**
     * Auto-continues all held frames so their async continuations fire.
     * Called on server reset.
     */
    public void reset() {
        // Snapshot and clear all maps BEFORE completing futures, so that
        // whenComplete callbacks see empty maps and are no-ops. The callbacks
        // use identity-pinned captured references and cannot corrupt new entries.
        List<PausedStreamFrame> snapshot = new ArrayList<>(heldFrames.values());
        heldFrames.clear();
        streamSequences.clear();
        nextResumable.clear();
        streamFrameIds.clear();
        for (PausedStreamFrame frame : snapshot) {
            frame.getDecisionFuture().complete(StreamFrameDecision.continueFrame());
        }
    }

    /**
     * Check if the given frame is the next one that can be resumed for its stream.
     * Frames must be resumed in order (by sequence number).
     */
    private boolean isNextResumable(PausedStreamFrame frame) {
        AtomicInteger resumable = nextResumable.get(frame.getStreamId());
        return resumable != null && resumable.get() == frame.getSequenceNumber();
    }

    private int getNextResumableSeq(String streamId) {
        AtomicInteger resumable = nextResumable.get(streamId);
        return resumable != null ? resumable.get() : -1;
    }
}
