package org.mockserver.matchers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the regex capture groups from a matched expectation's path pattern against the actual
 * request path, so they can be exposed to response/forward templates.
 * <p>
 * The pattern is compiled with the same flags {@link org.mockserver.model.NottableString#matches(String, boolean)}
 * uses for path matching — case-insensitive matching uses {@code DOTALL | CASE_INSENSITIVE | UNICODE_CASE}
 * and the opt-in {@code matchExactCase} mode uses {@code DOTALL} only — and is run as an anchored full match,
 * so a request that matched the path also extracts its groups (no case/DOTALL divergence). Both numbered
 * groups and Java named groups {@code (?<name>...)} are captured.
 * The returned list is 1-based aligned with {@link Matcher} group numbering: index 0 is the whole match
 * and index 1 the first capturing group, so a template author can use the indices they would naturally
 * expect from the pattern. A group that did not participate in the match yields {@code null} in the list
 * (and is omitted from the named map).
 * <p>
 * This is best-effort: a literal path with no capturing groups, a path that is not a regex, or a pattern
 * that fails to compile all yield no groups (an empty result), and never throw, so the match decision —
 * made elsewhere — is never affected by group extraction.
 *
 * @author jamesdbloom
 */
public class PathGroupExtractor {

    /**
     * Matches Java named-group declarations {@code (?<name>} so the names can be re-associated with their
     * group values. Negative-lookbehind / non-capturing constructs that start with {@code (?<} but are not
     * named groups (e.g. {@code (?<=...)} / {@code (?<!...)}) are excluded by requiring a word-character name.
     */
    private static final Pattern NAMED_GROUP_DECLARATION = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>");

    /**
     * Holds the numbered and named capture groups extracted from a path match.
     */
    public static final class PathGroups {
        private final List<String> numberedGroups;
        private final Map<String, String> namedGroups;

        private PathGroups(List<String> numberedGroups, Map<String, String> namedGroups) {
            this.numberedGroups = numberedGroups;
            this.namedGroups = namedGroups;
        }

        /**
         * @return true when the pattern produced at least one capturing group (numbered or named)
         */
        public boolean hasGroups() {
            // numberedGroups, when present, always carries index 0 (the whole match) plus at least one
            // capturing group; an empty list means there were no capturing groups to expose.
            return !numberedGroups.isEmpty();
        }

        public List<String> getNumberedGroups() {
            return numberedGroups;
        }

        public Map<String, String> getNamedGroups() {
            return namedGroups;
        }
    }

    /**
     * Runs {@code patternValue} as an anchored full-match regex against {@code actualPath} and returns its
     * capture groups. {@code caseSensitive} selects the same {@link Pattern} flags the path matcher used
     * (false — the default — uses {@code DOTALL | CASE_INSENSITIVE | UNICODE_CASE}; true — the opt-in
     * {@code matchExactCase} mode — uses {@code DOTALL} only), so extraction succeeds for exactly the
     * requests that matched. Returns a {@link PathGroups} with no groups when the pattern is null/blank,
     * has no capturing groups, does not match, or fails to compile.
     */
    public static PathGroups extract(String patternValue, String actualPath, boolean caseSensitive) {
        List<String> numberedGroups = new ArrayList<>();
        Map<String, String> namedGroups = new LinkedHashMap<>();
        if (patternValue == null || patternValue.isEmpty() || actualPath == null) {
            return new PathGroups(numberedGroups, namedGroups);
        }
        try {
            int flags = caseSensitive
                ? Pattern.DOTALL
                : Pattern.DOTALL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            Pattern pattern = Pattern.compile(patternValue, flags);
            Matcher matcher = pattern.matcher(actualPath);
            if (matcher.matches() && matcher.groupCount() > 0) {
                // index 0 is the whole match; 1..groupCount are the capturing groups, so the list index
                // lines up with java.util.regex group numbering for template authors.
                for (int i = 0; i <= matcher.groupCount(); i++) {
                    numberedGroups.add(matcher.group(i));
                }
                Matcher nameDeclaration = NAMED_GROUP_DECLARATION.matcher(patternValue);
                while (nameDeclaration.find()) {
                    String name = nameDeclaration.group(1);
                    try {
                        namedGroups.put(name, matcher.group(name));
                    } catch (IllegalArgumentException unknownName) {
                        // declared name not present in the compiled pattern's group set — skip it
                    }
                }
            }
        } catch (RuntimeException compileOrMatchFailure) {
            // an invalid pattern or a regex engine failure must never affect the response — return no groups
            return new PathGroups(new ArrayList<>(), new LinkedHashMap<>());
        }
        return new PathGroups(numberedGroups, namedGroups);
    }

    private PathGroupExtractor() {
    }
}
