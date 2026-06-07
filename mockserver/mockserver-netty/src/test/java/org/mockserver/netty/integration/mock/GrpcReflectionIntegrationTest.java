package org.mockserver.netty.integration.mock;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.WireFormat;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;
import org.mockserver.netty.MockServer;
import org.mockserver.scheduler.Scheduler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Integration test for gRPC Server Reflection through a running MockServer.
 *
 * <p><b>Architecture:</b> MockServer's {@code GrpcToHttpRequestHandler} is only wired into the
 * HTTP/2 Netty pipeline (ALPN or h2c), not HTTP/1.1. It intercepts requests with
 * {@code content-type: application/grpc} and matching reflection paths. This test uses
 * MockServer's own {@link NettyHttpClient} with {@link Protocol#HTTP_2} and
 * {@code withSecure(true)} to get a true HTTP/2 connection via ALPN, enabling full
 * server-level gRPC reflection testing.
 *
 * <p>Descriptors are pre-loaded at server startup via the {@code grpcDescriptorDirectory}
 * configuration to ensure the {@code GrpcToHttpRequestHandler} is in the Netty pipeline
 * from the first connection (it is conditionally added only when
 * {@code descriptorStore.hasServices()} is true at pipeline setup time).
 *
 * <p>The test sends gRPC-framed protobuf payloads as raw binary request bodies and
 * parses the binary gRPC-framed responses, verifying end-to-end reflection through:
 * Netty pipeline setup, HTTP/2 ALPN, GrpcToHttpRequestHandler dispatch,
 * GrpcServerReflectionHandler invocation, and the response path.
 */
public class GrpcReflectionIntegrationTest {

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static int mockServerPort;
    private static EventLoopGroup clientEventLoopGroup;

    @BeforeClass
    public static void startServer() {
        clientEventLoopGroup = new NioEventLoopGroup(3,
            new Scheduler.SchedulerThreadFactory(GrpcReflectionIntegrationTest.class.getSimpleName() + "-eventLoop"));

        // Pre-load gRPC descriptors so the Netty pipeline includes GrpcToHttpRequestHandler
        Configuration configuration = configuration()
            .grpcDescriptorDirectory("../mockserver-core/src/test/resources/grpc");
        mockServer = new MockServer(configuration);
        mockServerPort = mockServer.getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
    }

    private HttpResponse sendHttp2(HttpRequest httpRequest) throws Exception {
        return new NettyHttpClient(
            configuration(),
            new MockServerLogger(),
            clientEventLoopGroup,
            null,
            false
        ).sendRequest(httpRequest).get(15, SECONDS);
    }

    @Test
    public void shouldListServicesViaGrpcReflection() throws Exception {
        byte[] listServicesRequest = buildListServicesRequest("");

        HttpResponse response = sendHttp2(
            request()
                .withMethod("POST")
                .withPath("/grpc.reflection.v1.ServerReflection/ServerReflectionInfo")
                .withHeader("content-type", "application/grpc")
                .withHeader("te", "trailers")
                .withHeader(HOST.toString(), "localhost:" + mockServerPort)
                .withSecure(true)
                .withProtocol(Protocol.HTTP_2)
                .withBody(listServicesRequest)
        );

        assertThat("gRPC reflection response should be 200", response.getStatusCode(), is(200));

        byte[] responseBody = response.getBodyAsRawBytes();
        assertThat("response body should not be empty", responseBody.length, is(greaterThan(5)));

        List<String> serviceNames = parseListServicesResponse(responseBody);
        assertThat("should list the GreetingService",
            serviceNames, hasItem("com.example.grpc.GreetingService"));
    }

    @Test
    public void shouldReturnFileDescriptorForServiceSymbol() throws Exception {
        byte[] symbolRequest = buildFileContainingSymbolRequest("com.example.grpc.GreetingService");

        HttpResponse response = sendHttp2(
            request()
                .withMethod("POST")
                .withPath("/grpc.reflection.v1.ServerReflection/ServerReflectionInfo")
                .withHeader("content-type", "application/grpc")
                .withHeader("te", "trailers")
                .withHeader(HOST.toString(), "localhost:" + mockServerPort)
                .withSecure(true)
                .withProtocol(Protocol.HTTP_2)
                .withBody(symbolRequest)
        );

        assertThat("should return 200 for known symbol", response.getStatusCode(), is(200));

        byte[] responseBody = response.getBodyAsRawBytes();
        assertThat(responseBody.length, is(greaterThan(5)));

        List<byte[]> fdProtos = parseFileDescriptorResponse(responseBody);
        assertThat("should return at least one file descriptor proto", fdProtos, is(not(empty())));

        DescriptorProtos.FileDescriptorProto fdProto =
            DescriptorProtos.FileDescriptorProto.parseFrom(fdProtos.get(0));
        assertThat(fdProto.getName(), is("greeting.proto"));
        assertThat(fdProto.getPackage(), is("com.example.grpc"));
    }

