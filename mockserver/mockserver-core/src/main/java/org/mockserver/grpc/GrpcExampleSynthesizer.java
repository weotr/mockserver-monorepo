package org.mockserver.grpc;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;

import java.util.HashSet;
import java.util.Set;

/**
 * Synthesizes schema-valid example protobuf messages from a loaded protobuf
 * {@link Descriptors.Descriptor}. This is the gRPC analogue of the GraphQL
 * {@code GraphQLResponseSynthesizer} and the OpenAPI {@code ExampleBuilder}: given a
 * message type from a descriptor loaded via {@code PUT /mockserver/grpc/descriptors}, it
 * walks the message's fields and emits a deterministic, type-correct placeholder value for
 * every field — no hand-authored response body required.
 *
 * <p>The synthesizer builds a real {@link DynamicMessage} rather than hand-rolling JSON, so
 * the result is guaranteed to be a valid instance of the message type and round-trips
 * cleanly through {@link GrpcJsonMessageConverter} (well-known types such as
 * {@code google.protobuf.Timestamp} are rendered by the standard protobuf-JSON printer when
 * the type registry knows them).
 *
 * <p>Synthesis rules:
 * <ul>
 *   <li>scalars produce deterministic placeholders by Java/proto type
 *       (string {@code "string"}, numbers {@code 0}/{@code 0.0}, bool {@code true},
 *       bytes empty)</li>
 *   <li>enums produce their first declared value (index 0)</li>
 *   <li>nested messages recurse into a synthesized sub-message</li>
 *   <li>repeated fields produce a single synthesized element</li>
 *   <li>map fields produce a single synthesized entry</li>
 *   <li>well-known message types ({@code Timestamp}, {@code Duration}, the scalar wrappers,
 *       {@code Struct}/{@code Value}/{@code Empty}) get sensible canonical values</li>
 *   <li>recursion is bounded; a message type already on the current path is left at its
 *       proto default to avoid infinite recursion on self-referential schemas</li>
 * </ul>
 *
 * <p>Instances are stateless and thread-safe.
 */
public class GrpcExampleSynthesizer {

    private static final int MAX_RECURSION_DEPTH = 25;

    /**
     * Synthesize a schema-valid example message for the supplied message descriptor and
     * render it as protobuf-JSON via the supplied converter.
     *
     * @param messageDescriptor the message type to synthesize (e.g. a method's output type)
     * @param converter         the converter used to render the message as protobuf-JSON
     * @return a protobuf-JSON string that is a valid instance of {@code messageDescriptor}
     * @throws GrpcException if synthesis or rendering fails
     */
    public String synthesizeJson(Descriptors.Descriptor messageDescriptor, GrpcJsonMessageConverter converter) {
        if (messageDescriptor == null) {
            throw new GrpcException("cannot synthesize gRPC example for a null message descriptor");
        }
        if (converter == null) {
            throw new GrpcException("cannot synthesize gRPC example without a message converter");
        }
        DynamicMessage message = synthesizeMessage(messageDescriptor);
        return converter.toJson(message.toByteArray(), messageDescriptor);
    }

    /**
     * Synthesize a schema-valid example {@link DynamicMessage} for the supplied message
     * descriptor.
     */
    public DynamicMessage synthesizeMessage(Descriptors.Descriptor messageDescriptor) {
        if (messageDescriptor == null) {
            throw new GrpcException("cannot synthesize gRPC example for a null message descriptor");
        }
        return buildMessage(messageDescriptor, new HashSet<>(), 0);
    }

    private DynamicMessage buildMessage(Descriptors.Descriptor descriptor, Set<String> path, int depth) {
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(descriptor);
        if (depth > MAX_RECURSION_DEPTH) {
            return builder.build();
        }

        Set<String> nextPath = new HashSet<>(path);
        nextPath.add(descriptor.getFullName());

        // only populate the first field of a synthetic oneof so the emitted message is valid
        Set<Descriptors.OneofDescriptor> populatedOneofs = new HashSet<>();

        for (Descriptors.FieldDescriptor field : descriptor.getFields()) {
            Descriptors.OneofDescriptor oneof = field.getContainingOneof();
            if (oneof != null) {
                if (populatedOneofs.contains(oneof)) {
                    continue;
                }
                populatedOneofs.add(oneof);
            }

            if (field.isMapField()) {
                Object entry = buildMapEntry(field, nextPath, depth);
                if (entry != null) {
                    builder.addRepeatedField(field, entry);
                }
                continue;
            }

            if (field.isRepeated()) {
                Object element = buildFieldValue(field, nextPath, depth);
                if (element != null) {
                    builder.addRepeatedField(field, element);
                }
                continue;
            }

            Object value = buildFieldValue(field, nextPath, depth);
            if (value != null) {
                builder.setField(field, value);
            }
        }

        return builder.build();
    }

