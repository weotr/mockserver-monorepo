package org.mockserver.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.mock.Expectation;
import org.mockserver.model.Header;
import org.mockserver.model.Parameter;
import org.mockserver.serialization.ObjectMapperFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Imports expectations from Postman Collection v2.x (v2.0 and v2.1) JSON files.
 *
 * <p>Walks the {@code item[]} array recursively (folders contain nested {@code item[]}).
 * For each request that has saved example responses ({@code response[]}), one expectation
 * is created per example. Requests without saved examples are skipped with a logged count.
 *
 * <p>Handles Postman {@code url} being either a plain string or an object with
 * {@code raw}, {@code path[]}, and {@code query[]}.
 */
public class PostmanCollectionImporter {

    private static final Logger LOG = LoggerFactory.getLogger(PostmanCollectionImporter.class);
    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    /**
     * Parses a Postman Collection v2.x JSON string and returns one expectation per
     * saved example response, with sensitive headers and body fields redacted (see
     * {@link ImportRedaction}). Postman requests/examples often carry real
     * {@code Authorization}/API-key headers and secret body fields, so redaction is
     * on by default.
     *
     * @param collectionJson the Postman collection as a JSON string
     * @return the generated expectations (may be empty if no examples are present)
     * @throws IllegalArgumentException if the JSON is null, blank, or not a valid Postman collection
     */
    public List<Expectation> importExpectations(String collectionJson) {
        return importExpectations(collectionJson, ImportRedaction.Options.enabled());
    }

    /**
     * Parses a Postman Collection v2.x JSON string and returns one expectation per
     * saved example response, applying the supplied redaction options before the
     * expectations are returned.
     *
     * @param collectionJson   the Postman collection as a JSON string
     * @param redactionOptions controls whether/how sensitive data is masked; pass
     *                         {@link ImportRedaction.Options#disabled()} to keep
     *                         imported values verbatim
     * @return the generated expectations (may be empty if no examples are present)
     * @throws IllegalArgumentException if the JSON is null, blank, or not a valid Postman collection
     */
    public List<Expectation> importExpectations(String collectionJson, ImportRedaction.Options redactionOptions) {
        if (collectionJson == null || collectionJson.trim().isEmpty()) {
            throw new IllegalArgumentException("Postman collection JSON body is required");
        }

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(collectionJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to parse Postman collection JSON: " + e.getMessage(), e);
        }

        if (root.path("info").isMissingNode()) {
            throw new IllegalArgumentException("not a valid Postman collection — missing top-level 'info' object");
        }

        JsonNode items = root.path("item");
        if (items.isMissingNode() || !items.isArray()) {
            throw new IllegalArgumentException("not a valid Postman collection — missing top-level 'item' array");
        }

        List<Expectation> expectations = new ArrayList<>();
        AtomicInteger index = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);

        walkItems(items, expectations, index, skippedCount);

        if (skippedCount.get() > 0) {
            LOG.info("skipped {} Postman request(s) without saved example responses", skippedCount.get());
        }

