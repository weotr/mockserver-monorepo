package org.mockserver.mappers;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Cookie;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Protocol;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.COOKIE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.NottableString.string;

/**
 * @author jamesdbloom
 */
public class FullHttpRequestToMockServerHttpRequestTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger();

    private FullHttpRequestToMockServerHttpRequest createMapper(boolean isSecure, Integer port) {
        return new FullHttpRequestToMockServerHttpRequest(configuration(), mockServerLogger, isSecure, null, port);
    }

    // --- method mapping ---

    @Test
    public void shouldMapGetMethod() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/some/path");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getMethod(""), equalTo("GET"));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapPostMethod() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/some/path");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getMethod(""), equalTo("POST"));
        } finally {
            nettyRequest.release();
        }
    }

    // --- URI / path mapping ---

    @Test
    public void shouldMapPath() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/some/path");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getPath(), equalTo(string("/some/path")));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapPathWithQueryString() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
            "/some/path?paramOne=valueOne&paramTwo=valueTwo");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getPath(), equalTo(string("/some/path")));
            assertThat(result.getQueryStringParameterList(), hasSize(2));
        } finally {
            nettyRequest.release();
        }
    }

    // --- headers ---

    @Test
    public void shouldMapHeaders() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        nettyRequest.headers().add("headerName1", "headerValue1");
        nettyRequest.headers().add("headerName2", "headerValue2");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getFirstHeader("headerName1"), equalTo("headerValue1"));
            assertThat(result.getFirstHeader("headerName2"), equalTo("headerValue2"));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapMultipleHeaderValues() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        nettyRequest.headers().add("headerName1", "headerValue1_1");
        nettyRequest.headers().add("headerName1", "headerValue1_2");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            List<Header> headers = result.getHeaderList();
            boolean found = false;
            for (Header h : headers) {
                if (h.getName().getValue().equals("headerName1")) {
                    assertThat(h.getValues().size(), equalTo(2));
                    found = true;
                }
            }
            assertThat("headerName1 should be present", found, is(true));
        } finally {
            nettyRequest.release();
        }
    }

    // --- cookies ---

    @Test
    public void shouldMapCookies() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        nettyRequest.headers().add(COOKIE, "cookieName1=cookieValue1; cookieName2=cookieValue2");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            List<Cookie> cookies = result.getCookieList();
            assertThat(cookies, hasSize(2));
            assertThat(cookies, hasItem(new Cookie("cookieName1", "cookieValue1")));
            assertThat(cookies, hasItem(new Cookie("cookieName2", "cookieValue2")));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldHandleNoCookies() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getCookieList(), empty());
        } finally {
            nettyRequest.release();
        }
    }

    // --- body ---

    @Test
    public void shouldMapTextBody() {
        // given
        byte[] bodyBytes = "some body content".getBytes(StandardCharsets.UTF_8);
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.POST, "/path",
            Unpooled.copiedBuffer(bodyBytes)
        );
        nettyRequest.headers().set(CONTENT_TYPE, "text/plain; charset=utf-8");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getBodyAsString(), equalTo("some body content"));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapBinaryBody() {
        // given
        byte[] binaryData = new byte[]{0x00, 0x01, 0x02, 0x03, 0x7F};
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.POST, "/path",
            Unpooled.copiedBuffer(binaryData)
        );
        nettyRequest.headers().set(CONTENT_TYPE, "application/octet-stream");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getBodyAsRawBytes(), equalTo(binaryData));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapEmptyBody() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getBodyAsRawBytes(), equalTo(new byte[0]));
        } finally {
            nettyRequest.release();
        }
    }

    // --- secure / keep-alive / protocol ---

    @Test
    public void shouldMapSecureFlag() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");

        try {
            // when
            HttpRequest result = createMapper(true, 443)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.isSecure(), is(true));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapNonSecureFlag() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.isSecure(), is(false));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapKeepAlive() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        nettyRequest.headers().set(CONNECTION, "keep-alive");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.isKeepAlive(), is(true));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapProtocol() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_2);

            // then
            assertThat(result.getProtocol(), equalTo(Protocol.HTTP_2));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldDefaultToHTTP11WhenProtocolIsNull() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, null);

            // then
            assertThat(result.getProtocol(), equalTo(Protocol.HTTP_1_1));
        } finally {
            nettyRequest.release();
        }
    }

    // --- socket addresses ---

    @Test
    public void shouldMapRemoteAddress() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        InetSocketAddress remoteAddress = new InetSocketAddress("192.168.1.100", 54321);

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, remoteAddress, Protocol.HTTP_1_1);

            // then
            assertThat(result.getRemoteAddress(), containsString("192.168.1.100"));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapLocalAddress() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        InetSocketAddress localAddress = new InetSocketAddress("127.0.0.1", 8080);

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, localAddress, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getLocalAddress(), containsString("127.0.0.1"));
        } finally {
            nettyRequest.release();
        }
    }

    // --- null request ---

    @Test
    public void shouldHandleNullRequest() {
        // when
        HttpRequest result = createMapper(false, 80)
            .mapFullHttpRequestToMockServerRequest(null, null, null, null, Protocol.HTTP_1_1);

        // then
        assertThat(result, is(notNullValue()));
    }

    // --- preserved headers ---

    @Test
    public void shouldAddPreservedHeaders() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        nettyRequest.headers().add("existingHeader", "existingValue");
        List<Header> preservedHeaders = Collections.singletonList(new Header("preservedHeader", "preservedValue"));

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, preservedHeaders, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getFirstHeader("preservedHeader"), equalTo("preservedValue"));
            assertThat(result.getFirstHeader("existingHeader"), equalTo("existingValue"));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldNotDuplicatePreservedHeaderAlreadyInLiveHeaders() {
        // given - the preserved header is still present in the live request headers (e.g. when
        // request decompression is disabled, Content-Encoding is never stripped by Netty)
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        nettyRequest.headers().add("Content-Encoding", "gzip");
        List<Header> preservedHeaders = Collections.singletonList(new Header("content-encoding", "gzip"));

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, preservedHeaders, null, null, Protocol.HTTP_1_1);

            // then - the header appears exactly once, not duplicated
            assertThat(result.getHeader("Content-Encoding"), equalTo(Collections.singletonList("gzip")));
        } finally {
            nettyRequest.release();
        }
    }

    // --- headers-only mapping ---

    @Test
    public void shouldMapHeadersOnlyRequest() {
        // given
        io.netty.handler.codec.http.HttpRequest nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path?key=value");
        nettyRequest.headers().add("headerName", "headerValue");
        nettyRequest.headers().add(COOKIE, "cookieName=cookieValue");

        // when
        HttpRequest result = createMapper(false, 80)
            .mapHeadersOnlyHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

        // then
        assertThat(result.getMethod(""), equalTo("GET"));
        assertThat(result.getPath(), equalTo(string("/path")));
        assertThat(result.getFirstHeader("headerName"), equalTo("headerValue"));
        assertThat(result.getCookieList(), hasSize(1));
        assertThat(result.getQueryStringParameterList(), hasSize(1));
    }

    @Test
    public void shouldHandleNullHeadersOnlyRequest() {
        // when
        HttpRequest result = createMapper(false, 80)
            .mapHeadersOnlyHttpRequestToMockServerRequest(null, null, null, null, Protocol.HTTP_1_1);

        // then
        assertThat(result, is(notNullValue()));
    }
}
