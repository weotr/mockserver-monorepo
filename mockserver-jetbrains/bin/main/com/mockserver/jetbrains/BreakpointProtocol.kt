package com.mockserver.jetbrains

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpRequest

/**
 * Pure, no-IO, no-IDE encoding/decoding for the MockServer interactive-breakpoint
 * callback-WebSocket protocol — the **frozen contract** documented in
 * `docs/code/breakpoints.md` and implemented by the Java `MockServerClient`
 * (`BreakpointWebSocketClient`), every other language client, and the dashboard.
 *
 * Everything here is split out of [BreakpointWsClient] precisely so the wire
 * shapes can be asserted in unit tests with no live server and no IDE:
 *
 * - matcher registration / removal REST request builders;
 * - the `{ "type": <fqcn>, "value": <json-string> }` callback-WS envelope
 *   (`org.mockserver.serialization.model.WebSocketMessageDTO`), whose `value` is a
 *   **JSON-encoded string** (NOT a nested object);
 * - parsing a paused REQUEST (`HttpRequest`) or RESPONSE
 *   (`HttpRequestAndHttpResponse`) dispatch into a [PausedExchange];
 * - building the decision reply the server expects:
 *   - REQUEST phase: reply with an `HttpRequest` (CONTINUE/MODIFY — forward it) or
 *     an `HttpResponse` (ABORT — write it to the downstream client, don't forward);
 *   - RESPONSE phase: reply with an `HttpResponse` (CONTINUE/MODIFY).
 *
 * The reply MUST echo the dispatched request's `WebSocketCorrelationId` header
 * (object-map header form `{ "Name": ["value"] }`) so the server can route the
 * decision back to the parked exchange.
 */
object BreakpointProtocol {

    private val COMPACT: Gson = Gson()
    private val PRETTY: Gson = GsonBuilder().setPrettyPrinting().create()

    // Fully-qualified type names used in the WS envelope's `type` field — these are
    // server class names and are part of the frozen contract.
    const val TYPE_HTTP_REQUEST = "org.mockserver.model.HttpRequest"
    const val TYPE_HTTP_RESPONSE = "org.mockserver.model.HttpResponse"
    const val TYPE_HTTP_REQUEST_AND_RESPONSE = "org.mockserver.model.HttpRequestAndHttpResponse"
    const val TYPE_CLIENT_ID = "org.mockserver.serialization.model.WebSocketClientIdDTO"
    const val TYPE_PAUSED_STREAM_FRAME = "org.mockserver.serialization.model.PausedStreamFrameDTO"
    const val TYPE_STREAM_FRAME_DECISION = "org.mockserver.serialization.model.StreamFrameDecisionDTO"

    // Header names the server stamps on the dispatched HttpRequest (frozen contract).
    const val CORRELATION_ID_HEADER = "WebSocketCorrelationId"
    const val BREAKPOINT_ID_HEADER = "X-MockServer-BreakpointId"
    const val REQUEST_TIMESTAMP_HEADER = "X-MockServer-RequestTimestamp"

    // Registration header on the callback-WS upgrade (BreakpointWebSocketClient.CLIENT_REGISTRATION_ID_HEADER).
    const val CLIENT_REGISTRATION_ID_HEADER = "X-CLIENT-REGISTRATION-ID"

    /** The breakpoint phases this debugger supports (buffered REQUEST/RESPONSE only). */
    enum class Phase { REQUEST, RESPONSE }

    // ---------------------------------------------------------------------
    // REST: matcher registry endpoints (no IO).
    // ---------------------------------------------------------------------

    /**
     * `PUT /mockserver/breakpoint/matcher` — register a breakpoint matcher owned by
     * [clientId] on the request matcher [requestDefinitionJson] (an `httpRequest`
     * definition `{ method?, path?, headers?, ... }`) for [phases]. An optional
     * non-null [skipCount] makes it an Nth-hit (conditional) breakpoint. Returns
     * `{ id, phases, clientId, skipCount? }`.
     */
    fun buildRegisterMatcherRequest(
        baseUrl: String,
        requestDefinitionJson: String,
        phases: Set<Phase>,
        clientId: String,
        skipCount: Int? = null,
    ): HttpRequest {
        val httpRequest = tryParseJson(requestDefinitionJson) ?: JsonObject()
        val body = JsonObject().apply {
            add("httpRequest", httpRequest)
            add("phases", JsonArray().apply { phases.forEach { add(it.name) } })
            addProperty("clientId", clientId)
            if (skipCount != null && skipCount > 0) addProperty("skipCount", skipCount)
        }
        return HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/breakpoint/matcher"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(COMPACT.toJson(body)))
            .build()
    }

