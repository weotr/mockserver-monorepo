package org.mockserver.scim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;

/**
 * Applies a SCIM 2.0 {@code PatchOp} ({@code urn:ietf:params:scim:api:messages:2.0:PatchOp}) to a
 * stored resource. Supports the three operations — {@code add}, {@code replace}, {@code remove} —
 * over a deliberately small path grammar: a top-level attribute ({@code emails}) or a one-level
 * sub-attribute ({@code name.familyName}).
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@code replace} with a path — set the attribute to the supplied value.</li>
 *   <li>{@code replace} without a path — shallow-merge the supplied value object into the resource.</li>
 *   <li>{@code add} with a path — set a scalar/object, or append to an array attribute
 *       (e.g. {@code emails}, {@code members}).</li>
 *   <li>{@code add} without a path — shallow-merge the supplied value object.</li>
 *   <li>{@code remove} with a path — delete the attribute (or sub-attribute).</li>
 * </ul>
 *
 * <p>The SCIM-managed reserved attributes {@code id}, {@code meta} and {@code schemas} are
 * {@code readOnly} (RFC 7644 §3.5.2). PatchOps that target them — whether by an explicit
 * {@code path} (e.g. {@code "path":"meta"}) or by a no-path merge — are silently ignored as
 * no-ops so a client cannot overwrite the SCIM envelope or the server-managed metadata on the
 * stored copy. Ignoring (rather than returning a 400 mutability error) keeps the mock
 * non-breaking and consistent across {@code add}/{@code replace}/{@code remove}.
 *
 * <p>A malformed PatchOp throws {@link ScimPatchException} carrying the {@code invalidSyntax}
 * SCIM error keyword so the caller can emit a 400 error envelope.
 */
public class ScimPatchApplier {

    /** SCIM-managed reserved attributes that PATCH ops must not be able to mutate. */
    private static boolean isReservedAttribute(String attribute) {
        return "id".equals(attribute) || "meta".equals(attribute) || "schemas".equals(attribute);
    }

    /** Signals a malformed PatchOp; carries the SCIM {@code scimType} keyword. */
    public static class ScimPatchException extends RuntimeException {
        private final String scimType;

        public ScimPatchException(String message, String scimType) {
            super(message);
            this.scimType = scimType;
        }

        public String getScimType() {
            return scimType;
        }
    }

    /**
     * Applies the patch document to a copy of the resource and returns the patched copy.
     *
     * @param resource    the stored resource (not mutated)
     * @param patchOp     the PatchOp document
     * @throws ScimPatchException if the document is malformed
     */
    public ObjectNode apply(ObjectNode resource, ObjectNode patchOp) {
        if (patchOp == null) {
            throw new ScimPatchException("PatchOp body is required", "invalidSyntax");
        }
        JsonNode operations = patchOp.get("Operations");
        if (operations == null || !operations.isArray()) {
            throw new ScimPatchException("PatchOp must contain an Operations array", "invalidSyntax");
        }

        ObjectNode result = resource.deepCopy();
        for (JsonNode operationNode : operations) {
            if (!operationNode.isObject()) {
                throw new ScimPatchException("each Operation must be an object", "invalidSyntax");
            }
            ObjectNode operation = (ObjectNode) operationNode;
            JsonNode opNode = operation.get("op");
            if (opNode == null || !opNode.isTextual()) {
                throw new ScimPatchException("each Operation must have an op", "invalidSyntax");
            }
            String op = opNode.asText().toLowerCase();
            String path = operation.has("path") && !operation.get("path").isNull()
                ? operation.get("path").asText()
                : null;
            JsonNode value = operation.get("value");

            switch (op) {
                case "replace":
                    applyReplace(result, path, value);
                    break;
                case "add":
                    applyAdd(result, path, value);
                    break;
                case "remove":
                    applyRemove(result, path);
                    break;
                default:
                    throw new ScimPatchException("unsupported op: " + op, "invalidSyntax");
            }
        }
        return result;
    }

    private void applyReplace(ObjectNode resource, String path, JsonNode value) {
        if (path == null) {
            if (value == null || !value.isObject()) {
                throw new ScimPatchException("replace without a path requires an object value", "invalidSyntax");
            }
            mergeInto(resource, (ObjectNode) value);
            return;
        }
        if (value == null) {
            throw new ScimPatchException("replace requires a value", "invalidSyntax");
        }
        setAtPath(resource, path, value);
    }

    private void applyAdd(ObjectNode resource, String path, JsonNode value) {
        if (path == null) {
            if (value == null || !value.isObject()) {
                throw new ScimPatchException("add without a path requires an object value", "invalidSyntax");
            }
            mergeInto(resource, (ObjectNode) value);
            return;
        }
        if (value == null) {
            throw new ScimPatchException("add requires a value", "invalidSyntax");
        }
        // append semantics for array attributes; otherwise set
        String[] segments = splitPath(path);
        if (isReservedAttribute(segments[0])) {
            // id/meta/schemas are readOnly (RFC 7644 §3.5.2) — ignore as a no-op
            return;
        }
        if (segments.length == 1) {
            JsonNode existing = resource.get(segments[0]);
            if (existing != null && existing.isArray()) {
                appendTo((ArrayNode) existing, value);
                return;
            }
        }
        setAtPath(resource, path, value);
    }

    private void applyRemove(ObjectNode resource, String path) {
        if (path == null) {
            throw new ScimPatchException("remove requires a path", "invalidSyntax");
        }
        String[] segments = splitPath(path);
        if (isReservedAttribute(segments[0])) {
            // id/meta/schemas are readOnly (RFC 7644 §3.5.2) — ignore as a no-op
            return;
        }
        if (segments.length == 1) {
            resource.remove(segments[0]);
        } else {
            JsonNode parent = resource.get(segments[0]);
            if (parent != null && parent.isObject()) {
                ((ObjectNode) parent).remove(segments[1]);
            }
        }
    }

    private void setAtPath(ObjectNode resource, String path, JsonNode value) {
        String[] segments = splitPath(path);
        if (isReservedAttribute(segments[0])) {
            // id/meta/schemas are readOnly (RFC 7644 §3.5.2) — ignore as a no-op
            return;
        }
        if (segments.length == 1) {
            resource.set(segments[0], value.deepCopy());
        } else {
            JsonNode parentNode = resource.get(segments[0]);
            ObjectNode parent;
            if (parentNode != null && parentNode.isObject()) {
                parent = (ObjectNode) parentNode;
            } else {
                parent = resource.putObject(segments[0]);
            }
            parent.set(segments[1], value.deepCopy());
        }
    }

    private void appendTo(ArrayNode array, JsonNode value) {
        if (value.isArray()) {
            for (JsonNode element : value) {
                array.add(element.deepCopy());
            }
        } else {
            array.add(value.deepCopy());
        }
    }

    private void mergeInto(ObjectNode target, ObjectNode source) {
        Iterator<String> fieldNames = source.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            if (!"id".equals(field) && !"schemas".equals(field) && !"meta".equals(field)) {
                target.set(field, source.get(field).deepCopy());
            }
        }
    }

    private String[] splitPath(String path) {
        String[] segments = path.split("\\.");
        if (segments.length == 0 || segments.length > 2) {
            throw new ScimPatchException("unsupported PATCH path: " + path, "invalidSyntax");
        }
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw new ScimPatchException("malformed PATCH path: " + path, "invalidSyntax");
            }
        }
        return segments;
    }
}
