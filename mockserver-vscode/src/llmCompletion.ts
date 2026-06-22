// Phase 6 LLM authoring completion for `httpLlmResponse`. Provides a small,
// curated set of provider/model and field completions inside an `httpLlmResponse`
// block of a `*.mockserver.json` file. The catalogue is static (no network) and
// `vscode`-free so the suggestion logic can be unit-tested.
//
// This is deliberately a lightweight authoring aid, NOT a schema (the bundled
// JSON Schema already validates the file). It speeds up the common case of
// scaffolding an LLM mock response.

/** A completion suggestion: the text to insert and a short human label/detail. */
export interface LlmCompletion {
    insertText: string;
    label: string;
    detail: string;
}

/** Known LLM providers MockServer's LLM mocking supports (httpLlmResponse.provider). */
export const LLM_PROVIDERS = [
    "OPEN_AI",
    "AZURE_OPEN_AI",
    "ANTHROPIC",
    "BEDROCK",
    "GEMINI",
    "VERTEX_AI",
    "COHERE",
    "MISTRAL",
    "OLLAMA",
];

/** Representative model names per provider, for quick scaffolding. */
export const LLM_MODELS = [
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
];

/** The top-level fields of an httpLlmResponse block. */
export const LLM_FIELDS: LlmCompletion[] = [
    { insertText: "provider", label: "provider", detail: "LLM provider wire format (e.g. OPEN_AI)" },
    { insertText: "model", label: "model", detail: "Model name echoed in the mocked response" },
    { insertText: "messages", label: "messages", detail: "Conversation turns to base the reply on" },
    { insertText: "completion", label: "completion", detail: "The assistant completion text to return" },
    { insertText: "stream", label: "stream", detail: "Whether to stream the response (SSE)" },
    { insertText: "toolCalls", label: "toolCalls", detail: "Tool/function calls to emit" },
    { insertText: "usage", label: "usage", detail: "Token usage (promptTokens/completionTokens)" },
    { insertText: "finishReason", label: "finishReason", detail: "stop / length / tool_calls" },
];

/**
 * Decide whether the cursor is inside an `httpLlmResponse` block, given the text
 * BEFORE the cursor. Heuristic: the last `httpLlmResponse` key occurs after the
 * last top-level action key change — i.e. we are still within that object's
 * braces. We approximate "within braces" by counting unbalanced `{` after the
 * `httpLlmResponse` key. Pure and `vscode`-free.
 */
export function isInsideLlmResponse(textBeforeCursor: string): boolean {
    const key = "\"httpLlmResponse\"";
    const keyIndex = textBeforeCursor.lastIndexOf(key);
    if (keyIndex < 0) {
        return false;
    }
    // From the key onward, count braces. If we are still inside (more `{` than `}`
    // seen since the key's opening brace), the cursor is within the block.
    const after = textBeforeCursor.slice(keyIndex);
    let depth = 0;
    let sawOpen = false;
    for (const ch of after) {
        if (ch === "{") {
            depth++;
            sawOpen = true;
        } else if (ch === "}") {
            depth--;
        }
    }
    return sawOpen && depth > 0;
}

/**
 * Decide which suggestions to offer given the text before the cursor inside an
 * httpLlmResponse block: provider names right after a `"provider":`, model names
 * after `"model":`, otherwise the field names. Pure and `vscode`-free.
 */
export function llmSuggestions(textBeforeCursor: string): LlmCompletion[] {
    const tail = textBeforeCursor.slice(-80);
    if (/"provider"\s*:\s*"?[A-Z_]*$/.test(tail)) {
        return LLM_PROVIDERS.map((p) => ({ insertText: p, label: p, detail: "LLM provider" }));
    }
    if (/"model"\s*:\s*"?[\w.\-]*$/.test(tail)) {
        return LLM_MODELS.map((m) => ({ insertText: m, label: m, detail: "Model name" }));
    }
    return LLM_FIELDS;
}
