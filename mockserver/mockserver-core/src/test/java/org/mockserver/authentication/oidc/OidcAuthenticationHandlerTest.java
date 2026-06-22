package org.mockserver.authentication.oidc;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import org.mockserver.authentication.AuthenticationException;
import org.mockserver.authentication.AuthenticationResult;
import org.mockserver.authentication.jwt.JWKGenerator;
import org.mockserver.authentication.jwt.JWTGenerator;
import org.mockserver.keys.AsymmetricKeyGenerator;
import org.mockserver.keys.AsymmetricKeyPair;
import org.mockserver.keys.AsymmetricKeyPairAlgorithm;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.test.TempFileWriter;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static io.netty.handler.codec.http.HttpHeaderNames.AUTHORIZATION;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertThrows;
import static org.mockserver.model.HttpRequest.request;

public class OidcAuthenticationHandlerTest {

    private static final MockServerLogger mockServerLogger = new MockServerLogger();
    private static final String ISSUER = "https://idp.example.com";
    private static final String AUDIENCE = "mockserver-control-plane";

    private Map<String, Serializable> baseClaims() {
        Map<String, Serializable> claims = new LinkedHashMap<>();
        claims.put("exp", Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond());
        claims.put("iat", Clock.systemUTC().instant().minus(Duration.ofHours(1)).getEpochSecond());
        claims.put("nbf", Clock.systemUTC().instant().minus(Duration.ofHours(1)).getEpochSecond());
        claims.put("iss", ISSUER);
        claims.put("aud", AUDIENCE);
        claims.put("sub", "service-account-ci");
        claims.put("scope", "mockserver.read mockserver.write");
        return claims;
    }

    private OidcAuthenticationHandler handler(String jwkFile, Set<String> requiredScopes, String scopeClaim) {
        return new OidcAuthenticationHandler(mockServerLogger, jwkFile, ISSUER, AUDIENCE, scopeClaim, requiredScopes);
    }

    @Test
    public void validTokenAuthenticatesWithVerifiedPrincipalAndScopes() {
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        String jwkFile = TempFileWriter.write(new JWKGenerator().generateJWK(keyPair));
        String jwt = new JWTGenerator(keyPair).signJWT(baseClaims());

        OidcAuthenticationHandler handler = handler(jwkFile, new HashSet<>(Arrays.asList("mockserver.read")), "scope");
        HttpRequest req = request().withHeader(AUTHORIZATION.toString(), "Bearer " + jwt);

        AuthenticationResult result = handler.authenticate(req);
        assertThat(result.isAuthenticated(), is(true));
        assertThat(result.getPrincipal(), is("service-account-ci"));
        assertThat(result.getPrincipalSource(), is("verified-oidc"));
        assertThat(result.getScopes(), containsInAnyOrder("mockserver.read", "mockserver.write"));
        // redaction-safe claims subset; never the raw token
        assertThat(result.getClaims().get("sub"), is("service-account-ci"));
        assertThat(result.getClaims().get("iss"), is(ISSUER));
        assertThat(result.getClaims().containsKey("exp"), is(false));
        assertThat(handler.controlPlaneRequestAuthenticated(req), is(true));
    }

    @Test
    public void parsesArrayScopeClaim() {
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        String jwkFile = TempFileWriter.write(new JWKGenerator().generateJWK(keyPair));
        Map<String, Serializable> claims = baseClaims();
        claims.remove("scope");
        claims.put("roles", new java.util.ArrayList<>(Arrays.asList("admin", "operator")));
        String jwt = new JWTGenerator(keyPair).signJWT(claims);

        OidcAuthenticationHandler handler = handler(jwkFile, new HashSet<>(Arrays.asList("admin")), "roles");
        HttpRequest req = request().withHeader(AUTHORIZATION.toString(), "Bearer " + jwt);

        AuthenticationResult result = handler.authenticate(req);
        assertThat(result.isAuthenticated(), is(true));
        assertThat(result.getScopes(), containsInAnyOrder("admin", "operator"));
    }

    @Test
    public void badSignatureRejected() {
        AsymmetricKeyPair signingKeyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        AsymmetricKeyPair otherKeyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        String jwkFile = TempFileWriter.write(new JWKGenerator().generateJWK(otherKeyPair));
        String jwt = new JWTGenerator(signingKeyPair).signJWT(baseClaims());

        OidcAuthenticationHandler handler = handler(jwkFile, new HashSet<>(), "scope");
        HttpRequest req = request().withHeader(AUTHORIZATION.toString(), "Bearer " + jwt);

        assertThrows(AuthenticationException.class, () -> handler.authenticate(req));
    }

