package org.mockserver.netty.grpc;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.mockserver.grpc.GrpcException;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcHealthCheckHandler;
import org.mockserver.grpc.GrpcHealthRegistry;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.grpc.ServingStatus;
import org.mockserver.mock.action.http.GrpcChaosDecision;
import org.mockserver.mock.action.http.GrpcChaosRegistry;
import org.mockserver.mock.action.http.HttpQuotaRegistry;
import org.mockserver.model.GrpcChaosProfile;
import com.google.protobuf.Descriptors;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.slf4j.event.Level;

import java.util.List;
import java.util.concurrent.TimeUnit;

@ChannelHandler.Sharable
public class GrpcToHttpRequestHandler extends SimpleChannelInboundHandler<HttpRequest> {

    private final MockServerLogger mockServerLogger;
    private final GrpcProtoDescriptorStore descriptorStore;
    private final GrpcHealthCheckHandler healthCheckHandler;
    private final GrpcChaosRegistry grpcChaosRegistry;
    private final HttpQuotaRegistry quotaRegistry;

    public GrpcToHttpRequestHandler(MockServerLogger mockServerLogger, GrpcProtoDescriptorStore descriptorStore) {
        this(mockServerLogger, descriptorStore, new GrpcHealthCheckHandler(GrpcHealthRegistry.getInstance()),
            GrpcChaosRegistry.getInstance(), HttpQuotaRegistry.getInstance());
    }

    public GrpcToHttpRequestHandler(MockServerLogger mockServerLogger, GrpcProtoDescriptorStore descriptorStore, GrpcHealthCheckHandler healthCheckHandler) {
        this(mockServerLogger, descriptorStore, healthCheckHandler,
            GrpcChaosRegistry.getInstance(), HttpQuotaRegistry.getInstance());
    }