    /** `PUT /mockserver/breakpoint/matcher/remove` — remove the matcher with [id]. */
    fun buildRemoveMatcherRequest(baseUrl: String, id: String): HttpRequest {
        val body = JsonObject().apply { addProperty("id", id) }
        return HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/breakpoint/matcher/remove"))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(COMPACT.toJson(body)))
            .build()
    }

    /** `PUT /mockserver/breakpoint/matcher/clear` — remove all registered matchers. */
    fun buildClearMatchersRequest(baseUrl: String): HttpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/mockserver/breakpoint/matcher/clear"))
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build()

    /** Extract the server-assigned matcher `id` from a register-matcher response body, or null. */
    fun parseRegisteredId(body: String): String? {
        val parsed = tryParseJson(body) ?: return null
        if (!parsed.isJsonObject) return null
        val obj = parsed.asJsonObject
        return if (obj.has("id") && obj.get("id").isJsonPrimitive) obj.get("id").asString else null
    }

    // ---------------------------------------------------------------------
    // Callback-WS envelope (no IO).
    // ---------------------------------------------------------------------

    /**
     * A decoded callback-WS message. The envelope is
     * `{ "type": <fqcn>, "value": "<json string>" }`; [value] is the **already
     * un-escaped** inner JSON document (the server double-encodes it as a string).
     * [type] is null when the frame is not a recognised `{type,value}` envelope.
     */
    data class Envelope(val type: String?, val value: String?)

    /**
     * Decode a raw callback-WS text frame into an [Envelope]. The inner `value` is a
     * JSON string literal, so it is decoded back into its raw JSON document text.
     * Returns an envelope with null fields when [frame] is not a `{type,value}` object.
     */
    fun decodeEnvelope(frame: String): Envelope {
        val parsed = tryParseJson(frame) ?: return Envelope(null, null)
        if (!parsed.isJsonObject) return Envelope(null, null)
        val obj = parsed.asJsonObject
        val type = if (obj.has("type") && obj.get("type").isJsonPrimitive) obj.get("type").asString else null
        val value = when {
            !obj.has("value") || obj.get("value").isJsonNull -> null
            // The contract: value is a JSON-encoded *string*. asString un-escapes it.
            obj.get("value").isJsonPrimitive && obj.get("value").asJsonPrimitive.isString -> obj.get("value").asString
            // Defensive: some encoders may inline the object — accept that too.
            else -> COMPACT.toJson(obj.get("value"))
        }
        return Envelope(type, value)
    }

    /** True when [type] is the server-assigned client-id handshake message (carries no exchange). */
    fun isClientIdEnvelope(type: String?): Boolean = type == TYPE_CLIENT_ID

    /** Extract the assigned `clientId` from a decoded [WebSocketClientIdDTO] value document. */
    fun parseClientId(value: String?): String? {
        if (value == null) return null
        val parsed = tryParseJson(value) ?: return null
        if (!parsed.isJsonObject) return null
        val obj = parsed.asJsonObject
        return if (obj.has("clientId") && obj.get("clientId").isJsonPrimitive) obj.get("clientId").asString else null
    }

    // ---------------------------------------------------------------------
    // Paused exchange model + parsing.
    // ---------------------------------------------------------------------

    /**
     * A paused exchange dispatched over the callback WS, awaiting a decision.
     *
     * [phase] is REQUEST (only `request` present) or RESPONSE (`request` + `response`).
     * [requestJson] / [responseJson] are pretty-printed JSON documents for display
     * and editing. [correlationId] (echoed back in the reply) and [breakpointId] are
     * lifted from the dispatched request's headers; [requestTimestamp] (epoch-millis)
     * orders exchanges by original receipt time.
     */
    data class PausedExchange(
        val phase: Phase,
        val correlationId: String?,
        val breakpointId: String?,
        val requestTimestamp: Long?,
        val method: String?,
        val path: String?,
        val requestJson: String,
        val responseJson: String?,
    )

