package org.mockserver.mock;

import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.matchers.HttpRequestPropertiesMatcher;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.NottableOptionalString;
import org.mockserver.model.NottableSchemaString;
import org.mockserver.model.NottableString;
import org.mockserver.model.Parameters;
import org.mockserver.model.RequestDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import static org.mockserver.mock.SortableExpectationId.EXPECTATION_SORTABLE_PRIORITY_COMPARATOR;

/**
 * Candidate index for expectation matching above a size threshold.
 *
 * <p><b>Purpose.</b> {@code RequestMatchers.firstMatchingExpectation} scans every
 * registered expectation in global priority/insertion order and returns the first
 * match — pure O(n). For large expectation sets (1k–5k) this dominates the
 * request-serving hot path. This index narrows the scan to a small CANDIDATE set
 * for the request without changing which expectation is returned.
 *
 * <p><b>Hard guarantee — zero behavioural change.</b> The expectation returned for
 * any request is byte-for-byte identical to the full linear scan, including match
 * ORDER (priority + insertion order, first match wins). This holds because:
 * <ol>
 *   <li>An expectation is placed in a {@code (method, path)} BUCKET only when both
 *       its method and path are PLAIN LITERAL equality matchers (see
 *       {@link #bucketKeyFor(HttpRequestMatcher, boolean)}); such an expectation can
 *       match a request ONLY when the request's literal {@code (method, path)} equals
 *       the bucket key. Any other request provably fails the method/path criteria
 *       (which are AND-ed in {@code HttpRequestPropertiesMatcher}).</li>
 *   <li>Every expectation that is NOT safely bucketable (regex/notted/blank/optional/
 *       schema/path-parameter method or path, or a non-HTTP request definition) goes
 *       in the FALLTHROUGH list, which is checked on every request.</li>
 *   <li>The candidate set for a request is {@code bucket(requestKey) ∪ fallthrough}.
 *       Any expectation outside this set is in a different literal bucket and so
 *       cannot match the request. Therefore the first match among candidates,
 *       evaluated in the SAME GLOBAL sorted order, equals the first match of the
 *       full scan.</li>
 * </ol>
 *
 * <p><b>Global-order evaluation.</b> {@link #candidatesInGlobalOrder} returns the
 * candidate matchers sorted by exactly the comparator the backing
 * {@code CircularPriorityQueue} uses ({@link SortableExpectationId#EXPECTATION_SORTABLE_PRIORITY_COMPARATOR}
 * over each matcher's {@code expectation.getSortableId()}). This reproduces the
 * global total order, so a higher-priority fallthrough expectation still wins over
 * a lower-priority bucketed one.
 *
 * <p><b>Generation-driven (lazy) maintenance.</b> The index is NOT maintained per
 * mutation site. Instead it is rebuilt lazily from the authoritative sorted snapshot
 * whenever the control plane's monotonic modification counter differs from the
 * generation the index was last built at — exactly mirroring the
 * {@code CircularPriorityQueue.sortedCache} invalidate-on-mutation / rebuild-on-read
 * pattern. This is correct for ALL mutation kinds, including update-in-place where a
 * matcher keeps its identity but its method/path (and therefore its bucket) change.
 * In steady state (no control-plane change between requests) zero rebuilds happen and
 * every request reuses the built buckets.
 *
 * <p><b>Correctness depends on every mutation site bumping the counter.</b> The lazy
 * rebuild is correct ONLY because every method that structurally changes
 * {@code httpRequestMatchers} (or a matcher's method/path) bumps the counter — a missed
 * bump on a re-bucketing update WOULD leave the matcher in its old bucket and return a
 * stale result. {@code RequestMatchersCandidateIndexGenerationTest} asserts each
 * CPQ-mutating control-plane operation increments the generation, so a future mutation
 * site that forgets the bump fails the build rather than silently regressing matching.
 *
 * <p><b>Threading contract.</b> Mirrors {@code RequestMatchers}: control-plane
 * mutations are single-writer; reads ({@link #candidatesInGlobalOrder}) run on
 * data-plane threads and are eventually consistent. The rebuild path is
 * {@code synchronized} and publishes a wholly-built snapshot through a single
 * {@code volatile} reference, so a concurrent reader sees either the whole previous
 * index or the whole new one — never a torn view.
 */
class CandidateIndex {

    private static final char METHOD_PATH_SEPARATOR = '\n';

    /**
     * Immutable, wholly-built index snapshot. Published through a single volatile
     * reference so data-plane readers never observe a partially-built index.
     */
    private static final class Snapshot {
        final Map<String, List<HttpRequestMatcher>> buckets;
        final List<HttpRequestMatcher> fallthrough;
        final long generation;
        final boolean caseInsensitive;

        Snapshot(Map<String, List<HttpRequestMatcher>> buckets, List<HttpRequestMatcher> fallthrough, long generation, boolean caseInsensitive) {
            this.buckets = buckets;
            this.fallthrough = fallthrough;
            this.generation = generation;
            this.caseInsensitive = caseInsensitive;
        }
    }

