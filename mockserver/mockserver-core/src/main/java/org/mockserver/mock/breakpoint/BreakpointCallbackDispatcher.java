package org.mockserver.mock.breakpoint;

import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.closurecallback.websocketregistry.WebSocketRequestCallback;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.uuid.UUIDService;

import java.util.Map;
import java.util.concurrent.*;

import static org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry.BREAKPOINT_ID_HEADER_NAME;
import static org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry.REQUEST_TIMESTAMP_HEADER_NAME;
import static org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry.WEB_SOCKET_CORRELATION_ID_HEADER_NAME;
import static org.slf4j.event.Level.INFO;
import static org.slf4j.event.Level.WARN;

/**
 * Dispatches breakpoint-held exchanges to a callback WebSocket client for
 * interactive resolution, reusing the same {@link WebSocketClientRegistry}
 * dispatch primitives that object-callback ({@code forwardObject} /
 * {@code responseObject}) features already use.
 *
 * <h3>Protocol</h3>
 * <ul>
 *     <li><b>REQUEST phase:</b> the paused request is sent to the client
 *         (with a {@code WebSocketCorrelationId} header). The client replies
 *         with either:
 *         <ul>
 *             <li>An {@link HttpRequest} — interpreted as MODIFY (forward the
 *                 replacement) if different from the original, or CONTINUE if
 *                 identical.</li>
 *             <li>An {@link HttpResponse} — interpreted as ABORT (write that
 *                 response directly, do not forward).</li>
 *         </ul>
 *     </li>
 *     <li><b>RESPONSE phase:</b> the paused request+response are sent to the
 *         client. The client replies with an {@link HttpResponse} — the
 *         decision is MODIFY (write the replacement) or CONTINUE (if the client
 *         echoes the original). In practice the continuation code treats any
 *         reply as the response to write.</li>
 * </ul>
 *
 * <h3>Safety rails</h3>
 * <ul>
 *     <li>Timeout: auto-completes to CONTINUE after
 *         {@link Configuration#breakpointTimeoutMillis()}.</li>
 *     <li>Max-held cap: shared across all breakpoint dispatchers — if the cap
 *         is already reached, returns {@code null} (caller skips the breakpoint).</li>
 *     <li>Disconnect: all in-flight dispatches for a disconnected client are
 *         auto-completed to CONTINUE via
 *         {@link #autoCompleteForClient(String)}.</li>
 * </ul>
 *
 * <p>This class is thread-safe. Correlation handlers are registered before
 * the WS message is sent, and cleaned up on completion/timeout/disconnect.
 */
public class BreakpointCallbackDispatcher {

    private static final BreakpointCallbackDispatcher INSTANCE = new BreakpointCallbackDispatcher();

