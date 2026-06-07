package org.mockserver.mappers;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http2.HttpConversionUtil;
import org.apache.commons.lang3.Strings;
import org.mockserver.codec.BodyDecoderEncoder;
import org.mockserver.codec.ExpandedParameterDecoder;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.*;
import org.mockserver.url.URLParser;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpUtil.isKeepAlive;

/**
 * @author jamesdbloom
 */
public class FullHttpRequestToMockServerHttpRequest {

    private final MockServerLogger mockServerLogger;
    private final BodyDecoderEncoder bodyDecoderEncoder;
    private final ExpandedParameterDecoder formParameterParser;
    private final boolean isSecure;
    private final Certificate[] clientCertificates;
    private final Integer port;
    private final JDKCertificateToMockServerX509Certificate jdkCertificateToMockServerX509Certificate;

    public FullHttpRequestToMockServerHttpRequest(Configuration configuration, MockServerLogger mockServerLogger, boolean isSecure, Certificate[] clientCertificates, Integer port) {
        this.mockServerLogger = mockServerLogger;
        this.bodyDecoderEncoder = new BodyDecoderEncoder();
        this.formParameterParser = new ExpandedParameterDecoder(configuration, mockServerLogger);
        this.isSecure = isSecure;
        this.clientCertificates = clientCertificates;
        this.port = port;
        this.jdkCertificateToMockServerX509Certificate = new JDKCertificateToMockServerX509Certificate(mockServerLogger);
    }

    public HttpRequest mapFullHttpRequestToMockServerRequest(FullHttpRequest fullHttpRequest, List<Header> preservedHeaders, SocketAddress localAddress, SocketAddress remoteAddress, Protocol protocol) {
        return mapFullHttpRequestToMockServerRequest(fullHttpRequest, preservedHeaders, null, localAddress, remoteAddress, protocol);
    }

