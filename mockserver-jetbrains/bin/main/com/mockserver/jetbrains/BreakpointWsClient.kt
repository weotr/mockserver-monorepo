package com.mockserver.jetbrains

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicReference

/**
 * A small callback-WebSocket client for the MockServer interactive-breakpoint
 * protocol, built only on the JDK `java.net.http.WebSocket` so it works in
 * IntelliJ **Community** (no Ultimate-only or LSP API, no Netty dependency).
 *
 * Responsibilities, kept deliberately thin — all wire shapes live in the pure,
 * unit-tested [BreakpointProtocol]:
 *
 * 1. open `ws://localhost:<port>/_mockserver_callback_websocket` with the
 *    `X-CLIENT-REGISTRATION-ID` header so the server adopts a known [clientId];
 * 2. reassemble multi-part text frames, decode each `{type,value}` envelope, and
 *    hand REQUEST/RESPONSE paused exchanges to [onPaused];
 * 3. expose [sendDecision] to write a decision reply back over the same socket;
 * 4. surface connect/close/error to [onState].
 *
 * Threading: callbacks fire on the JDK HttpClient's listener thread, NOT the EDT —
 * callers must marshal UI work onto the EDT themselves (see [BreakpointDebuggerPanel]).
 */
class BreakpointWsClient(
    private val port: Int,
    /** Fired for each paused REQUEST/RESPONSE exchange (off the EDT). */
    private val onPaused: (BreakpointProtocol.PausedExchange) -> Unit,
    /** Fired on connect (true) / disconnect (false), and on error with a message. */
    private val onState: (connected: Boolean, message: String?) -> Unit,
    /** Fired for each paused stream frame (off the EDT). Default no-op. */
    private val onStreamFrame: (BreakpointProtocol.PausedStreamFrame) -> Unit = {},
) {

    /** The client id this debugger registers its matchers under (server adopts it). */
    val clientId: String = "jetbrains-debugger-" + UUID.randomUUID()

    private val webSocketRef = AtomicReference<WebSocket?>(null)
    @Volatile private var closing = false

    /** True once the socket is open and the handshake completed. */
    val connected: Boolean get() = webSocketRef.get() != null && !closing

    /**
     * Open the callback WebSocket. Completes (exceptionally on failure) when the
     * handshake finishes. Idempotent-ish: a second call while connected is a no-op.
     */
    fun connect(): CompletableFuture<Void> {
        if (webSocketRef.get() != null) return CompletableFuture.completedFuture(null)
        closing = false
        val uri = URI.create("ws://localhost:$port/_mockserver_callback_websocket")
        return HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .header(BreakpointProtocol.CLIENT_REGISTRATION_ID_HEADER, clientId)
            .buildAsync(uri, Listener())
            .thenAccept { ws ->
                webSocketRef.set(ws)
                onState(true, null)
            }
            .exceptionally { ex ->
                onState(false, ex.cause?.message ?: ex.message)
                throw if (ex is RuntimeException) ex else RuntimeException(ex)
            }
    }

    /**
     * Send a pre-built decision reply (from [BreakpointProtocol]) over the socket. If the
     * underlying send fails (e.g. the connection dropped between the caller's `connected`
     * check and here), [onState] is fired with the failure so the UI can keep the
     * exchange visible rather than silently dropping it.
     */
    fun sendDecision(replyJson: String) {
        val ws = webSocketRef.get() ?: throw IllegalStateException("breakpoint WebSocket is not connected")
        ws.sendText(replyJson, true).exceptionally { ex ->
            if (!closing) onState(false, ex.cause?.message ?: ex.message)
            null
        }
    }

    /** Close the socket; the server then auto-cleans this client's matchers and held items. */
    fun close() {
        closing = true
        val ws = webSocketRef.getAndSet(null) ?: return
        try {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "closed by IDE").orTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
            ws.abort()
        }
        onState(false, null)
    }

    private inner class Listener : WebSocket.Listener {

        // Text frames can be delivered in parts (last=false); reassemble before decoding.
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            // onOpen fires on the listener thread the instant the handshake completes —
            // before buildAsync's thenAccept runs. Publish the socket here too so a
            // decision sent in that narrow window (and `connected`) see a live socket.
            webSocketRef.set(webSocket)
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val frame = buffer.toString()
                buffer.setLength(0)
                handleFrame(frame)
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*>? {
            webSocketRef.compareAndSet(webSocket, null)
            if (!closing) onState(false, reason?.takeIf { it.isNotBlank() })
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable?) {
            webSocketRef.compareAndSet(webSocket, null)
            if (!closing) onState(false, error?.message)
        }
    }

    /**
     * Decode one reassembled text frame. The first frame is the server-assigned
     * client-id handshake (ignored — we registered our own id via the header);
     * REQUEST/RESPONSE dispatches become [BreakpointProtocol.PausedExchange]s, and
     * `PausedStreamFrameDTO` dispatches become [BreakpointProtocol.PausedStreamFrame]s
     * delivered to [onStreamFrame]. A UI callback that throws never kills the listener.
     */
    private fun handleFrame(frame: String) {
        val envelope = try {
            BreakpointProtocol.decodeEnvelope(frame)
        } catch (_: Exception) {
            return
        }
        if (BreakpointProtocol.isClientIdEnvelope(envelope.type)) return

        if (BreakpointProtocol.isStreamFrameEnvelope(envelope.type)) {
            val streamFrame = try {
                BreakpointProtocol.parsePausedStreamFrame(envelope)
            } catch (_: Exception) {
                null
            }
            if (streamFrame != null) {
                try {
                    onStreamFrame(streamFrame)
                } catch (_: Exception) {
                    // Never let a UI callback exception kill the listener thread.
                }
            }
            return
        }

        val paused = try {
            BreakpointProtocol.parsePausedExchange(envelope)
        } catch (_: Exception) {
            null
        }
        if (paused != null) {
            try {
                onPaused(paused)
            } catch (_: Exception) {
                // Never let a UI callback exception kill the listener thread.
            }
        }
    }
}
