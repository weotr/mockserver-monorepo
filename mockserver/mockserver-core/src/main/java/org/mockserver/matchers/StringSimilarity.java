package org.mockserver.matchers;

/**
 * Deterministic string-similarity metrics used by {@link FuzzyMatcher}.
 * <p>
 * Implements the Jaro and Jaro-Winkler similarity algorithms directly (no
 * external dependency) so the same pair of inputs always produces the same
 * ratio between {@code 0.0} (completely different) and {@code 1.0} (identical).
 *
 * @author jamesdbloom
 */
public final class StringSimilarity {

    private static final double WINKLER_SCALING_FACTOR = 0.1d;
    private static final int WINKLER_MAX_PREFIX = 4;

    private StringSimilarity() {
    }

    /**
     * Jaro-Winkler similarity between two strings, in the range {@code [0.0, 1.0]}.
     * <p>
     * Two {@code null} or two empty strings are considered identical ({@code 1.0});
     * comparing an empty/{@code null} string with a non-empty string yields {@code 0.0}.
     */
    public static double jaroWinkler(String first, String second) {
        String a = first == null ? "" : first;
        String b = second == null ? "" : second;

        if (a.equals(b)) {
            return 1.0d;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0d;
        }

        double jaro = jaro(a, b);

        int prefix = 0;
        int maxPrefix = Math.min(WINKLER_MAX_PREFIX, Math.min(a.length(), b.length()));
        while (prefix < maxPrefix && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }

        return jaro + (prefix * WINKLER_SCALING_FACTOR * (1.0d - jaro));
    }

    /**
     * Jaro similarity between two strings, in the range {@code [0.0, 1.0]}.
     */
    public static double jaro(String first, String second) {
        String a = first == null ? "" : first;
        String b = second == null ? "" : second;

        if (a.equals(b)) {
            return 1.0d;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0d;
        }

        int matchDistance = Math.max(a.length(), b.length()) / 2 - 1;
        if (matchDistance < 0) {
            matchDistance = 0;
        }

        boolean[] aMatches = new boolean[a.length()];
        boolean[] bMatches = new boolean[b.length()];

        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, b.length());
            for (int j = start; j < end; j++) {
                if (bMatches[j] || a.charAt(i) != b.charAt(j)) {
                    continue;
                }
                aMatches[i] = true;
                bMatches[j] = true;
                matches++;
                break;
            }
        }

        if (matches == 0) {
            return 0.0d;
        }

        // count transpositions
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < a.length(); i++) {
            if (!aMatches[i]) {
                continue;
            }
            while (!bMatches[k]) {
                k++;
            }
            if (a.charAt(i) != b.charAt(k)) {
                transpositions++;
            }
            k++;
        }

        double m = matches;
        double t = transpositions / 2.0d;
        return ((m / a.length()) + (m / b.length()) + ((m - t) / m)) / 3.0d;
    }
}
