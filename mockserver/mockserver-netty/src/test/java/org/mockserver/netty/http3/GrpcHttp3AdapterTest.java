package org.mockserver.netty.http3;

import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.BinaryBody;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.nio.file.Paths;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Unit tests for {@link GrpcHttp3Adapter}.
 * These do NOT require native QUIC -- they test the request/response transformation
 * and frame-building logic in isolation.
 */
public class GrpcHttp3AdapterTest {

    private GrpcProtoDescriptorStore descriptorStore;
    private GrpcJsonMessageConverter converter;

    @Before
    public void setUp() {
        descriptorStore = new GrpcProtoDescriptorStore(new MockServerLogger());
        descriptorStore.loadDescriptorSetFromPath(
            Paths.get("../mockserver-core/src/test/resources/grpc/greeting.dsc")
        );
        converter = descriptorStore.getConverter();
    }

    // ---- isGrpcRequest ----

    @Test
    public void shouldDetectGrpcContentType() {
        assertThat(GrpcHttp3Adapter.isGrpcRequest("application/grpc"), is(true));
        assertThat(GrpcHttp3Adapter.isGrpcRequest("application/grpc+proto"), is(true));
        assertThat(GrpcHttp3Adapter.isGrpcRequest("application/grpc+json"), is(true));
    }

    @Test
    public void shouldNotDetectNonGrpcContentType() {
        assertThat(GrpcHttp3Adapter.isGrpcRequest("application/json"), is(false));
        assertThat(GrpcHttp3Adapter.isGrpcRequest("text/plain"), is(false));
        assertThat(GrpcHttp3Adapter.isGrpcRequest(null), is(false));
        assertThat(GrpcHttp3Adapter.isGrpcRequest("application/grpc-web"), is(false));
    }

    // ---- transformGrpcRequest ----

    @Test
    public void shouldTransformGrpcRequestToJson() {
        // build a gRPC-framed protobuf HelloRequest
        byte[] protobuf = converter.toProtobuf(
            "{\"name\":\"Alice\"}",
            descriptorStore.getMethod("com.example.grpc.GreetingService", "Greeting").getInputType()
        );
        byte[] grpcFrame = GrpcFrameCodec.encode(protobuf);

        HttpRequest request = HttpRequest.request()
            .withMethod("POST")
            .withPath("/com.example.grpc.GreetingService/Greeting")
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withBody(new BinaryBody(grpcFrame));

        HttpRequest transformed = GrpcHttp3Adapter.transformGrpcRequest(request, descriptorStore);

        assertThat("should set x-grpc-service",
            transformed.getFirstHeader("x-grpc-service"), is("com.example.grpc.GreetingService"));
        assertThat("should set x-grpc-method",
            transformed.getFirstHeader("x-grpc-method"), is("Greeting"));
        assertThat("body should be JSON",
            transformed.getBodyAsString(), containsString("Alice"));
    }

    @Test(expected = org.mockserver.grpc.GrpcException.class)
    public void shouldThrowForUnknownGrpcMethod() {
        byte[] grpcFrame = GrpcFrameCodec.encode("data".getBytes());
        HttpRequest request = HttpRequest.request()
            .withMethod("POST")
            .withPath("/unknown.Service/UnknownMethod")
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withBody(new BinaryBody(grpcFrame));

        GrpcHttp3Adapter.transformGrpcRequest(request, descriptorStore);
    }

    @Test
    public void shouldHandleEmptyBodyInGrpcRequest() {
        HttpRequest request = HttpRequest.request()
            .withMethod("POST")
            .withPath("/com.example.grpc.GreetingService/Greeting")
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);

        HttpRequest transformed = GrpcHttp3Adapter.transformGrpcRequest(request, descriptorStore);

