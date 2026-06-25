package com.mockserver.jetbrains

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Pure (and minimally I/O) support for the agent-run call graph — the JetBrains
 * parity of the VS Code extension's `callGraph.ts`. The graph is the SAME one the
 * dashboard draws; it is fetched through the MCP `explain_agent_run` tool over
 * `POST /mockserver/mcp` (the call graph is only exposed via that tool, not a plain
 * REST endpoint), then rendered as a Mermaid `flowchart TD` string.
 *
 * Everything except the final [fetchCallGraph] is pure and unit-testable: envelope
 * parsing, `tools/call` result unwrapping, graph parsing, and Mermaid rendering.
 */
object AgentCallGraph {

    private val COMPACT: Gson = Gson()

    private const val MCP_PROTOCOL_VERSION = "2025-03-26"
    private const val SESSION_ID_HEADER = "Mcp-Session-Id"
    private const val TOOL_CALL = "TOOL_CALL"

    /** A node in the agent-run call graph. [kind] is USER/ASSISTANT/SYSTEM/TOOL/TOOL_CALL. */
    data class Node(val id: String, val kind: String, val label: String)

    /** An edge in the agent-run call graph. [kind] is NEXT/INVOKES/RESULT. */
    data class Edge(val from: String, val to: String, val kind: String)

    /** The parsed call graph: its [nodes] and [edges]. */
    data class CallGraph(val nodes: List<Node>, val edges: List<Edge>)

