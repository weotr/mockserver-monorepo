package org.mockserver.testing.integration.mock;

import org.apache.commons.io.IOUtils;
import org.junit.Test;
import org.mockserver.model.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static junit.framework.TestCase.assertEquals;
import static org.junit.Assume.assumeTrue;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.matchers.Times.exactly;
import static org.mockserver.model.BinaryBody.binary;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpStatusCode.OK_200;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.model.ParameterBody.params;
import static org.mockserver.model.Parameter.param;
import static org.mockserver.model.XPathBody.xpath;

/**
 * Explicit per-transport decode-smoke integration tests.
 * <p>
 * Each test creates an expectation keyed on <em>decoded</em> content and
 * verifies that a request carrying that encoding is matched correctly on
 * the current transport.  Because this class sits between
 * {@link AbstractMockingIntegrationTestBase} and
 * {@link AbstractBasicMockingIntegrationTest} in the inheritance chain,
 * every concrete transport subclass inherits and runs these ~7 methods,
 * forming an explicit, maintainable per-transport decode-coverage contract.
 * <p>
 * This intentionally overlaps with scattered encoding assertions in the
 * extended test classes (belt-and-suspenders).  The overlap is deliberate:
 * these named methods form a single discoverable contract so a future
 * change cannot silently drop a decode path.
 * <p>
 * Introduced as the safety net promised in integration-test decomposition
 * step #4 (commit a32ab7699 moved 28 control-plane methods to run once).
 */
public abstract class AbstractTransportDecodeSmokeIntegrationTest extends AbstractMockingIntegrationTestBase {

    /**
     * Whether this transport decompresses gzip/deflate request bodies.
     * Netty transports return {@code true} (HttpContentDecompressor handles it);
     * WAR/servlet transports return {@code false} (Tomcat does not decompress
     * request bodies).  Override in subclasses that lack request-body
     * decompression support.
     */
    protected boolean supportsRequestBodyDecompression() {
        return true;
    }

    // ========================================================================
    // (a) JSON body decode
    // ========================================================================

