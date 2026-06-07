package org.mockserver.oidc;

import com.fasterxml.jackson.databind.ObjectWriter;
import org.mockserver.authentication.jwt.JWKGenerator;
import org.mockserver.authentication.jwt.JWTGenerator;
import org.mockserver.keys.AsymmetricKeyGenerator;
import org.mockserver.keys.AsymmetricKeyPair;
import org.mockserver.keys.AsymmetricKeyPairAlgorithm;
import org.mockserver.mock.Expectation;
import org.mockserver.serialization.ObjectMapperFactory;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Generates MockServer {@link Expectation}s that serve a complete set of OIDC/OAuth2
 * identity provider endpoints: discovery, JWKS, token, userinfo, introspection, and
 * revocation. All tokens are signed with a freshly generated RSA key pair whose
 * public key is exposed via the JWKS endpoint, so OIDC-aware clients can validate
 * tokens end-to-end without any external infrastructure.
 *
 * <p>Usage mirrors the WSDL and OpenAPI importers: call
 * {@link #generate(OidcProviderConfiguration)} with a configuration (or defaults)
 * and upsert the returned expectations into the mock server.
 */
public class OidcProviderGenerator {

    private static final String APPLICATION_JSON = "application/json; charset=utf-8";

    private final ObjectWriter objectWriter = ObjectMapperFactory.createObjectMapper(true, false);

    /**
     * Generates OIDC provider expectations from the given configuration.
     *
     * @param config the provider configuration (must not be null)
     * @return the generated expectations (one per endpoint, never empty)
     */
    public List<Expectation> generate(OidcProviderConfiguration config) {
        if (config == null) {
            throw new IllegalArgumentException("OIDC provider configuration is required");
        }

        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(
            AsymmetricKeyPairAlgorithm.RSA2048_SHA256
        );
        JWKGenerator jwkGenerator = new JWKGenerator();
        JWTGenerator jwtGenerator = new JWTGenerator(keyPair);

        String jwksJson = jwkGenerator.generateJWK(keyPair);

        String issuer = config.getIssuer();
        String effectiveIssuer = config.isWrongIssuer() ? issuer + "/wrong" : issuer;
        String scopeString = String.join(" ", config.getScopes());

        Instant now = Instant.now();
        long iat = now.getEpochSecond();
        long exp;
        if (config.isIssueExpiredToken()) {
            exp = now.minusSeconds(3600).getEpochSecond();
        } else {
            exp = now.plusSeconds(config.getTokenExpirySeconds()).getEpochSecond();
        }

        // Build JWT claims
        Map<String, Serializable> claims = new LinkedHashMap<>();
        claims.put("iss", effectiveIssuer);
        claims.put("sub", config.getSubject());
        claims.put("aud", config.getAudience());
        claims.put("iat", iat);
        claims.put("exp", exp);
        claims.put("scope", scopeString);
        if (config.getAdditionalClaims() != null) {
            claims.putAll(config.getAdditionalClaims());
        }

        String accessToken = jwtGenerator.signJWT(claims);
        String idToken = jwtGenerator.signJWT(claims);

        if (config.isTamperedSignature()) {
            accessToken = tamperSignature(accessToken);
            idToken = tamperSignature(idToken);
        }

        List<Expectation> expectations = new ArrayList<>();

        // 1. Discovery document
        expectations.add(buildDiscoveryExpectation(config));

        // 2. JWKS endpoint
        expectations.add(buildJwksExpectation(config, jwksJson));

        // 3. Token endpoint
        expectations.add(buildTokenExpectation(config, accessToken, idToken, scopeString));

        // 4. Userinfo endpoint
        expectations.add(buildUserinfoExpectation(config));

        // 5. Introspection endpoint
        expectations.add(buildIntrospectionExpectation(config));

        // 6. Revocation endpoint
        expectations.add(buildRevocationExpectation(config));

        return expectations;
    }

    private Expectation buildDiscoveryExpectation(OidcProviderConfiguration config) {
        String issuer = config.getIssuer();
        Map<String, Object> discovery = new LinkedHashMap<>();
        discovery.put("issuer", issuer);
        discovery.put("authorization_endpoint", issuer + config.getAuthorizePath());
        discovery.put("token_endpoint", issuer + config.getTokenPath());
        discovery.put("userinfo_endpoint", issuer + config.getUserinfoPath());
        discovery.put("jwks_uri", issuer + config.getJwksPath());
        discovery.put("introspection_endpoint", issuer + config.getIntrospectPath());
        discovery.put("revocation_endpoint", issuer + config.getRevokePath());
        discovery.put("response_types_supported", Arrays.asList("code", "token", "id_token", "code token", "code id_token"));
        discovery.put("grant_types_supported", Arrays.asList(
            "authorization_code", "client_credentials", "refresh_token",
            "urn:ietf:params:oauth:grant-type:device_code"
        ));
        discovery.put("id_token_signing_alg_values_supported", Arrays.asList("RS256"));
        discovery.put("scopes_supported", config.getScopes());
        discovery.put("subject_types_supported", Arrays.asList("public"));

        return new Expectation(
            request()
                .withMethod("GET")
                .withPath("/.well-known/openid-configuration")
        )
            .withId("oidc.discovery")
            .thenRespond(response()
                .withStatusCode(200)
                .withHeader("content-type", APPLICATION_JSON)
                .withBody(serializeToJson(discovery)));
    }

    private Expectation buildJwksExpectation(OidcProviderConfiguration config, String jwksJson) {
        return new Expectation(
            request()
                .withMethod("GET")
                .withPath(config.getJwksPath())
        )
            .withId("oidc.jwks")
            .thenRespond(response()
                .withStatusCode(200)
                .withHeader("content-type", APPLICATION_JSON)
                .withBody(jwksJson));
    }

    private Expectation buildTokenExpectation(OidcProviderConfiguration config,
                                              String accessToken, String idToken, String scopeString) {
        Map<String, Object> tokenResponse = new LinkedHashMap<>();
        tokenResponse.put("access_token", accessToken);
        tokenResponse.put("id_token", idToken);
        tokenResponse.put("token_type", "Bearer");
        tokenResponse.put("expires_in", config.getTokenExpirySeconds());
        tokenResponse.put("scope", scopeString);

        return new Expectation(
            request()
                .withMethod("POST")
                .withPath(config.getTokenPath())
        )
            .withId("oidc.token")
            .thenRespond(response()
                .withStatusCode(200)
                .withHeader("content-type", APPLICATION_JSON)
                .withBody(serializeToJson(tokenResponse)));
    }

    private Expectation buildUserinfoExpectation(OidcProviderConfiguration config) {
        Map<String, Object> userinfo = new LinkedHashMap<>();
        userinfo.put("sub", config.getSubject());
        if (config.getAdditionalClaims() != null) {
            userinfo.putAll(config.getAdditionalClaims());
        }

        return new Expectation(
            request()
                .withMethod("GET")
                .withPath(config.getUserinfoPath())
        )
            .withId("oidc.userinfo")
            .thenRespond(response()
                .withStatusCode(200)
                .withHeader("content-type", APPLICATION_JSON)
                .withBody(serializeToJson(userinfo)));
    }

    private Expectation buildIntrospectionExpectation(OidcProviderConfiguration config) {
        Map<String, Object> introspection = new LinkedHashMap<>();
        boolean active = !config.isIssueExpiredToken();
        introspection.put("active", active);
        introspection.put("sub", config.getSubject());
        introspection.put("iss", config.getIssuer());
        introspection.put("aud", config.getAudience());
        introspection.put("scope", String.join(" ", config.getScopes()));
        if (config.getAdditionalClaims() != null) {
            introspection.putAll(config.getAdditionalClaims());
        }

        return new Expectation(
            request()
                .withMethod("POST")
                .withPath(config.getIntrospectPath())
        )
            .withId("oidc.introspect")
            .thenRespond(response()
                .withStatusCode(200)
                .withHeader("content-type", APPLICATION_JSON)
                .withBody(serializeToJson(introspection)));
    }

    private Expectation buildRevocationExpectation(OidcProviderConfiguration config) {
        return new Expectation(
            request()
                .withMethod("POST")
                .withPath(config.getRevokePath())
        )
            .withId("oidc.revoke")
            .thenRespond(response()
                .withStatusCode(200)
                .withHeader("content-type", APPLICATION_JSON)
                .withBody(""));
    }

    /**
     * Tampers with the signature segment of a JWT (the third dot-separated part)
     * by replacing the first character, causing signature verification to fail.
     */
    private static String tamperSignature(String jwt) {
        int lastDot = jwt.lastIndexOf('.');
        if (lastDot < 0) {
            return jwt;
        }
        String signature = jwt.substring(lastDot + 1);
        if (signature.isEmpty()) {
            return jwt;
        }
        char first = signature.charAt(0);
        char replacement = (first == 'A') ? 'B' : 'A';
        return jwt.substring(0, lastDot + 1) + replacement + signature.substring(1);
    }

    private String serializeToJson(Object value) {
        try {
            return objectWriter.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize OIDC response to JSON", e);
        }
    }
}
