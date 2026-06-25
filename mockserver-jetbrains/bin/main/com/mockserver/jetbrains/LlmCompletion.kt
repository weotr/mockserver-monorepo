package com.mockserver.jetbrains

/**
 * Pure, IDE-free LLM authoring completion for `httpLlmResponse` blocks in a
 * `*.mockserver.json` file — a direct port of the VS Code extension's
 * `llmCompletion.ts` so both editors offer the SAME curated suggestions.
 *
 * This is deliberately a lightweight authoring aid, NOT a schema (the bundled JSON
 * Schema already validates the file). It speeds up the common case of scaffolding
 * an LLM mock response: provider names after `"provider":`, model names after
 * `"model":`, otherwise the top-level `httpLlmResponse` field names.
 *
 * Everything here is pure (no IntelliJ-platform API), so it is unit-testable on the
 * plain classpath. The thin editor wiring lives in [LlmCompletionContributor].
 */
object LlmCompletion {

    /** A completion suggestion: the text to insert and a short human label/detail. */
    data class Suggestion(val insertText: String, val label: String, val detail: String)

    /** Known LLM providers MockServer's LLM mocking supports (httpLlmResponse.provider). */
    val PROVIDERS: List<String> = listOf(
        "OPEN_AI",
        "AZURE_OPEN_AI",
        "ANTHROPIC",
        "BEDROCK",
        "GEMINI",
        "VERTEX_AI",
        "COHERE",
        "MISTRAL",
        "OLLAMA",
    )

    /** Representative model names per provider, for quick scaffolding. */
    val MODELS: List<String> = listOf(
        "gpt-4o",
        "gpt-4o-mini",
        "o1",
        "claude-3-7-sonnet",
        "claude-3-5-haiku",
        "gemini-1.5-pro",
        "gemini-2.0-flash",
        "command-r-plus",
        "mistral-large-latest",
        "llama3.3",
    )

    /** The top-level fields of an httpLlmResponse block. */
    val FIELDS: List<Suggestion> = listOf(
        Suggestion("provider", "provider", "LLM provider wire format (e.g. OPEN_AI)"),
        Suggestion("model", "model", "Model name echoed in the mocked response"),
        Suggestion("messages", "messages", "Conversation turns to base the reply on"),
        Suggestion("completion", "completion", "The assistant completion text to return"),
        Suggestion("stream", "stream", "Whether to stream the response (SSE)"),
        Suggestion("toolCalls", "toolCalls", "Tool/function calls to emit"),
        Suggestion("usage", "usage", "Token usage (promptTokens/completionTokens)"),
        Suggestion("finishReason", "finishReason", "stop / length / tool_calls"),
    )

    private val PROVIDER_TAIL = Regex(""""provider"\s*:\s*"?[A-Z_]*$""")
    private val MODEL_TAIL = Regex(""""model"\s*:\s*"?[\w.\-]*$""")

    /**
     * Decide whether the cursor is inside an `httpLlmResponse` block, given the text
     * BEFORE the cursor. Heuristic (matching `llmCompletion.ts`): from the last
     * `"httpLlmResponse"` key onward, count braces — when more `{` than `}` have been
     * seen (and at least one `{`), the cursor is still within that object's braces.
     */
    fun isInsideLlmResponse(textBeforeCursor: String): Boolean {
        val key = "\"httpLlmResponse\""
        val keyIndex = textBeforeCursor.lastIndexOf(key)
        if (keyIndex < 0) return false
        var depth = 0
        var sawOpen = false
        for (ch in textBeforeCursor.substring(keyIndex)) {
            when (ch) {
                '{' -> { depth++; sawOpen = true }
                '}' -> depth--
            }
        }
        return sawOpen && depth > 0
    }

    /**
     * Decide which suggestions to offer given the text before the cursor inside an
     * httpLlmResponse block: provider names right after a `"provider":`, model names
     * after `"model":`, otherwise the field names. Pure.
     */
    fun suggestions(textBeforeCursor: String): List<Suggestion> {
        val tail = textBeforeCursor.takeLast(80)
        if (PROVIDER_TAIL.containsMatchIn(tail)) {
            return PROVIDERS.map { Suggestion(it, it, "LLM provider") }
        }
        if (MODEL_TAIL.containsMatchIn(tail)) {
            return MODELS.map { Suggestion(it, it, "Model name") }
        }
        return FIELDS
    }
}
