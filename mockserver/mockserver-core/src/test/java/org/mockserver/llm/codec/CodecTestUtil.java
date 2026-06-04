package org.mockserver.llm.codec;

/**
 * Shared test utilities for LLM codec round-trip tests.
 */
final class CodecTestUtil {

    private CodecTestUtil() {
        // utility class
    }

    /**
     * Minimal JSON string escaping for embedding dynamic values inside
     * hand-crafted JSON request bodies in round-trip tests.
     */
    static String escapeForJson(String value) {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\t", "\\t");
    }
}