    @Test
    public void decodeSmoke_jsonBody() {
        // when — expectation keyed on JSON body structure
        mockServerClient
            .when(
                request()
                    .withBody(json("{" + NEW_LINE +
                        "    \"id\": 1," + NEW_LINE +
                        "    \"name\": \"A green door\"" + NEW_LINE +
                        "}")),
                exactly(1)
            )
            .respond(
                response()
                    .withBody("json_decoded")
            );

        // then — request with JSON body must match
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("json_decoded"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody("{" + NEW_LINE +
                        "    \"id\": 1," + NEW_LINE +
                        "    \"name\": \"A green door\"" + NEW_LINE +
                        "}"),
                getHeadersToRemove()
            )
        );
    }

    // ========================================================================
    // (b) XML body via XPath decode
    // ========================================================================

    @Test
    public void decodeSmoke_xmlBodyViaXPath() {
        // when — expectation keyed on XPath match
        mockServerClient
            .when(
                request()
                    .withBody(xpath("/bookstore/book[price>30]/price")),
                exactly(1)
            )
            .respond(
                response()
                    .withBody("xpath_decoded")
            );

        // then — XML body satisfying XPath must match
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("xpath_decoded"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + NEW_LINE +
                        "<bookstore>" + NEW_LINE +
                        "  <book category=\"WEB\">" + NEW_LINE +
                        "    <title lang=\"en\">Learning XML</title>" + NEW_LINE +
                        "    <price>31.95</price>" + NEW_LINE +
                        "  </book>" + NEW_LINE +
                        "</bookstore>"),
                getHeadersToRemove()
            )
        );
    }

    // ========================================================================
    // (c) Binary body (PNG) decode
    // ========================================================================

    @Test
    @SuppressWarnings("ConstantConditions")
    public void decodeSmoke_binaryPngBody() throws IOException {
        // when — expectation keyed on binary PNG content
        byte[] pngBytes = IOUtils.toByteArray(getClass().getClassLoader().getResourceAsStream("test.png"));
        mockServerClient
            .when(
                request()
                    .withBody(binary(pngBytes, MediaType.ANY_IMAGE_TYPE)),
                exactly(1)
            )
            .respond(
                response()
                    .withBody("binary_png_decoded")
            );

        // then — request carrying same PNG bytes must match
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("binary_png_decoded"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody(binary(pngBytes, MediaType.ANY_IMAGE_TYPE)),
                getHeadersToRemove()
            )
        );
    }

    // ========================================================================
    // (d) Form / parameter body decode
    // ========================================================================

    @Test
    public void decodeSmoke_parameterBody() {
        // when — expectation keyed on form parameters
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_path"))
                    .withBody(params(
                        param("paramOne", "valueOne"),
                        param("paramTwo", "valueTwo")
                    )),
                exactly(1)
            )
            .respond(
                response()
                    .withBody("params_decoded")
            );

        // then — request with URL-encoded form body must match
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("params_decoded"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_path"))
                    .withBody(params(
                        param("paramOne", "valueOne"),
                        param("paramTwo", "valueTwo")
                    )),
                getHeadersToRemove()
            )
        );
    }

    // ========================================================================
    // (e) Multi-value header decode
    // ========================================================================

    @Test
    public void decodeSmoke_multiValueHeader() {
        // when — expectation keyed on multi-value header
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some_path"))
                    .withHeader("X-Decode-Smoke", "valueOne", "valueTwo"),
                exactly(1)
            )
            .respond(
                response()
                    .withBody("multi_header_decoded")
            );

        // then — request with multi-value header must match
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("multi_header_decoded"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withHeader("X-Decode-Smoke", "valueOne", "valueTwo"),
                getHeadersToRemove()
            )
        );
    }

    // ========================================================================
    // (f) Content-Encoding: gzip decompression decode path
    // ========================================================================

    @Test
    public void decodeSmoke_contentEncodingGzip() {
        // WAR/servlet transports do not decompress request bodies — skip there
        assumeTrue("transport does not support request body decompression",
            supportsRequestBodyDecompression());

        // Expectation keyed on the DECOMPRESSED (plaintext) body content.
        // The test client sends a request with Content-Encoding: gzip, and
        // MockServerHttpRequestToFullHttpRequest gzip-compresses the body on
        // the wire.  The server-side decompression path (Netty
        // HttpContentDecompressor or WAR servlet filter) must decompress it
        // back to plaintext for the body matcher to succeed.  If decompression
        // were broken the server would see raw gzip bytes, NOT the plaintext,
        // and this expectation would not match — the test would fail.
        String plainTextBody = "gzip decompression smoke test body";

        // when — expectation keyed on decompressed body
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some_path"))
                    .withBody(plainTextBody),
                exactly(1)
            )
            .respond(
                response()
                    .withBody("gzip_body_decompressed")
            );

        // then — request with Content-Encoding: gzip and plaintext body
        // (harness gzip-compresses the bytes on the wire) must match
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("gzip_body_decompressed"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_path"))
                    .withHeader(CONTENT_ENCODING.toString(), "gzip")
                    .withBody(plainTextBody),
                getHeadersToRemove()
            )
        );
    }

    // ========================================================================
    // (g) Non-UTF8 charset (UTF-16) body decode
    // ========================================================================

    @Test
    public void decodeSmoke_utf16Body() {
        // when — expectation keyed on UTF-16 encoded body
        String body = "我说中国话"; // 我说中国话
        mockServerClient
            .when(
                request()
                    .withBody(body, StandardCharsets.UTF_16),
                exactly(1)
            )
            .respond(
                response()
                    .withBody(body, StandardCharsets.UTF_8)
            );

        // then — request with UTF-16 body must decode and match
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeader(CONTENT_TYPE.toString(), MediaType.PLAIN_TEXT_UTF_8.toString())
                .withBody(body, MediaType.PLAIN_TEXT_UTF_8),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withBody(body, StandardCharsets.UTF_16),
                getHeadersToRemove()
            )
        );
    }
}
