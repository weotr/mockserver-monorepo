package org.mockserver.responseheaders;

import org.mockserver.configuration.Configuration;
import org.mockserver.model.Header;
import org.mockserver.model.HttpResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Applies the configured {@code defaultResponseHeaders} to outgoing responses that MockServer
 * returns, using add-if-absent semantics so any header explicitly set on the matched response
 * always wins.
 *
 * <p>The configured value is a pipe ({@code |}) separated list of {@code name=value} pairs, e.g.
 * {@code Server=MockServer|X-Trace-Id=abc123}. A header value may itself contain commas; only
 * {@code |} separates headers and only the first {@code =} in each pair separates the name from
 * the value. Empty segments, segments without an {@code =}, and segments with a blank name are
 * skipped.</p>
 *
 * @author jamesdbloom
 */
public class DefaultResponseHeaders {

    private final List<Header> defaultHeaders;

    public DefaultResponseHeaders(Configuration configuration) {
        // The parse result is memoised on the Configuration (recomputed only when the configured
        // value changes), so although a ResponseWriter - and therefore this object - is constructed
        // per HTTP request, the pipe-split parse runs once per distinct configured value rather than
        // on every response. See Configuration#parsedDefaultResponseHeaders().
        this.defaultHeaders = configuration.parsedDefaultResponseHeaders();
    }

    /**
     * Parses the configured {@code defaultResponseHeaders} value (a pipe-separated list of
     * {@code name=value} pairs) into an immutable list of {@link Header}s. Empty segments, segments
     * without an {@code =}, and segments with a blank name are skipped.
     *
     * <p>This is the single source of truth for the parse; callers are expected to memoise the
     * result (see {@link Configuration#parsedDefaultResponseHeaders()}) rather than invoke it per
     * request.</p>
     *
     * @param configuredValue the raw configured value, may be {@code null} or blank
     * @return an immutable list of parsed headers (empty when the value is blank)
     */
    public static List<Header> parse(String configuredValue) {
        if (isBlank(configuredValue)) {
            return Collections.emptyList();
        }
        List<Header> headers = new ArrayList<>();
        for (String segment : configuredValue.split("\\|")) {
            int equalsIndex = segment.indexOf('=');
            if (equalsIndex <= 0) {
                // no '=' (<0) or a blank name ('=...' gives index 0) - skip
                continue;
            }
            String name = segment.substring(0, equalsIndex).trim();
            String value = segment.substring(equalsIndex + 1).trim();
            if (!name.isEmpty()) {
                headers.add(new Header(name, value));
            }
        }
        return Collections.unmodifiableList(headers);
    }

    public boolean isEmpty() {
        return defaultHeaders.isEmpty();
    }

    /**
     * Adds each configured default header to the response only if the response does not already
     * contain a header with that name (case-insensitive). A header explicitly set on the matched
     * response therefore always wins.
     *
     * @param response the response MockServer is about to return
     */
    public void addDefaultResponseHeaders(HttpResponse response) {
        if (response == null || defaultHeaders.isEmpty()) {
            return;
        }
        for (Header header : defaultHeaders) {
            if (!response.containsHeader(header.getName().getValue())) {
                response.withHeader(header);
            }
        }
    }

}