    @Test
    public void wrongIssuerRejected() {
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        String jwkFile = TempFileWriter.write(new JWKGenerator().generateJWK(keyPair));
        Map<String, Serializable> claims = baseClaims();
        claims.put("iss", "https://evil.example.com");
        String jwt = new JWTGenerator(keyPair).signJWT(claims);

        OidcAuthenticationHandler handler = handler(jwkFile, new HashSet<>(), "scope");
        HttpRequest req = request().withHeader(AUTHORIZATION.toString(), "Bearer " + jwt);

        AuthenticationException exception = assertThrows(AuthenticationException.class, () -> handler.authenticate(req));
        assertThat(exception.getMessage(), is("JWT iss claim has value https://evil.example.com, must be " + ISSUER));
    }

    @Test
    public void wrongAudienceRejected() {
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        String jwkFile = TempFileWriter.write(new JWKGenerator().generateJWK(keyPair));
        Map<String, Serializable> claims = baseClaims();
        claims.put("aud", "some-other-audience");
        String jwt = new JWTGenerator(keyPair).signJWT(claims);

        OidcAuthenticationHandler handler = handler(jwkFile, new HashSet<>(), "scope");
        HttpRequest req = request().withHeader(AUTHORIZATION.toString(), "Bearer " + jwt);

        assertThrows(AuthenticationException.class, () -> handler.authenticate(req));
    }

    @Test
    public void expiredTokenRejected() {
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        String jwkFile = TempFileWriter.write(new JWKGenerator().generateJWK(keyPair));
        Map<String, Serializable> claims = baseClaims();
        claims.put("exp", Clock.systemUTC().instant().minus(Duration.ofHours(1)).getEpochSecond());
        String jwt = new JWTGenerator(keyPair).signJWT(claims);

        OidcAuthenticationHandler handler = handler(jwkFile, new HashSet<>(), "scope");
        HttpRequest req = request().withHeader(AUTHORIZATION.toString(), "Bearer " + jwt);

        AuthenticationException exception = assertThrows(AuthenticationException.class, () -> handler.authenticate(req));
        assertThat(exception.getMessage(), is("Expired JWT"));
    }

    @Test
    public void futureNotBeforeRejected() {
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        String jwkFile = TempFileWriter.write(new JWKGenerator().generateJWK(keyPair));
        Map<String, Serializable> claims = baseClaims();
        claims.put("nbf", Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond());
        String jwt = new JWTGenerator(keyPair).signJWT(claims);

        OidcAuthenticationHandler handler = handler(jwkFile, new HashSet<>(), "scope");
        HttpRequest req = request().withHeader(AUTHORIZATION.toString(), "Bearer " + jwt);

        assertThrows(AuthenticationException.class, () -> handler.authenticate(req));
    }

    @Test
    public void missingRequiredScopeRejected() {
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        String jwkFile = TempFileWriter.write(new JWKGenerator().generateJWK(keyPair));
        String jwt = new JWTGenerator(keyPair).signJWT(baseClaims());

        OidcAuthenticationHandler handler = handler(jwkFile, new HashSet<>(Arrays.asList("mockserver.admin")), "scope");
        HttpRequest req = request().withHeader(AUTHORIZATION.toString(), "Bearer " + jwt);

        AuthenticationException exception = assertThrows(AuthenticationException.class, () -> handler.authenticate(req));
        assertThat(exception.getMessage(), is("JWT missing required scopes: [mockserver.admin]"));
    }

    @Test
    public void missingAuthorizationHeaderRejected() {
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        String jwkFile = TempFileWriter.write(new JWKGenerator().generateJWK(keyPair));

        OidcAuthenticationHandler handler = handler(jwkFile, new HashSet<>(), "scope");

        AuthenticationException exception = assertThrows(AuthenticationException.class, () -> handler.authenticate(request()));
        assertThat(exception.getMessage(), is("no authorization header found"));
    }

    @Test
    public void nonBearerSchemeRejected() {
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        String jwkFile = TempFileWriter.write(new JWKGenerator().generateJWK(keyPair));
        String jwt = new JWTGenerator(keyPair).signJWT(baseClaims());

        OidcAuthenticationHandler handler = handler(jwkFile, new HashSet<>(), "scope");
        HttpRequest req = request().withHeader(AUTHORIZATION.toString(), "Basic " + jwt);

        AuthenticationException exception = assertThrows(AuthenticationException.class, () -> handler.authenticate(req));
        assertThat(exception.getMessage(), is("only \"Bearer\" supported for authorization header"));
    }

