package com.mockserver.jetbrains

/**
 * Pure, no-IDE, best-effort detection of MockServer usage in source text — the basis
 * for the Phase 4 gutter run-affordance. Deliberately regex-based (NOT a per-language
 * AST/PSI analysis): the JetBrains plugin targets Community across every JetBrains IDE
 * and language, so a language-agnostic textual probe is the only universally-available
 * option. False positives are acceptable (the gutter icon is purely an affordance — it
 * opens the dashboard/debugger), so the patterns favour recall.
 *
 * Detected markers (the integration points the roadmap lists):
 * - `new MockServerClient(...)` / `mockServerClient.when(...)` (client code);
 * - `@MockServerSettings` (JUnit 5 extension);
 * - `@MockServerTest` (Spring integration);
 * - `MockServerContainer` (Testcontainers).
 */
object MockServerCodeUsage {

    /** A recognised marker and a short human label for the gutter tooltip. */
    enum class Marker(val label: String) {
        CLIENT("MockServerClient usage"),
        SETTINGS("@MockServerSettings (JUnit 5)"),
        SPRING_TEST("@MockServerTest (Spring)"),
        TESTCONTAINERS("MockServerContainer (Testcontainers)"),
    }

    // Word-boundary-anchored so e.g. `MyMockServerClientWrapper` does not match.
    // Ordered most-specific-first (annotations and Testcontainers before the bare
    // `MockServerClient`) so e.g. `@MockServerSettings` is not misreported as CLIENT.
    private val ANNOTATION_SETTINGS = Regex("""@MockServerSettings\b""")
    private val ANNOTATION_SPRING = Regex("""@MockServerTest\b""")
    private val CONTAINER = Regex("""\bMockServerContainer\b""")
    // Catches the `MockServerClient` type AND the conventional `mockServerClient`
    // variable (e.g. `mockServerClient.when(...)`) the roadmap calls out — but NOT
    // `MockServerContainer` (handled above first) or `MyMockServerClientWrapper`
    // (the trailing \b fails before `Wrapper`).
    private val CLIENT_USAGE = Regex("""\b[Mm]ockServerClient\b""")

    private val ORDERED: List<Pair<Regex, Marker>> = listOf(
        ANNOTATION_SETTINGS to Marker.SETTINGS,
        ANNOTATION_SPRING to Marker.SPRING_TEST,
        CONTAINER to Marker.TESTCONTAINERS,
        CLIENT_USAGE to Marker.CLIENT,
    )

    /**
     * The first MockServer marker [text] matches, or null. Used by the line-marker
     * provider to decide whether to draw a gutter icon for a given source line / leaf.
     * `\bMockServerClient\b` covers both `new MockServerClient(...)` and
     * `mockServerClient.when(...)` is matched case-sensitively only when the type name
     * appears (the bare `MockServerClient` identifier).
     */
    fun detect(text: String): Marker? {
        for ((pattern, marker) in ORDERED) {
            if (pattern.containsMatchIn(text)) return marker
        }
        return null
    }

    /** True when [text] contains any MockServer marker. */
    fun matches(text: String): Boolean = detect(text) != null
}
