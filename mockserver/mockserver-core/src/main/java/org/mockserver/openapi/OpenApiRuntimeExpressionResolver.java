package org.mockserver.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.NottableString;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves OpenAPI runtime expressions (RFC 3.4.16) in callback after-action requests
 * using values from the triggering HTTP request.
 * <p>
 * Supported expressions:
 * <ul>
 *   <li>{@code {$request.body#/<json-pointer>}} — JSON Pointer into the triggering request body</li>
 *   <li>{@code {$request.query.<name>}} — query parameter value from the triggering request</li>
 *   <li>{@code {$request.header.<name>}} — header value from the triggering request</li>
 *   <li>{@code {$request.path.<name>}} — path parameter (best-effort; empty if unavailable)</li>
 *   <li>{@code {$request.method}} — HTTP method of the triggering request</li>
 *   <li>{@code {$url}} — full URL of the triggering request</li>
 * </ul>
 * <p>
 * Response-based expressions ({@code {$response.body#/...}}, {@code {$response.header.*}})
 * are out of scope — the response object is not available at after-action dispatch time.
 * These expressions are replaced with empty string for consistency.
 * <p>
 * Unresolvable expressions (unknown format or missing values) are replaced with empty string.
 */
public class OpenApiRuntimeExpressionResolver {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    /**
     * Pattern matching OpenAPI runtime expressions: {@code {$...}}
     */
    private static final Pattern EXPRESSION_PATTERN = Pattern.compile("\\{\\$([^}]+)}");

    /**
     * Quick check pattern — avoids cloning/processing when no expressions exist.
     */
    private static final Pattern HAS_EXPRESSION = Pattern.compile("\\{\\$");

    private OpenApiRuntimeExpressionResolver() {
    }

    /**
     * Returns true if the given string contains at least one OpenAPI runtime expression.
     */
    public static boolean containsExpression(String value) {
        return value != null && HAS_EXPRESSION.matcher(value).find();
    }

    /**
     * Returns true if the after-action HttpRequest contains any runtime expressions
     * in its path, headers, Host header, or string body.
     */
    public static boolean containsExpressions(HttpRequest afterActionRequest) {
        if (afterActionRequest == null) {
            return false;
        }
        if (afterActionRequest.getPath() != null && containsExpression(afterActionRequest.getPath().getValue())) {
            return true;
        }
        for (Header header : afterActionRequest.getHeaderList()) {
            for (NottableString val : header.getValues()) {
                if (containsExpression(val.getValue())) {
                    return true;
                }
            }
        }
        if (afterActionRequest.getBody() != null && containsExpression(afterActionRequest.getBodyAsString())) {
            return true;
        }
        return false;
    }

    /**
     * Resolves all OpenAPI runtime expressions in the after-action request using values
     * from the triggering request. Returns a new HttpRequest with resolved values.
     * The original afterActionRequest is NOT mutated.
     * <p>
     * If the afterActionRequest contains no expressions, this method returns it unchanged
     * (no clone, no allocation — strict no-op).
     *
     * @param afterActionRequest the callback request template (may contain expressions)
     * @param triggeringRequest  the HTTP request that triggered the expectation
     * @return a resolved copy, or the original if no expressions were present
     */
    public static HttpRequest resolve(HttpRequest afterActionRequest, HttpRequest triggeringRequest) {
        if (afterActionRequest == null || triggeringRequest == null) {
            return afterActionRequest;
        }
        if (!containsExpressions(afterActionRequest)) {
            return afterActionRequest;
        }

        HttpRequest resolved = afterActionRequest.clone();

        // Resolve path
        if (resolved.getPath() != null) {
            String pathValue = resolved.getPath().getValue();
            if (containsExpression(pathValue)) {
                String resolvedPath = resolveExpressions(pathValue, triggeringRequest);
                resolved.withPath(resolvedPath);
            }
        }

        // Resolve headers (including Host)
        List<Header> resolvedHeaders = new ArrayList<>();
        boolean headersChanged = false;
        for (Header header : resolved.getHeaderList()) {
            List<NottableString> resolvedValues = new ArrayList<>();
            boolean headerChanged = false;
            for (NottableString val : header.getValues()) {
                if (containsExpression(val.getValue())) {
                    resolvedValues.add(NottableString.string(resolveExpressions(val.getValue(), triggeringRequest)));
                    headerChanged = true;
                } else {
                    resolvedValues.add(val);
                }
            }
            if (headerChanged) {
                resolvedHeaders.add(new Header(header.getName(), resolvedValues));
                headersChanged = true;
            } else {
                resolvedHeaders.add(header);
            }
        }
        if (headersChanged) {
            resolved.withHeaders(resolvedHeaders);
        }

        // Resolve body (string body only)
        if (resolved.getBody() != null) {
            String bodyStr = resolved.getBodyAsString();
            if (bodyStr != null && containsExpression(bodyStr)) {
                resolved.withBody(resolveExpressions(bodyStr, triggeringRequest));
            }
        }

        // After resolving the path, check if it now looks like a full URL
        // (e.g., expression resolved to "https://example.com") and update Host/secure
        String resolvedPathValue = resolved.getPath() != null ? resolved.getPath().getValue() : null;
        if (resolvedPathValue != null && (resolvedPathValue.startsWith("http://") || resolvedPathValue.startsWith("https://"))) {
            try {
                java.net.URI uri = new java.net.URI(resolvedPathValue);
                resolved.withPath(uri.getPath() != null ? uri.getPath() : "/");
                resolved.withHeader("Host", uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : ""));
                resolved.withSecure(resolvedPathValue.startsWith("https://"));
            } catch (java.net.URISyntaxException ignored) {
                // leave as-is if URI parsing fails
            }
        }

        return resolved;
    }

    /**
     * Resolves all {@code {$...}} expression placeholders in the given string.
     */
    static String resolveExpressions(String template, HttpRequest triggeringRequest) {
        Matcher matcher = EXPRESSION_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String expression = matcher.group(1); // e.g., "request.body#/callbackUrl" or "url"
            String replacement = resolveExpression(expression, triggeringRequest);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Resolves a single expression (without the {@code {$...}} wrapper).
     */
    private static String resolveExpression(String expression, HttpRequest triggeringRequest) {
        if (expression.startsWith("request.body#")) {
            return resolveBodyJsonPointer(expression.substring("request.body#".length()), triggeringRequest);
        } else if (expression.startsWith("request.query.")) {
            String paramName = expression.substring("request.query.".length());
            String value = triggeringRequest.getFirstQueryStringParameter(paramName);
            return value != null ? value : "";
        } else if (expression.startsWith("request.header.")) {
            String headerName = expression.substring("request.header.".length());
            String value = triggeringRequest.getFirstHeader(headerName);
            return value != null ? value : "";
        } else if (expression.startsWith("request.path.")) {
            // Best-effort path parameter resolution — requires path parameters to be populated
            String paramName = expression.substring("request.path.".length());
            for (org.mockserver.model.Parameter param : triggeringRequest.getPathParameterList()) {
                if (param.getName() != null && paramName.equals(param.getName().getValue())) {
                    List<NottableString> values = param.getValues();
                    if (values != null && !values.isEmpty()) {
                        return values.get(0).getValue();
                    }
                }
            }
            return "";
        } else if (expression.equals("request.method")) {
            return triggeringRequest.getMethod() != null ? triggeringRequest.getMethod().getValue() : "";
        } else if (expression.equals("url")) {
            return buildUrl(triggeringRequest);
        } else if (expression.startsWith("response.")) {
            // Response-based expressions are out of scope — response not available at dispatch time
            return "";
        }
        // Unknown expression — replace with empty string
        return "";
    }

    /**
     * Resolves a JSON Pointer against the triggering request body.
     */
    private static String resolveBodyJsonPointer(String pointer, HttpRequest triggeringRequest) {
        String bodyStr = triggeringRequest.getBodyAsString();
        if (bodyStr == null || bodyStr.isEmpty()) {
            return "";
        }
        try {
            JsonNode rootNode = OBJECT_MAPPER.readTree(bodyStr);
            JsonNode resolved = rootNode.at(pointer);
            if (resolved.isMissingNode() || resolved.isNull()) {
                return "";
            }
            // Return text for text values, otherwise the JSON string representation
            return resolved.isTextual() ? resolved.asText() : resolved.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Reconstructs a best-effort URL from the triggering request.
     */
    private static String buildUrl(HttpRequest triggeringRequest) {
        String scheme = Boolean.TRUE.equals(triggeringRequest.isSecure()) ? "https" : "http";
        String host = triggeringRequest.getFirstHeader("Host");
        String path = triggeringRequest.getPath() != null ? triggeringRequest.getPath().getValue() : "/";
        if (host != null && !host.isEmpty()) {
            return scheme + "://" + host + path;
        }
        return path;
    }
}
