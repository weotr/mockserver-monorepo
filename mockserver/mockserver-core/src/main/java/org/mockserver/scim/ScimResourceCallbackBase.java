package org.mockserver.scim;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.mock.action.ExpectationResponseCallback;
import org.mockserver.mock.crud.CrudDataStore;
import org.mockserver.model.HttpRequest;
import org.mockserver.scim.ScimResourceStore.Provider;

import java.util.function.Function;

/**
 * Common base for the SCIM resource callbacks. Resolves the provider, applies the bearer gate, and
 * determines whether the request targets the {@code Users} or {@code Groups} collection (so a
 * single callback class can serve both resource types).
 */
abstract class ScimResourceCallbackBase implements ExpectationResponseCallback {

    /**
     * Resolves the resource type from the request path relative to the provider base path.
     */
    static ScimShaper.ResourceType resourceType(HttpRequest request, Provider provider) {
        String path = request.getPath().getValue();
        String groupsPrefix = provider.getBasePath() + "/" + ScimShaper.ResourceType.GROUP.getPathSegment();
        if (path.equals(groupsPrefix) || path.startsWith(groupsPrefix + "/")) {
            return ScimShaper.ResourceType.GROUP;
        }
        return ScimShaper.ResourceType.USER;
    }

    static CrudDataStore storeFor(Provider provider, ScimShaper.ResourceType type) {
        return type == ScimShaper.ResourceType.GROUP ? provider.getGroups() : provider.getUsers();
    }

    /**
     * Atomic read-modify-write for a single SCIM resource.
     *
     * <p>SCIM {@code PUT} and {@code PATCH} are read-modify-write sequences: the current resource is
     * read, the change is computed against it, and the result is written back. {@link CrudDataStore}
     * exposes each step ({@link CrudDataStore#getById}, {@link CrudDataStore#update}) as an
     * individually-locked operation, but nothing spans the read and the write, so two concurrent
     * updates to the same resource could each read the same base state and the second write would
     * silently clobber the first (a lost update).
     *
     * <p>This helper closes that race by serialising the whole read-apply-write on the store
     * instance's monitor. Every SCIM mutation of a given store goes through here (or, for create,
     * through the store's own atomic {@code create}), and SCIM stores are never shared with the
     * generic CRUD dispatcher, so the monitor is a complete mutual-exclusion boundary for the data
     * those callbacks own. (An equivalent fix would be an atomic {@code computeIfPresent(id, fn)} on
     * {@link CrudDataStore} itself holding its own write lock across the read-apply-write; that lives
     * in a package this code does not own, so the synchronisation is kept here instead.)
     *
     * @param store     the resource store
     * @param id        the resource id
     * @param transform given the current stored resource, produces the full replacement resource to
     *                  persist; invoked while the store monitor is held so it observes (and is the
     *                  sole writer of) the latest state
     * @return the persisted resource, or {@code null} if no resource with that id currently exists
     */
    static ObjectNode updateAtomically(CrudDataStore store, String id, Function<ObjectNode, ObjectNode> transform) {
        synchronized (store) {
            ObjectNode existing = store.getById(id);
            if (existing == null) {
                return null;
            }
            ObjectNode replacement = transform.apply(existing);
            return store.update(id, replacement);
        }
    }
}
