package org.mockserver.model;

import org.apache.commons.lang3.StringUtils;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal, self-contained predicate that gates whether a {@link HttpResponseModifier} applies.
 *
 * <p>The condition is evaluated against the in-flight (upstream) response and, optionally, the
 * original request. It is intentionally small — it reuses the well-known status-code-range string
 * forms rather than inventing a new condition language:
 *
 * <ul>
 *   <li><b>statusCode</b> — exact equality against the response status code.</li>
 *   <li><b>statusCodeRange</b> — an HTTP status class such as {@code "2xx"} / {@code "5XX"}
 *       (case-insensitive), matched when the status falls in {@code [N00, N99]}.</li>
 *   <li><b>responseHasHeader</b> — the response carries a header with this name (case-insensitive),
 *       any value.</li>
 *   <li><b>requestHasHeader</b> — the request carries a header with this name (case-insensitive),
 *       any value. Ignored when no request is available at the application site.</li>
 * </ul>
 *
 * <p>All configured criteria must hold (logical AND). A condition with no criteria configured
 * matches everything. An unparseable {@code statusCodeRange} is a clean non-match (never throws).
 *
 * @author jamesdbloom
 */
public class HttpResponseModifierCondition extends ObjectWithJsonToString {

    // one digit followed by "XX" (case-insensitive), e.g. 1xx..5xx
    private static final Pattern CLASS_RANGE_PATTERN = Pattern.compile("^\\s*([0-9])[xX]{2}\\s*$");

    private int hashCode;
    private Integer statusCode;
    private String statusCodeRange;
    private String responseHasHeader;
    private String requestHasHeader;

    public static HttpResponseModifierCondition responseModifierCondition() {
        return new HttpResponseModifierCondition();
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public HttpResponseModifierCondition withStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
        this.hashCode = 0;
        return this;
    }

    public String getStatusCodeRange() {
        return statusCodeRange;
    }

    public HttpResponseModifierCondition withStatusCodeRange(String statusCodeRange) {
        this.statusCodeRange = statusCodeRange;
        this.hashCode = 0;
        return this;
    }

    public String getResponseHasHeader() {
        return responseHasHeader;
    }

    public HttpResponseModifierCondition withResponseHasHeader(String responseHasHeader) {
        this.responseHasHeader = responseHasHeader;
        this.hashCode = 0;
        return this;
    }

    public String getRequestHasHeader() {
        return requestHasHeader;
    }

    public HttpResponseModifierCondition withRequestHasHeader(String requestHasHeader) {
        this.requestHasHeader = requestHasHeader;
        this.hashCode = 0;
        return this;
    }

    /**
     * @return {@code true} when every configured criterion holds for the given response/request.
     */
    public boolean matches(HttpResponse response, HttpRequest request) {
        if (statusCode != null) {
            if (response == null || response.getStatusCode() == null || !statusCode.equals(response.getStatusCode())) {
                return false;
            }
        }
        if (StringUtils.isNotBlank(statusCodeRange)) {
            Integer actual = response != null ? response.getStatusCode() : null;
            if (actual == null || !matchesStatusCodeRange(actual)) {
                return false;
            }
        }
        if (StringUtils.isNotBlank(responseHasHeader)) {
            if (response == null || !response.containsHeader(responseHasHeader)) {
                return false;
            }
        }
        if (StringUtils.isNotBlank(requestHasHeader)) {
            if (request == null || !request.containsHeader(requestHasHeader)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesStatusCodeRange(int actual) {
        Matcher classRange = CLASS_RANGE_PATTERN.matcher(statusCodeRange);
        if (classRange.matches()) {
            int low = Integer.parseInt(classRange.group(1)) * 100;
            return actual >= low && actual <= low + 99;
        }
        // unparseable expression is a clean non-match
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        HttpResponseModifierCondition that = (HttpResponseModifierCondition) o;
        return Objects.equals(statusCode, that.statusCode) &&
            Objects.equals(statusCodeRange, that.statusCodeRange) &&
            Objects.equals(responseHasHeader, that.responseHasHeader) &&
            Objects.equals(requestHasHeader, that.requestHasHeader);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(statusCode, statusCodeRange, responseHasHeader, requestHasHeader);
        }
        return hashCode;
    }
}