        return ImportRedaction.redact(expectations, redactionOptions);
    }

    private void walkItems(JsonNode items, List<Expectation> expectations,
                           AtomicInteger index, AtomicInteger skippedCount) {
        for (JsonNode item : items) {
            // Folder: has nested item[] without a request
            JsonNode nestedItems = item.path("item");
            if (nestedItems.isArray() && !nestedItems.isEmpty()) {
                walkItems(nestedItems, expectations, index, skippedCount);
                continue;
            }

            // Request item
            JsonNode requestNode = item.path("request");
            if (requestNode.isMissingNode()) {
                continue;
            }

            JsonNode responses = item.path("response");
            if (!responses.isArray() || responses.isEmpty()) {
                skippedCount.incrementAndGet();
                continue;
            }

            String itemName = textOrNull(item, "name");

            for (JsonNode exampleResponse : responses) {
                Expectation expectation = buildExpectation(requestNode, exampleResponse, itemName, index.getAndIncrement());
                if (expectation != null) {
                    expectations.add(expectation);
                }
            }
        }
    }

    private Expectation buildExpectation(JsonNode requestNode, JsonNode exampleResponse,
                                         String itemName, int index) {
        // --- Parse request ---
        String method = resolveMethod(requestNode);
        UrlComponents urlComponents = resolveUrl(requestNode);
        if (method == null || urlComponents.path == null) {
            return null;
        }

        var httpRequest = request()
            .withMethod(method.toUpperCase(Locale.ROOT))
            .withPath(urlComponents.path);

        if (!urlComponents.queryParams.isEmpty()) {
            httpRequest.withQueryStringParameters(urlComponents.queryParams);
        }

        // Request headers from request node
        List<Header> requestHeaders = parseHeaders(requestNode.path("header"));
        if (!requestHeaders.isEmpty()) {
            httpRequest.withHeaders(requestHeaders);
        }

        // Request body
        JsonNode body = requestNode.path("body");
        if (!body.isMissingNode()) {
            String rawBody = textOrNull(body, "raw");
            if (rawBody != null && !rawBody.isEmpty()) {
                httpRequest.withBody(rawBody);
            }
        }

        // --- Parse response ---
        int statusCode = exampleResponse.path("code").asInt(200);

        var httpResponse = response()
            .withStatusCode(statusCode);

        // Response headers
        List<Header> responseHeaders = parseHeaders(exampleResponse.path("header"));
        if (!responseHeaders.isEmpty()) {
            httpResponse.withHeaders(responseHeaders);
        }

        // Response body
        String responseBody = textOrNull(exampleResponse, "body");
        if (responseBody != null && !responseBody.isEmpty()) {
            httpResponse.withBody(responseBody);
        }

        // Build ID. The per-example index is ALWAYS included to guarantee uniqueness:
        // a single request item can have multiple saved example responses, and without
        // the index they would collide and silently overwrite each other on upsert.
        String suffix = (itemName != null && !itemName.isEmpty())
            ? sanitizeForId(itemName)
            : "item";
        String id = "postman-" + index + "-" + suffix;

        return new Expectation(httpRequest)
            .withId(id)
            .thenRespond(httpResponse);
    }

    private String resolveMethod(JsonNode requestNode) {
        // Method can be a direct string or inside the request object
        JsonNode methodNode = requestNode.path("method");
        if (!methodNode.isMissingNode() && methodNode.isTextual()) {
            return methodNode.asText();
        }
        return null;
    }

    private UrlComponents resolveUrl(JsonNode requestNode) {
        JsonNode urlNode = requestNode.path("url");

        // url can be a plain string
        if (urlNode.isTextual()) {
            return parseUrlString(urlNode.asText());
        }

        // url can be an object with raw, path[], query[]
        if (urlNode.isObject()) {
            // Try path array first (more structured)
            JsonNode pathArray = urlNode.path("path");
            String path;
            if (pathArray.isArray() && !pathArray.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode segment : pathArray) {
                    sb.append("/").append(segment.asText());
                }
                path = sb.toString();
            } else {
                // Fall back to raw
                String raw = textOrNull(urlNode, "raw");
                if (raw != null) {
                    return parseUrlString(raw);
                }
                path = "/";
            }

            // Parse query params from query array
            List<Parameter> queryParams = new ArrayList<>();
            JsonNode queryArray = urlNode.path("query");
            if (queryArray.isArray()) {
                for (JsonNode qp : queryArray) {
                    String name = textOrNull(qp, "key");
                    String value = textOrNull(qp, "value");
                    if (name != null) {
                        queryParams.add(Parameter.param(name, value != null ? value : ""));
                    }
                }
            }

            return new UrlComponents(path, queryParams);
        }

        return new UrlComponents("/", List.of());
    }

    private UrlComponents parseUrlString(String urlString) {
        if (urlString == null || urlString.isEmpty()) {
            return new UrlComponents("/", List.of());
        }
        try {
            URI uri = new URI(urlString);
            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            List<Parameter> params = new ArrayList<>();
            String query = uri.getRawQuery();
            if (query != null && !query.isEmpty()) {
                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq >= 0) {
                        params.add(Parameter.param(
                            decodeComponent(pair.substring(0, eq)),
                            decodeComponent(pair.substring(eq + 1))
                        ));
                    } else {
                        params.add(Parameter.param(decodeComponent(pair), ""));
                    }
                }
            }
            return new UrlComponents(path, params);
        } catch (Exception e) {
            // Best effort: use as path
            String path = urlString.startsWith("/") ? urlString : "/";
            return new UrlComponents(path, List.of());
        }
    }

    private List<Header> parseHeaders(JsonNode headersArray) {
        List<Header> headers = new ArrayList<>();
        if (headersArray == null || !headersArray.isArray()) {
            return headers;
        }
        for (JsonNode headerNode : headersArray) {
            String name = textOrNull(headerNode, "key");
            if (name == null) {
                name = textOrNull(headerNode, "name");
            }
            String value = textOrNull(headerNode, "value");
            if (name != null && value != null) {
                headers.add(Header.header(name, value));
            }
        }
        return headers;
    }

    private static String sanitizeForId(String name) {
        // Replace non-alphanumeric characters with hyphens, collapse multiple hyphens
        return name.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull()) {
            return null;
        }
        return child.asText();
    }

    private static String decodeComponent(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static class UrlComponents {
        final String path;
        final List<Parameter> queryParams;

        UrlComponents(String path, List<Parameter> queryParams) {
            this.path = path;
            this.queryParams = queryParams;
        }
    }
}
