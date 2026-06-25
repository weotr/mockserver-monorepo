package com.mockserver.jetbrains

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.http.HttpRequest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [BreakpointProtocol] — the frozen-contract wire shapes for the
 * interactive-breakpoint callback WebSocket. Asserted with no live server and no IDE.
 *
 * The shapes here are checked against `docs/code/breakpoints.md` and the Java client's
 * `BreakpointWebSocketClient` / `WebSocketMessageSerializer`:
 * - envelope = `{ type: <fqcn>, value: "<json string>" }` (value is an ENCODED STRING);
 * - REQUEST dispatch = `HttpRequest`; RESPONSE dispatch = `HttpRequestAndHttpResponse`;
 * - reply: REQUEST → HttpRequest (continue/modify) or HttpResponse (abort);
 *   RESPONSE → HttpResponse; the reply must echo `WebSocketCorrelationId`.
 */
class BreakpointProtocolTest {

    private fun bodyOf(request: HttpRequest): String {
        val publisher = request.bodyPublisher().orElseThrow()
        val sb = StringBuilder()
        publisher.subscribe(object : java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
            override fun onSubscribe(s: java.util.concurrent.Flow.Subscription) { s.request(Long.MAX_VALUE) }
            override fun onNext(item: java.nio.ByteBuffer) { sb.append(java.nio.charset.StandardCharsets.UTF_8.decode(item)) }
            override fun onError(t: Throwable) {}
            override fun onComplete() {}
        })
        return sb.toString()
    }

    /** The server wraps `value` as a JSON string; emulate that for parser tests. */
    private fun envelope(type: String, valueDocumentJson: String): String {
        val obj = com.google.gson.JsonObject()
        obj.addProperty("type", type)
        obj.addProperty("value", valueDocumentJson) // addProperty escapes it as a string
        return obj.toString()
    }

    // --- matcher registration ------------------------------------------

    @Test
    fun `register matcher PUTs httpRequest, phases, clientId`() {
        val req = BreakpointProtocol.buildRegisterMatcherRequest(
            "http://localhost:1080",
            """{ "method": "GET", "path": "/api/.*" }""",
            setOf(BreakpointProtocol.Phase.REQUEST, BreakpointProtocol.Phase.RESPONSE),
            "client-123"
        )
        assertEquals("PUT", req.method())
        assertEquals("http://localhost:1080/mockserver/breakpoint/matcher", req.uri().toString())
        val body = JsonParser.parseString(bodyOf(req)).asJsonObject
        assertEquals("client-123", body.get("clientId").asString)
        assertEquals("GET", body.getAsJsonObject("httpRequest").get("method").asString)
        val phases = body.getAsJsonArray("phases").map { it.asString }.toSet()
        assertEquals(setOf("REQUEST", "RESPONSE"), phases)
        assertFalse(body.has("skipCount"))
    }

    @Test
    fun `register matcher includes skipCount only when positive`() {
        val withSkip = BreakpointProtocol.buildRegisterMatcherRequest(
            "http://localhost:1080", """{ "path": "/a" }""", setOf(BreakpointProtocol.Phase.REQUEST), "c", 3
        )
        assertEquals(3, JsonParser.parseString(bodyOf(withSkip)).asJsonObject.get("skipCount").asInt)

        val zeroSkip = BreakpointProtocol.buildRegisterMatcherRequest(
            "http://localhost:1080", """{ "path": "/a" }""", setOf(BreakpointProtocol.Phase.REQUEST), "c", 0
        )
        assertFalse(JsonParser.parseString(bodyOf(zeroSkip)).asJsonObject.has("skipCount"))
    }

    @Test
    fun `remove and clear matcher endpoints`() {
        val remove = BreakpointProtocol.buildRemoveMatcherRequest("http://localhost:1080", "id-9")
        assertEquals("http://localhost:1080/mockserver/breakpoint/matcher/remove", remove.uri().toString())
        assertEquals("id-9", JsonParser.parseString(bodyOf(remove)).asJsonObject.get("id").asString)

        val clear = BreakpointProtocol.buildClearMatchersRequest("http://localhost:1080")
        assertEquals("http://localhost:1080/mockserver/breakpoint/matcher/clear", clear.uri().toString())
        assertEquals("PUT", clear.method())
    }

    @Test
    fun `parseRegisteredId reads the server-assigned id`() {
        assertEquals("abc", BreakpointProtocol.parseRegisteredId("""{ "id": "abc", "phases": ["REQUEST"] }"""))
        assertNull(BreakpointProtocol.parseRegisteredId("""{ "phases": ["REQUEST"] }"""))
        assertNull(BreakpointProtocol.parseRegisteredId("not json"))
    }

    // --- envelope decoding ---------------------------------------------

    @Test
    fun `decode envelope un-escapes the value string`() {
        val frame = envelope(BreakpointProtocol.TYPE_HTTP_REQUEST, """{ "method": "GET", "path": "/x" }""")
        val decoded = BreakpointProtocol.decodeEnvelope(frame)
        assertEquals(BreakpointProtocol.TYPE_HTTP_REQUEST, decoded.type)
        // value must be the raw inner document, not a quoted/escaped string.
        assertEquals("GET", JsonParser.parseString(decoded.value!!).asJsonObject.get("method").asString)
    }

    @Test
    fun `client id envelope is recognised and parsed`() {
        val frame = envelope(BreakpointProtocol.TYPE_CLIENT_ID, """{ "clientId": "assigned-1" }""")
        val decoded = BreakpointProtocol.decodeEnvelope(frame)
        assertTrue(BreakpointProtocol.isClientIdEnvelope(decoded.type))
        assertEquals("assigned-1", BreakpointProtocol.parseClientId(decoded.value))
    }

    // --- paused exchange parsing ---------------------------------------

    @Test
    fun `parse REQUEST phase exchange lifts headers`() {
        val value = """
            {
              "method": "POST",
              "path": "/api/order",
              "headers": {
                "WebSocketCorrelationId": ["corr-1"],
                "X-MockServer-BreakpointId": ["bp-7"],
                "X-MockServer-RequestTimestamp": ["1700000000000"]
              }
            }
        """.trimIndent()
        val exchange = BreakpointProtocol.parsePausedExchange(
            BreakpointProtocol.decodeEnvelope(envelope(BreakpointProtocol.TYPE_HTTP_REQUEST, value))
        )
        assertNotNull(exchange)
        assertEquals(BreakpointProtocol.Phase.REQUEST, exchange.phase)
        assertEquals("corr-1", exchange.correlationId)
        assertEquals("bp-7", exchange.breakpointId)
        assertEquals(1700000000000L, exchange.requestTimestamp)
        assertEquals("POST", exchange.method)
        assertEquals("/api/order", exchange.path)
        assertNull(exchange.responseJson)
    }

    @Test
    fun `parse RESPONSE phase exchange reads request and response`() {
        val value = """
            {
              "httpRequest": { "method": "GET", "path": "/x", "headers": { "WebSocketCorrelationId": ["corr-2"] } },
              "httpResponse": { "statusCode": 200, "body": "ok" }
            }
        """.trimIndent()
        val exchange = BreakpointProtocol.parsePausedExchange(
            BreakpointProtocol.decodeEnvelope(envelope(BreakpointProtocol.TYPE_HTTP_REQUEST_AND_RESPONSE, value))
        )
        assertNotNull(exchange)
        assertEquals(BreakpointProtocol.Phase.RESPONSE, exchange.phase)
        assertEquals("corr-2", exchange.correlationId)
        assertEquals("GET", exchange.method)
        assertNotNull(exchange.responseJson)
        assertTrue(exchange.responseJson!!.contains("200"))
    }

    @Test
    fun `unrecognised envelope type yields no exchange`() {
        val decoded = BreakpointProtocol.decodeEnvelope(envelope("com.example.Other", """{ "a": 1 }"""))
        assertNull(BreakpointProtocol.parsePausedExchange(decoded))
    }

    // --- decision replies ----------------------------------------------

    private fun requestExchange(correlationId: String? = "corr-1") = BreakpointProtocol.PausedExchange(
        phase = BreakpointProtocol.Phase.REQUEST,
        correlationId = correlationId,
        breakpointId = "bp-1",
        requestTimestamp = 1L,
        method = "GET",
        path = "/x",
        requestJson = """{ "method": "GET", "path": "/x", "headers": { "WebSocketCorrelationId": ["corr-1"] } }""",
        responseJson = null,
    )

    private fun responseExchange() = BreakpointProtocol.PausedExchange(
        phase = BreakpointProtocol.Phase.RESPONSE,
        correlationId = "corr-2",
        breakpointId = "bp-2",
        requestTimestamp = 1L,
        method = "GET",
        path = "/x",
        requestJson = """{ "method": "GET", "path": "/x" }""",
        responseJson = """{ "statusCode": 200, "body": "ok" }""",
    )

    /** Decode a reply envelope back into (type, valueDocument). */
    private fun replyDoc(reply: String): Pair<String, com.google.gson.JsonObject> {
        val env = BreakpointProtocol.decodeEnvelope(reply)
        return env.type!! to JsonParser.parseString(env.value!!).asJsonObject
    }

    @Test
    fun `REQUEST continue replies with an HttpRequest carrying the correlation id`() {
        val (type, doc) = replyDoc(BreakpointProtocol.buildContinueRequestReply(requestExchange()))
        assertEquals(BreakpointProtocol.TYPE_HTTP_REQUEST, type)
        val corr = doc.getAsJsonObject("headers").getAsJsonArray("WebSocketCorrelationId").get(0).asString
        assertEquals("corr-1", corr)
    }

    @Test
    fun `REQUEST modify replies with the edited HttpRequest and re-applies the correlation id`() {
        val edited = """{ "method": "PUT", "path": "/changed" }"""
        val (type, doc) = replyDoc(BreakpointProtocol.buildModifyRequestReply(requestExchange(), edited))
        assertEquals(BreakpointProtocol.TYPE_HTTP_REQUEST, type)
        assertEquals("PUT", doc.get("method").asString)
        // The user removed headers while editing — correlation id must be re-applied.
        assertEquals("corr-1", doc.getAsJsonObject("headers").getAsJsonArray("WebSocketCorrelationId").get(0).asString)
    }

    @Test
    fun `modify reply forces the original correlation id even if the user edited it`() {
        // The user accidentally changed the routing header while editing.
        val edited = """{ "method": "PUT", "path": "/x", "headers": { "WebSocketCorrelationId": ["WRONG"] } }"""
        val (_, doc) = replyDoc(BreakpointProtocol.buildModifyRequestReply(requestExchange("corr-1"), edited))
        val values = doc.getAsJsonObject("headers").getAsJsonArray("WebSocketCorrelationId")
        // Exactly one value, and it is the ORIGINAL — not the user's "WRONG".
        assertEquals(1, values.size())
        assertEquals("corr-1", values.get(0).asString)
    }

    @Test
    fun `REQUEST abort replies with an HttpResponse (default 503)`() {
        val (type, doc) = replyDoc(BreakpointProtocol.buildAbortReply(requestExchange()))
        assertEquals(BreakpointProtocol.TYPE_HTTP_RESPONSE, type)
        assertEquals(503, doc.get("statusCode").asInt)
        assertEquals("corr-1", doc.getAsJsonObject("headers").getAsJsonArray("WebSocketCorrelationId").get(0).asString)
    }

    @Test
    fun `RESPONSE continue replies with the original HttpResponse`() {
        val (type, doc) = replyDoc(BreakpointProtocol.buildContinueResponseReply(responseExchange()))
        assertEquals(BreakpointProtocol.TYPE_HTTP_RESPONSE, type)
        assertEquals(200, doc.get("statusCode").asInt)
        assertEquals("corr-2", doc.getAsJsonObject("headers").getAsJsonArray("WebSocketCorrelationId").get(0).asString)
    }

    @Test
    fun `RESPONSE modify replies with the edited HttpResponse`() {
        val edited = """{ "statusCode": 418, "body": "teapot" }"""
        val (type, doc) = replyDoc(BreakpointProtocol.buildModifyResponseReply(responseExchange(), edited))
        assertEquals(BreakpointProtocol.TYPE_HTTP_RESPONSE, type)
        assertEquals(418, doc.get("statusCode").asInt)
        assertEquals("corr-2", doc.getAsJsonObject("headers").getAsJsonArray("WebSocketCorrelationId").get(0).asString)
    }

    @Test
    fun `ABORT is rejected on a RESPONSE-phase exchange`() {
        assertThrows<IllegalArgumentException> { BreakpointProtocol.buildAbortReply(responseExchange()) }
    }

    @Test
    fun `request-phase replies are rejected on a response-phase exchange and vice versa`() {
        assertThrows<IllegalArgumentException> { BreakpointProtocol.buildContinueRequestReply(responseExchange()) }
        assertThrows<IllegalArgumentException> { BreakpointProtocol.buildContinueResponseReply(requestExchange()) }
    }

    @Test
    fun `modify reply rejects non-object edited text`() {
        assertThrows<IllegalArgumentException> { BreakpointProtocol.buildModifyRequestReply(requestExchange(), "not json") }
        assertThrows<IllegalArgumentException> { BreakpointProtocol.buildModifyResponseReply(responseExchange(), "[1,2,3]") }
    }
}
