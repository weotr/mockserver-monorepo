package org.mockserver.scim;

import org.mockserver.mock.crud.CrudDataStore;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory state backing the mock SCIM 2.0 provider.
 *
 * <p>Holds one {@link Provider} per registered {@code basePath} (e.g. {@code /scim/v2}). Each
 * provider owns a {@code Users} and a {@code Groups} {@link CrudDataStore} plus the per-provider
 * enforcement flags (bearer token, filter, patch). The SCIM class callbacks are instantiated fresh
 * per request and therefore cannot share instance state, so they resolve their provider here by the
 * request path.
 *
 * <p>This is a process-wide singleton, mirroring {@link org.mockserver.oidc.OidcAuthorizationStore}
 * and the other in-memory registries. {@link #reset()} clears all providers and is wired into
 * {@code HttpState.reset()}.
 */
public class ScimResourceStore {

    private static final ScimResourceStore INSTANCE = new ScimResourceStore();

    private final ConcurrentHashMap<String, Provider> providers = new ConcurrentHashMap<>();

    ScimResourceStore() {
    }

    public static ScimResourceStore getInstance() {
        return INSTANCE;
    }

    /**
     * Registers (or replaces) the provider serving the given base path. The most recently registered
     * provider for a base path wins, so re-running {@code PUT /mockserver/scim} with the same base
     * path refreshes the seeded data and enforcement flags.
     */
    public void registerProvider(Provider provider) {
        providers.put(provider.basePath, provider);
    }

    /**
     * Finds the provider whose base path is the longest prefix of the given request path, or
     * {@code null} if none registered. Longest-prefix matching lets overlapping base paths coexist.
     */
    public Provider providerForRequestPath(String requestPath) {
        if (requestPath == null) {
            return null;
        }
        Provider best = null;
        for (Provider provider : providers.values()) {
            if (requestPath.equals(provider.basePath) || requestPath.startsWith(provider.basePath + "/")) {
                if (best == null || provider.basePath.length() > best.basePath.length()) {
                    best = provider;
                }
            }
        }
        return best;
    }

    public Provider providerForBasePath(String basePath) {
        return providers.get(basePath);
    }

    public void reset() {
        providers.clear();
    }

    /**
     * Immutable description of a registered SCIM provider: its Users and Groups stores plus the
     * per-provider enforcement flags.
     */
    public static class Provider {
        final String basePath;
        final CrudDataStore users;
        final CrudDataStore groups;
        final boolean requireBearerToken;
        final String expectedBearerToken;
        final boolean enforceFilter;
        final boolean enforcePatch;

        public Provider(String basePath, CrudDataStore users, CrudDataStore groups,
                        boolean requireBearerToken, String expectedBearerToken,
                        boolean enforceFilter, boolean enforcePatch) {
            this.basePath = basePath;
            this.users = users;
            this.groups = groups;
            this.requireBearerToken = requireBearerToken;
            this.expectedBearerToken = expectedBearerToken;
            this.enforceFilter = enforceFilter;
            this.enforcePatch = enforcePatch;
        }

        public String getBasePath() {
            return basePath;
        }

        public CrudDataStore getUsers() {
            return users;
        }

        public CrudDataStore getGroups() {
            return groups;
        }

        public boolean isRequireBearerToken() {
            return requireBearerToken;
        }

        public String getExpectedBearerToken() {
            return expectedBearerToken;
        }

        public boolean isEnforceFilter() {
            return enforceFilter;
        }

        public boolean isEnforcePatch() {
            return enforcePatch;
        }
    }
}