    private static final ScheduledExecutorService TIMEOUT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MockServer-breakpoint-ws-timeout");
        t.setDaemon(true);
        return t;
    });

    /**
     * Tracks in-flight dispatch futures keyed by correlationId so they can be
     * auto-completed on client disconnect.
     */
    private final ConcurrentHashMap<String, InFlightDispatch> inFlight = new ConcurrentHashMap<>();

    public static BreakpointCallbackDispatcher getInstance() {
        return INSTANCE;
    }

    /**
     * Dispatches a REQUEST-phase breakpoint over the callback WebSocket.
     *
     * @param clientId               the owning callback client
     * @param request                the captured request to hold
     * @param webSocketClientRegistry the WS registry for sending messages
     * @param configuration          for timeout and max-held
     * @param logger                 for logging
     * @return a future that completes with the breakpoint decision, or {@code null}
     *         if the max-held cap is reached or the client is not connected
     */
    public CompletableFuture<BreakpointDecision> dispatchRequest(
        String clientId,
        HttpRequest request,
        WebSocketClientRegistry webSocketClientRegistry,
        Configuration configuration,
        MockServerLogger logger
    ) {
        return dispatchRequest(clientId, null, request, webSocketClientRegistry, configuration, logger);
    }

    /**
     * Dispatches a REQUEST-phase breakpoint over the callback WebSocket, tagging
     * the message with the matched breakpoint id so the client can route it to
     * the correct per-breakpoint handler.
     *
     * @param clientId               the owning callback client
     * @param breakpointId           the matched breakpoint's id (may be null for backward compat)
     * @param request                the captured request to hold
     * @param webSocketClientRegistry the WS registry for sending messages
     * @param configuration          for timeout and max-held
     * @param logger                 for logging
     * @return a future that completes with the breakpoint decision, or {@code null}
     *         if the max-held cap is reached or the client is not connected
     */
    public CompletableFuture<BreakpointDecision> dispatchRequest(
        String clientId,
        String breakpointId,
        HttpRequest request,
        WebSocketClientRegistry webSocketClientRegistry,
        Configuration configuration,
        MockServerLogger logger
    ) {
        // max-held cap (shared with all breakpoint registries and dispatchers)
        int maxHeld = configuration.breakpointMaxHeld();
        int totalHeld = totalHeldCount();
        if (totalHeld >= maxHeld) {
            if (logger != null && logger.isEnabledForInstance(INFO)) {
                logger.logEvent(new LogEntry().setLogLevel(INFO)
                    .setHttpRequest(request)
                    .setMessageFormat("breakpoint WS dispatch cap reached ({}/{}), skipping for request:{}")
                    .setArguments(totalHeld, maxHeld, request));
            }
            return null;
        }

        String correlationId = UUIDService.getUUID();
        CompletableFuture<BreakpointDecision> future = new CompletableFuture<>();
        InFlightDispatch dispatch = new InFlightDispatch(correlationId, clientId, future);
        inFlight.put(correlationId, dispatch);

        // Register forward callback handler — the client replies with an HttpRequest
        // (continue/modify) OR an HttpResponse (abort). All cleanup (handler
        // unregister, timeout cancel, in-flight removal) is centralised in the
        // whenComplete below so EVERY completion route (reply, timeout, disconnect,
        // reset, send-failure) cleans up uniformly.
        webSocketClientRegistry.registerForwardCallbackHandler(correlationId, new WebSocketRequestCallback() {
            @Override
            public void handle(HttpRequest callbackRequest) {
                // Strip the internal dispatch headers so they are not forwarded to
                // the upstream when the (echoed) request is continued/modified.
                HttpRequest cleaned = callbackRequest
                    .removeHeader(WEB_SOCKET_CORRELATION_ID_HEADER_NAME)
                    .removeHeader(BREAKPOINT_ID_HEADER_NAME)
                    .removeHeader(REQUEST_TIMESTAMP_HEADER_NAME);
                // The client's echoed request is a fresh deserialized object that has
                // lost the transient receivedTimestamp (it is @JsonIgnore). Re-apply
                // the ORIGINAL request's timestamp so the subsequent RESPONSE-phase
                // breakpoint (and the dashboard's Live Exchanges sort) groups by the
                // original request time instead of when the response paused.
                cleaned.withReceivedTimestamp(request.getReceivedTimestamp());
                future.complete(BreakpointDecision.modify(cleaned));
            }

            @Override
            public void handleError(HttpResponse httpResponse) {
                future.complete(BreakpointDecision.continueOriginal());
            }
        });

        // Also register a response callback handler — if the client replies with an
        // HttpResponse instead of an HttpRequest, it means ABORT (write that response)
        webSocketClientRegistry.registerResponseCallbackHandler(correlationId, response -> {
            HttpResponse cleaned = response.removeHeader(WEB_SOCKET_CORRELATION_ID_HEADER_NAME);
            future.complete(BreakpointDecision.abort(cleaned));
        });

        // Schedule timeout auto-continue
        long timeoutMillis = configuration.breakpointTimeoutMillis();
        ScheduledFuture<?> timeoutHandle = TIMEOUT_SCHEDULER.schedule(() -> {
            if (future.complete(BreakpointDecision.continueOriginal()) && logger != null && logger.isEnabledForInstance(INFO)) {
                logger.logEvent(new LogEntry().setLogLevel(INFO)
                    .setHttpRequest(request)
                    .setMessageFormat("breakpoint WS dispatch auto-continued (timeout {}ms) for request:{}")
                    .setArguments(timeoutMillis, request));
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);

        // Single cleanup point for all completion routes.
        // Use conditional remove so a stale callback from a reset()-completed
        // dispatch cannot remove a NEW dispatch with the same correlationId.
        future.whenComplete((decision, throwable) -> {
            timeoutHandle.cancel(false);
            cleanup(correlationId, webSocketClientRegistry);
            inFlight.remove(correlationId, dispatch);
        });

        // Send the request to the client, including the breakpoint id and timestamp headers
        Long requestTimestamp = request.getReceivedTimestamp();
        HttpRequest dispatchClone = request.clone().withHeader(WEB_SOCKET_CORRELATION_ID_HEADER_NAME, correlationId);
        if (breakpointId != null) {
            dispatchClone.withHeader(BREAKPOINT_ID_HEADER_NAME, breakpointId);
        }
        if (requestTimestamp != null) {
            dispatchClone.withHeader(REQUEST_TIMESTAMP_HEADER_NAME, String.valueOf(requestTimestamp));
        }
        if (!webSocketClientRegistry.sendClientMessage(clientId, dispatchClone, null)) {
            // Client not connected — complete (triggers whenComplete cleanup) and return null
            future.complete(BreakpointDecision.continueOriginal());
            if (logger != null && logger.isEnabledForInstance(WARN)) {
                logger.logEvent(new LogEntry().setLogLevel(WARN)
                    .setHttpRequest(request)
                    .setMessageFormat("breakpoint WS dispatch failed: client {} not connected for request:{}")
                    .setArguments(clientId, request));
            }
            return null;
        }

        return future;
    }

    /**
     * Dispatches a RESPONSE-phase breakpoint over the callback WebSocket.
     *
     * @param clientId               the owning callback client
     * @param request                the original request (for context)
     * @param response               the upstream response to hold
     * @param webSocketClientRegistry the WS registry
     * @param configuration          for timeout and max-held
     * @param logger                 for logging
     * @return a future that completes with the breakpoint decision, or {@code null}
     *         if the max-held cap is reached or the client is not connected
     */
    public CompletableFuture<BreakpointDecision> dispatchResponse(
        String clientId,
        HttpRequest request,
        HttpResponse response,
        WebSocketClientRegistry webSocketClientRegistry,
        Configuration configuration,
        MockServerLogger logger
    ) {
        return dispatchResponse(clientId, null, request, response, webSocketClientRegistry, configuration, logger);
    }

    /**
     * Dispatches a RESPONSE-phase breakpoint over the callback WebSocket, tagging
     * the message with the matched breakpoint id.
     *
     * @param clientId               the owning callback client
     * @param breakpointId           the matched breakpoint's id (may be null)
     * @param request                the original request (for context)
     * @param response               the upstream response to hold
     * @param webSocketClientRegistry the WS registry
     * @param configuration          for timeout and max-held
     * @param logger                 for logging
     * @return a future that completes with the breakpoint decision, or {@code null}
     *         if the max-held cap is reached or the client is not connected
     */
    public CompletableFuture<BreakpointDecision> dispatchResponse(
        String clientId,
        String breakpointId,
        HttpRequest request,
        HttpResponse response,
        WebSocketClientRegistry webSocketClientRegistry,
        Configuration configuration,
        MockServerLogger logger
    ) {
        // max-held cap (shared with all breakpoint registries and dispatchers)
        int maxHeld = configuration.breakpointMaxHeld();
        int totalHeld = totalHeldCount();
        if (totalHeld >= maxHeld) {
            if (logger != null && logger.isEnabledForInstance(INFO)) {
                logger.logEvent(new LogEntry().setLogLevel(INFO)
                    .setHttpRequest(request)
                    .setMessageFormat("breakpoint WS dispatch cap reached ({}/{}), skipping for response to request:{}")
                    .setArguments(totalHeld, maxHeld, request));
            }
            return null;
        }

        String correlationId = UUIDService.getUUID();
        CompletableFuture<BreakpointDecision> future = new CompletableFuture<>();
        InFlightDispatch dispatch = new InFlightDispatch(correlationId, clientId, future);
        inFlight.put(correlationId, dispatch);

        // Register response callback handler — client replies with an HttpResponse
        // (the replacement or original). Cleanup is centralised in whenComplete.
        webSocketClientRegistry.registerResponseCallbackHandler(correlationId, callbackResponse -> {
            HttpResponse cleaned = callbackResponse.removeHeader(WEB_SOCKET_CORRELATION_ID_HEADER_NAME);
            future.complete(BreakpointDecision.modifyResponse(cleaned));
        });

        // Schedule timeout auto-continue
        long timeoutMillis = configuration.breakpointTimeoutMillis();
        ScheduledFuture<?> timeoutHandle = TIMEOUT_SCHEDULER.schedule(() -> {
            if (future.complete(BreakpointDecision.continueOriginal()) && logger != null && logger.isEnabledForInstance(INFO)) {
                logger.logEvent(new LogEntry().setLogLevel(INFO)
                    .setHttpRequest(request)
                    .setMessageFormat("breakpoint WS dispatch auto-continued (timeout {}ms) for response to request:{}")
                    .setArguments(timeoutMillis, request));
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);

        // Single cleanup point for all completion routes.
        // Use conditional remove so a stale callback cannot remove a newer dispatch.
        future.whenComplete((decision, throwable) -> {
            timeoutHandle.cancel(false);
            cleanup(correlationId, webSocketClientRegistry);
            inFlight.remove(correlationId, dispatch);
        });

        // Send request+response to the client, including the breakpoint id and timestamp headers
        Long requestTimestamp = request.getReceivedTimestamp();
        HttpRequest responseDispatchClone = request.clone().withHeader(WEB_SOCKET_CORRELATION_ID_HEADER_NAME, correlationId);
        if (breakpointId != null) {
            responseDispatchClone.withHeader(BREAKPOINT_ID_HEADER_NAME, breakpointId);
        }
        if (requestTimestamp != null) {
            responseDispatchClone.withHeader(REQUEST_TIMESTAMP_HEADER_NAME, String.valueOf(requestTimestamp));
        }
        if (!webSocketClientRegistry.sendClientMessage(clientId, responseDispatchClone, response)) {
            // Client not connected — complete (triggers whenComplete cleanup) and return null
            future.complete(BreakpointDecision.continueOriginal());
            if (logger != null && logger.isEnabledForInstance(WARN)) {
                logger.logEvent(new LogEntry().setLogLevel(WARN)
                    .setHttpRequest(request)
                    .setMessageFormat("breakpoint WS dispatch failed: client {} not connected for response to request:{}")
                    .setArguments(clientId, request));
            }
            return null;
        }

        return future;
    }

    /**
     * Returns the total held count across all breakpoint dispatchers:
     * this dispatcher's in-flight WS dispatches (REQUEST/RESPONSE phases) and
     * StreamFrameCallbackDispatcher's in-flight WS stream frame dispatches.
     */
    static int totalHeldCount() {
        return BreakpointCallbackDispatcher.getInstance().inFlightCount()
            + StreamFrameCallbackDispatcher.getInstance().inFlightCount();
    }

    /**
     * Auto-completes all in-flight WS breakpoint dispatches for the given client
     * with CONTINUE. Called when the client's WebSocket connection closes.
     *
     * @param clientId the disconnecting client
     * @return the number of in-flight dispatches auto-completed
     */
    public int autoCompleteForClient(String clientId) {
        if (clientId == null) {
            return 0;
        }
        int count = 0;
        for (Map.Entry<String, InFlightDispatch> entry : inFlight.entrySet()) {
            InFlightDispatch dispatch = entry.getValue();
            if (clientId.equals(dispatch.clientId) && !dispatch.future.isDone()) {
                dispatch.future.complete(BreakpointDecision.continueOriginal());
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the number of in-flight WS breakpoint dispatches.
     */
    public int inFlightCount() {
        return inFlight.size();
    }

    /**
     * Resets all in-flight dispatches (auto-continue) — called on server reset.
     *
     * <p>Takes a snapshot and clears the map BEFORE completing futures, so that
     * asynchronous {@code whenComplete} callbacks cannot race with subsequent
     * dispatches that re-populate the map.
     */
    public void reset() {
        java.util.List<InFlightDispatch> snapshot = new java.util.ArrayList<>(inFlight.values());
        inFlight.clear();
        for (InFlightDispatch dispatch : snapshot) {
            dispatch.future.complete(BreakpointDecision.continueOriginal());
        }
    }

    private void cleanup(String correlationId, WebSocketClientRegistry registry) {
        registry.unregisterForwardCallbackHandler(correlationId);
        registry.unregisterResponseCallbackHandler(correlationId);
    }

    /**
     * Tracks an in-flight WS breakpoint dispatch for disconnect cleanup.
     */
    private static class InFlightDispatch {
        final String correlationId;
        final String clientId;
        final CompletableFuture<BreakpointDecision> future;

        InFlightDispatch(String correlationId, String clientId, CompletableFuture<BreakpointDecision> future) {
            this.correlationId = correlationId;
            this.clientId = clientId;
            this.future = future;
        }
    }
}
