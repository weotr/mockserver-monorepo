package org.mockserver.authentication.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import org.mockserver.authentication.AuthenticationException;
import org.mockserver.authentication.AuthenticationHandler;
import org.mockserver.authentication.AuthenticationResult;
import org.mockserver.file.FilePath;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.url.URLParser;
import org.slf4j.event.Level;

import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.netty.handler.codec.http.HttpHeaderNames.AUTHORIZATION;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Control-plane authentication handler for an external OIDC IdP: verifies an
 * {@code Authorization: Bearer <jwt>} access token's signature against the IdP's JWK
 * set, asserts issuer, audience, exp/nbf and required scopes, and surfaces the
 * VERIFIED {@code sub} as the principal (source {@code verified-oidc}) along with a
 * redaction-safe subset of claims and the normalised scope set.
 * <p>
 * The JWK set is resolved either directly from {@code controlPlaneOidcJwksUri}, or by
 * fetching {@code {issuer}/.well-known/openid-configuration} and reading its
 * {@code jwks_uri}. Off by default — only constructed when
 * {@code controlPlaneOidcAuthenticationRequired} is enabled.
 * <p>
 * The raw token is NEVER stored or logged; only the redacted claim subset is exposed.
 */
public class OidcAuthenticationHandler implements AuthenticationHandler {

    private static final Set<String> REDACTION_SAFE_CLAIMS = Set.of("sub", "iss", "aud", "scope", "groups", "email");

    private final MockServerLogger mockServerLogger;
    private final String scopeClaim;
    private Throwable initialisationException;
    private OidcJWTValidator validator;

