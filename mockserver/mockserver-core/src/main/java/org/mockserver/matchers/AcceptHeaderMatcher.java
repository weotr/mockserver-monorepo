package org.mockserver.matchers;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HTTP content-negotiation matcher for the {@code Accept} request header (RFC 7231 §5.3.2).
 *
 * <p>This is an <em>opt-in</em> matcher value form for key/value header matching. When the
 * expected (matcher) header value is written as {@code accept:<media-type>} — for example
 * {@code accept:application/json} — it matches when the actual request {@code Accept} header
 * makes that media type acceptable. Acceptability respects:
 *
 * <ul>
 *   <li>exact media ranges ({@code application/json}),</li>
 *   <li>subtype wildcards (type slash star),</li>
 *   <li>the full wildcard (star slash star),</li>
 *   <li>quality values ({@code q=0..1}, default {@code 1}); a {@code q=0} range
 *       <em>excludes</em> the media type,</li>
 *   <li>preference order: most-specific acceptable range with the highest positive
 *       {@code q} wins, so a more-specific {@code q=0} can exclude a media type that a
 *       broader wildcard would otherwise accept.</li>
 * </ul>
 *
 * <p>Parsing is deliberately lenient: malformed media ranges or {@code q} values are skipped
 * rather than throwing, so a hostile or sloppy {@code Accept} header never fails the request
 * with an exception. An absent / blank actual {@code Accept} header is treated as accepting
 * nothing for this directive (it does not match).
 *
 * <p>Negation of the overall result is handled by the surrounding
 * {@link org.mockserver.model.NottableString} {@code !} prefix (e.g. {@code !accept:text/html}),
 * not here.
 *
 * @author jamesdbloom
 */
class AcceptHeaderMatcher {

    private static final String PREFIX = "accept:";

    private AcceptHeaderMatcher() {
    }

    /**
     * @return {@code true} when the matcher value is an {@code accept:<media-type>} directive,
     * so the caller must use {@link #matches(String, String)} rather than default string / regex
     * behaviour.
     */
    static boolean isAcceptDirective(String matcherValue) {
        if (matcherValue == null) {
            return false;
        }
        if (!startsWithIgnoreCase(matcherValue, PREFIX)) {
            return false;
        }
        return StringUtils.isNotBlank(matcherValue.substring(PREFIX.length()));
    }

    /**
     * Evaluate an {@code accept:<media-type>} matcher value against an actual {@code Accept}
     * header value.
     *
     * @param matcherValue an {@code accept:} directive such as {@code "accept:application/json"};
     *                     callers should first confirm {@link #isAcceptDirective(String)}
     * @param acceptHeaderValue the actual request {@code Accept} header value
     * @return {@code true} only when the desired media type is acceptable per RFC 7231; a blank
     * actual header, or one whose best matching range has {@code q=0}, never matches
     */
    static boolean matches(String matcherValue, String acceptHeaderValue) {
        if (!isAcceptDirective(matcherValue) || StringUtils.isBlank(acceptHeaderValue)) {
            return false;
        }
        MediaType desired = MediaType.parse(matcherValue.substring(PREFIX.length()));
        if (desired == null) {
            return false;
        }
        List<MediaRange> ranges = parseAcceptHeader(acceptHeaderValue);
        // pick the most-specific matching range; its q-value decides acceptability so a more-specific
        // q=0 can override a broader wildcard. Among equally-specific matching ranges (e.g. a duplicated
        // media type) prefer the higher quality, so any positive-q range makes the type acceptable.
        MediaRange best = null;
        for (MediaRange range : ranges) {
            if (range.matches(desired)) {
                if (best == null
                    || range.specificity() > best.specificity()
                    || (range.specificity() == best.specificity() && range.quality > best.quality)) {
                    best = range;
                }
            }
        }
        return best != null && best.quality > 0d;
    }

