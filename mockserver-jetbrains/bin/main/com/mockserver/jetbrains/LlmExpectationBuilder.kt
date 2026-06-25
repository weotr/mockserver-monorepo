package com.mockserver.jetbrains

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Pure builder that turns the LLM tool-window form fields into a MockServer
 * expectation JSON document with an `httpLlmResponse` action — the JetBrains parity
 * of the VS Code LLM-authoring aids. Kept IDE-free so it is unit-testable.
 *
 * The produced expectation matches on `path` (and an optional `method`) and responds
 * with an `httpLlmResponse` carrying the provider/model/completion the user typed.
 * Optional token usage and streaming are included only when set.
 */
object LlmExpectationBuilder {

    private val PRETTY: Gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()

    /** The form inputs for an LLM expectation. [completion] and [provider] are required upstream. */
    data class Form(
        val path: String,
        val method: String?,
        val provider: String,
        val model: String?,
        val completion: String,
        val stream: Boolean = false,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val finishReason: String? = null,
    )

    /**
     * Build the pretty-printed expectation JSON for [form]. Throws
     * [IllegalArgumentException] when a required field (path, provider, completion)
     * is blank.
     */
    fun build(form: Form): String {
        require(form.path.isNotBlank()) { "Path is required." }
        require(form.provider.isNotBlank()) { "Provider is required." }
        require(form.completion.isNotBlank()) { "Completion text is required." }

        val httpRequest = JsonObject().apply {
            form.method?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("method", it) }
            addProperty("path", form.path.trim())
        }

        val httpLlmResponse = JsonObject().apply {
            addProperty("provider", form.provider.trim())
            form.model?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("model", it) }
            addProperty("completion", form.completion)
            if (form.stream) addProperty("stream", true)
            form.finishReason?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("finishReason", it) }
            if (form.promptTokens != null || form.completionTokens != null) {
                add("usage", JsonObject().apply {
                    form.promptTokens?.let { addProperty("promptTokens", it) }
                    form.completionTokens?.let { addProperty("completionTokens", it) }
                })
            }
        }

        val expectation = JsonObject().apply {
            add("httpRequest", httpRequest)
            add("httpLlmResponse", httpLlmResponse)
        }
        return PRETTY.toJson(expectation)
    }

    /**
     * Wrap one or more `httpRequest` definitions from an expectation file into the
     * JSON body MockServer's `PUT /mockserver/expectation` accepts (the document is
     * sent as-is). Exposed for symmetry/testing; the action sends the built text.
     */
    fun asExpectationArray(expectationJson: String): String {
        val parsed = JsonParser.parseString(expectationJson)
        return if (parsed.isJsonArray) PRETTY.toJson(parsed) else PRETTY.toJson(JsonArray().apply { add(parsed) })
    }
}
