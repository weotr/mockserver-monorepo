package org.mockserver.llm;

import org.mockserver.model.ToolUse;

import java.util.List;

/**
 * Pure, deterministic <strong>approximate</strong> token counter for LLM text.
 *
 * <p><strong>This is an estimate, not a real tokenizer.</strong> It does
 * <em>not</em> implement byte-pair encoding (BPE), SentencePiece, or any
 * provider's exact vocabulary, so its counts will differ from a provider's
 * billed token counts — typically within roughly &plusmn;20% for ordinary English
 * prose, and further off for code, non-Latin scripts, or long runs of
 * punctuation. It exists only so MockServer can populate <em>plausible</em>
 * {@code usage} numbers when a mocked completion omits them, and to back the
 * rough character-based estimates already used for token-quota accounting.
 *
 * <h2>Heuristic</h2>
 * The estimate blends two cheap signals that bracket real tokenizer behaviour:
 * <ul>
 *   <li><strong>Characters &divide; 4</strong> — the long-standing rule of thumb
 *       that ~4 characters of English text map to one token.</li>
 *   <li><strong>Words &times; 4/3</strong> — real tokenizers split common words
 *       into ~1.3 tokens on average (sub-word units, leading spaces, suffixes).</li>
 * </ul>
 * The two are averaged. Whitespace runs are collapsed for the word count and a
 * small allowance is added for punctuation density, which a BPE tokenizer tends
 * to split into separate tokens. The result is clamped to at least {@code 1} for
 * any non-empty text (every real request costs at least one token) and {@code 0}
 * for {@code null}/empty.
 *
 * <p>The method is pure and side-effect free: the same input always yields the
 * same count, so it never makes a test flaky.
 */
public final class TokenCounter {

    /** Average characters per token for the char-based signal. */
    private static final double CHARS_PER_TOKEN = 4.0;

    /** Average tokens per whitespace-delimited word for the word-based signal. */
    private static final double TOKENS_PER_WORD = 4.0 / 3.0;

    private TokenCounter() {
    }

    /**
     * Estimate the approximate token count for a single piece of text.
     *
     * @param text the text to estimate (may be {@code null})
     * @return {@code 0} for {@code null}/empty text, otherwise an approximate
     *         token count {@code >= 1}
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int chars = text.length();
        double charEstimate = chars / CHARS_PER_TOKEN;

        int words = countWords(text);
        double wordEstimate = words * TOKENS_PER_WORD;

        // Punctuation/symbols are frequently split into their own tokens by BPE
        // tokenizers, so add a small allowance proportional to their density.
        int punctuation = countPunctuation(text);
        double punctuationAllowance = punctuation * 0.5;

        double blended = (charEstimate + wordEstimate) / 2.0 + punctuationAllowance;
        return Math.max(1, (int) Math.round(blended));
    }

    /**
     * Estimate the approximate prompt (input) token count for a decoded
     * conversation: the sum of the per-message text estimates plus a small
     * fixed per-message overhead (real chat formats wrap each message in role
     * markers / delimiters that cost a few tokens). Tool-call arguments and tool
     * results carried on a message are included in its text estimate.
     *
     * @param conversation the decoded conversation (may be {@code null})
     * @return {@code 0} for a {@code null}/empty conversation, otherwise an
     *         approximate prompt token count {@code >= 1}
     */
    public static int estimatePromptTokens(ParsedConversation conversation) {
        if (conversation == null || conversation.getMessages() == null || conversation.getMessages().isEmpty()) {
            return 0;
        }
        long total = 0;
        for (ParsedMessage message : conversation.getMessages()) {
            if (message == null) {
                continue;
            }
            // ~3 tokens of per-message chat-format overhead (role marker + delimiters).
            total += 3;
            total += estimateTokens(message.getTextContent());
            List<ToolUse> toolCalls = message.getToolCalls();
            if (toolCalls != null) {
                for (ToolUse toolCall : toolCalls) {
                    if (toolCall != null) {
                        total += estimateTokens(toolCall.getName());
                        total += estimateTokens(toolCall.getArguments());
                    }
                }
            }
            if (message.getToolResults() != null) {
                for (String result : message.getToolResults().values()) {
                    total += estimateTokens(result);
                }
            }
        }
        if (total <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * Estimate the approximate completion (output) token count for the text and
     * tool-call arguments a mocked completion would return.
     *
     * @param text      the response text (may be {@code null})
     * @param toolCalls the response tool calls (may be {@code null}/empty)
     * @return an approximate completion token count ({@code 0} when there is no
     *         output at all)
     */
    public static int estimateCompletionTokens(String text, List<ToolUse> toolCalls) {
        long total = estimateTokens(text);
        if (toolCalls != null) {
            for (ToolUse toolCall : toolCalls) {
                if (toolCall != null) {
                    total += estimateTokens(toolCall.getName());
                    total += estimateTokens(toolCall.getArguments());
                }
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private static int countWords(String text) {
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                inWord = false;
            } else if (!inWord) {
                inWord = true;
                words++;
            }
        }
        return words;
    }

    private static int countPunctuation(String text) {
        int punctuation = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c) && !Character.isWhitespace(c)) {
                punctuation++;
            }
        }
        return punctuation;
    }
}
