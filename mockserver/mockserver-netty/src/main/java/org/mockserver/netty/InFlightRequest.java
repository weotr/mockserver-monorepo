package org.mockserver.netty;

import org.mockserver.lifecycle.LifeCycle;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-request in-flight token used by the WS7.2 graceful-shutdown connection drain.
 *
 * <p>A token is created and {@link LifeCycle#requestProcessingStarted()} is incremented exactly once
 * when a data-plane HTTP request begins processing in {@link HttpRequestHandler#channelRead0}. The
 * matching {@link LifeCycle#requestProcessingComplete()} decrement is driven by {@link #complete()},
 * which is invoked from whichever of these fires first:</p>
 *
 * <ul>
 *   <li>the response funnel — {@code NettyResponseWriter.sendResponse(...)}, through which every
 *       data-plane response flows (normal, streaming, chunked, forward/proxy, error/exception,
 *       breakpoint-modified); or</li>
 *   <li>the channel {@code closeFuture} safety net — covers requests that never produce a response
 *       (connection drop or pipeline-killing exception mid-processing).</li>
 * </ul>
 *
 * <p>An {@link AtomicBoolean} guard guarantees the decrement fires <em>exactly once</em> regardless
 * of how many of those hooks fire, so the in-flight counter can never leak (which would make
 * {@code stop()} always wait the full drain timeout) nor be decremented twice.</p>
 *
 * @author jamesdbloom
 */
public final class InFlightRequest {

    private final LifeCycle server;
    private final AtomicBoolean completed = new AtomicBoolean(false);

    private InFlightRequest(LifeCycle server) {
        this.server = server;
    }

    /**
     * Increment the in-flight counter and return a token whose {@link #complete()} will decrement it
     * exactly once. Returns {@code null} when no {@link LifeCycle} is available (so callers can
     * no-op safely).
     */
    public static InFlightRequest started(LifeCycle server) {
        if (server == null) {
            return null;
        }
        InFlightRequest inFlightRequest = new InFlightRequest(server);
        server.requestProcessingStarted();
        return inFlightRequest;
    }

    /**
     * Decrement the in-flight counter, but only the first time this is called for this token.
     * Safe to call from any thread and from multiple completion hooks.
     */
    public void complete() {
        if (completed.compareAndSet(false, true)) {
            server.requestProcessingComplete();
        }
    }
}
