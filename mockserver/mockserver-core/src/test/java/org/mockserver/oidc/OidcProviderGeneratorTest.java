package org.mockserver.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.io.Serializable;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class OidcProviderGeneratorTest {

    private final OidcProviderGenerator generator = new OidcProviderGenerator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void defaultsProduceSixExpectations() {
        List<Expectation> expectations = generator.generate(new OidcProviderConfiguration());

        assertThat(expectations.size(), is(6));
        assertThat(expectations.get(0).getId(), is("oidc.discovery"));
        assertThat(expectations.get(1).getId(), is("oidc.jwks"));
        assertThat(expectations.get(2).getId(), is("oidc.token"));
        assertThat(expectations.get(3).getId(), is("oidc.userinfo"));
        assertThat(expectations.get(4).getId(), is("oidc.introspect"));
        assertThat(expectations.get(5).getId(), is("oidc.revoke"));
    }

    @Test
    public void discoveryDocumentHasCorrectFields() throws Exception {
        OidcProviderConfiguration config = new OidcProviderConfiguration()
            .setIssuer("https://idp.example.com");

        List<Expectation> expectations = generator.generate(config);
        Expectation discovery = expectations.get(0);

        HttpRequest request = (HttpRequest) discovery.getHttpRequest();
        assertThat(request.getMethod().getValue(), is("GET"));
        assertThat(request.getPath().getValue(), is("/.well-known/openid-configuration"));

        HttpResponse response = discovery.getHttpResponse();
        assertThat(response.getStatusCode(), is(200));

        JsonNode doc = objectMapper.readTree(response.getBodyAsString());
        assertThat(doc.get("issuer").asText(), is("https://idp.example.com"));
        assertThat(doc.get("jwks_uri").asText(), is("https://idp.example.com/.well-known/jwks.json"));
        assertThat(doc.get("token_endpoint").asText(), is("https://idp.example.com/token"));
        assertThat(doc.get("authorization_endpoint").asText(), is("https://idp.example.com/authorize"));
        assertThat(doc.get("userinfo_endpoint").asText(), is("https://idp.example.com/userinfo"));
        assertThat(doc.get("introspection_endpoint").asText(), is("https://idp.example.com/introspect"));
        assertThat(doc.get("revocation_endpoint").asText(), is("https://idp.example.com/revoke"));

        // Check supported values
        assertTrue(doc.get("grant_types_supported").isArray());
        assertThat(doc.get("grant_types_supported").size(), is(4));
        assertThat(doc.get("id_token_signing_alg_values_supported").get(0).asText(), is("RS256"));
        assertThat(doc.get("subject_types_supported").get(0).asText(), is("public"));
    }

    @Test
    public void discoveryDocumentUsesCustomPaths() throws Exception {
        OidcProviderConfiguration config = new OidcProviderConfiguration()
            .setIssuer("https://auth.test")
            .setJwksPath("/custom/jwks")
            .setTokenPath("/custom/token")
            .setAuthorizePath("/custom/auth")
            .setUserinfoPath("/custom/me")
            .setIntrospectPath("/custom/introspect")
            .setRevokePath("/custom/revoke");

        List<Expectation> expectations = generator.generate(config);
        JsonNode doc = objectMapper.readTree(expectations.get(0).getHttpResponse().getBodyAsString());

        assertThat(doc.get("jwks_uri").asText(), is("https://auth.test/custom/jwks"));
        assertThat(doc.get("token_endpoint").asText(), is("https://auth.test/custom/token"));
        assertThat(doc.get("authorization_endpoint").asText(), is("https://auth.test/custom/auth"));
        assertThat(doc.get("userinfo_endpoint").asText(), is("https://auth.test/custom/me"));
        assertThat(doc.get("introspection_endpoint").asText(), is("https://auth.test/custom/introspect"));
        assertThat(doc.get("revocation_endpoint").asText(), is("https://auth.test/custom/revoke"));

        // Verify the endpoint paths themselves match
        assertThat(((HttpRequest) expectations.get(1).getHttpRequest()).getPath().getValue(), is("/custom/jwks"));
        assertThat(((HttpRequest) expectations.get(2).getHttpRequest()).getPath().getValue(), is("/custom/token"));
        assertThat(((HttpRequest) expectations.get(3).getHttpRequest()).getPath().getValue(), is("/custom/me"));
        assertThat(((HttpRequest) expectations.get(4).getHttpRequest()).getPath().getValue(), is("/custom/introspect"));
        assertThat(((HttpRequest) expectations.get(5).getHttpRequest()).getPath().getValue(), is("/custom/revoke"));
    }

    @Test
    public void jwksContainsRsaPublicKey() throws Exception {
        List<Expectation> expectations = generator.generate(new OidcProviderConfiguration());
        Expectation jwks = expectations.get(1);

        HttpRequest request = (HttpRequest) jwks.getHttpRequest();
        assertThat(request.getMethod().getValue(), is("GET"));
        assertThat(request.getPath().getValue(), is("/.well-known/jwks.json"));

        String jwksBody = jwks.getHttpResponse().getBodyAsString();
        JsonNode jwksJson = objectMapper.readTree(jwksBody);
        assertTrue("JWKS should have a 'keys' array", jwksJson.has("keys"));
        assertThat(jwksJson.get("keys").size(), is(1));

        JsonNode key = jwksJson.get("keys").get(0);
        assertThat(key.get("kty").asText(), is("RSA"));
        assertThat(key.get("use").asText(), is("sig"));
        assertTrue("Key should have 'n' (modulus)", key.has("n"));
        assertTrue("Key should have 'e' (exponent)", key.has("e"));
    }

    @Test
    public void accessTokenVerifiesAgainstJwks() throws Exception {
        OidcProviderConfiguration config = new OidcProviderConfiguration()
            .setIssuer("https://idp.test")
            .setSubject("test-user")
            .setAudience("test-audience");

        List<Expectation> expectations = generator.generate(config);

        // Parse JWKS
        String jwksBody = expectations.get(1).getHttpResponse().getBodyAsString();
        JWKSet jwkSet = JWKSet.parse(jwksBody);
        JWK jwk = jwkSet.getKeys().get(0);
        RSAKey rsaKey = (RSAKey) jwk;
        JWSVerifier verifier = new RSASSAVerifier(rsaKey);

        // Parse token response
        JsonNode tokenResponse = objectMapper.readTree(expectations.get(2).getHttpResponse().getBodyAsString());
        String accessTokenStr = tokenResponse.get("access_token").asText();
        assertThat(accessTokenStr, is(notNullValue()));

        // Verify the access token signature
        SignedJWT signedJWT = SignedJWT.parse(accessTokenStr);
        assertTrue("Access token should verify against JWKS public key", signedJWT.verify(verifier));

        // Verify claims
        assertThat(signedJWT.getJWTClaimsSet().getIssuer(), is("https://idp.test"));
        assertThat(signedJWT.getJWTClaimsSet().getSubject(), is("test-user"));
        assertThat(signedJWT.getJWTClaimsSet().getAudience().get(0), is("test-audience"));
        assertThat(signedJWT.getJWTClaimsSet().getStringClaim("scope"), is("openid profile email"));

        // exp should be in the future
        long expEpoch = signedJWT.getJWTClaimsSet().getExpirationTime().getTime() / 1000;
        assertThat(expEpoch, greaterThan(Instant.now().getEpochSecond()));
    }

    @Test
    public void idTokenVerifiesAgainstJwks() throws Exception {
        List<Expectation> expectations = generator.generate(new OidcProviderConfiguration());

        String jwksBody = expectations.get(1).getHttpResponse().getBodyAsString();
        JWKSet jwkSet = JWKSet.parse(jwksBody);
        RSAKey rsaKey = (RSAKey) jwkSet.getKeys().get(0);
        JWSVerifier verifier = new RSASSAVerifier(rsaKey);

        JsonNode tokenResponse = objectMapper.readTree(expectations.get(2).getHttpResponse().getBodyAsString());
        String idTokenStr = tokenResponse.get("id_token").asText();

        SignedJWT signedJWT = SignedJWT.parse(idTokenStr);
        assertTrue("ID token should verify against JWKS public key", signedJWT.verify(verifier));
    }

    @Test
    public void tokenResponseHasCorrectStructure() throws Exception {
        OidcProviderConfiguration config = new OidcProviderConfiguration()
            .setTokenExpirySeconds(7200)
            .setScopes(Arrays.asList("openid", "custom"));

        List<Expectation> expectations = generator.generate(config);

        HttpRequest request = (HttpRequest) expectations.get(2).getHttpRequest();
        assertThat(request.getMethod().getValue(), is("POST"));
        assertThat(request.getPath().getValue(), is("/token"));

        JsonNode tokenResponse = objectMapper.readTree(expectations.get(2).getHttpResponse().getBodyAsString());
        assertThat(tokenResponse.get("token_type").asText(), is("Bearer"));
        assertThat(tokenResponse.get("expires_in").asInt(), is(7200));
        assertThat(tokenResponse.get("scope").asText(), is("openid custom"));
        assertTrue(tokenResponse.has("access_token"));
        assertTrue(tokenResponse.has("id_token"));
    }

    @Test
    public void userinfoReturnsSubjectAndAdditionalClaims() throws Exception {
        Map<String, Serializable> additional = new HashMap<>();
        additional.put("email", "user@example.com");
        additional.put("name", "Test User");

        OidcProviderConfiguration config = new OidcProviderConfiguration()
            .setSubject("sub-123")
            .setAdditionalClaims(additional);

        List<Expectation> expectations = generator.generate(config);
        Expectation userinfo = expectations.get(3);

        HttpRequest request = (HttpRequest) userinfo.getHttpRequest();
        assertThat(request.getMethod().getValue(), is("GET"));
        assertThat(request.getPath().getValue(), is("/userinfo"));

        JsonNode body = objectMapper.readTree(userinfo.getHttpResponse().getBodyAsString());
        assertThat(body.get("sub").asText(), is("sub-123"));
        assertThat(body.get("email").asText(), is("user@example.com"));
        assertThat(body.get("name").asText(), is("Test User"));
    }

    @Test
    public void introspectionReturnsActiveTrue() throws Exception {
        List<Expectation> expectations = generator.generate(new OidcProviderConfiguration());
        Expectation introspect = expectations.get(4);

        HttpRequest request = (HttpRequest) introspect.getHttpRequest();
        assertThat(request.getMethod().getValue(), is("POST"));
        assertThat(request.getPath().getValue(), is("/introspect"));

        JsonNode body = objectMapper.readTree(introspect.getHttpResponse().getBodyAsString());
        assertThat(body.get("active").asBoolean(), is(true));
        assertThat(body.get("sub").asText(), is("mock-user"));
    }

    @Test
    public void revocationReturns200() {
        List<Expectation> expectations = generator.generate(new OidcProviderConfiguration());
        Expectation revoke = expectations.get(5);

        HttpRequest request = (HttpRequest) revoke.getHttpRequest();
        assertThat(request.getMethod().getValue(), is("POST"));
        assertThat(request.getPath().getValue(), is("/revoke"));
        assertThat(revoke.getHttpResponse().getStatusCode(), is(200));
    }

    // --- Negative testing flags ---

    @Test
    public void issueExpiredTokenProducesExpInThePast() throws Exception {
        OidcProviderConfiguration config = new OidcProviderConfiguration()
            .setIssueExpiredToken(true);

        List<Expectation> expectations = generator.generate(config);

        JsonNode tokenResponse = objectMapper.readTree(expectations.get(2).getHttpResponse().getBodyAsString());
        String accessTokenStr = tokenResponse.get("access_token").asText();
        SignedJWT signedJWT = SignedJWT.parse(accessTokenStr);

        long expEpoch = signedJWT.getJWTClaimsSet().getExpirationTime().getTime() / 1000;
        assertThat("exp should be in the past", expEpoch, lessThan(Instant.now().getEpochSecond()));
    }

    @Test
    public void issueExpiredTokenSetsIntrospectionActiveToFalse() throws Exception {
        OidcProviderConfiguration config = new OidcProviderConfiguration()
            .setIssueExpiredToken(true);

        List<Expectation> expectations = generator.generate(config);

        JsonNode introspection = objectMapper.readTree(expectations.get(4).getHttpResponse().getBodyAsString());
        assertThat(introspection.get("active").asBoolean(), is(false));
    }

    @Test
    public void wrongIssuerProducesMismatchedIss() throws Exception {
        OidcProviderConfiguration config = new OidcProviderConfiguration()
            .setIssuer("https://correct.issuer")
            .setWrongIssuer(true);

        List<Expectation> expectations = generator.generate(config);

        JsonNode tokenResponse = objectMapper.readTree(expectations.get(2).getHttpResponse().getBodyAsString());
        String accessTokenStr = tokenResponse.get("access_token").asText();
        SignedJWT signedJWT = SignedJWT.parse(accessTokenStr);

        // The token's iss should NOT match the configured issuer
        String tokenIssuer = signedJWT.getJWTClaimsSet().getIssuer();
        assertThat(tokenIssuer, is("https://correct.issuer/wrong"));

        // But the discovery document should still report the correct issuer
        JsonNode discovery = objectMapper.readTree(expectations.get(0).getHttpResponse().getBodyAsString());
        assertThat(discovery.get("issuer").asText(), is("https://correct.issuer"));
    }

    @Test
    public void tamperedSignatureFailsVerification() throws Exception {
        OidcProviderConfiguration config = new OidcProviderConfiguration()
            .setTamperedSignature(true);

        List<Expectation> expectations = generator.generate(config);

        String jwksBody = expectations.get(1).getHttpResponse().getBodyAsString();
        JWKSet jwkSet = JWKSet.parse(jwksBody);
        RSAKey rsaKey = (RSAKey) jwkSet.getKeys().get(0);
        JWSVerifier verifier = new RSASSAVerifier(rsaKey);

        JsonNode tokenResponse = objectMapper.readTree(expectations.get(2).getHttpResponse().getBodyAsString());
        String accessTokenStr = tokenResponse.get("access_token").asText();

        SignedJWT signedJWT = SignedJWT.parse(accessTokenStr);
        assertFalse("Tampered signature should FAIL verification", signedJWT.verify(verifier));
    }

    @Test
    public void additionalClaimsAppearInToken() throws Exception {
        Map<String, Serializable> additional = new HashMap<>();
        additional.put("custom_claim", "custom_value");
        additional.put("role", "admin");

        OidcProviderConfiguration config = new OidcProviderConfiguration()
            .setAdditionalClaims(additional);

        List<Expectation> expectations = generator.generate(config);

        JsonNode tokenResponse = objectMapper.readTree(expectations.get(2).getHttpResponse().getBodyAsString());
        String accessTokenStr = tokenResponse.get("access_token").asText();
        SignedJWT signedJWT = SignedJWT.parse(accessTokenStr);

        assertThat(signedJWT.getJWTClaimsSet().getStringClaim("custom_claim"), is("custom_value"));
        assertThat(signedJWT.getJWTClaimsSet().getStringClaim("role"), is("admin"));
    }

    @Test
    public void nullConfigThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(null));
    }

    @Test
    public void allResponsesHaveJsonContentType() {
        List<Expectation> expectations = generator.generate(new OidcProviderConfiguration());

        for (Expectation expectation : expectations) {
            String contentType = expectation.getHttpResponse().getFirstHeader("content-type");
            assertThat(
                "Expectation " + expectation.getId() + " should have JSON content type",
                contentType,
                containsString("application/json")
            );
        }
    }
}
