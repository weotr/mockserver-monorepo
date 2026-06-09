package org.mockserver.grpc;

import com.google.protobuf.Descriptors;
import org.mockserver.model.GrpcStreamMessage;

import java.nio.charset.StandardCharsets;

/**
 * Transport-neutral helper that encodes a single gRPC stream message (JSON) into
 * a gRPC length-prefixed frame, ready to be written as an HTTP/2 DATA frame or an
 * HTTP/3 DATA frame.
 * <p>
 * Shared by the HTTP/2 server-streaming handler
 * ({@link org.mockserver.mock.action.http.GrpcStreamResponseActionHandler}) and the
 * HTTP/3 server-streaming writer so the encoding semantics are identical across
 * transports:
 * <ul>
 *   <li>empty / null JSON -&gt; an empty gRPC frame (5-byte header, zero-length payload);</li>
 *   <li>JSON with a resolvable method descriptor -&gt; protobuf-encoded then gRPC-framed;</li>
 *   <li>JSON without a descriptor -&gt; the raw UTF-8 bytes gRPC-framed (best-effort
 *       passthrough so raw expectations still produce a valid gRPC frame).</li>
 * </ul>
 */
public final class GrpcStreamMessageEncoder {

    private GrpcStreamMessageEncoder() {
        // utility class
    }

    /**
     * Encode the given message JSON to a gRPC length-prefixed frame.
     *
     * @param json             the message JSON (may be null/empty)
     * @param methodDescriptor the resolved gRPC method descriptor (may be null)
     * @param converter        the JSON/protobuf converter (may be null)
     * @return the gRPC-framed bytes
     */
    public static byte[] encode(String json, Descriptors.MethodDescriptor methodDescriptor, GrpcJsonMessageConverter converter) {
        if (json == null || json.isEmpty()) {
            return GrpcFrameCodec.encode(new byte[0]);
        }
        if (methodDescriptor != null && converter != null) {
            byte[] protobufBytes = converter.toProtobuf(json, methodDescriptor.getOutputType());
            return GrpcFrameCodec.encode(protobufBytes);
        }
        return GrpcFrameCodec.encode(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Encode the given message JSON to a gRPC length-prefixed frame, resolving the
     * converter from the descriptor store.
     */
    public static byte[] encode(String json, Descriptors.MethodDescriptor methodDescriptor, GrpcProtoDescriptorStore descriptorStore) {
        GrpcJsonMessageConverter converter = descriptorStore != null ? descriptorStore.getConverter() : null;
        return encode(json, methodDescriptor, converter);
    }

    /**
     * Encode a {@link GrpcStreamMessage} to a gRPC length-prefixed frame.
     */
    public static byte[] encode(GrpcStreamMessage message, Descriptors.MethodDescriptor methodDescriptor, GrpcProtoDescriptorStore descriptorStore) {
        return encode(message.getJson(), methodDescriptor, descriptorStore);
    }
}