    @Test
    public void shouldReturnV1AlphaReflection() throws Exception {
        byte[] listServicesRequest = buildListServicesRequest("");

        HttpResponse response = sendHttp2(
            request()
                .withMethod("POST")
                .withPath("/grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo")
                .withHeader("content-type", "application/grpc")
                .withHeader("te", "trailers")
                .withHeader(HOST.toString(), "localhost:" + mockServerPort)
                .withSecure(true)
                .withProtocol(Protocol.HTTP_2)
                .withBody(listServicesRequest)
        );

        assertThat("v1alpha should return 200", response.getStatusCode(), is(200));

        byte[] responseBody = response.getBodyAsRawBytes();
        assertThat(responseBody.length, is(greaterThan(5)));
        List<String> serviceNames = parseListServicesResponse(responseBody);
        assertThat("v1alpha should also list the GreetingService",
            serviceNames, hasItem("com.example.grpc.GreetingService"));
    }

    // ---- gRPC request builders ----

    private static final int REQ_HOST_FIELD = 1;
    private static final int REQ_FILE_CONTAINING_SYMBOL_FIELD = 4;
    private static final int REQ_LIST_SERVICES_FIELD = 7;
    private static final int RESP_FILE_DESCRIPTOR_RESPONSE_FIELD = 4;
    private static final int RESP_LIST_SERVICES_RESPONSE_FIELD = 6;
    private static final int FDR_FILE_DESCRIPTOR_PROTO_FIELD = 1;
    private static final int LSR_SERVICE_FIELD = 1;
    private static final int SR_NAME_FIELD = 1;

    private byte[] buildListServicesRequest(String host) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        if (host != null && !host.isEmpty()) {
            cos.writeString(REQ_HOST_FIELD, host);
        }
        cos.writeString(REQ_LIST_SERVICES_FIELD, "*");
        cos.flush();
        return grpcFrame(baos.toByteArray());
    }

    private byte[] buildFileContainingSymbolRequest(String symbol) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        cos.writeString(REQ_FILE_CONTAINING_SYMBOL_FIELD, symbol);
        cos.flush();
        return grpcFrame(baos.toByteArray());
    }

    private static byte[] grpcFrame(byte[] proto) {
        byte[] framed = new byte[5 + proto.length];
        framed[0] = 0; // no compression
        framed[1] = (byte) ((proto.length >> 24) & 0xFF);
        framed[2] = (byte) ((proto.length >> 16) & 0xFF);
        framed[3] = (byte) ((proto.length >> 8) & 0xFF);
        framed[4] = (byte) (proto.length & 0xFF);
        System.arraycopy(proto, 0, framed, 5, proto.length);
        return framed;
    }

    // ---- gRPC response parsers ----

    private List<String> parseListServicesResponse(byte[] grpcFramed) throws IOException {
        byte[] proto = stripGrpcFrame(grpcFramed);
        CodedInputStream cis = CodedInputStream.newInstance(proto);
        List<String> serviceNames = new ArrayList<>();

        while (!cis.isAtEnd()) {
            int tag = cis.readTag();
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            if (fieldNumber == RESP_LIST_SERVICES_RESPONSE_FIELD) {
                byte[] listServiceBytes = cis.readByteArray();
                serviceNames.addAll(parseListServiceResponse(listServiceBytes));
            } else {
                cis.skipField(tag);
            }
        }
        return serviceNames;
    }

    private List<String> parseListServiceResponse(byte[] bytes) throws IOException {
        CodedInputStream cis = CodedInputStream.newInstance(bytes);
        List<String> names = new ArrayList<>();
        while (!cis.isAtEnd()) {
            int tag = cis.readTag();
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            if (fieldNumber == LSR_SERVICE_FIELD) {
                byte[] serviceBytes = cis.readByteArray();
                names.add(parseServiceResponseName(serviceBytes));
            } else {
                cis.skipField(tag);
            }
        }
        return names;
    }

    private String parseServiceResponseName(byte[] bytes) throws IOException {
        CodedInputStream cis = CodedInputStream.newInstance(bytes);
        while (!cis.isAtEnd()) {
            int tag = cis.readTag();
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            if (fieldNumber == SR_NAME_FIELD) {
                return cis.readString();
            } else {
                cis.skipField(tag);
            }
        }
        return "";
    }

    private List<byte[]> parseFileDescriptorResponse(byte[] grpcFramed) throws IOException {
        byte[] proto = stripGrpcFrame(grpcFramed);
        CodedInputStream cis = CodedInputStream.newInstance(proto);
        List<byte[]> fdProtos = new ArrayList<>();

        while (!cis.isAtEnd()) {
            int tag = cis.readTag();
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            if (fieldNumber == RESP_FILE_DESCRIPTOR_RESPONSE_FIELD) {
                byte[] fdrBytes = cis.readByteArray();
                fdProtos.addAll(parseFileDescriptorResponseInner(fdrBytes));
            } else {
                cis.skipField(tag);
            }
        }
        return fdProtos;
    }

    private List<byte[]> parseFileDescriptorResponseInner(byte[] bytes) throws IOException {
        CodedInputStream cis = CodedInputStream.newInstance(bytes);
        List<byte[]> protos = new ArrayList<>();
        while (!cis.isAtEnd()) {
            int tag = cis.readTag();
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            if (fieldNumber == FDR_FILE_DESCRIPTOR_PROTO_FIELD) {
                protos.add(cis.readByteArray());
            } else {
                cis.skipField(tag);
            }
        }
        return protos;
    }

    private byte[] stripGrpcFrame(byte[] grpcFramed) {
        byte[] proto = new byte[grpcFramed.length - 5];
        System.arraycopy(grpcFramed, 5, proto, 0, proto.length);
        return proto;
    }
}
