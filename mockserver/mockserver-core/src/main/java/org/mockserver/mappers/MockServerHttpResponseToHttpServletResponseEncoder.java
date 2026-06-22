package org.mockserver.mappers;

import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import org.mockserver.codec.BodyServletDecoderEncoder;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Cookie;
import org.mockserver.model.Header;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.NottableString;
import jakarta.servlet.http.HttpServletResponse;

import static io.netty.handler.codec.http.HttpHeaderNames.*;

/**
 * @author jamesdbloom
 */
public class MockServerHttpResponseToHttpServletResponseEncoder {

    private final BodyServletDecoderEncoder bodyDecoderEncoder;

    public MockServerHttpResponseToHttpServletResponseEncoder(MockServerLogger mockServerLogger) {
        bodyDecoderEncoder = new BodyServletDecoderEncoder(mockServerLogger);
    }

    public void mapMockServerResponseToHttpServletResponse(HttpResponse httpResponse, HttpServletResponse httpServletResponse) {
        setStatusCode(httpResponse, httpServletResponse);
        setHeaders(httpResponse, httpServletResponse);
        setTrailers(httpResponse, httpServletResponse);
        setCookies(httpResponse, httpServletResponse);
        setBody(httpResponse, httpServletResponse);
    }

    private void setStatusCode(HttpResponse httpResponse, HttpServletResponse httpServletResponse) {
        int statusCode = httpResponse.getStatusCode() != null ? httpResponse.getStatusCode() : 200;
        // Servlet 6 removed HttpServletResponse.setStatus(int, String); the reason phrase
        // is dictated by the container based on the status code.
        httpServletResponse.setStatus(statusCode);
    }

    private void setHeaders(HttpResponse httpResponse, HttpServletResponse httpServletResponse) {
        if (httpResponse.getHeaderList() != null) {
            for (Header header : httpResponse.getHeaderList()) {
                String headerName = sanitizeHeaderValue(header.getName().getValue());
                if (!headerName.equalsIgnoreCase(CONTENT_LENGTH.toString())
                    && !headerName.equalsIgnoreCase(TRANSFER_ENCODING.toString())
                    && !headerName.equalsIgnoreCase(HOST.toString())
                    && !headerName.equalsIgnoreCase(ACCEPT_ENCODING.toString())
                    && !headerName.equalsIgnoreCase(CONNECTION.toString())) {
                    for (NottableString value : header.getValues()) {
                        httpServletResponse.addHeader(headerName, sanitizeHeaderValue(value.getValue()));
                    }
                }
            }
        }
        addContentTypeHeader(httpResponse, httpServletResponse);
    }

    /**
     * Map response trailers (trailing headers) onto the Servlet response via
     * {@link HttpServletResponse#setTrailerFields}. The container emits them as a chunked
     * trailer section (HTTP/1.1) or a trailing HEADERS frame (HTTP/2). No trailers set leaves
     * the response untouched. The container is responsible for the {@code Trailer} announcing
     * header and chunked framing on this (WAR / Servlet) deployment path.
     * <p>
     * <strong>Servlet API limitation:</strong> {@link HttpServletResponse#setTrailerFields}
     * takes a {@code Supplier<Map<String,String>>} -- a single string value per name -- so it
     * cannot represent multi-valued or duplicate-named trailers the way the HTTP wire format or
     * the Netty path can. To avoid silently dropping values, a multi-valued trailer is joined
     * into one comma-separated value (HTTP list semantics, RFC 9110 section 5.3); duplicate
     * trailer names collapse to the last one written (the {@code Map} key semantics). This is a
     * limitation of the Servlet WAR deployment path only -- the Netty (JAR/Docker) path emits
     * each value separately.
     */
    private void setTrailers(HttpResponse httpResponse, HttpServletResponse httpServletResponse) {
        if (httpResponse.getTrailerList() == null || httpResponse.getTrailerList().isEmpty()) {
            return;
        }
        java.util.Map<String, String> trailerFields = new java.util.LinkedHashMap<>();
        for (Header trailer : httpResponse.getTrailerList()) {
            String name = sanitizeHeaderValue(trailer.getName().getValue());
            // Join multiple values with ", " (HTTP list semantics) rather than dropping all but
            // the first, since the Servlet API only models one string value per trailer name.
            String value = trailer.getValues().stream()
                .map(nottableString -> sanitizeHeaderValue(nottableString.getValue()))
                .collect(java.util.stream.Collectors.joining(", "));
            trailerFields.put(name, value);
        }
        try {
            httpServletResponse.setTrailerFields(() -> trailerFields);
        } catch (IllegalStateException | UnsupportedOperationException ignored) {
            // setTrailerFields throws when the response is committed or the protocol/container
            // does not support trailers -- degrade gracefully (no trailers) on the WAR path.
        }
    }

    private void setCookies(HttpResponse httpResponse, HttpServletResponse httpServletResponse) {
        if (httpResponse.getCookieList() != null) {
            for (Cookie cookie : httpResponse.getCookieList()) {
                if (httpResponse.cookieHeaderDoesNotAlreadyExists(cookie)) {
                    httpServletResponse.addHeader(SET_COOKIE.toString(), ServerCookieEncoder.LAX.encode(new DefaultCookie(cookie.getName().getValue(), cookie.getValue().toString())));
                }
            }
        }
    }

    private void setBody(HttpResponse httpResponse, HttpServletResponse httpServletResponse) {
        bodyDecoderEncoder.bodyToServletResponse(httpServletResponse, httpResponse.getBody(), httpResponse.getFirstHeader(CONTENT_TYPE.toString()));
    }

    private void addContentTypeHeader(HttpResponse httpResponse, HttpServletResponse httpServletResponse) {
        if (httpServletResponse.getContentType() == null
            && httpResponse.getBody() != null
            && httpResponse.getBody().getContentType() != null) {
            httpServletResponse.addHeader(CONTENT_TYPE.toString(), sanitizeHeaderValue(httpResponse.getBody().getContentType()));
        }
    }

    private static String sanitizeHeaderValue(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\r", "").replace("\n", "");
    }
}