    /**
     * Parse a decoded [Envelope] into a [PausedExchange], or null when the envelope is
     * not a REQUEST (`HttpRequest`) or RESPONSE (`HttpRequestAndHttpResponse`) dispatch.
     *
     * - `HttpRequest` → REQUEST phase: the value IS the request document.
     * - `HttpRequestAndHttpResponse` → RESPONSE phase: `{ httpRequest, httpResponse }`.
     *   The correlation/breakpoint/timestamp headers live on the inner `httpRequest`.
     */
    fun parsePausedExchange(envelope: Envelope): PausedExchange? {
        val value = envelope.value ?: return null
        val parsed = tryParseJson(value) ?: return null
        if (!parsed.isJsonObject) return null
        return when (envelope.type) {
            TYPE_HTTP_REQUEST -> {
                val request = parsed.asJsonObject
                PausedExchange(
                    phase = Phase.REQUEST,
                    correlationId = headerValue(request, CORRELATION_ID_HEADER),
                    breakpointId = headerValue(request, BREAKPOINT_ID_HEADER),
                    requestTimestamp = headerValue(request, REQUEST_TIMESTAMP_HEADER)?.toLongOrNull(),
                    method = stringField(request, "method"),
                    path = stringField(request, "path"),
                    requestJson = PRETTY.toJson(request),
                    responseJson = null,
                )
            }
            TYPE_HTTP_REQUEST_AND_RESPONSE -> {
                val obj = parsed.asJsonObject
                val request = if (obj.has("httpRequest") && obj.get("httpRequest").isJsonObject) obj.getAsJsonObject("httpRequest") else JsonObject()
                val response = if (obj.has("httpResponse") && obj.get("httpResponse").isJsonObject) obj.getAsJsonObject("httpResponse") else JsonObject()
                PausedExchange(
                    phase = Phase.RESPONSE,
                    correlationId = headerValue(request, CORRELATION_ID_HEADER),
                    breakpointId = headerValue(request, BREAKPOINT_ID_HEADER),
                    requestTimestamp = headerValue(request, REQUEST_TIMESTAMP_HEADER)?.toLongOrNull(),
                    method = stringField(request, "method"),
                    path = stringField(request, "path"),
                    requestJson = PRETTY.toJson(request),
                    responseJson = PRETTY.toJson(response),
                )
            }
            else -> null
        }
    }

    // ---------------------------------------------------------------------
    // Decision replies (the WS reply IS the resolution — no REST call).
    // ---------------------------------------------------------------------

    /**
     * Build a REQUEST-phase CONTINUE reply: an `HttpRequest` envelope carrying the
     * unchanged [exchange] request with its `WebSocketCorrelationId` preserved. The
     * server treats an identical request as CONTINUE.
     */
    fun buildContinueRequestReply(exchange: PausedExchange): String {
        require(exchange.phase == Phase.REQUEST) { "CONTINUE-request reply is REQUEST phase only" }
        val request = JsonParser.parseString(exchange.requestJson).asJsonObject
        ensureCorrelationId(request, exchange.correlationId)
        return encodeEnvelope(TYPE_HTTP_REQUEST, request)
    }

    /**
     * Build a REQUEST-phase MODIFY reply from the user's edited [editedRequestJson]
     * (a full `httpRequest` document). The server forwards this replacement request.
     * The original `WebSocketCorrelationId` is (re)applied so routing still works even
     * if the user removed it while editing. Throws [IllegalArgumentException] when the
     * edited text is not a JSON object.
     */
    fun buildModifyRequestReply(exchange: PausedExchange, editedRequestJson: String): String {
        require(exchange.phase == Phase.REQUEST) { "MODIFY-request reply is REQUEST phase only" }
        val request = parseObjectOrThrow(editedRequestJson, "request")
        ensureCorrelationId(request, exchange.correlationId)
        return encodeEnvelope(TYPE_HTTP_REQUEST, request)
    }

    /**
     * Build a REQUEST-phase ABORT reply: an `HttpResponse` envelope. The server writes
     * this response directly to the downstream client and does NOT forward upstream.
     * ABORT is REQUEST-phase only (per breakpoints.md). [abortResponseJson] is a full
     * `httpResponse` document (default 503 when blank).
     */
    fun buildAbortReply(exchange: PausedExchange, abortResponseJson: String? = null): String {
        require(exchange.phase == Phase.REQUEST) { "ABORT is REQUEST phase only" }
        val response = if (abortResponseJson.isNullOrBlank()) {
            JsonObject().apply { addProperty("statusCode", 503); addProperty("reasonPhrase", "Aborted by MockServer breakpoint") }
        } else {
            parseObjectOrThrow(abortResponseJson, "response")
        }
        ensureCorrelationId(response, exchange.correlationId)
        return encodeEnvelope(TYPE_HTTP_RESPONSE, response)
    }