    /**
     * Parse an {@code Accept} header value into its constituent media ranges, ordered by quality
     * descending then specificity descending then declaration order. Parsing is lenient: any
     * malformed entry is skipped.
     */
    static List<MediaRange> parseAcceptHeader(String acceptHeaderValue) {
        List<MediaRange> ranges = new ArrayList<>();
        if (StringUtils.isBlank(acceptHeaderValue)) {
            return ranges;
        }
        int order = 0;
        for (String element : acceptHeaderValue.split(",")) {
            MediaRange range = MediaRange.parse(element, order);
            if (range != null) {
                ranges.add(range);
                order++;
            }
        }
        ranges.sort((a, b) -> {
            int byQuality = Double.compare(b.quality, a.quality);
            if (byQuality != 0) {
                return byQuality;
            }
            int bySpecificity = Integer.compare(b.specificity(), a.specificity());
            if (bySpecificity != 0) {
                return bySpecificity;
            }
            return Integer.compare(a.order, b.order);
        });
        return ranges;
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.length() >= prefix.length()
            && value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /**
     * A simple {@code type/subtype} media type (no parameters), used for the desired media type
     * supplied by the matcher directive.
     */
    static final class MediaType {
        final String type;
        final String subtype;

        private MediaType(String type, String subtype) {
            this.type = type;
            this.subtype = subtype;
        }

        static MediaType parse(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            // strip any parameters (e.g. "; charset=utf-8") from the desired media type
            int semicolon = trimmed.indexOf(';');
            if (semicolon >= 0) {
                trimmed = trimmed.substring(0, semicolon).trim();
            }
            if (trimmed.isEmpty()) {
                return null;
            }
            int slash = trimmed.indexOf('/');
            if (slash <= 0 || slash == trimmed.length() - 1) {
                return null;
            }
            String type = trimmed.substring(0, slash).trim().toLowerCase(Locale.ROOT);
            String subtype = trimmed.substring(slash + 1).trim().toLowerCase(Locale.ROOT);
            if (type.isEmpty() || subtype.isEmpty()) {
                return null;
            }
            return new MediaType(type, subtype);
        }
    }

    /**
     * One media range from an {@code Accept} header, e.g. {@code text/html;q=0.9}, with its parsed
     * quality value. Wildcards are represented by {@code *} in {@link #type} / {@link #subtype}.
     */
    static final class MediaRange {
        final String type;
        final String subtype;
        final double quality;
        final int order;

        private MediaRange(String type, String subtype, double quality, int order) {
            this.type = type;
            this.subtype = subtype;
            this.quality = quality;
            this.order = order;
        }

        static MediaRange parse(String element, int order) {
            if (element == null) {
                return null;
            }
            String trimmed = element.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            String[] parts = trimmed.split(";");
            String mediaRange = parts[0].trim();
            int slash = mediaRange.indexOf('/');
            if (slash <= 0 || slash == mediaRange.length() - 1) {
                return null;
            }
            String type = mediaRange.substring(0, slash).trim().toLowerCase(Locale.ROOT);
            String subtype = mediaRange.substring(slash + 1).trim().toLowerCase(Locale.ROOT);
            if (type.isEmpty() || subtype.isEmpty()) {
                return null;
            }
            // a bare "*" type must pair with "*" subtype to be the full wildcard; "*/json" is invalid
            if (type.equals("*") && !subtype.equals("*")) {
                return null;
            }
            double quality = 1d;
            for (int i = 1; i < parts.length; i++) {
                String param = parts[i].trim();
                int equals = param.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String name = param.substring(0, equals).trim();
                if (name.equalsIgnoreCase("q")) {
                    quality = parseQuality(param.substring(equals + 1).trim());
                    // RFC 7231: the first "q" delimits media-range params from accept-ext params;
                    // stop once it is read.
                    break;
                }
            }
            return new MediaRange(type, subtype, quality, order);
        }

        private static double parseQuality(String value) {
            try {
                double q = Double.parseDouble(value);
                if (q < 0d) {
                    return 0d;
                }
                if (q > 1d) {
                    return 1d;
                }
                return q;
            } catch (NumberFormatException nfe) {
                // malformed q value — default to fully acceptable rather than throwing
                return 1d;
            }
        }

        boolean matches(MediaType desired) {
            boolean typeMatches = type.equals("*") || type.equals(desired.type);
            boolean subtypeMatches = subtype.equals("*") || subtype.equals(desired.subtype);
            return typeMatches && subtypeMatches;
        }

        /**
         * Specificity for preference ordering: an exact {@code type/subtype} (2) is preferred over a
         * subtype wildcard (1), which is preferred over the full wildcard (0).
         */
        int specificity() {
            int score = 0;
            if (!type.equals("*")) {
                score += 2;
            }
            if (!subtype.equals("*")) {
                score += 1;
            }
            return score;
        }
    }
}