    public GrpcToHttpRequestHandler(MockServerLogger mockServerLogger, GrpcProtoDescriptorStore descriptorStore,
                                    GrpcHealthCheckHandler healthCheckHandler,
                                    GrpcChaosRegistry grpcChaosRegistry, HttpQuotaRegistry quotaRegistry) {
        this.mockServerLogger = mockServerLogger;
        this.descriptorStore = descriptorStore;
        this.healthCheckHandler = healthCheckHandler;
        this.grpcChaosRegistry = grpcChaosRegistry;
        this.quotaRegistry = quotaRegistry;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpRequest request) {
        String contentType = request.getFirstHeader("content-type");
        // Handle gRPC health check without requiring a descriptor
        if (GrpcStatusMapper.isGrpcContentType(contentType) && healthCheckHandler != null) {
            String path = request.getPath() != null ? request.getPath().getValue() : "";
            if (healthCheckHandler.isHealthCheckRequest(path)) {
                String serviceName = healthCheckHandler.decodeServiceName(request.getBodyAsRawBytes());
                ServingStatus status = healthCheckHandler.getStatus(serviceName);
                byte[] responseBody = healthCheckHandler.encodeResponse(status);
                org.mockserver.model.HttpResponse healthResponse = org.mockserver.model.HttpResponse.response()
                    .withStatusCode(200)
                    .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
                    .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "0")
                    .withBody(responseBody);
                ctx.writeAndFlush(healthResponse);
                return;
            }
        }
        // gRPC chaos fault injection: probabilistically return a gRPC error status
        // before normal request handling. Only on the error-injection path is latency applied;
        // pass-through latency is a future addition.
        if (GrpcStatusMapper.isGrpcContentType(contentType)) {
            String chaosPath = request.getPath() != null ? request.getPath().getValue() : "";
            String[] chaosParts = parseGrpcPath(chaosPath);
            String chaosServiceName = chaosParts[0];
            GrpcChaosProfile chaosProfile = grpcChaosRegistry.get(chaosServiceName);
            if (chaosProfile != null && chaosProfile.hasAnyFault()) {
                int matchCount = grpcChaosRegistry.incrementMatchCount(chaosServiceName);
                GrpcChaosDecision.GrpcFault fault = GrpcChaosDecision.evaluate(chaosProfile, matchCount, quotaRegistry);
                if (fault != null) {
                    GrpcStatusMapper.GrpcStatusCode code = fault.getStatusCode();
                    String message = fault.getMessage() != null ? fault.getMessage() : code.name();
                    org.mockserver.model.HttpResponse errorResponse = org.mockserver.model.HttpResponse.response()
                        .withStatusCode(200)
                        .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
                        .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, String.valueOf(code.getCode()))
                        .withHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER, message);
                    Long latencyMs = chaosProfile.getLatencyMs();
                    if (latencyMs != null && latencyMs > 0) {
                        ctx.channel().eventLoop().schedule(() -> ctx.writeAndFlush(errorResponse), latencyMs, TimeUnit.MILLISECONDS);
                    } else {
                        ctx.writeAndFlush(errorResponse);
                    }
                    return;
                }
            }
        }
        if (GrpcStatusMapper.isGrpcContentType(contentType) && descriptorStore.hasServices()) {
            try {
                HttpRequest converted = convertGrpcRequest(request);
                ctx.fireChannelRead(converted);
            } catch (GrpcException e) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("gRPC request error:{}:{}")
                        .setArguments(request.getPath(), e.getMessage())
                );
                GrpcStatusMapper.GrpcStatusCode statusCode = e.getMessage() != null && e.getMessage().startsWith("unknown gRPC method")
                    ? GrpcStatusMapper.GrpcStatusCode.UNIMPLEMENTED
                    : GrpcStatusMapper.GrpcStatusCode.INTERNAL;
                org.mockserver.model.HttpResponse errorResponse = org.mockserver.model.HttpResponse.response()
                    .withStatusCode(200)
                    .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
                    .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, String.valueOf(statusCode.getCode()))
                    .withHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER, e.getMessage());
                ctx.writeAndFlush(errorResponse);
            } catch (Exception e) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("failed to convert gRPC request to JSON:{}:{}")
                        .setArguments(request.getPath(), e.getMessage())
                );
                org.mockserver.model.HttpResponse errorResponse = org.mockserver.model.HttpResponse.response()
                    .withStatusCode(200)
                    .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
                    .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, String.valueOf(GrpcStatusMapper.GrpcStatusCode.INTERNAL.getCode()))
                    .withHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER, "failed to decode gRPC request: " + e.getMessage());
                ctx.writeAndFlush(errorResponse);
            }
        } else {
            ctx.fireChannelRead(request);
        }
    }

    private HttpRequest convertGrpcRequest(HttpRequest request) {
        String path = request.getPath() != null ? request.getPath().getValue() : "";
        String[] parts = parseGrpcPath(path);
        String serviceName = parts[0];
        String methodName = parts[1];

        Descriptors.MethodDescriptor methodDescriptor = descriptorStore.getMethod(serviceName, methodName);
        if (methodDescriptor == null) {
            throw new GrpcException("unknown gRPC method: " + serviceName + "/" + methodName);
        }

        byte[] bodyBytes = request.getBodyAsRawBytes();
        if (bodyBytes == null || bodyBytes.length == 0) {
            return request;
        }

        List<byte[]> messages = GrpcFrameCodec.decode(bodyBytes);
        if (messages.isEmpty()) {
            throw new GrpcException("failed to decode gRPC frame from request body");
        }
        GrpcJsonMessageConverter converter = descriptorStore.getConverter();

        if (messages.size() == 1) {
            String json = converter.toJson(messages.get(0), methodDescriptor.getInputType());
            return request
                .clone()
                .withBody(json)
                .withHeader("x-grpc-service", serviceName)
                .withHeader("x-grpc-method", methodName)
                .withHeader("x-grpc-original-content-type", request.getFirstHeader("content-type"));
        } else if (messages.size() > 1) {
            StringBuilder jsonArray = new StringBuilder("[");
            for (int i = 0; i < messages.size(); i++) {
                if (i > 0) {
                    jsonArray.append(",");
                }
                jsonArray.append(converter.toJson(messages.get(i), methodDescriptor.getInputType()));
            }
            jsonArray.append("]");
            return request
                .clone()
                .withBody(jsonArray.toString())
                .withHeader("x-grpc-service", serviceName)
                .withHeader("x-grpc-method", methodName)
                .withHeader("x-grpc-original-content-type", request.getFirstHeader("content-type"))
                .withHeader("x-grpc-client-streaming", "true");
        }

        return request;
    }

    static String[] parseGrpcPath(String path) {
        if (path == null || path.isEmpty()) {
            return new String[]{"", ""};
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        int slashIndex = path.lastIndexOf('/');
        if (slashIndex < 1 || slashIndex == path.length() - 1) {
            return new String[]{path, ""};
        }
        return new String[]{path.substring(0, slashIndex), path.substring(slashIndex + 1)};
    }
}