    // -1 generation forces a (re)build on first use.
    private volatile Snapshot snapshot = new Snapshot(new HashMap<>(), new ArrayList<>(), -1L, true);

    /**
     * Returns the candidate matchers for the request in GLOBAL sorted order (the same
     * order {@code CircularPriorityQueue.toSortedList()} produces), so the first match
     * among them equals the first match of the full scan.
     *
     * <p>If the index generation or case mode is stale relative to the supplied current
     * generation/case mode, the index is rebuilt from the authoritative snapshot first.
     *
     * @param requestDefinition     the incoming request
     * @param currentGeneration     the control plane's current modification counter
     * @param caseInsensitiveNow    whether case-folding applies now (matchExactCase off)
     * @param authoritativeSnapshot supplier of the full sorted snapshot, used only when a
     *                              rebuild is required (never evaluated when the index is fresh)
     */
    List<HttpRequestMatcher> candidatesInGlobalOrder(
        RequestDefinition requestDefinition,
        long currentGeneration,
        boolean caseInsensitiveNow,
        Supplier<List<HttpRequestMatcher>> authoritativeSnapshot
    ) {
        Snapshot current = snapshot;
        if (current.generation != currentGeneration || current.caseInsensitive != caseInsensitiveNow) {
            current = rebuild(authoritativeSnapshot.get(), currentGeneration, caseInsensitiveNow);
        }

        List<HttpRequestMatcher> candidates = new ArrayList<>(current.fallthrough);
        if (requestDefinition instanceof HttpRequest) {
            HttpRequest httpRequest = (HttpRequest) requestDefinition;
            // Case-insensitive bucketing folds the key with toLowerCase(ROOT), which is NOT
            // equivalent to the matcher's char-by-char equalsIgnoreCase for non-ASCII characters
            // (e.g. Turkish dotted/dotless i U+0130/U+0131, long s U+017F): a non-ASCII request can
            // equalsIgnoreCase-match a bucketed pure-ASCII literal yet fold to a different bucket key
            // (U+0130 even changes length under toLowerCase). We therefore cannot narrow a non-ASCII
            // request in case-insensitive mode without risking a SILENT MISS of a bucketed
            // expectation — fall back to the full authoritative scan for this request (byte-for-byte
            // identical to the un-indexed path). Case-sensitive mode uses exact equality with no
            // fold, so a non-ASCII request can never exact-match a pure-ASCII bucketed literal and
            // narrowing stays sound.
            if (current.caseInsensitive && requestHasNonAsciiMethodOrPath(httpRequest)) {
                return authoritativeSnapshot.get();
            }
            String key = requestKeyFor(httpRequest, current.caseInsensitive);
            if (key != null) {
                List<HttpRequestMatcher> bucket = current.buckets.get(key);
                if (bucket != null) {
                    candidates.addAll(bucket);
                }
            }
        }
        // A non-HTTP request (DNS/binary/OpenAPI) cannot match any bucketed (literal HTTP
        // method+path) expectation, so its candidate set is the fallthrough only — which
        // is correct: all such expectations are themselves in the fallthrough.

        if (candidates.size() > 1) {
            candidates.sort((a, b) -> EXPECTATION_SORTABLE_PRIORITY_COMPARATOR.compare(
                sortableId(a), sortableId(b)
            ));
        }
        return candidates;
    }

    /**
     * Rebuilds the index from the authoritative sorted snapshot and publishes it. The
     * double-check inside the lock avoids redundant concurrent rebuilds for the same
     * generation. Returns the snapshot that is in effect after the call.
     */
    private synchronized Snapshot rebuild(List<HttpRequestMatcher> authoritative, long generation, boolean caseInsensitive) {
        Snapshot current = snapshot;
        if (current.generation == generation && current.caseInsensitive == caseInsensitive) {
            // Another writer already rebuilt this exact generation.
            return current;
        }
        Map<String, List<HttpRequestMatcher>> buckets = new HashMap<>();
        List<HttpRequestMatcher> fallthrough = new ArrayList<>();
        for (HttpRequestMatcher matcher : authoritative) {
            if (matcher == null || matcher.getExpectation() == null) {
                continue;
            }
            String key = bucketKeyFor(matcher, caseInsensitive);
            if (key == null) {
                fallthrough.add(matcher);
            } else {
                buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(matcher);
            }
        }
        Snapshot rebuilt = new Snapshot(buckets, fallthrough, generation, caseInsensitive);
        this.snapshot = rebuilt;
        return rebuilt;
    }

    private static SortableExpectationId sortableId(HttpRequestMatcher matcher) {
        return matcher.getExpectation() != null
            ? matcher.getExpectation().getSortableId()
            : SortableExpectationId.NULL;
    }

    // ---- bucketable predicate ----