    /**
     * Build a RESPONSE-phase CONTINUE reply: an `HttpResponse` envelope carrying the
     * unchanged upstream/mock response with the correlation id applied. (RESPONSE phase
     * has no separate ABORT — write-this-response covers both CONTINUE and MODIFY.)
     */
    fun buildContinueResponseReply(exchange: PausedExchange): String {
        require(exchange.phase == Phase.RESPONSE) { "CONTINUE-response reply is RESPONSE phase only" }
        val response = JsonParser.parseString(exchange.responseJson ?: "{}").asJsonObject
        ensureCorrelationId(response, exchange.correlationId)
        return encodeEnvelope(TYPE_HTTP_RESPONSE, response)
    }

    /**
     * Build a RESPONSE-phase MODIFY reply from the user's edited [editedResponseJson]
     * (a full `httpResponse` document). The server writes this replacement response to
     * the downstream client. Throws [IllegalArgumentException] on non-object input.
     */
    fun buildModifyResponseReply(exchange: PausedExchange, editedResponseJson: String): String {
        require(exchange.phase == Phase.RESPONSE) { "MODIFY-response reply is RESPONSE phase only" }
        val response = parseObjectOrThrow(editedResponseJson, "response")
        ensureCorrelationId(response, exchange.correlationId)
        return encodeEnvelope(TYPE_HTTP_RESPONSE, response)
    }

    // ---------------------------------------------------------------------
    // Stream-frame phase (RESPONSE_STREAM / INBOUND_STREAM) — parity with the
    // VS Code extension's breakpointProtocol.ts. A paused stream frame
    // (PausedStreamFrameDTO) is resolved with a StreamFrameDecisionDTO reply.
    // ---------------------------------------------------------------------

    /** Per-frame resolution actions (StreamFrameDecisionDTO.action). */
    enum class StreamFrameAction { CONTINUE, MODIFY, DROP, INJECT, CLOSE }

    /**
     * A paused stream frame dispatched over the callback WS (the frozen
     * `PausedStreamFrameDTO` shape). [body] is the frame payload, Base64-encoded.
     * [correlationId] is echoed back in the [StreamFrameDecisionDTO] reply so the
     * server can route the decision to the parked frame.
     */
    data class PausedStreamFrame(
        val correlationId: String,
        val streamId: String?,
        val sequenceNumber: Long?,
        val direction: String?,
        val phase: String?,
        val body: String?,
        val requestMethod: String?,
        val requestPath: String?,
        val breakpointId: String?,
        val requestTimestamp: Long?,
    )

    /**
     * True when [type] is a paused stream-frame dispatch. (This debugger only
     * registers REQUEST/RESPONSE matchers today, so the server will not normally push
     * these — but the client decodes them so stream support can be enabled.)
     */
    fun isStreamFrameEnvelope(type: String?): Boolean = type == TYPE_PAUSED_STREAM_FRAME

    /**
     * Parse a decoded [Envelope] into a [PausedStreamFrame], or null when it is not a
     * `PausedStreamFrameDTO`. A frame with no `correlationId` is rejected (null) — the
     * reply could not be routed without it.
     */
    fun parsePausedStreamFrame(envelope: Envelope): PausedStreamFrame? {
        if (envelope.type != TYPE_PAUSED_STREAM_FRAME) return null
        val value = envelope.value ?: return null
        val parsed = tryParseJson(value) ?: return null
        if (!parsed.isJsonObject) return null
        val obj = parsed.asJsonObject
        val correlationId = stringField(obj, "correlationId") ?: return null
        return PausedStreamFrame(
            correlationId = correlationId,
            streamId = stringField(obj, "streamId"),
            sequenceNumber = longField(obj, "sequenceNumber"),
            direction = stringField(obj, "direction"),
            phase = stringField(obj, "phase"),
            body = stringField(obj, "body"),
            requestMethod = stringField(obj, "requestMethod"),
            requestPath = stringField(obj, "requestPath"),
            breakpointId = stringField(obj, "breakpointId"),
            requestTimestamp = stringField(obj, "requestTimestamp")?.toLongOrNull() ?: longField(obj, "requestTimestamp"),
        )
    }