    /** Parse a `callGraph` object (e.g. from an explain_agent_run result) defensively. */
    fun parseCallGraph(raw: JsonElement?): CallGraph? {
        if (raw == null || !raw.isJsonObject) return null
        val obj = raw.asJsonObject
        val rawNodes = if (obj.has("nodes") && obj.get("nodes").isJsonArray) obj.getAsJsonArray("nodes") else JsonArray()
        val rawEdges = if (obj.has("edges") && obj.get("edges").isJsonArray) obj.getAsJsonArray("edges") else JsonArray()
        val nodes = rawNodes.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val n = element.asJsonObject
            val id = stringOrNull(n, "id") ?: return@mapNotNull null
            Node(id, stringOrNull(n, "kind") ?: "UNKNOWN", stringOrNull(n, "label") ?: "")
        }
        val edges = rawEdges.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val e = element.asJsonObject
            val from = stringOrNull(e, "from") ?: return@mapNotNull null
            val to = stringOrNull(e, "to") ?: return@mapNotNull null
            Edge(from, to, stringOrNull(e, "kind") ?: "NEXT")
        }
        return CallGraph(nodes, edges)
    }

    /**
     * Render the graph as a Mermaid `flowchart TD` string (matches the dashboard and
     * the VS Code extension). TOOL_CALL nodes use the stadium `([ ])` shape; all
     * others use the rectangle `[" "]` shape with a `KIND: label` text. Labels are
     * escaped for the GitHub/Mermaid renderer (no quotes, collapsed whitespace,
     * truncated) — and must never contain HTML.
     */
    fun toMermaid(graph: CallGraph): String {
        val lines = ArrayList<String>()
        lines.add("flowchart TD")
        for (node in graph.nodes) {
            if (node.kind == TOOL_CALL) {
                lines.add("  ${node.id}([${escape(node.label)}])")
            } else {
                lines.add("  ${node.id}[\"${escape(node.kind + ": " + node.label)}\"]")
            }
        }
        for (edge in graph.edges) {
            lines.add("  ${edge.from} -->|${edge.kind}| ${edge.to}")
        }
        return lines.joinToString("\n")
    }

    /** Mermaid-safe label: no quotes, collapsed whitespace, trimmed, max 60 chars. */
    private fun escape(label: String): String =
        label.replace("\"", "'").replace(Regex("\\s+"), " ").trim().take(60)

    /**
     * Parse a JSON-RPC `tools/call` response [body] into the tool's result object. The
     * MCP transport wraps the result in `result.content[0].text` as JSON. Falls back to
     * the raw `result` object. Returns `null` on an error envelope. Pure.
     */
    fun parseMcpToolResult(body: String): JsonObject? {
        val parsed = try {
            JsonParser.parseString(body)
        } catch (_: Exception) {
            return null
        }
        if (!parsed.isJsonObject) return null
        val root = parsed.asJsonObject
        if (root.has("error") && !root.get("error").isJsonNull) return null
        val rpcResult = if (root.has("result") && root.get("result").isJsonObject) root.getAsJsonObject("result") else return null
        if (rpcResult.has("content") && rpcResult.get("content").isJsonArray) {
            val content = rpcResult.getAsJsonArray("content")
            if (content.size() > 0 && content.get(0).isJsonObject) {
                val first = content.get(0).asJsonObject
                val text = stringOrNull(first, "text")
                if (text != null) {
                    return try {
                        val inner = JsonParser.parseString(text)
                        if (inner.isJsonObject) inner.asJsonObject else JsonObject().apply { addProperty("text", text) }
                    } catch (_: Exception) {
                        JsonObject().apply { addProperty("text", text) }
                    }
                }
            }
        }
        return rpcResult
    }

    /** Build the MCP `initialize` request body (handshake step 1). Pure. */
    fun buildInitializeBody(): String {
        val params = JsonObject().apply {
            addProperty("protocolVersion", MCP_PROTOCOL_VERSION)
            add("capabilities", JsonObject())
            add("clientInfo", JsonObject().apply {
                addProperty("name", "mockserver-jetbrains")
                addProperty("version", "1")
            })
        }
        return COMPACT.toJson(JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", System.currentTimeMillis())
            addProperty("method", "initialize")
            add("params", params)
        })
    }

    /** Build the MCP `notifications/initialized` notification body (handshake step 2). Pure. */
    fun buildInitializedNotificationBody(): String =
        COMPACT.toJson(JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", "notifications/initialized")
        })

    /**
     * Build the MCP `tools/call` body for `explain_agent_run` with [arguments] (e.g.
     * `{ sessionId }`, or empty for the latest run). Pure.
     */
    fun buildExplainAgentRunBody(arguments: JsonObject): String {
        val params = JsonObject().apply {
            addProperty("name", "explain_agent_run")
            add("arguments", arguments)
        }
        return COMPACT.toJson(JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", System.currentTimeMillis())
            addProperty("method", "tools/call")
            add("params", params)
        })
    }

    private fun stringOrNull(obj: JsonObject, field: String): String? {
        if (!obj.has(field) || !obj.get(field).isJsonPrimitive) return null
        val primitive = obj.get(field).asJsonPrimitive
        return if (primitive.isString) primitive.asString else null
    }

    // ---------------------------------------------------------------------
    // I/O — must run off the EDT. Runs the MCP initialize → tools/call flow.
    // ---------------------------------------------------------------------

    private fun newClient(): HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    /**
     * Fetch the agent-run call graph via the MCP `explain_agent_run` tool against
     * [baseUrl] (`http://localhost:<port>`). [arguments] are the tool arguments. Returns
     * the parsed [CallGraph], or `null` when the server returns no `callGraph`. Throws
     * (with a clear message) when the MCP transport itself fails.
     */
    fun fetchCallGraph(baseUrl: String, arguments: JsonObject): CallGraph? {
        val client = newClient()
        val mcpUri = URI.create("$baseUrl/mockserver/mcp")

        // 1. initialize — capture the Mcp-Session-Id response header.
        val initResponse = client.send(
            HttpRequest.newBuilder()
                .uri(mcpUri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildInitializeBody()))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        if (initResponse.statusCode() !in 200..299) {
            throw RuntimeException("MCP initialize failed: HTTP ${initResponse.statusCode()}")
        }
        val sessionId = initResponse.headers().firstValue(SESSION_ID_HEADER).orElse(null)
            ?: throw RuntimeException("MCP initialize returned no $SESSION_ID_HEADER header")

        // 2. notifications/initialized.
        client.send(
            HttpRequest.newBuilder()
                .uri(mcpUri)
                .header("Content-Type", "application/json")
                .header(SESSION_ID_HEADER, sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(buildInitializedNotificationBody()))
                .build(),
            HttpResponse.BodyHandlers.discarding()
        )

        // 3. tools/call explain_agent_run.
        val callResponse = client.send(
            HttpRequest.newBuilder()
                .uri(mcpUri)
                .header("Content-Type", "application/json")
                .header(SESSION_ID_HEADER, sessionId)
                .POST(HttpRequest.BodyPublishers.ofString(buildExplainAgentRunBody(arguments)))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        if (callResponse.statusCode() !in 200..299) {
            throw RuntimeException("MockServer returned ${callResponse.statusCode()}: ${callResponse.body()}")
        }
        val result = parseMcpToolResult(callResponse.body() ?: "") ?: return null
        val callGraph = if (result.has("callGraph")) result.get("callGraph") else return null
        return parseCallGraph(callGraph)
    }
}