    public OidcAuthenticationHandler(MockServerLogger mockServerLogger, String jwksUri, String issuer, String audience, String scopeClaim, Set<String> requiredScopes) {
        this.mockServerLogger = mockServerLogger;
        this.scopeClaim = isBlank(scopeClaim) ? "scope" : scopeClaim;
        try {
            if (isBlank(issuer) && isBlank(audience)) {
                throw new IllegalArgumentException("at least one of controlPlaneOidcIssuer or controlPlaneOidcAudience must be configured; with neither set any validly-signed token from the configured JWKS would be accepted regardless of who it was minted for");
            }
            requireSecureRemoteUrl(jwksUri, "controlPlaneOidcJwksUri");
            requireSecureRemoteUrl(issuer, "controlPlaneOidcIssuer");
            String resolvedJwksUri = resolveJwksUri(jwksUri, issuer);
            requireSecureRemoteUrl(resolvedJwksUri, "OIDC discovery jwks_uri");
            this.validator = new OidcJWTValidator(
                jwkSource(resolvedJwksUri),
                isNotBlank(audience) ? audience : null,
                isNotBlank(issuer) ? issuer : null,
                this.scopeClaim,
                requiredScopes
            );
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception building OIDC validator for jwksUri:{}issuer:{}")
                    .setArguments(jwksUri, issuer)
                    .setThrowable(throwable)
            );
            this.initialisationException = throwable;
        }
    }

    private static String resolveJwksUri(String jwksUri, String issuer) throws Exception {
        if (isNotBlank(jwksUri)) {
            return jwksUri;
        }
        if (isBlank(issuer)) {
            throw new IllegalArgumentException("controlPlaneOidcJwksUri or controlPlaneOidcIssuer must be configured");
        }
        String discoveryUrl = issuer.endsWith("/")
            ? issuer + ".well-known/openid-configuration"
            : issuer + "/.well-known/openid-configuration";
        JsonNode discovery = ObjectMapperFactory.createObjectMapper().readTree(URI.create(discoveryUrl).toURL());
        JsonNode jwksUriNode = discovery.get("jwks_uri");
        if (jwksUriNode == null || !jwksUriNode.isTextual() || isBlank(jwksUriNode.asText())) {
            throw new IllegalArgumentException("OIDC discovery document at " + discoveryUrl + " did not contain a jwks_uri");
        }
        return jwksUriNode.asText();
    }

    /**
     * Rejects a PLAINTEXT remote URL (http:// to a non-loopback host) so the JWK set /
     * discovery document cannot be fetched over an interceptable channel. Allows:
     * blank values, https:// (any host), http:// to loopback/localhost (local testing),
     * and non-URL file/classpath JWKS paths (used by unit tests).
     */
    private static void requireSecureRemoteUrl(String url, String propertyName) {
        if (isBlank(url) || !URLParser.isFullUrl(url)) {
            return;
        }
        URI uri = URI.create(url);
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return;
        }
        if ("http".equalsIgnoreCase(uri.getScheme()) && isLoopbackHost(uri.getHost())) {
            return;
        }
        throw new IllegalArgumentException(propertyName + " must use https:// for a remote host (plaintext http:// is only permitted to localhost/loopback); was: " + url);
    }

    private static boolean isLoopbackHost(String host) {
        if (isBlank(host)) {
            return false;
        }
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private static JWKSource<SecurityContext> jwkSource(String resolvedJwksUri) throws Exception {
        if (URLParser.isFullUrl(resolvedJwksUri)) {
            return new RemoteJWKSet<>(URI.create(resolvedJwksUri).toURL());
        }
        return new ImmutableJWKSet<>(JWKSet.load(new File(FilePath.absolutePathFromClassPathOrPath(resolvedJwksUri))));
    }

    @Override
    public AuthenticationResult authenticate(HttpRequest request) {
        if (validator == null) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("OIDC control plane request failed authentication because OIDC validator is not initialised:{}")
                    .setArguments(requestSummary(request))
                    .setThrowable(initialisationException)
            );
            throw new AuthenticationException("OIDC validator is not initialised", false);
        }
        String token = extractBearerToken(request);
        JWTClaimsSet claims = validator.validate(token);
        String principal = claims.getSubject();
        Set<String> scopes = OidcScopeParser.parseScopes(claims, scopeClaim);
        Map<String, Object> redactedClaims = redactedClaims(claims);
        return AuthenticationResult.authenticated(principal, "verified-oidc", redactedClaims, scopes);
    }

    @Override
    public boolean controlPlaneRequestAuthenticated(HttpRequest request) {
        return authenticate(request).isAuthenticated();
    }

    private String extractBearerToken(HttpRequest request) {
        List<String> authorizationHeaders = request.getHeader(AUTHORIZATION.toString());
        if (authorizationHeaders.isEmpty()) {
            logAuthorisationFailure(request, "no authorization header found");
        }
        for (String authorizationHeader : authorizationHeaders) {
            int idx = authorizationHeader.indexOf(' ');
            if (idx <= 0) {
                logAuthorisationFailure(request, "authorization header is invalid format");
            } else {
                String headerPrefix = authorizationHeader.substring(0, idx);
                if (isBlank(headerPrefix)) {
                    logAuthorisationFailure(request, "authorization type must be specified for authorization header");
                } else if ("Bearer".equalsIgnoreCase(headerPrefix)) {
                    return authorizationHeader.substring(idx + 1).trim();
                } else {
                    logAuthorisationFailure(request, "only \"Bearer\" supported for authorization header");
                }
            }
        }
        // unreachable: every branch above throws
        throw new AuthenticationException("no authorization header found", false);
    }

    private Map<String, Object> redactedClaims(JWTClaimsSet claims) {
        Map<String, Object> redacted = new LinkedHashMap<>();
        for (String claimName : REDACTION_SAFE_CLAIMS) {
            Object value = claims.getClaim(claimName);
            if (value != null) {
                redacted.put(claimName, value);
            }
        }
        return redacted;
    }

    private void logAuthorisationFailure(HttpRequest request, String failureReason) {
        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(Level.ERROR)
                .setMessageFormat("OIDC control plane request failed:{}for request:{}")
                .setArguments(failureReason, requestSummary(request))
        );
        throw new AuthenticationException(failureReason, false);
    }

    /**
     * A token-free, single-line summary of the rejected request for the ERROR log: method, path and
     * remote address only. The full {@link HttpRequest} is deliberately NOT logged here because it
     * carries the {@code Authorization: Bearer <jwt>} header — logging the whole request would write
     * the rejected control-plane token to the server log in cleartext regardless of any opt-in
     * redaction switch.
     */
    static String requestSummary(HttpRequest request) {
        if (request == null) {
            return "<no request>";
        }
        String remoteAddress = request.getRemoteAddress();
        return request.getMethod("") + " " + request.getPath().getValue()
            + (isBlank(remoteAddress) ? "" : " from " + remoteAddress);
    }

}