        assertThat("should set x-grpc-service",
            transformed.getFirstHeader("x-grpc-service"), is("com.example.grpc.GreetingService"));
        assertThat("should set x-grpc-method",
            transformed.getFirstHeader("x-grpc-method"), is("Greeting"));
    }

    // ---- transformGrpcResponse ----

    @Test
    public void shouldTransformGrpcResponse() {
        HttpResponse response = HttpResponse.response()
            .withStatusCode(200)
            .withBody("{\"greeting\":\"Hello World\"}");

        GrpcHttp3Adapter.GrpcResponseParts parts = GrpcHttp3Adapter.transformGrpcResponse(
            response,
            "com.example.grpc.GreetingService",
            "Greeting",
            descriptorStore
        );

        assertThat("should have body", parts.hasBody(), is(true));
        assertThat("grpc-status should be 0", parts.grpcStatus(), is("0"));

        // verify the body is valid gRPC framing
        List<byte[]> decoded = GrpcFrameCodec.decode(parts.grpcFrameBytes());
        assertThat("should have one message", decoded.size(), is(1));

        // verify the protobuf decodes back to the expected JSON
        String json = converter.toJson(
            decoded.get(0),
            descriptorStore.getMethod("com.example.grpc.GreetingService", "Greeting").getOutputType()
        );
        assertThat("response JSON should contain greeting", json, containsString("Hello World"));
    }

    @Test
    public void shouldTransformGrpcResponseWithStatusName() {
        HttpResponse response = HttpResponse.response()
            .withStatusCode(200)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_NAME_HEADER, "NOT_FOUND")
            .withBody("{\"greeting\":\"not found\"}");

        GrpcHttp3Adapter.GrpcResponseParts parts = GrpcHttp3Adapter.transformGrpcResponse(
            response,
            "com.example.grpc.GreetingService",
            "Greeting",
            descriptorStore
        );

        assertThat("grpc-status should be NOT_FOUND (5)",
            parts.grpcStatus(), is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.NOT_FOUND.getCode())));
    }

    @Test
    public void shouldTransformGrpcResponseWithExplicitGrpcStatus() {
        HttpResponse response = HttpResponse.response()
            .withStatusCode(200)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "13")
            .withHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER, "internal error");

        GrpcHttp3Adapter.GrpcResponseParts parts = GrpcHttp3Adapter.transformGrpcResponse(
            response,
            "com.example.grpc.GreetingService",
            "Greeting",
            descriptorStore
        );

        assertThat("grpc-status should be 13", parts.grpcStatus(), is("13"));
        assertThat("grpc-message should be 'internal error'", parts.grpcMessage(), is("internal error"));
        assertThat("should not have body", parts.hasBody(), is(false));
    }

    // ---- buildInitialHeadersFrame ----

    @Test
    public void shouldBuildInitialHeadersFrameWithoutGrpcStatus() {
        DefaultHttp3HeadersFrame frame = GrpcHttp3Adapter.buildInitialHeadersFrame();

        assertThat(":status should be 200",
            frame.headers().status().toString(), is("200"));
        assertThat("content-type should be application/grpc",
            frame.headers().get("content-type").toString(), is(GrpcStatusMapper.GRPC_CONTENT_TYPE));
        assertThat("should NOT contain grpc-status",
            frame.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER), is(nullValue()));
    }

    // ---- buildTrailingHeadersFrame ----

    @Test
    public void shouldBuildTrailingHeadersFrameWithGrpcStatus() {
        DefaultHttp3HeadersFrame frame = GrpcHttp3Adapter.buildTrailingHeadersFrame("0", null);

        assertThat("should NOT contain :status (trailers have no pseudo-headers)",
            frame.headers().status(), is(nullValue()));
        assertThat("grpc-status should be 0",
            frame.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(), is("0"));
    }

    @Test
    public void shouldBuildTrailingHeadersFrameWithGrpcMessage() {
        DefaultHttp3HeadersFrame frame = GrpcHttp3Adapter.buildTrailingHeadersFrame("13", "internal error");

        assertThat("grpc-status should be 13",
            frame.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(), is("13"));
        assertThat("grpc-message should be 'internal error'",
            frame.headers().get(GrpcStatusMapper.GRPC_MESSAGE_HEADER).toString(), is("internal error"));
    }

    // ---- buildTrailersOnlyFrame ----

    @Test
    public void shouldBuildTrailersOnlyFrameWithStatusAndGrpcStatus() {
        DefaultHttp3HeadersFrame frame = GrpcHttp3Adapter.buildTrailersOnlyFrame("12", "unimplemented");

        assertThat(":status should be 200",
            frame.headers().status().toString(), is("200"));
        assertThat("grpc-status should be 12",
            frame.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(), is("12"));
        assertThat("grpc-message should be 'unimplemented'",
            frame.headers().get(GrpcStatusMapper.GRPC_MESSAGE_HEADER).toString(), is("unimplemented"));
    }

    // ---- buildDataFrame ----

    @Test
    public void shouldBuildDataFrameFromGrpcBytes() {
        byte[] data = GrpcFrameCodec.encode("hello".getBytes());
        DefaultHttp3DataFrame frame = GrpcHttp3Adapter.buildDataFrame(data);

        assertThat("frame should not be null", frame, is(notNullValue()));
        byte[] content = new byte[frame.content().readableBytes()];
        frame.content().readBytes(content);
        frame.release();

        assertThat("content should match input", content, is(data));
    }

    @Test
    public void shouldReturnNullDataFrameForEmptyBody() {
        assertThat(GrpcHttp3Adapter.buildDataFrame(null), is(nullValue()));
        assertThat(GrpcHttp3Adapter.buildDataFrame(new byte[0]), is(nullValue()));
    }

    // ---- errorResponse ----

    @Test
    public void shouldBuildErrorResponse() {
        GrpcHttp3Adapter.GrpcResponseParts parts = GrpcHttp3Adapter.errorResponse(
            GrpcStatusMapper.GrpcStatusCode.INTERNAL, "something went wrong"
        );

        assertThat("should not have body", parts.hasBody(), is(false));
        assertThat("grpc-status should be 13",
            parts.grpcStatus(), is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.INTERNAL.getCode())));
        assertThat("grpc-message should be set", parts.grpcMessage(), is("something went wrong"));
    }

    // ---- parseGrpcPath ----

    @Test
    public void shouldParseGrpcPath() {
        String[] parts = GrpcHttp3Adapter.parseGrpcPath("/com.example.Service/Method");
        assertThat(parts[0], is("com.example.Service"));
        assertThat(parts[1], is("Method"));
    }

    @Test
    public void shouldParseGrpcPathWithoutLeadingSlash() {
        String[] parts = GrpcHttp3Adapter.parseGrpcPath("com.example.Service/Method");
        assertThat(parts[0], is("com.example.Service"));
        assertThat(parts[1], is("Method"));
    }

    @Test
    public void shouldHandleEmptyPath() {
        String[] parts = GrpcHttp3Adapter.parseGrpcPath("");
        assertThat(parts[0], is(""));
        assertThat(parts[1], is(""));
    }

    @Test
    public void shouldHandleNullPath() {
        String[] parts = GrpcHttp3Adapter.parseGrpcPath(null);
        assertThat(parts[0], is(""));
        assertThat(parts[1], is(""));
    }
}