    @Test
    public void discoveryDocumentResolvesJwksUri() throws Exception {
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        String jwksJson = new JWKGenerator().generateJWK(keyPair);
        String jwt = new JWTGenerator(keyPair).signJWT(baseClaims());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String issuer = "http://127.0.0.1:" + port;
        String jwksUrl = issuer + "/jwks";
        server.createContext("/.well-known/openid-configuration", exchange -> {
            byte[] body = ("{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + jwksUrl + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/jwks", exchange -> {
            byte[] body = jwksJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            // issuer claim in baseClaims is ISSUER; align to the test issuer for verification
            Map<String, Serializable> claims = baseClaims();
            claims.put("iss", issuer);
            String issuerJwt = new JWTGenerator(keyPair).signJWT(claims);

            // jwksUri left blank so the handler must discover it from the issuer's discovery document
            OidcAuthenticationHandler handler = new OidcAuthenticationHandler(mockServerLogger, null, issuer, AUDIENCE, "scope", new HashSet<>());
            HttpRequest req = request().withHeader(AUTHORIZATION.toString(), "Bearer " + issuerJwt);

            AuthenticationResult result = handler.authenticate(req);
            assertThat(result.isAuthenticated(), equalTo(true));
            assertThat(result.getPrincipal(), is("service-account-ci"));
        } finally {
            server.stop(0);
        }
    }

    // Fix 1 — asymmetric algorithms only

    @Test
    public void unsecuredAlgNoneTokenRejected() throws Exception {
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        String jwkFile = TempFileWriter.write(new JWKGenerator().generateJWK(keyPair));

        // an unsecured (alg=none) JWT carrying otherwise-valid claims
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience(AUDIENCE)
            .subject("service-account-ci")
            .expirationTime(new Date(System.currentTimeMillis() + Duration.ofHours(1).toMillis()))
            .claim("scope", "mockserver.read mockserver.write");
        String unsecuredJwt = new PlainJWT(claims.build()).serialize();

        OidcAuthenticationHandler handler = handler(jwkFile, new HashSet<>(), "scope");
        HttpRequest req = request().withHeader(AUTHORIZATION.toString(), "Bearer " + unsecuredJwt);

        assertThrows(AuthenticationException.class, () -> handler.authenticate(req));
    }

    @Test
    public void hmacSignedTokenRejected() throws Exception {
        // classic algorithm-confusion attack: sign an HS256 token using the RSA PUBLIC key
        // bytes as the HMAC shared secret. An asymmetric-only validator must reject it.
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        String jwkFile = TempFileWriter.write(new JWKGenerator().generateJWK(keyPair));

        byte[] publicKeyBytes = keyPair.getKeyPair().getPublic().getEncoded();
        // HMAC secret must be >= 256 bits for HS256; an RSA-2048 SPKI encoding comfortably exceeds this
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience(AUDIENCE)
            .subject("service-account-ci")
            .expirationTime(new Date(System.currentTimeMillis() + Duration.ofHours(1).toMillis()))
            .claim("scope", "mockserver.read mockserver.write");
        SignedJWT hmacJwt = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(keyPair.getKeyId()).build(),
            claims.build()
        );
        hmacJwt.sign(new MACSigner(publicKeyBytes));

        OidcAuthenticationHandler handler = handler(jwkFile, new HashSet<>(), "scope");
        HttpRequest req = request().withHeader(AUTHORIZATION.toString(), "Bearer " + hmacJwt.serialize());

        assertThrows(AuthenticationException.class, () -> handler.authenticate(req));
    }

    // Fix 2 — secure-by-default: require iss-or-aud, and require exp

    @Test
    public void bothIssuerAndAudienceBlankFailsClosed() {
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        String jwkFile = TempFileWriter.write(new JWKGenerator().generateJWK(keyPair));
        String jwt = new JWTGenerator(keyPair).signJWT(baseClaims());

        // neither issuer nor audience configured -> construction fails, validator left null, all requests 401
        OidcAuthenticationHandler handler = new OidcAuthenticationHandler(mockServerLogger, jwkFile, "", "", "scope", new HashSet<>());
        HttpRequest req = request().withHeader(AUTHORIZATION.toString(), "Bearer " + jwt);

        AuthenticationException exception = assertThrows(AuthenticationException.class, () -> handler.authenticate(req));
        assertThat(exception.getMessage(), is("OIDC validator is not initialised"));
        // fail-closed: the not-initialised exception is not client-safe, so HttpState returns a generic 401
        assertThat(exception.isClientSafeMessage(), is(false));
    }

    @Test
    public void tokenWithoutExpRejected() {
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        String jwkFile = TempFileWriter.write(new JWKGenerator().generateJWK(keyPair));
        Map<String, Serializable> claims = baseClaims();
        claims.remove("exp");
        String jwt = new JWTGenerator(keyPair).signJWT(claims);

        OidcAuthenticationHandler handler = handler(jwkFile, new HashSet<>(), "scope");
        HttpRequest req = request().withHeader(AUTHORIZATION.toString(), "Bearer " + jwt);

        AuthenticationException exception = assertThrows(AuthenticationException.class, () -> handler.authenticate(req));
        assertThat(exception.getMessage(), is("JWT missing required claims: [exp]"));
    }

    @Test
    public void issuerOnlyConfigurationStillConstructs() {
        // confirms requiring iss-OR-aud (not both) — audience blank is fine when issuer is set
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        String jwkFile = TempFileWriter.write(new JWKGenerator().generateJWK(keyPair));
        String jwt = new JWTGenerator(keyPair).signJWT(baseClaims());

        OidcAuthenticationHandler handler = new OidcAuthenticationHandler(mockServerLogger, jwkFile, ISSUER, "", "scope", new HashSet<>());
        HttpRequest req = request().withHeader(AUTHORIZATION.toString(), "Bearer " + jwt);

        AuthenticationResult result = handler.authenticate(req);
        assertThat(result.isAuthenticated(), is(true));
        assertThat(result.getPrincipal(), is("service-account-ci"));
    }

    // Fix 3 — remote JWKS/discovery URL must be HTTPS (loopback http allowed)

    @Test
    public void plaintextRemoteJwksUriFailsClosed() {
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        String jwt = new JWTGenerator(keyPair).signJWT(baseClaims());

        // http:// to a non-loopback host -> construction fails closed, validator null, all requests 401
        OidcAuthenticationHandler handler = new OidcAuthenticationHandler(mockServerLogger, "http://idp.example.com/jwks", ISSUER, AUDIENCE, "scope", new HashSet<>());
        HttpRequest req = request().withHeader(AUTHORIZATION.toString(), "Bearer " + jwt);

        AuthenticationException exception = assertThrows(AuthenticationException.class, () -> handler.authenticate(req));
        assertThat(exception.getMessage(), is("OIDC validator is not initialised"));
    }

    @Test
    public void plaintextRemoteIssuerFailsClosed() {
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        String jwkFile = TempFileWriter.write(new JWKGenerator().generateJWK(keyPair));
        String jwt = new JWTGenerator(keyPair).signJWT(baseClaims());

        // http:// issuer to a non-loopback host -> construction fails closed even with a valid file JWKS
        OidcAuthenticationHandler handler = new OidcAuthenticationHandler(mockServerLogger, jwkFile, "http://idp.example.com", AUDIENCE, "scope", new HashSet<>());
        HttpRequest req = request().withHeader(AUTHORIZATION.toString(), "Bearer " + jwt);

        AuthenticationException exception = assertThrows(AuthenticationException.class, () -> handler.authenticate(req));
        assertThat(exception.getMessage(), is("OIDC validator is not initialised"));
    }

    // Fix 5 — the auth-failure log must summarise the request WITHOUT the bearer token

    @Test
    public void requestSummaryIsTokenFree() {
        HttpRequest req = request()
            .withMethod("PUT")
            .withPath("/mockserver/oidc")
            .withRemoteAddress("203.0.113.7:5555")
            .withHeader(AUTHORIZATION.toString(), "Bearer super-secret-jwt-value");

        String summary = OidcAuthenticationHandler.requestSummary(req);

        // includes a token-free operational summary
        assertThat(summary, is("PUT /mockserver/oidc from 203.0.113.7:5555"));
        // and never the bearer token / authorization header
        org.junit.Assert.assertFalse("request summary must not leak the bearer token",
            summary.contains("super-secret-jwt-value"));
        org.junit.Assert.assertFalse("request summary must not leak the authorization header",
            summary.toLowerCase().contains("bearer"));
    }

    @Test
    public void requestSummaryOmitsRemoteAddressWhenAbsent() {
        HttpRequest req = request()
            .withMethod("PUT")
            .withPath("/mockserver/oidc")
            .withHeader(AUTHORIZATION.toString(), "Bearer super-secret-jwt-value");

        String summary = OidcAuthenticationHandler.requestSummary(req);

        assertThat(summary, is("PUT /mockserver/oidc"));
        org.junit.Assert.assertFalse(summary.contains("super-secret-jwt-value"));
    }
}
