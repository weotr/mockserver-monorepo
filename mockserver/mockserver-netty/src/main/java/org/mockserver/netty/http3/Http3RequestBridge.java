package org.mockserver.netty.http3;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.incubator.codec.http3.DefaultHttp3DataFrame;
import io.netty.incubator.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.incubator.codec.http3.Http3DataFrame;
import io.netty.incubator.codec.http3.Http3Headers;
import io.netty.incubator.codec.http3.Http3HeadersFrame;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts between HTTP/3 frames and MockServer's {@link HttpRequest}/{@link HttpResponse} model.
 * <p>
 * These are pure conversion helpers with no Netty channel dependencies, so they
 * can be unit-tested without the native QUIC transport.
 */
public final class Http3RequestBridge {

    private Http3RequestBridge() {
        // utility class
    }

    /**
     * Build a MockServer {@link HttpRequest} from the HTTP/3 pseudo-headers and
     * accumulated body bytes.
     *
     * @param method    the :method pseudo-header value
     * @param path      the :path pseudo-header value (may include query string)
     * @param scheme    the :scheme pseudo-header value (nullable)
     * @param authority the :authority pseudo-header value (nullable)
     * @param headers   list of non-pseudo-header name/value pairs
     * @param body      the accumulated request body bytes (may be empty)
     * @return a fully populated HttpRequest
     */
    public static HttpRequest toHttpRequest(
        String method,
        String path,
        String scheme,
        String authority,
        List<Map.Entry<String, String>> headers,
        byte[] body
    ) {
        // split path and query
        String requestPath = path;
        String queryString = "";
        if (path != null) {
            int queryIndex = path.indexOf('?');
            if (queryIndex >= 0) {
                requestPath = path.substring(0, queryIndex);
                queryString = path.substring(queryIndex + 1);
            }
        }
        if (requestPath == null || requestPath.isEmpty()) {
            requestPath = "/";
        }

        HttpRequest request = HttpRequest.request()
            .withMethod(method != null ? method : "GET")
            .withPath(requestPath)
            .withSecure(true); // HTTP/3 is always over TLS

        if (!queryString.isEmpty()) {
            request.withQueryStringParameters(parseQueryString(queryString));
        }

        // set authority as Host header if present
        if (authority != null && !authority.isEmpty()) {
            request.withHeader("host", authority);
        }

        // add regular headers
        if (headers != null) {
            for (Map.Entry<String, String> header : headers) {
                request.withHeader(header.getKey(), header.getValue());
            }
        }

        // set body -- use string body for text content types so that expectation
        // matching (which compares string bodies) works correctly; use binary body
        // for everything else
        if (body != null && body.length > 0) {
            String contentType = null;
            if (headers != null) {
                for (Map.Entry<String, String> header : headers) {
                    if ("content-type".equalsIgnoreCase(header.getKey())) {
                        contentType = header.getValue();
                        break;
                    }
                }
            }
            if (isTextContentType(contentType)) {
                java.nio.charset.Charset charset = extractCharset(contentType);
                request.withBody(new String(body, charset));
            } else {
                request.withBody(body);
            }
        }

        return request;
    }

    /**
     * Extract pseudo-headers and regular headers from an HTTP/3 headers frame.
     */
    public static ParsedHeaders parseHeaders(Http3HeadersFrame headersFrame) {
        Http3Headers h3Headers = headersFrame.headers();
        String method = charSeqToString(h3Headers.method());
        String path = charSeqToString(h3Headers.path());
        String scheme = charSeqToString(h3Headers.scheme());
        String authority = charSeqToString(h3Headers.authority());

        List<Map.Entry<String, String>> regularHeaders = new ArrayList<>();
        h3Headers.forEach(entry -> {
            String name = entry.getKey().toString();
            // skip pseudo-headers (they start with ':')
            if (!name.startsWith(":")) {
                regularHeaders.add(new AbstractMap.SimpleImmutableEntry<>(name, entry.getValue().toString()));
            }
        });

        return new ParsedHeaders(method, path, scheme, authority, regularHeaders);
    }

    /**
     * Convert a MockServer {@link HttpResponse} into an HTTP/3 headers frame.
     */
    public static DefaultHttp3HeadersFrame toHttp3HeadersFrame(HttpResponse response) {
        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        int statusCode = response.getStatusCode() != null ? response.getStatusCode() : 200;
        headersFrame.headers().status(String.valueOf(statusCode));
        headersFrame.headers().add("server", "mockserver-http3");

        if (response.getHeaderMultimap() != null) {
            response.getHeaderMultimap().entries().forEach(entry -> {
                String name = entry.getKey().getValue().toLowerCase();
                // skip connection-oriented headers that are meaningless in HTTP/3
                if (!"connection".equals(name) && !"transfer-encoding".equals(name)) {
                    headersFrame.headers().add(name, entry.getValue().getValue());
                }
            });
        }

        return headersFrame;
    }

