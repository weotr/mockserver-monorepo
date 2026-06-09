package org.mockserver.mock.breakpoint;

import org.mockserver.configuration.Configuration;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Process-wide registry of request breakpoints. Holds paused (breakpointed)
 * proxy-forward exchanges until they are resolved via the control-plane REST
 * API or auto-continued by the timeout rail.
 *
 * <p>Thread-safe: uses a {@link ConcurrentHashMap} internally and is designed
 * to be called from Netty worker threads (never the event loop).
 *
 * <p><b>DoS rail:</b> the registry enforces a hard cap on concurrently held
 * exchanges ({@link Configuration#breakpointMaxHeld()}, default 50). When the
 * cap is reached, new breakpoint intercepts are skipped and the request is
 * forwarded normally.
 *
 * <p><b>Timeout rail:</b> each paused exchange auto-continues if not resolved
 * within {@link Configuration#breakpointTimeoutMillis()} (default 30 000 ms).
 */
public class BreakpointRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(BreakpointRegistry.class);

    private static final BreakpointRegistry INSTANCE = new BreakpointRegistry();

    private static final ScheduledExecutorService TIMEOUT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MockServer-breakpoint-timeout");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<String, PausedExchange> held = new ConcurrentHashMap<>();

    public static BreakpointRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Attempts to pause a forwarded request at a breakpoint.
     *
     * <p>If the held-exchange cap is already reached, returns {@code null}
     * (the caller should proceed with forwarding normally).
     *
     * <p>Otherwise, registers a {@link PausedExchange} and schedules a
     * timeout auto-continue. Returns the registered exchange whose
     * {@link PausedExchange#getDecisionFuture()} the caller should block on
     * (on the scheduler worker thread, NOT the event loop).
     *
     * @param correlationId the request's log correlation id (unique per request)
     * @param request       the captured {@link HttpRequest} (already deserialized, no raw ByteBuf)
     * @param expectationId the matched expectation id, or null for unmatched proxy
     * @param configuration the active server configuration (for maxHeld and timeout)
     * @return the registered {@link PausedExchange}, or {@code null} if the cap is reached
     */
    public PausedExchange pause(String correlationId, HttpRequest request, String expectationId, Configuration configuration) {
        int maxHeld = configuration.breakpointMaxHeld();
        if (held.size() >= maxHeld) {
            LOG.info("breakpoint cap reached ({}/{}), skipping breakpoint for correlation={}", held.size(), maxHeld, correlationId);
            return null;
        }

        PausedExchange exchange = new PausedExchange(correlationId, request, expectationId);
        held.put(correlationId, exchange);

        // schedule timeout auto-continue: complete with CONTINUE if not yet resolved
        long timeoutMillis = configuration.breakpointTimeoutMillis();
        ScheduledFuture<?> timeoutHandle = TIMEOUT_SCHEDULER.schedule(() -> {
            if (exchange.getDecisionFuture().complete(BreakpointDecision.continueOriginal())) {
                LOG.info("breakpoint auto-continued (timeout {}ms) for correlation={}", timeoutMillis, correlationId);
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);

        // when the decision is resolved (by API call or timeout), clean up
        exchange.getDecisionFuture().whenComplete((decision, throwable) -> {
            timeoutHandle.cancel(false);
            held.remove(correlationId);
        });

        return exchange;
    }

    /**
     * Resolves a paused exchange as CONTINUE (forward original request).
     *
     * @return true if the exchange was found and resolved
     */
    public boolean resolveContinue(String correlationId) {
        PausedExchange exchange = held.get(correlationId);
        if (exchange == null) {
            return false;
        }
        return exchange.getDecisionFuture().complete(BreakpointDecision.continueOriginal());
    }

    /**
     * Resolves a paused exchange as MODIFY (forward a replacement request).
     *
     * @return true if the exchange was found and resolved
     */
    public boolean resolveModify(String correlationId, HttpRequest modifiedRequest) {
        PausedExchange exchange = held.get(correlationId);
        if (exchange == null) {
            return false;
        }
        return exchange.getDecisionFuture().complete(BreakpointDecision.modify(modifiedRequest));
    }

    /**
     * Resolves a paused exchange as ABORT (do not forward; return the given response).
     *
     * @return true if the exchange was found and resolved
     */
    public boolean resolveAbort(String correlationId, HttpResponse abortResponse) {
        PausedExchange exchange = held.get(correlationId);
        if (exchange == null) {
            return false;
        }
        return exchange.getDecisionFuture().complete(BreakpointDecision.abort(abortResponse));
    }

    /**
     * Returns a snapshot of all currently held (paused) exchanges.
     */
    public Map<String, PausedExchange> entries() {
        return new LinkedHashMap<>(held);
    }

    /**
     * Number of currently held exchanges.
     */
    public int size() {
        return held.size();
    }

    /**
     * Auto-continues all held exchanges so their async continuations fire.
     * Called on server reset.
     *
     * <p>Uses a drain loop (poll until empty) instead of iterating + clear(),
     * because each {@code complete()} triggers a {@code whenComplete} callback
     * that removes the entry from {@code held}. An explicit {@code held.clear()}
     * after the loop would race with entries added mid-reset — the callback
     * removal handles cleanup correctly.
     */
    public void reset() {
        // Drain: complete each, let the whenComplete callback remove it.
        // Loop until snapshot is empty to catch entries added during the reset.
        while (!held.isEmpty()) {
            for (PausedExchange exchange : held.values()) {
                exchange.getDecisionFuture().complete(BreakpointDecision.continueOriginal());
            }
        }
    }
}