    private Object buildMapEntry(Descriptors.FieldDescriptor mapField, Set<String> path, int depth) {
        Descriptors.Descriptor entryType = mapField.getMessageType();
        Descriptors.FieldDescriptor keyField = entryType.findFieldByName("key");
        Descriptors.FieldDescriptor valueField = entryType.findFieldByName("value");
        if (keyField == null || valueField == null) {
            return null;
        }
        DynamicMessage.Builder entryBuilder = DynamicMessage.newBuilder(entryType);
        Object key = buildFieldValue(keyField, path, depth);
        Object value = buildFieldValue(valueField, path, depth);
        if (key != null) {
            entryBuilder.setField(keyField, key);
        }
        if (value != null) {
            entryBuilder.setField(valueField, value);
        }
        return entryBuilder.build();
    }

    private Object buildFieldValue(Descriptors.FieldDescriptor field, Set<String> path, int depth) {
        switch (field.getJavaType()) {
            case INT:
                return 0;
            case LONG:
                return 0L;
            case FLOAT:
                return 0.0f;
            case DOUBLE:
                return 0.0d;
            case BOOLEAN:
                return true;
            case STRING:
                return "string";
            case BYTE_STRING:
                return ByteString.EMPTY;
            case ENUM:
                return firstEnumValue(field.getEnumType());
            case MESSAGE:
                return buildMessageField(field.getMessageType(), path, depth);
            default:
                return null;
        }
    }

    private Descriptors.EnumValueDescriptor firstEnumValue(Descriptors.EnumDescriptor enumType) {
        if (enumType.getValues().isEmpty()) {
            return null;
        }
        return enumType.getValues().get(0);
    }

    private Object buildMessageField(Descriptors.Descriptor messageType, Set<String> path, int depth) {
        Object wellKnown = wellKnownValue(messageType);
        if (wellKnown != null) {
            return wellKnown;
        }
        // guard against infinite recursion on self-referential schemas: leave the field
        // at its proto default (an empty sub-message) rather than recursing forever
        if (path.contains(messageType.getFullName())) {
            return DynamicMessage.getDefaultInstance(messageType);
        }
        return buildMessage(messageType, path, depth + 1);
    }

    /**
     * Provides canonical example values for the protobuf well-known types so the synthesized
     * JSON renders the way the standard protobuf-JSON printer expects (e.g. a Timestamp as an
     * RFC-3339 string rather than {@code {seconds, nanos}}). Returns {@code null} for any type
     * that is not a recognised well-known type, in which case the normal recursion applies.
     */
    private Object wellKnownValue(Descriptors.Descriptor messageType) {
        String fullName = messageType.getFullName();
        switch (fullName) {
            case "google.protobuf.Timestamp": {
                DynamicMessage.Builder b = DynamicMessage.newBuilder(messageType);
                setIfPresent(b, messageType, "seconds", 1577836800L); // 2020-01-01T00:00:00Z
                return b.build();
            }
            case "google.protobuf.Duration": {
                DynamicMessage.Builder b = DynamicMessage.newBuilder(messageType);
                setIfPresent(b, messageType, "seconds", 0L);
                return b.build();
            }
            case "google.protobuf.StringValue": {
                DynamicMessage.Builder b = DynamicMessage.newBuilder(messageType);
                setIfPresent(b, messageType, "value", "string");
                return b.build();
            }
            case "google.protobuf.BoolValue": {
                DynamicMessage.Builder b = DynamicMessage.newBuilder(messageType);
                setIfPresent(b, messageType, "value", true);
                return b.build();
            }
            case "google.protobuf.Int32Value":
            case "google.protobuf.UInt32Value": {
                DynamicMessage.Builder b = DynamicMessage.newBuilder(messageType);
                setIfPresent(b, messageType, "value", 0);
                return b.build();
            }
            case "google.protobuf.Int64Value":
            case "google.protobuf.UInt64Value": {
                DynamicMessage.Builder b = DynamicMessage.newBuilder(messageType);
                setIfPresent(b, messageType, "value", 0L);
                return b.build();
            }
            case "google.protobuf.FloatValue": {
                DynamicMessage.Builder b = DynamicMessage.newBuilder(messageType);
                setIfPresent(b, messageType, "value", 0.0f);
                return b.build();
            }
            case "google.protobuf.DoubleValue": {
                DynamicMessage.Builder b = DynamicMessage.newBuilder(messageType);
                setIfPresent(b, messageType, "value", 0.0d);
                return b.build();
            }
            case "google.protobuf.BytesValue": {
                DynamicMessage.Builder b = DynamicMessage.newBuilder(messageType);
                setIfPresent(b, messageType, "value", ByteString.EMPTY);
                return b.build();
            }
            case "google.protobuf.Empty":
            case "google.protobuf.Struct":
            case "google.protobuf.Value":
            case "google.protobuf.Any":
                // an empty instance renders cleanly as {} / null and avoids over-specifying
                return DynamicMessage.getDefaultInstance(messageType);
            default:
                return null;
        }
    }

    private void setIfPresent(DynamicMessage.Builder builder, Descriptors.Descriptor type, String fieldName, Object value) {
        Descriptors.FieldDescriptor field = type.findFieldByName(fieldName);
        if (field != null) {
            builder.setField(field, value);
        }
    }
}