    /**
     * Build the `StreamFrameDecisionDTO` reply envelope for a paused [frame]. The
     * `correlationId` is always echoed from the frame. [bodyBase64] (replacement /
     * injected bytes, Base64-encoded) is required for MODIFY and INJECT and ignored
     * for CONTINUE/DROP/CLOSE. Throws [IllegalArgumentException] when MODIFY/INJECT is
     * requested without a body, mirroring the frozen contract (the server cannot apply
     * a body-less MODIFY/INJECT).
     */
    fun buildStreamFrameReply(
        frame: PausedStreamFrame,
        action: StreamFrameAction,
        bodyBase64: String? = null,
    ): String {
        val needsBody = action == StreamFrameAction.MODIFY || action == StreamFrameAction.INJECT
        if (needsBody && bodyBase64.isNullOrEmpty()) {
            throw IllegalArgumentException("A Base64 body is required for stream-frame ${action.name}.")
        }
        val decision = JsonObject().apply {
            addProperty("correlationId", frame.correlationId)
            addProperty("action", action.name)
            if (needsBody) addProperty("body", bodyBase64)
        }
        return encodeEnvelope(TYPE_STREAM_FRAME_DECISION, decision)
    }

    // ---------------------------------------------------------------------
    // Internals.
    // ---------------------------------------------------------------------

    /** Wrap [document] in the `{ type, value:<json-string> }` envelope the server reads. */
    private fun encodeEnvelope(type: String, document: JsonObject): String {
        val envelope = JsonObject().apply {
            addProperty("type", type)
            // value is a JSON-encoded STRING (matches WebSocketMessageSerializer).
            addProperty("value", COMPACT.toJson(document))
        }
        return COMPACT.toJson(envelope)
    }

    /**
     * Force [obj]'s `headers` (object-map form `{ Name: [value] }`) to carry the
     * **original** dispatched `WebSocketCorrelationId` the server uses to route the
     * decision back. The original id always wins — any value the user typed while
     * editing is overwritten, so an accidental edit of this routing header cannot
     * orphan the parked exchange. A no-op only when [correlationId] is null.
     */
    private fun ensureCorrelationId(obj: JsonObject, correlationId: String?) {
        if (correlationId == null) return
        val headers = if (obj.has("headers") && obj.get("headers").isJsonObject) {
            obj.getAsJsonObject("headers")
        } else {
            JsonObject().also { obj.add("headers", it) }
        }
        // Remove any user-typed variant (case-insensitive) then set the canonical one.
        headers.entrySet().map { it.key }.filter { it.equals(CORRELATION_ID_HEADER, ignoreCase = true) }
            .forEach { headers.remove(it) }
        headers.add(CORRELATION_ID_HEADER, JsonArray().apply { add(correlationId) })
    }

    /** Read the first value of header [name] from [request]'s object-map `headers`, or null. */
    private fun headerValue(request: JsonObject, name: String): String? {
        if (!request.has("headers") || !request.get("headers").isJsonObject) return null
        val headers = request.getAsJsonObject("headers")
        for ((key, value) in headers.entrySet()) {
            if (!key.equals(name, ignoreCase = true)) continue
            if (value.isJsonArray && value.asJsonArray.size() > 0) {
                val first = value.asJsonArray.get(0)
                if (first.isJsonPrimitive) return first.asString
            } else if (value.isJsonPrimitive) {
                return value.asString
            }
        }
        return null
    }

    private fun stringField(obj: JsonObject, field: String): String? {
        if (!obj.has(field) || !obj.get(field).isJsonPrimitive) return null
        return obj.get(field).asString
    }

    private fun longField(obj: JsonObject, field: String): Long? {
        if (!obj.has(field) || !obj.get(field).isJsonPrimitive) return null
        return try { obj.get(field).asLong } catch (_: Exception) { null }
    }

    private fun parseObjectOrThrow(text: String, what: String): JsonObject {
        val parsed = try {
            JsonParser.parseString(text)
        } catch (ex: Exception) {
            throw IllegalArgumentException("The edited $what isn't valid JSON: ${ex.message}")
        }
        if (!parsed.isJsonObject) {
            throw IllegalArgumentException("The edited $what must be a JSON object.")
        }
        return parsed.asJsonObject
    }

    private fun tryParseJson(text: String): JsonElement? =
        try {
            val element = JsonParser.parseString(text)
            if (element.isJsonObject || element.isJsonArray) element else null
        } catch (_: Exception) {
            null
        }
}
