package com.mockserver.jetbrains

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the stream-frame breakpoint protocol (JB3): parsing a
 * `PausedStreamFrameDTO` dispatch and building the `StreamFrameDecisionDTO` reply.
 * Mirrors the VS Code extension's breakpointProtocol.ts stream-frame contract. IDE-free.
 */
class StreamFrameProtocolTest {

    /** The server wraps `value` as a JSON string; emulate that for parser tests. */
    private fun envelope(type: String, valueDocumentJson: String): String {
        val obj = com.google.gson.JsonObject()
        obj.addProperty("type", type)
        obj.addProperty("value", valueDocumentJson)
        return obj.toString()
    }

    private fun replyDoc(reply: String): Pair<String, com.google.gson.JsonObject> {
        val env = BreakpointProtocol.decodeEnvelope(reply)
        return env.type!! to JsonParser.parseString(env.value!!).asJsonObject
    }

    @Test
    fun `stream-frame envelope is recognised and parsed`() {
        val value = """
            {
              "correlationId": "corr-9",
              "streamId": "s-1",
              "sequenceNumber": 3,
              "direction": "OUTBOUND",
              "phase": "RESPONSE_STREAM",
              "body": "aGVsbG8=",
              "requestMethod": "GET",
              "requestPath": "/sse",
              "breakpointId": "bp-3",
              "requestTimestamp": 1700000000000
            }
        """.trimIndent()
        val decoded = BreakpointProtocol.decodeEnvelope(envelope(BreakpointProtocol.TYPE_PAUSED_STREAM_FRAME, value))
        assertTrue(BreakpointProtocol.isStreamFrameEnvelope(decoded.type))
        val frame = BreakpointProtocol.parsePausedStreamFrame(decoded)
        assertNotNull(frame)
        assertEquals("corr-9", frame.correlationId)
        assertEquals("s-1", frame.streamId)
        assertEquals(3L, frame.sequenceNumber)
        assertEquals("OUTBOUND", frame.direction)
        assertEquals("RESPONSE_STREAM", frame.phase)
        assertEquals("aGVsbG8=", frame.body)
        assertEquals("GET", frame.requestMethod)
        assertEquals("/sse", frame.requestPath)
        assertEquals(1700000000000L, frame.requestTimestamp)
    }

    @Test
    fun `a frame without a correlation id is rejected`() {
        val decoded = BreakpointProtocol.decodeEnvelope(
            envelope(BreakpointProtocol.TYPE_PAUSED_STREAM_FRAME, """{ "streamId": "s", "body": "x" }""")
        )
        assertNull(BreakpointProtocol.parsePausedStreamFrame(decoded))
    }

    @Test
    fun `non-stream envelope yields no frame`() {
        val decoded = BreakpointProtocol.decodeEnvelope(
            envelope(BreakpointProtocol.TYPE_HTTP_REQUEST, """{ "method": "GET" }""")
        )
        assertFalse(BreakpointProtocol.isStreamFrameEnvelope(decoded.type))
        assertNull(BreakpointProtocol.parsePausedStreamFrame(decoded))
    }

    private fun frame() = BreakpointProtocol.PausedStreamFrame(
        correlationId = "corr-9", streamId = "s-1", sequenceNumber = 1, direction = "OUTBOUND",
        phase = "RESPONSE_STREAM", body = "aGVsbG8=", requestMethod = "GET", requestPath = "/sse",
        breakpointId = "bp-3", requestTimestamp = 1L,
    )

    @Test
    fun `CONTINUE and DROP replies carry the action and correlation id without a body`() {
        val (typeC, docC) = replyDoc(BreakpointProtocol.buildStreamFrameReply(frame(), BreakpointProtocol.StreamFrameAction.CONTINUE))
        assertEquals(BreakpointProtocol.TYPE_STREAM_FRAME_DECISION, typeC)
        assertEquals("CONTINUE", docC.get("action").asString)
        assertEquals("corr-9", docC.get("correlationId").asString)
        assertFalse(docC.has("body"))

        val (_, docD) = replyDoc(BreakpointProtocol.buildStreamFrameReply(frame(), BreakpointProtocol.StreamFrameAction.DROP))
        assertEquals("DROP", docD.get("action").asString)
        assertFalse(docD.has("body"))
    }

    @Test
    fun `MODIFY carries the replacement body`() {
        val (_, doc) = replyDoc(
            BreakpointProtocol.buildStreamFrameReply(frame(), BreakpointProtocol.StreamFrameAction.MODIFY, "Z29vZGJ5ZQ==")
        )
        assertEquals("MODIFY", doc.get("action").asString)
        assertEquals("Z29vZGJ5ZQ==", doc.get("body").asString)
    }

    @Test
    fun `MODIFY and INJECT without a body are rejected`() {
        assertThrows<IllegalArgumentException> {
            BreakpointProtocol.buildStreamFrameReply(frame(), BreakpointProtocol.StreamFrameAction.MODIFY, null)
        }
        assertThrows<IllegalArgumentException> {
            BreakpointProtocol.buildStreamFrameReply(frame(), BreakpointProtocol.StreamFrameAction.INJECT, "")
        }
    }
}
