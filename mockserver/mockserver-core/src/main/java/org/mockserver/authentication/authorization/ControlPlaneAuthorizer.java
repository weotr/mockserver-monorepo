package org.mockserver.authentication.authorization;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Coarse, hierarchical authorization for the control plane ("RBAC by standards
 * conformance"): maps a verified principal's scopes/groups through a configured
 * {@code value -> role} mapping into the set of granted {@link ControlPlaneRole}s, then
 * decides whether those granted roles satisfy the role REQUIRED by an operation.
 * <p>
 * The required role is the coarse read/mutate split: a read requires {@link
 * ControlPlaneRole#READ}; any other (mutating) operation requires {@link
 * ControlPlaneRole#MUTATE}. Granted roles are hierarchical, so an {@code ADMIN}
 * scope satisfies everything and a {@code MUTATE} scope also satisfies reads.
 * <p>
 * Fail-closed by construction: a principal with no scopes, or whose scopes map to no
 * role, is granted no roles and is denied every operation. Authorization therefore
 * requires a verified principal whose scopes/groups are mapped — i.e. control-plane
 * OIDC authentication should be enabled alongside it.
 * <p>
 * Immutable and side-effect free; the enforcement decision (HTTP 403, audit outcome)
 * is the caller's responsibility.
 */
public class ControlPlaneAuthorizer {

    private final Map<String, ControlPlaneRole> scopeToRole;

    /**
     * @param scopeToRole mapping from a verified scope/group VALUE to the coarse role it
     *                    grants; unrecognised roles must already be filtered out by the
     *                    caller (parsing keeps only read/mutate/admin). May be empty or
     *                    null (then nothing is ever granted).
     */
    public ControlPlaneAuthorizer(Map<String, ControlPlaneRole> scopeToRole) {
        this.scopeToRole = scopeToRole == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(scopeToRole));
    }

    /**
     * Maps the principal's verified scopes through the configured mapping into the set
     * of granted roles. Scopes not present in the mapping contribute nothing. Never null.
     */
    public Set<ControlPlaneRole> grantedRoles(Set<String> verifiedScopes) {
        Set<ControlPlaneRole> granted = EnumSet.noneOf(ControlPlaneRole.class);
        if (verifiedScopes == null || scopeToRole.isEmpty()) {
            return granted;
        }
        for (String scope : verifiedScopes) {
            ControlPlaneRole role = scopeToRole.get(scope);
            if (role != null) {
                granted.add(role);
            }
        }
        return granted;
    }

    /**
     * The coarse role required for an operation: {@link ControlPlaneRole#READ} for a
     * read, {@link ControlPlaneRole#MUTATE} for everything else (a mutation).
     */
    public ControlPlaneRole requiredRole(boolean isRead) {
        return isRead ? ControlPlaneRole.READ : ControlPlaneRole.MUTATE;
    }

    /**
     * @return true if the principal's {@code verifiedScopes} grant a role that satisfies
     * the role required by the operation (read vs mutate). Fail-closed: an empty granted
     * set never satisfies anything.
     */
    public boolean isAuthorized(Set<String> verifiedScopes, boolean isRead) {
        ControlPlaneRole required = requiredRole(isRead);
        for (ControlPlaneRole granted : grantedRoles(verifiedScopes)) {
            if (granted.satisfies(required)) {
                return true;
            }
        }
        return false;
    }
}
