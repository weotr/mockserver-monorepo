package org.mockserver.netty.grpc;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import com.google.protobuf.Descriptors;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.grpc.GrpcWebTranslator;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.slf4j.event.Level;

import java.util.List;

@ChannelHandler.Sharable
public class GrpcToHttpResponseHandler extends MessageToMessageEncoder<HttpResponse> {

    private final MockServerLogger mockServerLogger;
    private final GrpcProtoDescriptorStore descriptorStore;

    public GrpcToHttpResponseHandler(MockServerLogger mockServerLogger, GrpcProtoDescriptorStore descriptorStore) {
        this.mockServerLogger = mockServerLogger;
        this.descriptorStore = descriptorStore;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, HttpResponse response, List<Object> out) {
        String grpcWebContentType = response.getFirstHeader("x-grpc-web-content-type");
        String grpcService = response.getFirstHeader("x-grpc-service");
        String grpcMethod = response.getFirstHeader("x-grpc-method");

        if (grpcService != null && !grpcService.isEmpty() && grpcMethod != null && !grpcMethod.isEmpty()) {
            try {
                HttpResponse converted = convertToGrpcResponse(response, grpcService, grpcMethod);
                if (grpcWebContentType != null && !grpcWebContentType.isEmpty()) {
                    converted = convertToGrpcWebResponse(converted, grpcWebContentType);
                }
                out.add(converted);
            } catch (Exception e) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("failed to convert response to gRPC for {}/{}:{}")
                        .setArguments(grpcService, grpcMethod, e.getMessage())
                );
                HttpResponse errorResponse = response.clone()
                    .withStatusCode(200)
                    .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
                    .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, String.valueOf(GrpcStatusMapper.GrpcStatusCode.INTERNAL.getCode()))
                    .withHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER, "failed to encode gRPC response: " + e.getMessage())
                    .removeHeader("x-grpc-service")
                    .removeHeader("x-grpc-method")
                    .removeHeader("x-grpc-web-content-type");
                if (grpcWebContentType != null && !grpcWebContentType.isEmpty()) {
                    errorResponse = convertToGrpcWebResponse(errorResponse, grpcWebContentType);
                }
                out.add(errorResponse);
            }
        } else if (grpcWebContentType != null && !grpcWebContentType.isEmpty()) {
            // gRPC-Web request that bypassed descriptor conversion (e.g. health check, reflection, chaos)
            out.add(convertToGrpcWebResponse(response, grpcWebContentType));
        } else {
            out.add(response);
        }
    }

    /**
     * Converts a standard gRPC response (with grpc-status/grpc-message as HTTP headers)
     * into a gRPC-Web response (with trailers embedded in the body as a trailer frame).
     */
    private HttpResponse convertToGrpcWebResponse(HttpResponse response, String grpcWebContentType) {
        String grpcStatus = response.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER);
        String grpcMessage = response.getFirstHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER);
        byte[] messageBody = response.getBodyAsRawBytes();
        boolean isTextVariant = GrpcWebTranslator.isGrpcWebTextContentType(grpcWebContentType);

        byte[] grpcWebBody = GrpcWebTranslator.encodeResponseBody(
            messageBody, grpcStatus, grpcMessage, isTextVariant
        );

        return response.clone()
            .withBody(new org.mockserver.model.BinaryBody(grpcWebBody))
            .replaceHeader(new org.mockserver.model.Header("content-type", GrpcWebTranslator.responseContentType(grpcWebContentType)))
            .removeHeader(GrpcStatusMapper.GRPC_STATUS_HEADER)
            .removeHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER)
            .removeHeader("x-grpc-web-content-type");
    }

    private HttpResponse convertToGrpcResponse(HttpResponse response, String serviceName, String methodName) {
        Descriptors.MethodDescriptor methodDescriptor = descriptorStore.getMethod(serviceName, methodName);
        if (methodDescriptor == null) {
            return response.clone()
                .removeHeader("x-grpc-service")
                .removeHeader("x-grpc-method");
        }

        String statusName = response.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_NAME_HEADER);
        GrpcStatusMapper.GrpcStatusCode statusCode;
        if (statusName != null && !statusName.isEmpty()) {
            statusCode = GrpcStatusMapper.fromName(statusName);
        } else {
            statusCode = GrpcStatusMapper.GrpcStatusCode.OK;
        }

        String bodyString = response.getBodyAsString();
        if (bodyString == null || bodyString.isEmpty()) {
            // No hand-authored response body: for a successful (OK) response, synthesize a
            // schema-valid example message from the loaded descriptor's output type so the
            // client receives a well-formed, type-correct protobuf message rather than an
            // empty frame. Non-OK statuses keep the empty-body behaviour (the status is the
            // payload). If synthesis fails for any reason, fall back to an empty response.
            if (statusCode == GrpcStatusMapper.GrpcStatusCode.OK) {
                try {
                    String synthesized = descriptorStore.getExampleSynthesizer()
                        .synthesizeJson(methodDescriptor.getOutputType(), descriptorStore.getConverter());
                    if (synthesized != null && !synthesized.isEmpty()) {
                        bodyString = synthesized;
                    }
                } catch (Exception e) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setMessageFormat("failed to synthesize gRPC example response for {}/{}:{}")
                            .setArguments(serviceName, methodName, e.getMessage())
                    );
                }
            }
            if (bodyString == null || bodyString.isEmpty()) {
                return response.clone()
                    .withStatusCode(200)
                    .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
                    .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, String.valueOf(statusCode.getCode()))
                    .removeHeader("x-grpc-service")
                    .removeHeader("x-grpc-method");
            }
        }

        GrpcJsonMessageConverter converter = descriptorStore.getConverter();
        byte[] protobufBytes = converter.toProtobuf(bodyString, methodDescriptor.getOutputType());
        byte[] grpcFrame = GrpcFrameCodec.encode(protobufBytes);

        return response.clone()
            .withStatusCode(200)
            .withBody(new org.mockserver.model.BinaryBody(grpcFrame))
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, String.valueOf(statusCode.getCode()))
            .removeHeader("x-grpc-service")
            .removeHeader("x-grpc-method")
            .removeHeader(GrpcStatusMapper.GRPC_STATUS_NAME_HEADER);
    }
}
