package org.mockserver.authentication;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable outcome of authenticating a control-plane request, carrying not just
 * the boolean decision but the VERIFIED principal, the source of that verification,
 * and a redaction-safe subset of token claims and scopes.
 * <p>
 * This is the richer return type for {@link AuthenticationHandler#authenticate}. The
 * legacy boolean SPI ({@link AuthenticationHandler#controlPlaneRequestAuthenticated})
 * is preserved and adapted via a default method, so existing (and third-party)
 * handlers keep working unchanged and the control plane behaves byte-for-byte
 * identically when no enriched handler is configured.
 * <p>
 * Construct only via the {@link #authenticated} / {@link #unauthenticated} factories;
 * all collections are wrapped unmodifiable and never null.
 */
public class AuthenticationResult {

    private static final AuthenticationResult UNAUTHENTICATED = new AuthenticationResult(false, null, "none", Collections.emptyMap(), Collections.emptySet());

    private final boolean authenticated;
    private final String principal;
    private final String principalSource;
    private final Map<String, Object> claims;
    private final Set<String> scopes;

    private AuthenticationResult(boolean authenticated, String principal, String principalSource, Map<String, Object> claims, Set<String> scopes) {
        this.authenticated = authenticated;
        this.principal = principal;
        this.principalSource = principalSource != null ? principalSource : "none";
        this.claims = claims == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(claims));
        this.scopes = scopes == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(scopes));
    }

    /**
     * An authenticated result.
     *
     * @param principal       the verified principal (e.g. the {@code sub} claim), or null for an authenticated-but-anonymous handler (e.g. mTLS-only)
     * @param principalSource how the principal was verified: "verified-oidc" / "verified-mtls" / "verified-jwt" / "none"
     * @param claims          a redaction-safe subset of token claims (NEVER the raw token); may be empty, never null
     * @param scopes          normalised granted scopes; may be empty, never null
     */
    public static AuthenticationResult authenticated(String principal, String principalSource, Map<String, Object> claims, Set<String> scopes) {
        return new AuthenticationResult(true, principal, principalSource, claims, scopes);
    }

    public static AuthenticationResult unauthenticated() {
        return UNAUTHENTICATED;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public String getPrincipal() {
        return principal;
    }

    public String getPrincipalSource() {
        return principalSource;
    }

    public Map<String, Object> getClaims() {
        return claims;
    }

    public Set<String> getScopes() {
        return scopes;
    }

}
