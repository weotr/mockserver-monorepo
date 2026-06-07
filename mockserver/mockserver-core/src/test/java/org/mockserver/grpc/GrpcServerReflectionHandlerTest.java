package org.mockserver.grpc;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.WireFormat;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class GrpcServerReflectionHandlerTest {

    private GrpcProtoDescriptorStore store;
    private GrpcServerReflectionHandler handler;

    @Before
    public void setUp() {
        store = new GrpcProtoDescriptorStore(new MockServerLogger());
        Path descriptorPath = Paths.get("src/test/resources/grpc/greeting.dsc");
        store.loadDescriptorSetFromPath(descriptorPath);
        handler = new GrpcServerReflectionHandler(store);
    }

    // --- isReflectionRequest ---

    @Test
    public void shouldMatchV1ReflectionPath() {
        assertThat(handler.isReflectionRequest(
            "/grpc.reflection.v1.ServerReflection/ServerReflectionInfo"), is(true));
    }

    @Test
    public void shouldMatchV1AlphaReflectionPath() {
        assertThat(handler.isReflectionRequest(
            "/grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo"), is(true));
    }

    @Test
    public void shouldNotMatchOtherPath() {
        assertThat(handler.isReflectionRequest("/other.Service/Method"), is(false));
    }

    @Test
    public void shouldNotMatchNullPath() {
        assertThat(handler.isReflectionRequest(null), is(false));
    }

    @Test
    public void shouldNotMatchEmptyPath() {
        assertThat(handler.isReflectionRequest(""), is(false));
    }

    @Test
    public void shouldNotMatchPartialPath() {
        assertThat(handler.isReflectionRequest(
            "/grpc.reflection.v1.ServerReflection/OtherMethod"), is(false));
    }

    // --- list_services ---

    @Test
    public void shouldListServices() throws IOException {
        byte[] requestBody = buildListServicesRequest("");
        byte[] responseBody = handler.handleReflectionRequest(requestBody);

        // Parse the gRPC-framed response
        assertThat(responseBody, is(notNullValue()));
        assertThat(responseBody.length, is(greaterThan(5)));

        List<String> serviceNames = parseListServicesResponse(responseBody);
        assertThat(serviceNames, hasItem("com.example.grpc.GreetingService"));
        assertThat(serviceNames.size(), is(1));
    }

    @Test
    public void shouldListServicesWithHost() throws IOException {
        byte[] requestBody = buildListServicesRequest("localhost");
        byte[] responseBody = handler.handleReflectionRequest(requestBody);

        List<String> serviceNames = parseListServicesResponse(responseBody);
        assertThat(serviceNames, hasItem("com.example.grpc.GreetingService"));
    }

    // --- file_containing_symbol ---

    @Test
    public void shouldReturnFileDescriptorForServiceSymbol() throws IOException {
        byte[] requestBody = buildFileContainingSymbolRequest("com.example.grpc.GreetingService");
        byte[] responseBody = handler.handleReflectionRequest(requestBody);

        assertThat(responseBody, is(notNullValue()));
        assertThat(responseBody.length, is(greaterThan(5)));

        List<byte[]> fileDescriptorProtos = parseFileDescriptorResponse(responseBody);
        assertThat(fileDescriptorProtos, is(not(empty())));

        // Parse the first file descriptor proto and verify it has the expected file name
        DescriptorProtos.FileDescriptorProto fdProto =
            DescriptorProtos.FileDescriptorProto.parseFrom(fileDescriptorProtos.get(0));
        assertThat(fdProto.getName(), is("greeting.proto"));
        assertThat(fdProto.getPackage(), is("com.example.grpc"));
    }

    @Test
    public void shouldReturnFileDescriptorForMessageSymbol() throws IOException {
        byte[] requestBody = buildFileContainingSymbolRequest("com.example.grpc.HelloRequest");
        byte[] responseBody = handler.handleReflectionRequest(requestBody);

        List<byte[]> fileDescriptorProtos = parseFileDescriptorResponse(responseBody);
        assertThat(fileDescriptorProtos, is(not(empty())));

        DescriptorProtos.FileDescriptorProto fdProto =
            DescriptorProtos.FileDescriptorProto.parseFrom(fileDescriptorProtos.get(0));
        assertThat(fdProto.getName(), is("greeting.proto"));
    }

    @Test
    public void shouldReturnErrorForUnknownSymbol() throws IOException {
        byte[] requestBody = buildFileContainingSymbolRequest("com.example.NonExistent");
        byte[] responseBody = handler.handleReflectionRequest(requestBody);

        assertThat(responseBody, is(notNullValue()));
        // Should have an error_response (field 5 of ServerReflectionResponse)
        // Verify it's a valid gRPC frame with content
        assertThat(responseBody.length, is(greaterThan(5)));
    }

    // --- file_by_filename ---

    @Test
    public void shouldReturnFileDescriptorByFilename() throws IOException {
        byte[] requestBody = buildFileByFilenameRequest("greeting.proto");
        byte[] responseBody = handler.handleReflectionRequest(requestBody);

        assertThat(responseBody, is(notNullValue()));
        assertThat(responseBody.length, is(greaterThan(5)));

        List<byte[]> fileDescriptorProtos = parseFileDescriptorResponse(responseBody);
        assertThat(fileDescriptorProtos, is(not(empty())));

        DescriptorProtos.FileDescriptorProto fdProto =
            DescriptorProtos.FileDescriptorProto.parseFrom(fileDescriptorProtos.get(0));
        assertThat(fdProto.getName(), is("greeting.proto"));
    }

    @Test
    public void shouldReturnErrorForUnknownFilename() throws IOException {
        byte[] requestBody = buildFileByFilenameRequest("nonexistent.proto");
        byte[] responseBody = handler.handleReflectionRequest(requestBody);

        assertThat(responseBody, is(notNullValue()));
        assertThat(responseBody.length, is(greaterThan(5)));
    }

    // --- edge cases ---

    @Test
    public void shouldHandleNullBody() {
        byte[] responseBody = handler.handleReflectionRequest(null);
        assertThat(responseBody, is(notNullValue()));
        assertThat(responseBody.length, is(greaterThan(4))); // at least the gRPC frame header
    }

    @Test
    public void shouldHandleEmptyBody() {
        byte[] responseBody = handler.handleReflectionRequest(new byte[5]);
        assertThat(responseBody, is(notNullValue()));
    }

    @Test
    public void shouldHandleShortBody() {
        byte[] responseBody = handler.handleReflectionRequest(new byte[3]);
        assertThat(responseBody, is(notNullValue()));
    }

    // --- decodeRequest ---

    @Test
    public void shouldDecodeListServicesRequest() throws IOException {
        byte[] requestBody = buildListServicesRequest("myhost");
        GrpcServerReflectionHandler.ReflectionRequest req = handler.decodeRequest(requestBody);
        assertThat(req.type, is(GrpcServerReflectionHandler.RequestType.LIST_SERVICES));
        assertThat(req.host, is("myhost"));
    }

    @Test
    public void shouldDecodeFileContainingSymbolRequest() throws IOException {
        byte[] requestBody = buildFileContainingSymbolRequest("com.example.MyService");
        GrpcServerReflectionHandler.ReflectionRequest req = handler.decodeRequest(requestBody);
        assertThat(req.type, is(GrpcServerReflectionHandler.RequestType.FILE_CONTAINING_SYMBOL));
        assertThat(req.argument, is("com.example.MyService"));
    }

    @Test
    public void shouldDecodeFileByFilenameRequest() throws IOException {
        byte[] requestBody = buildFileByFilenameRequest("test.proto");
        GrpcServerReflectionHandler.ReflectionRequest req = handler.decodeRequest(requestBody);
        assertThat(req.type, is(GrpcServerReflectionHandler.RequestType.FILE_BY_FILENAME));
        assertThat(req.argument, is("test.proto"));
    }

    @Test
    public void shouldDecodeUnknownRequestType() {
        GrpcServerReflectionHandler.ReflectionRequest req = handler.decodeRequest(new byte[5]);
        assertThat(req.type, is(GrpcServerReflectionHandler.RequestType.UNKNOWN));
    }

    // --- grpcFrame ---

    @Test
    public void shouldProduceValidGrpcFrame() {
        byte[] proto = new byte[]{0x0A, 0x05, 0x68, 0x65, 0x6C, 0x6C, 0x6F}; // dummy proto
        byte[] framed = GrpcServerReflectionHandler.grpcFrame(proto);
        assertThat(framed.length, is(5 + proto.length));
        assertThat(framed[0], is((byte) 0)); // no compression
        // big-endian length
        int length = ((framed[1] & 0xFF) << 24) | ((framed[2] & 0xFF) << 16)
            | ((framed[3] & 0xFF) << 8) | (framed[4] & 0xFF);
        assertThat(length, is(proto.length));
    }

    // --- Helper methods to build gRPC-framed ServerReflectionRequest messages ---

    /**
     * Builds a gRPC-framed ServerReflectionRequest with list_services (field 7).
     */
    private byte[] buildListServicesRequest(String host) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        if (host != null && !host.isEmpty()) {
            cos.writeString(GrpcServerReflectionHandler.REQ_HOST_FIELD, host);
        }
        // list_services = field 7, value is typically "*" or empty
        cos.writeString(GrpcServerReflectionHandler.REQ_LIST_SERVICES_FIELD, "*");
        cos.flush();
        return GrpcServerReflectionHandler.grpcFrame(baos.toByteArray());
    }

    /**
     * Builds a gRPC-framed ServerReflectionRequest with file_containing_symbol (field 4).
     */
    private byte[] buildFileContainingSymbolRequest(String symbol) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        cos.writeString(GrpcServerReflectionHandler.REQ_FILE_CONTAINING_SYMBOL_FIELD, symbol);
        cos.flush();
        return GrpcServerReflectionHandler.grpcFrame(baos.toByteArray());
    }

    /**
     * Builds a gRPC-framed ServerReflectionRequest with file_by_filename (field 3).
     */
    private byte[] buildFileByFilenameRequest(String filename) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(baos);
        cos.writeString(GrpcServerReflectionHandler.REQ_FILE_BY_FILENAME_FIELD, filename);
        cos.flush();
        return GrpcServerReflectionHandler.grpcFrame(baos.toByteArray());
    }

    // --- Helper methods to parse gRPC-framed ServerReflectionResponse ---

    /**
     * Parses a gRPC-framed ServerReflectionResponse and extracts service names
     * from a ListServiceResponse (field 6).
     */
    private List<String> parseListServicesResponse(byte[] grpcFramed) throws IOException {
        byte[] proto = stripGrpcFrame(grpcFramed);
        CodedInputStream cis = CodedInputStream.newInstance(proto);
        List<String> serviceNames = new ArrayList<>();

        while (!cis.isAtEnd()) {
            int tag = cis.readTag();
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            if (fieldNumber == GrpcServerReflectionHandler.RESP_LIST_SERVICES_RESPONSE_FIELD) {
                // embedded ListServiceResponse message
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
            if (fieldNumber == GrpcServerReflectionHandler.LSR_SERVICE_FIELD) {
                // embedded ServiceResponse message
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
            if (fieldNumber == GrpcServerReflectionHandler.SR_NAME_FIELD) {
                return cis.readString();
            } else {
                cis.skipField(tag);
            }
        }
        return "";
    }

    /**
     * Parses a gRPC-framed ServerReflectionResponse and extracts the
     * file_descriptor_proto bytes from a FileDescriptorResponse (field 4).
     */
    private List<byte[]> parseFileDescriptorResponse(byte[] grpcFramed) throws IOException {
        byte[] proto = stripGrpcFrame(grpcFramed);
        CodedInputStream cis = CodedInputStream.newInstance(proto);
        List<byte[]> fdProtos = new ArrayList<>();

        while (!cis.isAtEnd()) {
            int tag = cis.readTag();
            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            if (fieldNumber == GrpcServerReflectionHandler.RESP_FILE_DESCRIPTOR_RESPONSE_FIELD) {
                // embedded FileDescriptorResponse message
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
            if (fieldNumber == GrpcServerReflectionHandler.FDR_FILE_DESCRIPTOR_PROTO_FIELD) {
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
