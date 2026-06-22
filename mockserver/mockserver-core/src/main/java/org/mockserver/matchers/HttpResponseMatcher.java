package org.mockserver.matchers;

import org.apache.commons.lang3.StringUtils;
import org.mockserver.codec.JsonSchemaBodyDecoder;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;

import static org.mockserver.matchers.MatchDifference.Field.BODY;
import static org.mockserver.matchers.MatchDifference.Field.COOKIES;
import static org.mockserver.matchers.MatchDifference.Field.HEADERS;
import static org.mockserver.matchers.MatchDifference.Field.OPERATION;
import static org.mockserver.model.NottableString.string;

/**
 * Matches an actual {@link HttpResponse} against a template {@link HttpResponse}.
 * <p>
 * Each template field constrains the match only when it is set (non-null/non-blank).
 * A null or empty template matches any response.
 * <p>
 * Body matching shares the exact dispatch used by request matching via {@link BodyMatching}, so a
 * response body matcher has full parity with a request body matcher: XML/form actual bodies are
 * converted to JSON for the JSON family of matchers, an optional template body matches an absent
 * response body, multipart and binary (including original/compressed) bodies are handled, and an
 * absent actual body against a JSON/XML matcher is a clean non-match rather than a swallowed NPE.
 * <p>
 * Cookie matching mirrors the request matcher: when the template declares structured cookies they
 * are matched (sub-set semantics, extra response cookies allowed, notted values supported) via the
 * same {@link HashMapMatcher} the request side uses.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class HttpResponseMatcher {

    private final HttpResponse template;
    private final StatusCodeMatcher statusCodeMatcher;
    private final RegexStringMatcher reasonPhraseMatcher;
    private final MultiValueMapMatcher headerMatcher;
    private final HashMapMatcher cookieMatcher;
    private final BodyMatcher bodyMatcher;
    private final Boolean bodyOptional;
    private final JsonSchemaBodyDecoder jsonSchemaBodyParser;
    private final MockServerLogger mockServerLogger;

    public HttpResponseMatcher(Configuration configuration, MockServerLogger mockServerLogger, HttpResponse template) {
        this.template = template;
        this.mockServerLogger = mockServerLogger;
        if (template != null) {
            // statusCode: when either an exact statusCode or a statusCodeRange expression is set,
            // a StatusCodeMatcher enforces the constraint (exact equality, class range such as "2XX",
            // or numeric operator such as ">= 400"). When NEITHER is set there is no status
            // constraint and the matcher is null (match all) — byte-for-byte the prior behaviour.
            this.statusCodeMatcher = (template.getStatusCode() != null || StringUtils.isNotBlank(template.getStatusCodeRange()))
                ? new StatusCodeMatcher(template.getStatusCode(), template.getStatusCodeRange(), mockServerLogger)
                : null;
            // reason-phrase honours the opt-in matchExactCase flag, matching the response body's
            // exact-case behaviour (built via BodyMatcherBuilder, which already consults the flag).
            this.reasonPhraseMatcher = template.getReasonPhrase() != null
                ? new RegexStringMatcher(mockServerLogger, string(template.getReasonPhrase()), false, configuration != null && configuration.matchExactCase())
                : null;
            this.headerMatcher = template.getHeaders() != null && !template.getHeaders().isEmpty()
                ? new MultiValueMapMatcher(mockServerLogger, template.getHeaders(), false)
                : null;
            // cookies: reuse the request side's HashMapMatcher for sub-set / notted semantics
            this.cookieMatcher = template.getCookies() != null && !template.getCookies().isEmpty()
                ? new HashMapMatcher(mockServerLogger, template.getCookies(), false)
                : null;
            this.bodyMatcher = template.getBody() != null
                ? BodyMatcherBuilder.buildBodyMatcher(configuration, mockServerLogger, template.getBody(), false)
                : null;
            this.bodyOptional = template.getBody() != null ? template.getBody().getOptional() : null;
            // No expectation / template request is available for a response match — they are used by
            // the parser only for a JSON-conversion failure log, which the response path reports as
            // null (it has no originating request to attribute the failure to).
            this.jsonSchemaBodyParser = this.bodyMatcher != null
                ? new JsonSchemaBodyDecoder(configuration, mockServerLogger, null, null)
                : null;
        } else {
            this.statusCodeMatcher = null;
            this.reasonPhraseMatcher = null;
            this.headerMatcher = null;
            this.cookieMatcher = null;
            this.bodyMatcher = null;
            this.bodyOptional = null;
            this.jsonSchemaBodyParser = null;
        }
    }

    /**
     * Returns {@code true} when the actual response matches the template.
     * A null template matches everything.
     */
    public boolean matches(HttpResponse actual) {
        return matches(null, actual);
    }

    /**
     * Returns {@code true} when the actual response matches the template, recording per-field
     * differences into {@code context} when it is non-null. The boolean result is identical to
     * {@link #matches(HttpResponse)}.
     * <p>
     * Response fields are bucketed onto existing {@link MatchDifference.Field} constants rather than
     * introducing new enum constants: {@code Field} is the shared field vocabulary established by the
     * request matcher, and any code that enumerates {@code Field.values()} would observe a longer
     * array if response-only constants were added. statusCode and
     * reasonPhrase both record under {@link MatchDifference.Field#OPERATION} (a neutral bucket);
     * headers, cookies and body use their natural {@link MatchDifference.Field#HEADERS},
     * {@link MatchDifference.Field#COOKIES} and {@link MatchDifference.Field#BODY} buckets.
     */
    public boolean matches(MatchDifference context, HttpResponse actual) {
        if (template == null) {
            return true;
        }
        if (actual == null) {
            return false;
        }

        boolean matches = true;

        // statusCode: exact integer equality (default), class range ("2XX"), or numeric operator
        // (">= 400"), delegated to StatusCodeMatcher. A null actual status never matches a template
        // status constraint (preserving the prior exact-equals semantics where equals(null) is false).
        if (statusCodeMatcher != null) {
            boolean statusMatches = actual.getStatusCode() != null && statusCodeMatcher.matches(actual.getStatusCode());
            if (!statusMatches) {
                if (context != null) {
                    Object expected = StringUtils.isNotBlank(template.getStatusCodeRange())
                        ? template.getStatusCodeRange()
                        : template.getStatusCode();
                    context.currentField(OPERATION);
                    context.addDifference(mockServerLogger, "statusCode match failed expected:{}found:{}", expected, actual.getStatusCode());
                }
                matches = false;
            }
        }

        // reasonPhrase: regex match when set on template
        if (reasonPhraseMatcher != null) {
            if (context != null) {
                context.currentField(OPERATION);
            }
            if (!reasonPhraseMatcher.matches(context, string(actual.getReasonPhrase()))) {
                matches = false;
            }
        }

        // headers: reuse existing MultiValueMapMatcher
        if (headerMatcher != null) {
            if (context != null) {
                context.currentField(HEADERS);
            }
            if (!headerMatcher.matches(context, actual.getHeaders())) {
                matches = false;
            }
        }

        // cookies: reuse the request side's HashMapMatcher (sub-set / notted semantics)
        if (cookieMatcher != null) {
            if (context != null) {
                context.currentField(COOKIES);
            }
            if (!cookieMatcher.matches(context, actual.getCookies())) {
                matches = false;
            }
        }

        // body: share the exact dispatch used by request matching so the response body matcher has
        // full parity (JSON conversion of XML/form bodies, optional body, multipart, binary
        // original/compressed, null-safe JSON/XML). BodyMatching sets currentField(BODY) itself and
        // records body differences into the (optional) context.
        if (bodyMatcher != null) {
            if (context != null) {
                context.currentField(BODY);
            }
            if (!BodyMatching.bodyMatches(
                bodyMatcher,
                bodyOptional,
                BodyMatching.of(actual),
                context,
                jsonSchemaBodyParser,
                mockServerLogger
            )) {
                matches = false;
            }
        }

        return matches;
    }
}
