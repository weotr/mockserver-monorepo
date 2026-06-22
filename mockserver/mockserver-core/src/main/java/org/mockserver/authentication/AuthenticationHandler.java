package org.mockserver.authentication;

import org.mockserver.model.HttpRequest;

import java.util.Map;
import java.util.Set;

public interface AuthenticationHandler {

    /**
     * Legacy boolean SPI: returns true if the control-plane request is authenticated.
     * Implementations may throw {@link AuthenticationException} to signal a 401 with a
     * specific reason. Existing and third-party handlers implement only this method.
     */
    boolean controlPlaneRequestAuthenticated(HttpRequest request);

    /**
     * Richer authentication that additionally surfaces the VERIFIED principal, the
     * source of verification, and a redaction-safe subset of claims/scopes for audit.
     * <p>
     * Default-adapts the legacy {@link #controlPlaneRequestAuthenticated} so existing
     * handlers need ZERO changes: a true outcome becomes an authenticated-but-anonymous
     * result (principal null, source "none"), preserving byte-for-byte behaviour. May
     * throw {@link AuthenticationException} (401) exactly as the boolean method does.
     */
    default AuthenticationResult authenticate(HttpRequest request) {
        return controlPlaneRequestAuthenticated(request)
            ? AuthenticationResult.authenticated(null, "none", Map.of(), Set.of())
            : AuthenticationResult.unauthenticated();
    }
}