    /**
     * Convert the body of a MockServer {@link HttpResponse} into an HTTP/3 data frame.
     * Returns null if the response has no body.
     */
    public static DefaultHttp3DataFrame toHttp3DataFrame(HttpResponse response) {
        byte[] bodyBytes = response.getBodyAsRawBytes();
        if (bodyBytes == null || bodyBytes.length == 0) {
            return null;
        }
        return new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(bodyBytes));
    }

    /**
     * Accumulate body data from an HTTP/3 data frame into a composite buffer.
     */
    public static void accumulateBody(CompositeByteBuf composite, Http3DataFrame dataFrame) {
        ByteBuf content = dataFrame.content();
        if (content.isReadable()) {
            composite.addComponent(true, content.retain());
        }
    }

    /**
     * Read the accumulated composite buffer into a byte array.
     */
    public static byte[] readAccumulatedBody(CompositeByteBuf composite) {
        if (composite.readableBytes() == 0) {
            return new byte[0];
        }
        byte[] body = new byte[composite.readableBytes()];
        composite.readBytes(body);
        return body;
    }

    /**
     * Determine if the content-type header indicates text content that should be
     * stored as a string body rather than binary.
     */
    private static boolean isTextContentType(String contentType) {
        if (contentType == null || contentType.isEmpty()) {
            // no content-type: assume text to maximise expectation matching compatibility
            return true;
        }
        String lower = contentType.toLowerCase();
        return lower.startsWith("text/")
            || lower.contains("json")
            || lower.contains("xml")
            || lower.contains("html")
            || lower.contains("javascript")
            || lower.contains("yaml")
            || lower.contains("csv")
            || lower.contains("x-www-form-urlencoded");
    }

    /**
     * Extract the charset from a content-type header value, defaulting to UTF-8.
     */
    private static java.nio.charset.Charset extractCharset(String contentType) {
        if (contentType != null) {
            String lower = contentType.toLowerCase();
            int charsetIndex = lower.indexOf("charset=");
            if (charsetIndex >= 0) {
                String charsetName = contentType.substring(charsetIndex + 8).trim();
                // strip quotes and trailing parameters
                if (charsetName.startsWith("\"")) {
                    charsetName = charsetName.substring(1);
                }
                int endIndex = charsetName.indexOf(';');
                if (endIndex >= 0) {
                    charsetName = charsetName.substring(0, endIndex);
                }
                if (charsetName.endsWith("\"")) {
                    charsetName = charsetName.substring(0, charsetName.length() - 1);
                }
                try {
                    return java.nio.charset.Charset.forName(charsetName.trim());
                } catch (Exception ignored) {
                    // fall through to default
                }
            }
        }
        return java.nio.charset.StandardCharsets.UTF_8;
    }

    private static String charSeqToString(CharSequence seq) {
        return seq != null ? seq.toString() : null;
    }

    private static org.mockserver.model.Parameters parseQueryString(String queryString) {
        org.mockserver.model.Parameters parameters = new org.mockserver.model.Parameters();
        if (queryString == null || queryString.isEmpty()) {
            return parameters;
        }
        for (String param : queryString.split("&")) {
            int eqIndex = param.indexOf('=');
            if (eqIndex >= 0) {
                String name = param.substring(0, eqIndex);
                String value = param.substring(eqIndex + 1);
                parameters.withEntry(name, value);
            } else if (!param.isEmpty()) {
                parameters.withEntry(param, "");
            }
        }
        return parameters;
    }

    /**
     * Parsed HTTP/3 pseudo-headers and regular headers.
     */
    public static final class ParsedHeaders {
        private final String method;
        private final String path;
        private final String scheme;
        private final String authority;
        private final List<Map.Entry<String, String>> headers;

        public ParsedHeaders(String method, String path, String scheme, String authority, List<Map.Entry<String, String>> headers) {
            this.method = method;
            this.path = path;
            this.scheme = scheme;
            this.authority = authority;
            this.headers = headers;
        }

        public String method() {
            return method;
        }

        public String path() {
            return path;
        }

        public String scheme() {
            return scheme;
        }

        public String authority() {
            return authority;
        }

        public List<Map.Entry<String, String>> headers() {
            return headers;
        }
    }
}
