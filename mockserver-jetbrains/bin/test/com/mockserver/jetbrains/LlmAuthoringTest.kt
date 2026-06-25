package com.mockserver.jetbrains

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the LLM authoring aids (JB1): the pure completion catalogue
 * [LlmCompletion], the [LlmExpectationBuilder] form-to-expectation builder, the
 * [AgentCallGraph] parse/Mermaid/MCP helpers, and the [MermaidRenderer] HTML escaping.
 * All assertions are IDE-free.
 */
class LlmAuthoringTest {

    // --- LlmCompletion --------------------------------------------------

    @Test
    fun `inside detection follows the httpLlmResponse brace depth`() {
        assertFalse(LlmCompletion.isInsideLlmResponse("""{ "httpRequest": { "path": "/x" """))
        assertTrue(LlmCompletion.isInsideLlmResponse("""{ "httpLlmResponse": { "provider": """))
        // Closed block: cursor is no longer inside.
        assertFalse(LlmCompletion.isInsideLlmResponse("""{ "httpLlmResponse": { "provider": "OPEN_AI" } } """))
    }

    @Test
    fun `provider suggestions fire right after a provider key`() {
        val providers = LlmCompletion.suggestions("""{ "httpLlmResponse": { "provider": "O""").map { it.insertText }
        assertTrue(providers.contains("OPEN_AI"))
        assertTrue(providers.contains("ANTHROPIC"))
    }

    @Test
    fun `model suggestions fire right after a model key`() {
        val models = LlmCompletion.suggestions("""{ "httpLlmResponse": { "model": "gpt""").map { it.insertText }
        assertTrue(models.contains("gpt-4o"))
    }

    @Test
    fun `field suggestions are the default`() {
        val fields = LlmCompletion.suggestions("""{ "httpLlmResponse": { """).map { it.insertText }
        assertTrue(fields.contains("provider"))
        assertTrue(fields.contains("completion"))
        assertTrue(fields.contains("usage"))
    }

    // --- LlmExpectationBuilder ------------------------------------------

    @Test
    fun `builds an httpLlmResponse expectation with usage and stream`() {
        val json = LlmExpectationBuilder.build(
            LlmExpectationBuilder.Form(
                path = "/v1/chat/completions",
                method = "POST",
                provider = "OPEN_AI",
                model = "gpt-4o",
                completion = "Hello!",
                stream = true,
                promptTokens = 10,
                completionTokens = 5,
                finishReason = "stop",
            )
        )
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals("POST", obj.getAsJsonObject("httpRequest").get("method").asString)
        assertEquals("/v1/chat/completions", obj.getAsJsonObject("httpRequest").get("path").asString)
        val llm = obj.getAsJsonObject("httpLlmResponse")
        assertEquals("OPEN_AI", llm.get("provider").asString)
        assertEquals("gpt-4o", llm.get("model").asString)
        assertEquals("Hello!", llm.get("completion").asString)
        assertTrue(llm.get("stream").asBoolean)
        assertEquals(10, llm.getAsJsonObject("usage").get("promptTokens").asInt)
        assertEquals("stop", llm.get("finishReason").asString)
    }

    @Test
    fun `omits optional fields and requires path provider completion`() {
        val json = LlmExpectationBuilder.build(
            LlmExpectationBuilder.Form(
                path = "/x", method = "", provider = "ANTHROPIC", model = "", completion = "hi"
            )
        )
        val obj = JsonParser.parseString(json).asJsonObject
        assertFalse(obj.getAsJsonObject("httpRequest").has("method"))
        val llm = obj.getAsJsonObject("httpLlmResponse")
        assertFalse(llm.has("model"))
        assertFalse(llm.has("usage"))
        assertFalse(llm.has("stream"))

        assertThrows<IllegalArgumentException> {
            LlmExpectationBuilder.build(LlmExpectationBuilder.Form("", null, "OPEN_AI", null, "x"))
        }
        assertThrows<IllegalArgumentException> {
            LlmExpectationBuilder.build(LlmExpectationBuilder.Form("/x", null, "", null, "x"))
        }
        assertThrows<IllegalArgumentException> {
            LlmExpectationBuilder.build(LlmExpectationBuilder.Form("/x", null, "OPEN_AI", null, ""))
        }
    }

    // --- AgentCallGraph -------------------------------------------------

    @Test
    fun `parse and render a call graph as mermaid`() {
        val raw = JsonParser.parseString(
            """
            {
              "nodes": [
                { "id": "n1", "kind": "USER", "label": "ask weather" },
                { "id": "n2", "kind": "TOOL_CALL", "label": "get_weather" }
              ],
              "edges": [ { "from": "n1", "to": "n2", "kind": "INVOKES" } ]
            }
            """.trimIndent()
        )
        val graph = AgentCallGraph.parseCallGraph(raw)!!
        assertEquals(2, graph.nodes.size)
        val mermaid = AgentCallGraph.toMermaid(graph)
        assertTrue(mermaid.startsWith("flowchart TD"))
        assertTrue(mermaid.contains("""n1["USER: ask weather"]"""))
        assertTrue(mermaid.contains("n2([get_weather])"))
        assertTrue(mermaid.contains("n1 -->|INVOKES| n2"))
    }

    @Test
    fun `parseMcpToolResult unwraps the content text json`() {
        val body = """
            { "jsonrpc": "2.0", "id": 1, "result": {
                "content": [ { "type": "text", "text": "{\"callGraph\": {\"nodes\": [], \"edges\": []}}" } ]
            } }
        """.trimIndent()
        val result = AgentCallGraph.parseMcpToolResult(body)!!
        assertTrue(result.has("callGraph"))
        val graph = AgentCallGraph.parseCallGraph(result.get("callGraph"))!!
        assertEquals(0, graph.nodes.size)
    }

    @Test
    fun `parseMcpToolResult returns null on an error envelope`() {
        assertEquals(null, AgentCallGraph.parseMcpToolResult("""{ "jsonrpc": "2.0", "error": { "code": -1 } }"""))
        assertEquals(null, AgentCallGraph.parseMcpToolResult("not json"))
    }

    @Test
    fun `mcp request bodies carry the explain_agent_run tool and arguments`() {
        val init = JsonParser.parseString(AgentCallGraph.buildInitializeBody()).asJsonObject
        assertEquals("initialize", init.get("method").asString)
        val call = JsonParser.parseString(
            AgentCallGraph.buildExplainAgentRunBody(com.google.gson.JsonObject().apply { addProperty("sessionId", "s-1") })
        ).asJsonObject
        assertEquals("tools/call", call.get("method").asString)
        val params = call.getAsJsonObject("params")
        assertEquals("explain_agent_run", params.get("name").asString)
        assertEquals("s-1", params.getAsJsonObject("arguments").get("sessionId").asString)
    }

    // --- MermaidRenderer ------------------------------------------------

    @Test
    fun `mermaid html escapes the source into a js string and blocks script breakout`() {
        val html = MermaidRenderer.toHtml("""flowchart TD
  n1["a</script><b>"]""")
        // The raw closing tag must NOT appear verbatim — it is unicode-escaped.
        assertFalse(html.contains("</script><b>"))
        assertTrue(html.contains("\\u003C") || html.contains("\\u003c"))
        assertTrue(html.contains("mermaid"))
    }

    @Test
    fun `escapeForJsString escapes quotes newlines and angle brackets`() {
        val escaped = MermaidRenderer.escapeForJsString("a\"b\nc<d>")
        // < and > are unicode-escaped so the source can't break out of a script tag.
        assertEquals("a\\\"b\\nc\\u003Cd\\u003E", escaped)
    }
}