    /**
     * Returns the {@code (method, path)} bucket key for a matcher when — and only when
     * — the expectation matches exactly one literal {@code (method, path)} pair, or
     * {@code null} (meaning "fallthrough — checked on every request") otherwise.
     *
     * <p>EXTREMELY conservative: any doubt routes the expectation to the fallthrough.
     * An expectation is bucketable iff ALL hold:
     * <ul>
     *   <li>its request definition is a plain {@link HttpRequest} (not OpenAPI/DNS/binary);</li>
     *   <li>the matcher is an {@link HttpRequestPropertiesMatcher} (the only matcher whose
     *       method/path semantics this predicate reasons about);</li>
     *   <li>it declares NO path parameters (otherwise the matched path is rewritten to a
     *       {@code .*} regex by {@code PathParametersDecoder.normalisePathWithParametersForMatching});</li>
     *   <li>both method and path are plain literal values — non-null, not blank, not notted
     *       ({@code !}), not optional, not a schema/OpenAPI string, and containing no regex
     *       metacharacter and only ASCII characters (so the matcher's anchored-regex path is
     *       provably equivalent to a literal (case-insensitive) string equals).</li>
     * </ul>
     */
    private static String bucketKeyFor(HttpRequestMatcher matcher, boolean caseInsensitive) {
        if (!(matcher instanceof HttpRequestPropertiesMatcher)) {
            return null;
        }
        Expectation expectation = matcher.getExpectation();
        if (expectation == null) {
            return null;
        }
        RequestDefinition requestDefinition = expectation.getHttpRequest();
        if (!(requestDefinition instanceof HttpRequest)) {
            return null;
        }
        HttpRequest httpRequest = (HttpRequest) requestDefinition;

        // Path parameters rewrite the matched path into a regex — not bucketable.
        Parameters pathParameters = httpRequest.getPathParameters();
        if (pathParameters != null && !pathParameters.isEmpty()) {
            return null;
        }

        String method = literalValue(httpRequest.getMethod());
        if (method == null) {
            return null;
        }
        String path = literalValue(httpRequest.getPath());
        if (path == null) {
            return null;
        }
        return composeKey(method, path, caseInsensitive);
    }

    /**
     * Returns the plain literal string value of a method/path matcher component, or
     * {@code null} when the component is anything other than a plain literal (blank,
     * notted, optional, schema, or containing a regex metacharacter / non-ASCII char).
     */
    private static String literalValue(NottableString nottableString) {
        if (nottableString == null) {
            return null;
        }
        // Schema (OpenAPI) and optional matchers are never plain literal equality.
        if (nottableString instanceof NottableSchemaString || nottableString instanceof NottableOptionalString) {
            return null;
        }
        if (nottableString.isOptional() || nottableString.isNot()) {
            return null;
        }
        if (nottableString.isBlank()) {
            // A blank matcher matches ANY value — must be checked on every request.
            return null;
        }
        String value = nottableString.getValue();
        if (value == null) {
            return null;
        }
        // Must be a pure-ASCII literal so the matcher's anchored-regex comparison is
        // provably equivalent to a (case-insensitive) literal equals. Mirrors the
        // RegexStringMatcher pure-ASCII-literal short-circuit.
        if (!isPureAsciiLiteral(value)) {
            return null;
        }
        return value;
    }

    private static String requestKeyFor(HttpRequest request, boolean caseInsensitive) {
        NottableString method = request.getMethod();
        NottableString path = request.getPath();
        if (method == null || path == null) {
            return null;
        }
        String methodValue = method.getValue();
        String pathValue = path.getValue();
        if (methodValue == null || pathValue == null) {
            return null;
        }
        return composeKey(methodValue, pathValue, caseInsensitive);
    }

    /**
     * True when the request's method or path value contains a non-ASCII character. Used to force
     * the full-scan fallback in case-insensitive mode, where toLowerCase(ROOT) bucket-key folding
     * diverges from the matcher's char-by-char equalsIgnoreCase (see {@link #candidatesInGlobalOrder}).
     */
    private static boolean requestHasNonAsciiMethodOrPath(HttpRequest request) {
        NottableString method = request.getMethod();
        NottableString path = request.getPath();
        return (method != null && !isPureAscii(method.getValue()))
            || (path != null && !isPureAscii(path.getValue()));
    }

    private static String composeKey(String method, String path, boolean caseInsensitive) {
        if (caseInsensitive) {
            method = method.toLowerCase(Locale.ROOT);
            path = path.toLowerCase(Locale.ROOT);
        }
        return method + METHOD_PATH_SEPARATOR + path;
    }

    // ---- pure-ASCII-literal test (mirrors RegexStringMatcher) ----

    private static boolean isPureAsciiLiteral(String s) {
        return !looksLikeRegex(s) && isPureAscii(s);
    }

    private static boolean looksLikeRegex(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            switch (s.charAt(i)) {
                case '\\':
                case '.':
                case '[':
                case ']':
                case '{':
                case '}':
                case '(':
                case ')':
                case '*':
                case '+':
                case '?':
                case '^':
                case '$':
                case '|':
                    return true;
                default:
                    // continue scanning
            }
        }
        return false;
    }

    private static boolean isPureAscii(String s) {
        if (s == null) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }
}
