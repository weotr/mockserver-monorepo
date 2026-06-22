package org.mockserver.scim;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.mock.crud.CrudDataStore;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.scim.ScimResourceStore.Provider;

import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.scim.ScimCallbackSupport.*;

/**
 * Serves the SCIM single-resource endpoints: {@code GET}, {@code PUT}, {@code PATCH}, {@code DELETE}
 * on {@code <basePath>/Users|Groups/{id}}.
 */
public class ScimResourceCallback extends ScimResourceCallbackBase {

    @Override
    public HttpResponse handle(HttpRequest request) {
        Provider provider = provider(request);
        if (provider == null) {
            return scimError(404, "no SCIM provider registered for this path", null);
        }
        HttpResponse unauthorized = bearerGate(request, provider);
        if (unauthorized != null) {
            return unauthorized;
        }

        ScimShaper.ResourceType type = resourceType(request, provider);
        CrudDataStore store = storeFor(provider, type);
        String id = resourceId(request, provider, type);
        if (id == null) {
            return scimError(404, "resource not found", null);
        }

        String method = request.getMethod().getValue();
        switch (method.toUpperCase()) {
            case "GET":
                return get(request, provider, type, store, id);
            case "PUT":
                return put(request, provider, type, store, id);
            case "PATCH":
                return patch(request, provider, type, store, id);
            case "DELETE":
                return delete(store, id);
            default:
                return scimError(405, "method not allowed", null);
        }
    }

    private HttpResponse get(HttpRequest request, Provider provider, ScimShaper.ResourceType type,
                             CrudDataStore store, String id) {
        ObjectNode stored = store.getById(id);
        if (stored == null) {
            return notFound(type, id);
        }
        return json(200, SHAPER.shapeResource(stored, type, publicBaseUrl(request), provider.getBasePath(), false));
    }

    private HttpResponse put(HttpRequest request, Provider provider, ScimShaper.ResourceType type,
                             CrudDataStore store, String id) {
        ObjectNode payload = parseObject(request.getBodyAsString());
        if (payload == null) {
            return scimError(400, "request body must be a JSON object", "invalidSyntax");
        }
        String requiredAttribute = type == ScimShaper.ResourceType.USER ? "userName" : "displayName";
        if (!payload.hasNonNull(requiredAttribute) || payload.get(requiredAttribute).asText().isEmpty()) {
            return scimError(400, requiredAttribute + " is required", "invalidValue");
        }
        // read-modify-write must be atomic so a concurrent update is not lost; the created-timestamp
        // is preserved against the resource state observed inside the critical section
        ObjectNode updated = updateAtomically(store, id, existing -> {
            if (existing.has("meta") && existing.get("meta").isObject() && existing.get("meta").has("created")) {
                ObjectNode meta = payload.has("meta") && payload.get("meta").isObject()
                    ? (ObjectNode) payload.get("meta")
                    : payload.putObject("meta");
                meta.put("created", existing.get("meta").get("created").asText());
            }
            return payload;
        });
        if (updated == null) {
            return notFound(type, id);
        }
        return json(200, SHAPER.shapeResource(updated, type, publicBaseUrl(request), provider.getBasePath(), false));
    }

    private HttpResponse patch(HttpRequest request, Provider provider, ScimShaper.ResourceType type,
                               CrudDataStore store, String id) {
        if (!provider.isEnforcePatch()) {
            return scimError(501, "PATCH is not supported", null);
        }
        ObjectNode patchOp = parseObject(request.getBodyAsString());
        if (patchOp == null) {
            return scimError(400, "request body must be a JSON object", "invalidSyntax");
        }
        // the PatchOp is applied against the resource state observed inside the critical section, so
        // concurrent PATCHes serialise and no increment is lost (read-modify-write is atomic)
        ObjectNode updated;
        try {
            ScimPatchApplier applier = new ScimPatchApplier();
            updated = updateAtomically(store, id, existing -> applier.apply(existing, patchOp));
        } catch (ScimPatchApplier.ScimPatchException e) {
            return scimError(400, e.getMessage(), e.getScimType());
        }
        if (updated == null) {
            return notFound(type, id);
        }
        return json(200, SHAPER.shapeResource(updated, type, publicBaseUrl(request), provider.getBasePath(), false));
    }

    private HttpResponse delete(CrudDataStore store, String id) {
        boolean removed = store.delete(id);
        if (!removed) {
            return scimError(404, "resource not found", null);
        }
        return response().withStatusCode(204);
    }

    private HttpResponse notFound(ScimShaper.ResourceType type, String id) {
        String singular = type == ScimShaper.ResourceType.USER ? "User" : "Group";
        return scimError(404, singular + " " + id + " not found", null);
    }
}
