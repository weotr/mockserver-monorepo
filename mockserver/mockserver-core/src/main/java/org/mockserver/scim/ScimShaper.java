package org.mockserver.scim;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

/**
 * Shapes raw stored resources into SCIM 2.0 wire representations and builds the SCIM envelopes
 * (ListResponse and Error). Responsible for injecting the {@code schemas}, {@code id}, and
 * {@code meta} attributes every SCIM resource must carry.
 *
 * <p>SCIM responses use the {@code application/scim+json} media type and a small set of well-known
 * schema URNs defined in RFC 7643/7644.
 */
public class ScimShaper {

    public static final String CONTENT_TYPE = "application/scim+json";

    public static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    public static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";
    public static final String LIST_RESPONSE_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    public static final String ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error";
    public static final String PATCH_OP_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    public static final String SERVICE_PROVIDER_CONFIG_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig";

    private static final JsonNodeFactory NODE_FACTORY = JsonNodeFactory.instance;

    /**
     * The two SCIM resource types served by this provider, expressed as the singular resource name
     * used in URLs ({@code Users}, {@code Groups}) mapped to its core schema URN.
     */
    public enum ResourceType {
        USER("Users", USER_SCHEMA),
        GROUP("Groups", GROUP_SCHEMA);

        private final String pathSegment;
        private final String schema;

        ResourceType(String pathSegment, String schema) {
            this.pathSegment = pathSegment;
            this.schema = schema;
        }

        public String getPathSegment() {
            return pathSegment;
        }

        public String getSchema() {
            return schema;
        }
    }

    /**
     * Shapes a stored resource into its SCIM wire form: ensures {@code schemas} contains the
     * resource's core schema, ensures {@code id} is present, and injects/refreshes {@code meta}.
     *
     * @param stored        the raw stored resource (already carries the persisted {@code id})
     * @param type          the resource type (User/Group)
     * @param publicBaseUrl scheme+authority for the {@code meta.location} (may be empty/null)
     * @param basePath      the provider base path (e.g. {@code /scim/v2})
     * @param created       {@code true} when this is a freshly created resource (sets meta.created)
     * @return a new shaped {@link ObjectNode} (the input is not mutated)
     */
    public ObjectNode shapeResource(ObjectNode stored, ResourceType type, String publicBaseUrl,
                                    String basePath, boolean created) {
        ObjectNode resource = stored.deepCopy();

        // schemas — ensure the core schema is present (preserve any extension schemas already set)
        if (!resource.has("schemas") || !resource.get("schemas").isArray() || resource.get("schemas").isEmpty()) {
            ArrayNode schemas = resource.putArray("schemas");
            schemas.add(type.getSchema());
        }

        String id = resource.has("id") ? resource.get("id").asText() : null;

        // meta — resourceType, created, lastModified, location, version
        String now = Instant.now().toString();
        ObjectNode meta = resource.has("meta") && resource.get("meta").isObject()
            ? (ObjectNode) resource.get("meta")
            : resource.putObject("meta");
        meta.put("resourceType", singular(type));
        // preserve an existing created timestamp (persisted at create time); only stamp one when
        // the resource has none (e.g. seeded initial data) or on an explicit create
        if (!meta.has("created") || (created && meta.get("created").asText().isEmpty())) {
            meta.put("created", now);
        }
        meta.put("lastModified", now);
        if (id != null) {
            String location = (publicBaseUrl == null ? "" : publicBaseUrl) + basePath + "/" + type.getPathSegment() + "/" + id;
            meta.put("location", location);
        }
        meta.put("version", "W/\"" + Integer.toHexString((now + (id == null ? "" : id)).hashCode()) + "\"");
        resource.set("meta", meta);

        return resource;
    }

    /**
     * Builds a SCIM ListResponse envelope around the supplied (already shaped) resources.
     *
     * @param resources    the page of resources to include
     * @param totalResults total number of matching resources (before pagination)
     * @param startIndex   1-based index of the first resource in this page
     */
    public ObjectNode listResponse(List<ObjectNode> resources, int totalResults, int startIndex) {
        ObjectNode envelope = NODE_FACTORY.objectNode();
        ArrayNode schemas = envelope.putArray("schemas");
        schemas.add(LIST_RESPONSE_SCHEMA);
        envelope.put("totalResults", totalResults);
        envelope.put("startIndex", startIndex);
        envelope.put("itemsPerPage", resources.size());
        ArrayNode resourcesNode = envelope.putArray("Resources");
        for (ObjectNode resource : resources) {
            resourcesNode.add(resource);
        }
        return envelope;
    }

    /**
     * Builds a SCIM Error envelope.
     *
     * @param status   the HTTP status code (as a string, per RFC 7644)
     * @param detail   a human-readable description
     * @param scimType the SCIM detail error keyword (may be null, e.g. for 401/404/500)
     */
    public ObjectNode error(int status, String detail, String scimType) {
        ObjectNode envelope = NODE_FACTORY.objectNode();
        ArrayNode schemas = envelope.putArray("schemas");
        schemas.add(ERROR_SCHEMA);
        if (scimType != null) {
            envelope.put("scimType", scimType);
        }
        envelope.put("detail", detail);
        envelope.put("status", String.valueOf(status));
        return envelope;
    }

    private static String singular(ResourceType type) {
        return type == ResourceType.USER ? "User" : "Group";
    }
}
