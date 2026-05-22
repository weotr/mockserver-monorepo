package org.mockserver.fixture;

import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.model.*;

import java.util.*;

/**
 * Masks sensitive data in recorded expectations before they are written to fixture files.
 * <p>
 * Operates on copies: the live event log is never mutated. Header values for a configurable
 * set of header names are replaced with a placeholder ({@value REDACTED_PLACEHOLDER}).
 * <p>
 * Default sensitive headers: {@code Authorization}, {@code x-api-key}, {@code api-key},
 * {@code Cookie}, {@code Set-Cookie}, {@code Proxy-Authorization}.
 */
public class FixtureRedactor {

    public static final String REDACTED_PLACEHOLDER = "***REDACTED***";

    private static final Set<String> DEFAULT_SENSITIVE_HEADERS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    static {
        DEFAULT_SENSITIVE_HEADERS.add("Authorization");
        DEFAULT_SENSITIVE_HEADERS.add("x-api-key");
        DEFAULT_SENSITIVE_HEADERS.add("api-key");
        DEFAULT_SENSITIVE_HEADERS.add("Cookie");
        DEFAULT_SENSITIVE_HEADERS.add("Set-Cookie");
        DEFAULT_SENSITIVE_HEADERS.add("Proxy-Authorization");
    }

    private final Set<String> sensitiveHeaders;

    /**
     * Create a redactor with the default sensitive header list.
     */
    public FixtureRedactor() {
        this.sensitiveHeaders = DEFAULT_SENSITIVE_HEADERS;
    }

    /**
     * Create a redactor with a custom sensitive header list.
     *
     * @param sensitiveHeaders header names to redact (case-insensitive)
     */
    public FixtureRedactor(Collection<String> sensitiveHeaders) {
        this.sensitiveHeaders = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        this.sensitiveHeaders.addAll(sensitiveHeaders);
    }

    /**
     * Redact sensitive headers in an array of expectations. Returns new Expectation objects;
     * the originals are not modified.
     *
     * @param expectations the expectations to redact
     * @return new expectations with sensitive header values replaced
     */
    public Expectation[] redact(Expectation[] expectations) {
        if (expectations == null) {
            return new Expectation[0];
        }
        Expectation[] result = new Expectation[expectations.length];
        for (int i = 0; i < expectations.length; i++) {
            result[i] = redactExpectation(expectations[i]);
        }
        return result;
    }

    private Expectation redactExpectation(Expectation expectation) {
        RequestDefinition requestDef = expectation.getHttpRequest();
        HttpResponse response = expectation.getHttpResponse();
        HttpSseResponse sseResponse = expectation.getHttpSseResponse();

        RequestDefinition redactedRequestDef = requestDef;
        if (requestDef instanceof HttpRequest) {
            redactedRequestDef = redactRequest((HttpRequest) requestDef);
        }
        HttpResponse redactedResponse = response != null ? redactResponse(response) : null;
        HttpSseResponse redactedSseResponse = sseResponse != null ? redactSseResponse(sseResponse) : null;

        Expectation result = new Expectation(
            redactedRequestDef,
            Times.unlimited(),
            TimeToLive.unlimited(),
            expectation.getPriority()
        );

        if (redactedSseResponse != null) {
            result.thenRespondWithSse(redactedSseResponse);
        } else if (redactedResponse != null) {
            result.thenRespond(redactedResponse);
        } else if (response != null) {
            result.thenRespond(response);
        } else if (sseResponse != null) {
            result.thenRespondWithSse(sseResponse);
        }

        return result;
    }

    private HttpRequest redactRequest(HttpRequest request) {
        HttpRequest redacted = request.clone();
        if (redacted.getHeaderList() != null) {
            Headers headers = new Headers();
            for (Header header : redacted.getHeaderList()) {
                String name = header.getName().getValue();
                if (sensitiveHeaders.contains(name)) {
                    headers.withEntry(new Header(name, REDACTED_PLACEHOLDER));
                } else {
                    headers.withEntry(header);
                }
            }
            redacted.withHeaders(headers);
        }
        return redacted;
    }

    private HttpResponse redactResponse(HttpResponse response) {
        HttpResponse redacted = response.clone();
        if (redacted.getHeaderList() != null) {
            Headers headers = new Headers();
            for (Header header : redacted.getHeaderList()) {
                String name = header.getName().getValue();
                if (sensitiveHeaders.contains(name)) {
                    headers.withEntry(new Header(name, REDACTED_PLACEHOLDER));
                } else {
                    headers.withEntry(header);
                }
            }
            redacted.withHeaders(headers);
        }
        return redacted;
    }

    private HttpSseResponse redactSseResponse(HttpSseResponse sseResponse) {
        HttpSseResponse redacted = HttpSseResponse.sseResponse();
        redacted.withStatusCode(sseResponse.getStatusCode());
        redacted.withCloseConnection(sseResponse.getCloseConnection());
        if (sseResponse.getEvents() != null) {
            redacted.withEvents(sseResponse.getEvents());
        }
        if (sseResponse.getHeaders() != null) {
            Headers headers = new Headers();
            for (Header header : sseResponse.getHeaders().getEntries()) {
                String name = header.getName().getValue();
                if (sensitiveHeaders.contains(name)) {
                    headers.withEntry(new Header(name, REDACTED_PLACEHOLDER));
                } else {
                    headers.withEntry(header);
                }
            }
            redacted.withHeaders(headers);
        }
        return redacted;
    }
}
