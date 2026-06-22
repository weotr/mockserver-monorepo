// Pure, `vscode`-free best-effort detection of MockServer usage in source code,
// for the Phase 4 "test/code-aware" gutter affordances. This is deliberately a
// regex scan, NOT a full AST parse: it finds the line a developer would want a
// run/debug gutter icon on (a `new MockServerClient(...)`, a JUnit 5
// `@MockServerSettings`, or a Testcontainers `MockServerContainer`). False
// positives are harmless (an extra CodeLens); false negatives just mean no lens.

/** A detected MockServer usage site in a source file. */
export interface CodeAwareHit {
    /** Zero-based line index of the usage. */
    line: number;
    /** The kind of usage detected. */
    kind: "client" | "junit5" | "testcontainers";
    /** A short human-readable label for the gutter/CodeLens title. */
    label: string;
}

// Each pattern is anchored to a distinctive token so a comment merely mentioning
// "MockServerClient" in prose is far less likely to trip it (we require the
// constructor parens / annotation / generic-or-constructor form).
const PATTERNS: Array<{ re: RegExp; kind: CodeAwareHit["kind"]; label: string }> = [
    {
        // new MockServerClient(...)  — Java/Kotlin/JS client construction
        re: /\bnew\s+MockServerClient\s*\(/,
        kind: "client",
        label: "MockServerClient — open dashboard / inspect",
    },
    {
        // @MockServerSettings — JUnit 5 extension annotation
        re: /@MockServerSettings\b/,
        kind: "junit5",
        label: "@MockServerSettings — MockServer JUnit 5 test",
    },
    {
        // new MockServerContainer(...) OR a field/var typed MockServerContainer
        re: /\bMockServerContainer\s*[(<]|:\s*MockServerContainer\b|\bMockServerContainer\s+\w+\s*=/,
        kind: "testcontainers",
        label: "Testcontainers MockServerContainer — inspect instance",
    },
];

/**
 * Scan source text for MockServer usage sites, returning at most one hit per line
 * (the first pattern that matches that line wins). Lines are split on `\n`; the
 * returned `line` is a zero-based index. Pure and `vscode`-free.
 *
 * The scan is intentionally line-oriented and cheap so it can run on every open
 * Java/Kotlin/JS/TS document without parsing.
 */
export function detectMockServerUsage(text: string): CodeAwareHit[] {
    const hits: CodeAwareHit[] = [];
    const lines = text.split("\n");
    for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        for (const pattern of PATTERNS) {
            if (pattern.re.test(line)) {
                hits.push({ line: i, kind: pattern.kind, label: pattern.label });
                break; // one hit per line
            }
        }
    }
    return hits;
}

/** File extensions the code-aware scan applies to (where MockServer clients live). */
export const CODE_AWARE_EXTENSIONS = [".java", ".kt", ".kts", ".js", ".ts", ".groovy", ".scala"];

/** True when a file path has an extension the code-aware scan should run on. */
export function isCodeAwareFile(fsPath: string): boolean {
    const lower = fsPath.toLowerCase();
    return CODE_AWARE_EXTENSIONS.some((ext) => lower.endsWith(ext));
}