    public HttpRequest mapFullHttpRequestToMockServerRequest(FullHttpRequest fullHttpRequest, List<Header> preservedHeaders, byte[] originalRawBody, SocketAddress localAddress, SocketAddress remoteAddress, Protocol protocol) {
        HttpRequest httpRequest = new HttpRequest();
        try {
            if (fullHttpRequest != null) {
                if (fullHttpRequest.decoderResult().isFailure()) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setMessageFormat("exception decoding request " + fullHttpRequest.decoderResult().cause().getMessage())
                            .setThrowable(fullHttpRequest.decoderResult().cause())
                    );
                }
                populateHeadersAndMetadata(httpRequest, fullHttpRequest, preservedHeaders, localAddress, remoteAddress, protocol);
                setBody(httpRequest, fullHttpRequest, originalRawBody);
            }
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception decoding request{}")
                    .setArguments(fullHttpRequest)
                    .setThrowable(throwable)
            );
        }
        return httpRequest;
    }

    /**
     * Map the headers/method/path/query/cookies of a Netty HttpRequest (no body) to a MockServer HttpRequest.
     * Used by the early-response path which dispatches before HttpObjectAggregator buffers the body.
     */
    public HttpRequest mapHeadersOnlyHttpRequestToMockServerRequest(io.netty.handler.codec.http.HttpRequest nettyHttpRequest, List<Header> preservedHeaders, SocketAddress localAddress, SocketAddress remoteAddress, Protocol protocol) {
        HttpRequest httpRequest = new HttpRequest();
        try {
            if (nettyHttpRequest != null) {
                if (nettyHttpRequest.decoderResult().isFailure()) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setMessageFormat("exception decoding request " + nettyHttpRequest.decoderResult().cause().getMessage())
                            .setThrowable(nettyHttpRequest.decoderResult().cause())
                    );
                }
                populateHeadersAndMetadata(httpRequest, nettyHttpRequest, preservedHeaders, localAddress, remoteAddress, protocol);
            }
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception decoding request headers{}")
                    .setArguments(nettyHttpRequest)
                    .setThrowable(throwable)
            );
        }
        return httpRequest;
    }

    private void populateHeadersAndMetadata(HttpRequest httpRequest, io.netty.handler.codec.http.HttpRequest nettyHttpRequest, List<Header> preservedHeaders, SocketAddress localAddress, SocketAddress remoteAddress, Protocol protocol) {
        httpRequest.withMethod(nettyHttpRequest.method().name());
        httpRequest.withKeepAlive(isKeepAlive(nettyHttpRequest));
        httpRequest.withSecure(isSecure);
        httpRequest.withProtocol(protocol == null ? Protocol.HTTP_1_1 : protocol);
        httpRequest.withPath(URLParser.returnPath(nettyHttpRequest.uri()));
        if (nettyHttpRequest.uri().contains("?")) {
            httpRequest.withQueryStringParameters(formParameterParser.retrieveQueryParameters(nettyHttpRequest.uri(), true));
        }
        setHeadersFromNettyRequest(httpRequest, nettyHttpRequest, preservedHeaders);
        setCookiesFromNettyRequest(httpRequest, nettyHttpRequest);
        setSocketAddressFromNettyRequest(httpRequest, nettyHttpRequest, localAddress, remoteAddress);
        jdkCertificateToMockServerX509Certificate.setClientCertificates(httpRequest, clientCertificates);
    }

    private void setHeadersFromNettyRequest(HttpRequest httpRequest, io.netty.handler.codec.http.HttpRequest nettyHttpRequest, List<Header> preservedHeaders) {
        boolean hasPreservedTransferEncoding = false;
        if (preservedHeaders != null) {
            for (Header preservedHeader : preservedHeaders) {
                if (TRANSFER_ENCODING.toString().equalsIgnoreCase(preservedHeader.getName().getValue())) {
                    hasPreservedTransferEncoding = true;
                    break;
                }
            }
        }
        HttpHeaders httpHeaders = nettyHttpRequest.headers();
        if (!httpHeaders.isEmpty()) {
            Headers headers = new Headers();
            for (String headerName : httpHeaders.names()) {
                if (hasPreservedTransferEncoding && headerName.equalsIgnoreCase(CONTENT_LENGTH.toString())) {
                    continue;
                }
                headers.withEntry(headerName, httpHeaders.getAll(headerName));
            }
            httpRequest.withHeaders(headers);
        }
        if (preservedHeaders != null && !preservedHeaders.isEmpty()) {
            for (Header preservedHeader : preservedHeaders) {
                // only re-add a preserved header if it was actually removed downstream (i.e. it is no
                // longer present in the live request headers); otherwise it would be duplicated — e.g.
                // when request decompression is disabled the Content-Encoding header is never stripped
                if (!httpHeaders.contains(preservedHeader.getName().getValue())) {
                    httpRequest.withHeader(preservedHeader);
                }
            }
        }
        if (Protocol.HTTP_2.equals(httpRequest.getProtocol())) {
            Integer streamId = nettyHttpRequest.headers().getInt(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text());
            httpRequest.withStreamId(streamId);
        }
    }

    private void setCookiesFromNettyRequest(HttpRequest httpRequest, io.netty.handler.codec.http.HttpRequest nettyHttpRequest) {
        List<String> cookieHeaders = nettyHttpRequest.headers().getAll(COOKIE);
        if (!cookieHeaders.isEmpty()) {
            Cookies cookies = new Cookies();
            for (String cookieHeader : cookieHeaders) {
                Set<Cookie> decodedCookies = ServerCookieDecoder.LAX.decode(cookieHeader);
                for (io.netty.handler.codec.http.cookie.Cookie decodedCookie : decodedCookies) {
                    cookies.withEntry(
                        decodedCookie.name(),
                        decodedCookie.value()
                    );
                }
            }
            httpRequest.withCookies(cookies);
        }
    }

    private void setSocketAddressFromNettyRequest(HttpRequest httpRequest, io.netty.handler.codec.http.HttpRequest nettyHttpRequest, SocketAddress localAddress, SocketAddress remoteAddress) {
        httpRequest.withSocketAddress(isSecure, nettyHttpRequest.headers().get("host"), port);
        if (remoteAddress instanceof InetSocketAddress) {
            httpRequest.withRemoteAddress(Strings.CS.removeStart(remoteAddress.toString(), "/"));
        }
        if (localAddress instanceof InetSocketAddress) {
            httpRequest.withLocalAddress(Strings.CS.removeStart(localAddress.toString(), "/"));
        }
    }

    private void setBody(HttpRequest httpRequest, FullHttpRequest fullHttpRequest, byte[] originalRawBody) {
        ByteBuf content = fullHttpRequest.content();
        byte[] decompressedBytes = null;
        if (content != null && content.readableBytes() > 0) {
            decompressedBytes = new byte[content.readableBytes()];
            // non-destructive read so byteBufToBody can still consume the content below
            content.getBytes(content.readerIndex(), decompressedBytes);
        }
        httpRequest.withBody(bodyDecoderEncoder.byteBufToBody(content, fullHttpRequest.headers().get(CONTENT_TYPE)));
        // retain the original on-the-wire bytes only when the body was actually compressed (i.e. the
        // captured bytes differ from the decompressed body), so getBodyAsOriginalRawBytes() returns what
        // the client sent and a BinaryBody expectation can match the compressed payload
        if (originalRawBody != null && originalRawBody.length > 0 && !Arrays.equals(originalRawBody, decompressedBytes)) {
            httpRequest.withOriginalBody(originalRawBody);
        }
    }
}
