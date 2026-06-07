package org.mockserver.netty.grpc;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Test;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcHealthCheckHandler;
import org.mockserver.grpc.GrpcHealthRegistry;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.grpc.GrpcWebTranslator;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.action.http.GrpcChaosRegistry;
import org.mockserver.mock.action.http.HttpQuotaRegistry;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;

/**
 * Handler-level tests for gRPC-Web support.
 * <p>
 * Verifies that gRPC-Web requests (binary and text variants) are translated to standard
 * gRPC, processed by the existing pipeline, and that responses are correctly re-framed
 * as gRPC-Web with trailers embedded in the body.
 * <p>
 * Uses {@link EmbeddedChannel} to test the Netty handler pipeline without a live server.
 */
public class GrpcWebHandlerTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger();
    private final GrpcChaosRegistry chaosRegistry = new GrpcChaosRegistry(System::currentTimeMillis);

    @After
    public void tearDown() {
        chaosRegistry.reset();
    }

    /**
     * Creates a channel with both GrpcToHttpResponseHandler (outbound) and
     * GrpcToHttpRequestHandler (inbound), mirroring the real pipeline order.
     */
    private EmbeddedChannel channelWithGrpcHandlers() {
        GrpcProtoDescriptorStore descriptorStore = new GrpcProtoDescriptorStore(mockServerLogger);
        GrpcToHttpResponseHandler responseHandler = new GrpcToHttpResponseHandler(mockServerLogger, descriptorStore);
        GrpcToHttpRequestHandler requestHandler = new GrpcToHttpRequestHandler(
            mockServerLogger, descriptorStore,
            new GrpcHealthCheckHandler(GrpcHealthRegistry.getInstance()),
            chaosRegistry, HttpQuotaRegistry.getInstance()
        );
        return new EmbeddedChannel(responseHandler, requestHandler);
    }

    // ---- Request translation ----

    @Test
    public void shouldTranslateGrpcWebBinaryRequestToStandardGrpc() {
        EmbeddedChannel channel = channelWithGrpcHandlers();
        byte[] grpcFrame = GrpcFrameCodec.encode("test message".getBytes());

        HttpRequest request = request()
            .withMethod("POST")
            .withPath("/com.example.Service/Method")
            .withHeader("content-type", "application/grpc-web")
            .withBody(grpcFrame);

        channel.writeInbound(request);

        // Request should pass through (no descriptor loaded, so no conversion)
        HttpRequest passedThrough = channel.readInbound();
        assertThat(passedThrough, is(notNullValue()));
        // content-type should be translated to standard gRPC
        assertThat(passedThrough.getFirstHeader("content-type"), is(GrpcStatusMapper.GRPC_CONTENT_TYPE));
        // original content-type preserved for response handler
        assertThat(passedThrough.getFirstHeader("x-grpc-web-content-type"), is("application/grpc-web"));
    }

    @Test
    public void shouldTranslateGrpcWebTextRequestByBase64Decoding() {
        EmbeddedChannel channel = channelWithGrpcHandlers();
        byte[] grpcFrame = GrpcFrameCodec.encode("test message".getBytes());
        byte[] base64Body = Base64.getEncoder().encode(grpcFrame);

        HttpRequest request = request()
            .withMethod("POST")
            .withPath("/com.example.Service/Method")
            .withHeader("content-type", "application/grpc-web-text")
            .withBody(base64Body);

        channel.writeInbound(request);

        HttpRequest passedThrough = channel.readInbound();
        assertThat(passedThrough, is(notNullValue()));
        assertThat(passedThrough.getFirstHeader("content-type"), is(GrpcStatusMapper.GRPC_CONTENT_TYPE));
        assertThat(passedThrough.getFirstHeader("x-grpc-web-content-type"), is("application/grpc-web-text"));
        // Body should be the original gRPC frame (base64-decoded)
        assertThat(passedThrough.getBodyAsRawBytes(), is(grpcFrame));
    }

    @Test
    public void shouldNotAffectStandardGrpcRequests() {
        EmbeddedChannel channel = channelWithGrpcHandlers();
        byte[] grpcFrame = GrpcFrameCodec.encode("test".getBytes());

        HttpRequest request = request()
            .withMethod("POST")
            .withPath("/com.example.Service/Method")
            .withHeader("content-type", "application/grpc")
            .withBody(grpcFrame);

        channel.writeInbound(request);

        HttpRequest passedThrough = channel.readInbound();
        assertThat(passedThrough, is(notNullValue()));
        assertThat(passedThrough.getFirstHeader("content-type"), is("application/grpc"));
        assertThat(passedThrough.getFirstHeader("x-grpc-web-content-type"), is(""));
    }

    @Test
    public void shouldNotAffectNonGrpcRequests() {
        EmbeddedChannel channel = channelWithGrpcHandlers();

        HttpRequest request = request()
            .withMethod("POST")
            .withPath("/api/users")
            .withHeader("content-type", "application/json")
            .withBody("{\"name\": \"test\"}");

        channel.writeInbound(request);

        HttpRequest passedThrough = channel.readInbound();
        assertThat(passedThrough, is(notNullValue()));
        assertThat(passedThrough.getFirstHeader("content-type"), is("application/json"));
    }

    // ---- Response translation (gRPC-Web binary) ----

    @Test
    public void shouldConvertDirectResponseToGrpcWebBinary() {
        EmbeddedChannel channel = channelWithGrpcHandlers();

        // Simulate a direct response (e.g., health check) tagged for gRPC-Web
        HttpResponse response = HttpResponse.response()
            .withStatusCode(200)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "0")
            .withHeader("x-grpc-web-content-type", "application/grpc-web")
            .withBody(GrpcFrameCodec.encode("health ok".getBytes()));

        channel.writeOutbound(response);

        HttpResponse result = channel.readOutbound();
        assertThat(result, is(notNullValue()));
        // Content-type should be gRPC-Web
        assertThat(result.getFirstHeader("content-type"), is("application/grpc-web"));
        // grpc-status should NOT be in HTTP headers (moved to trailer frame in body)
        assertThat(result.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER), is(""));
        // x-grpc-web-content-type marker should be removed
        assertThat(result.getFirstHeader("x-grpc-web-content-type"), is(""));

        // Verify body contains message frame + trailer frame
        byte[] body = result.getBodyAsRawBytes();
        assertThat(body, is(notNullValue()));
        ByteBuffer buf = ByteBuffer.wrap(body);
        // message frame
        byte flag1 = buf.get();
        assertThat(flag1 & 0x80, is(0));
        int len1 = buf.getInt();
        byte[] msg = new byte[len1];
        buf.get(msg);
        assertThat(msg, is("health ok".getBytes()));
        // trailer frame
        byte flag2 = buf.get();
        assertThat(flag2, is(GrpcWebTranslator.TRAILER_FLAG));
        int len2 = buf.getInt();
        byte[] trailerBody = new byte[len2];
        buf.get(trailerBody);
        String trailerText = new String(trailerBody, StandardCharsets.US_ASCII);
        assertThat(trailerText, containsString("grpc-status: 0\r\n"));
    }

    // ---- Response translation (gRPC-Web text) ----

    @Test
    public void shouldConvertDirectResponseToGrpcWebText() {
        EmbeddedChannel channel = channelWithGrpcHandlers();

        HttpResponse response = HttpResponse.response()
            .withStatusCode(200)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "0")
            .withHeader("x-grpc-web-content-type", "application/grpc-web-text")
            .withBody(GrpcFrameCodec.encode("text response".getBytes()));

        channel.writeOutbound(response);

        HttpResponse result = channel.readOutbound();
        assertThat(result, is(notNullValue()));
        assertThat(result.getFirstHeader("content-type"), is("application/grpc-web-text"));

        // Body should be base64-encoded
        byte[] body = result.getBodyAsRawBytes();
        byte[] decoded = Base64.getDecoder().decode(body);

        ByteBuffer buf = ByteBuffer.wrap(decoded);
        // message frame
        byte flag1 = buf.get();
        assertThat(flag1 & 0x80, is(0));
        int len1 = buf.getInt();
        byte[] msg = new byte[len1];
        buf.get(msg);
        assertThat(msg, is("text response".getBytes()));
        // trailer frame
        byte flag2 = buf.get();
        assertThat(flag2, is(GrpcWebTranslator.TRAILER_FLAG));
    }

    // ---- Error status mapping ----

    @Test
    public void shouldMapErrorStatusInGrpcWebResponse() {
        EmbeddedChannel channel = channelWithGrpcHandlers();

        HttpResponse response = HttpResponse.response()
            .withStatusCode(200)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "5")
            .withHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER, "not found")
            .withHeader("x-grpc-web-content-type", "application/grpc-web");

        channel.writeOutbound(response);

        HttpResponse result = channel.readOutbound();
        assertThat(result, is(notNullValue()));

        byte[] body = result.getBodyAsRawBytes();
        ByteBuffer buf = ByteBuffer.wrap(body);
        // only trailer frame (no message body)
        byte flag = buf.get();
        assertThat(flag, is(GrpcWebTranslator.TRAILER_FLAG));
        int len = buf.getInt();
        byte[] trailerBody = new byte[len];
        buf.get(trailerBody);
        String trailerText = new String(trailerBody, StandardCharsets.US_ASCII);
        assertThat(trailerText, containsString("grpc-status: 5\r\n"));
        assertThat(trailerText, containsString("grpc-message: not found\r\n"));
    }

    @Test
    public void shouldMapErrorStatusInGrpcWebTextResponse() {
        EmbeddedChannel channel = channelWithGrpcHandlers();

        HttpResponse response = HttpResponse.response()
            .withStatusCode(200)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "13")
            .withHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER, "internal error")
            .withHeader("x-grpc-web-content-type", "application/grpc-web-text");

        channel.writeOutbound(response);

        HttpResponse result = channel.readOutbound();
        assertThat(result.getFirstHeader("content-type"), is("application/grpc-web-text"));

        byte[] decoded = Base64.getDecoder().decode(result.getBodyAsRawBytes());
        ByteBuffer buf = ByteBuffer.wrap(decoded);
        byte flag = buf.get();
        assertThat(flag, is(GrpcWebTranslator.TRAILER_FLAG));
        int len = buf.getInt();
        byte[] trailerBody = new byte[len];
        buf.get(trailerBody);
        String trailerText = new String(trailerBody, StandardCharsets.US_ASCII);
        assertThat(trailerText, containsString("grpc-status: 13\r\n"));
        assertThat(trailerText, containsString("grpc-message: internal error\r\n"));
    }

    // ---- Response without gRPC-Web marker passes through unchanged ----

    @Test
    public void shouldPassThroughResponseWithoutGrpcWebMarker() {
        EmbeddedChannel channel = channelWithGrpcHandlers();

        HttpResponse response = HttpResponse.response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{\"result\": \"ok\"}");

        channel.writeOutbound(response);

        HttpResponse result = channel.readOutbound();
        assertThat(result, is(notNullValue()));
        assertThat(result.getFirstHeader("content-type"), is("application/json"));
        assertThat(result.getBodyAsString(), is("{\"result\": \"ok\"}"));
    }

    // ---- Health check via gRPC-Web ----

    @Test
    public void shouldHandleHealthCheckViaGrpcWebBinary() {
        EmbeddedChannel channel = channelWithGrpcHandlers();

        // Health check request body: empty service name (field 1 = empty string)
        // gRPC-framed empty protobuf message
        byte[] healthFrame = GrpcFrameCodec.encode(new byte[0]);

        HttpRequest request = request()
            .withMethod("POST")
            .withPath("/grpc.health.v1.Health/Check")
            .withHeader("content-type", "application/grpc-web")
            .withBody(healthFrame);

        channel.writeInbound(request);

        // Health check response should be written as gRPC-Web
        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        assertThat(response.getFirstHeader("content-type"), is("application/grpc-web"));
        // grpc-status should be in body trailer, not headers
        assertThat(response.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER), is(""));

        // Verify body has message frame + trailer frame
        byte[] body = response.getBodyAsRawBytes();
        ByteBuffer buf = ByteBuffer.wrap(body);
        // should have at least one frame
        assertThat(buf.remaining() >= 5, is(true));
        // find the trailer frame
        while (buf.remaining() >= 5) {
            byte flag = buf.get();
            int len = buf.getInt();
            if ((flag & 0x80) != 0) {
                // trailer frame
                byte[] trailerBody = new byte[len];
                buf.get(trailerBody);
                String trailerText = new String(trailerBody, StandardCharsets.US_ASCII);
                assertThat(trailerText, containsString("grpc-status: 0\r\n"));
                return;
            }
            buf.position(buf.position() + len);
        }
        throw new AssertionError("no trailer frame found in gRPC-Web response");
    }

    @Test
    public void shouldHandleHealthCheckViaGrpcWebText() {
        EmbeddedChannel channel = channelWithGrpcHandlers();

        byte[] healthFrame = GrpcFrameCodec.encode(new byte[0]);
        byte[] base64Body = Base64.getEncoder().encode(healthFrame);

        HttpRequest request = request()
            .withMethod("POST")
            .withPath("/grpc.health.v1.Health/Check")
            .withHeader("content-type", "application/grpc-web-text")
            .withBody(base64Body);

        channel.writeInbound(request);

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        assertThat(response.getFirstHeader("content-type"), is("application/grpc-web-text"));

        // Body should be base64-encoded
        byte[] body = response.getBodyAsRawBytes();
        byte[] decoded = Base64.getDecoder().decode(body);

        ByteBuffer buf = ByteBuffer.wrap(decoded);
        // find the trailer frame
        while (buf.remaining() >= 5) {
            byte flag = buf.get();
            int len = buf.getInt();
            if ((flag & 0x80) != 0) {
                byte[] trailerBody = new byte[len];
                buf.get(trailerBody);
                String trailerText = new String(trailerBody, StandardCharsets.US_ASCII);
                assertThat(trailerText, containsString("grpc-status: 0\r\n"));
                return;
            }
            buf.position(buf.position() + len);
        }
        throw new AssertionError("no trailer frame found in gRPC-Web-text response");
    }

    // ---- gRPC-Web+proto content type ----

    @Test
    public void shouldHandleGrpcWebProtoContentType() {
        EmbeddedChannel channel = channelWithGrpcHandlers();
        byte[] grpcFrame = GrpcFrameCodec.encode("proto message".getBytes());

        HttpRequest request = request()
            .withMethod("POST")
            .withPath("/com.example.Service/Method")
            .withHeader("content-type", "application/grpc-web+proto")
            .withBody(grpcFrame);

        channel.writeInbound(request);

        HttpRequest passedThrough = channel.readInbound();
        assertThat(passedThrough, is(notNullValue()));
        assertThat(passedThrough.getFirstHeader("content-type"), is(GrpcStatusMapper.GRPC_CONTENT_TYPE));
        assertThat(passedThrough.getFirstHeader("x-grpc-web-content-type"), is("application/grpc-web+proto"));
    }
}
