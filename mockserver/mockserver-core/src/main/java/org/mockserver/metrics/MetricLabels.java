package org.mockserver.metrics;

/**
 * Small static helpers for deriving low-cardinality metric label values from load-scenario traffic.
 *
 * <p>The {@code route} label must stay low-cardinality (one Prometheus time series per distinct
 * value), so a raw request path like {@code /api/orders/12345} cannot be used directly — it would
 * create a new series per id. {@link #routeOf(String)} templatises the path by collapsing any
 * segment that is <em>all digits</em> or looks like a <em>UUID/hex id</em> to the literal token
 * {@code {id}}. So:
 * <ul>
 *   <li>{@code /api/orders/12345} → {@code /api/orders/{id}}</li>
 *   <li>{@code /users/9f1c8e0a-1b2c-4d3e-8f90-abcdef012345} → {@code /users/{id}}</li>
 *   <li>{@code /v2/items/deadbeefcafebabe} → {@code /v2/items/{id}} (16+ hex chars)</li>
 *   <li>{@code /api/orders} → {@code /api/orders} (unchanged — no id segment)</li>
 * </ul>
 *
 * <p>A scenario step may override this entirely by giving the step an explicit
 * {@link org.mockserver.load.LoadStep#getName() name}, in which case the orchestrator uses the
 * step name as the {@code route} label (see {@code LoadScenarioOrchestrator}).
 */
public final class MetricLabels {

    private MetricLabels() {
    }

    /**
     * Templatise a request path into a low-cardinality {@code route} label by replacing
     * id-shaped segments with {@code {id}}. A null/blank path returns {@code "/"}; the query string
     * (anything after {@code ?}) is dropped.
     *
     * @param path the raw request path (may include a query string)
     * @return the templatised route label
     */
    public static String routeOf(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        int q = path.indexOf('?');
        String withoutQuery = q >= 0 ? path.substring(0, q) : path;
        if (withoutQuery.isEmpty()) {
            return "/";
        }
        String[] segments = withoutQuery.split("/", -1);
        StringBuilder result = new StringBuilder(withoutQuery.length());
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                result.append('/');
            }
            String segment = segments[i];
            result.append(isIdLike(segment) ? "{id}" : segment);
        }
        return result.length() == 0 ? "/" : result.toString();
    }

    /**
     * A path segment is "id-like" (and should be templatised) when it is all digits, a UUID, or a
     * long hex string (16+ hex chars — typical for object ids / hashes). Short non-numeric tokens
     * like {@code orders} or {@code v2} are kept verbatim so genuine route structure survives.
     */
    private static boolean isIdLike(String segment) {
        if (segment == null || segment.isEmpty()) {
            return false;
        }
        if (isAllDigits(segment)) {
            return true;
        }
        if (isUuid(segment)) {
            return true;
        }
        return segment.length() >= 16 && isAllHex(segment);
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUuid(String s) {
        if (s.length() != 36) {
            return false;
        }
        for (int i = 0; i < 36; i++) {
            char c = s.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') {
                    return false;
                }
            } else if (!isHex(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isHex(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
