package org.mockserver.authentication.jwt;

import com.google.common.collect.ImmutableMap;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.Test;
import org.mockserver.authentication.AuthenticationException;
import org.mockserver.keys.AsymmetricKeyGenerator;
import org.mockserver.keys.AsymmetricKeyPair;
import org.mockserver.keys.AsymmetricKeyPairAlgorithm;
import org.mockserver.test.TempFileWriter;

import java.io.File;
import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThrows;

public class JWTValidatorTest {

    // --- Positive: valid JWT with RSA signature ---

    @Test
    public void shouldValidateJWTSignedWithRSA256() {
        // given
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        JWKSource<SecurityContext> jwkSource = createJWKSource(keyPair);
        JWTValidator validator = new JWTValidator(jwkSource);
        String jwt = new JWTGenerator(keyPair).generateJWT();

        // when
        JWTClaimsSet claimsSet = validator.validate(jwt);

        // then
        assertThat(claimsSet, notNullValue());
        assertThat(claimsSet.getSubject(), notNullValue());
    }

    @Test
    public void shouldValidateJWTSignedWithEC256() {
        // given
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.EC256_SHA256);
        JWKSource<SecurityContext> jwkSource = createJWKSource(keyPair);
        JWTValidator validator = new JWTValidator(jwkSource);
        String jwt = new JWTGenerator(keyPair).signJWT(validClaims());

        // when
        JWTClaimsSet claimsSet = validator.validate(jwt);

        // then
        assertThat(claimsSet, notNullValue());
    }

    @Test
    public void shouldValidateJWTSignedWithEC384() {
        // given
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.EC384_SHA384);
        JWKSource<SecurityContext> jwkSource = createJWKSource(keyPair);
        JWTValidator validator = new JWTValidator(jwkSource);
        String jwt = new JWTGenerator(keyPair).signJWT(validClaims());

        // when
        JWTClaimsSet claimsSet = validator.validate(jwt);

        // then
        assertThat(claimsSet, notNullValue());
    }

    @Test
    public void shouldValidateJWTSignedWithRSA4096() {
        // given
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA4096_SHA512);
        JWKSource<SecurityContext> jwkSource = createJWKSource(keyPair);
        JWTValidator validator = new JWTValidator(jwkSource);
        String jwt = new JWTGenerator(keyPair).signJWT(validClaims());

        // when
        JWTClaimsSet claimsSet = validator.validate(jwt);

        // then
        assertThat(claimsSet, notNullValue());
    }

    // --- Negative: expired JWT ---

    @Test
    public void shouldRejectExpiredJWT() {
        // given
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        JWKSource<SecurityContext> jwkSource = createJWKSource(keyPair);
        JWTValidator validator = new JWTValidator(jwkSource);
        String jwt = new JWTGenerator(keyPair).signJWT(ImmutableMap.of(
            "exp", Clock.systemUTC().instant().minus(Duration.ofHours(1)).getEpochSecond(),
            "iat", Clock.systemUTC().instant().minus(Duration.ofHours(2)).getEpochSecond(),
            "sub", "test-subject"
        ));

        // when / then
        AuthenticationException exception = assertThrows(AuthenticationException.class, () -> validator.validate(jwt));
        assertThat(exception.getMessage(), equalTo("Expired JWT"));
    }

    // --- Negative: wrong signature (different key) ---

    @Test
    public void shouldRejectJWTWithWrongSignature() {
        // given
        AsymmetricKeyPair signingKeyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        AsymmetricKeyPair validationKeyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        JWKSource<SecurityContext> jwkSource = createJWKSource(validationKeyPair);
        JWTValidator validator = new JWTValidator(jwkSource);
        String jwt = new JWTGenerator(signingKeyPair).generateJWT();

        // when / then
        AuthenticationException exception = assertThrows(AuthenticationException.class, () -> validator.validate(jwt));
        assertThat(exception.getMessage(), containsString("Signed JWT rejected"));
    }

    // --- Negative: malformed JWT ---

    @Test
    public void shouldRejectMalformedJWT() {
        // given
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        JWKSource<SecurityContext> jwkSource = createJWKSource(keyPair);
        JWTValidator validator = new JWTValidator(jwkSource);

        // when / then
        AuthenticationException exception = assertThrows(AuthenticationException.class, () -> validator.validate("not.a.valid.jwt"));
        assertThat(exception.getMessage(), notNullValue());
    }

    // --- Audience validation ---

    @Test
    public void shouldValidateJWTWithCorrectAudience() {
        // given
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        JWKSource<SecurityContext> jwkSource = createJWKSource(keyPair);
        JWTValidator validator = new JWTValidator(jwkSource).withExpectedAudience("my-audience");
        String jwt = new JWTGenerator(keyPair).signJWT(ImmutableMap.of(
            "exp", Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond(),
            "iat", Clock.systemUTC().instant().minus(Duration.ofMinutes(5)).getEpochSecond(),
            "sub", "test-subject",
            "aud", "my-audience"
        ));

        // when
        JWTClaimsSet claimsSet = validator.validate(jwt);

        // then
        assertThat(claimsSet.getSubject(), equalTo("test-subject"));
    }

    @Test
    public void shouldRejectJWTWithWrongAudience() {
        // given
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        JWKSource<SecurityContext> jwkSource = createJWKSource(keyPair);
        JWTValidator validator = new JWTValidator(jwkSource).withExpectedAudience("expected-audience");
        String jwt = new JWTGenerator(keyPair).signJWT(ImmutableMap.of(
            "exp", Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond(),
            "iat", Clock.systemUTC().instant().minus(Duration.ofMinutes(5)).getEpochSecond(),
            "sub", "test-subject",
            "aud", "wrong-audience"
        ));

        // when / then
        AuthenticationException exception = assertThrows(AuthenticationException.class, () -> validator.validate(jwt));
        assertThat(exception.getMessage(), containsString("JWT audience rejected"));
        assertThat(exception.getMessage(), containsString("wrong-audience"));
    }

    @Test
    public void shouldSkipAudienceCheckWhenExpectedAudienceIsBlank() {
        // given
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        JWKSource<SecurityContext> jwkSource = createJWKSource(keyPair);
        JWTValidator validator = new JWTValidator(jwkSource).withExpectedAudience("");
        String jwt = new JWTGenerator(keyPair).signJWT(ImmutableMap.of(
            "exp", Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond(),
            "iat", Clock.systemUTC().instant().minus(Duration.ofMinutes(5)).getEpochSecond(),
            "sub", "test-subject"
        ));

        // when
        JWTClaimsSet claimsSet = validator.validate(jwt);

        // then
        assertThat(claimsSet.getSubject(), equalTo("test-subject"));
    }

    // --- Required claims ---

    @Test
    public void shouldValidateJWTWithAllRequiredClaimsPresent() {
        // given
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        JWKSource<SecurityContext> jwkSource = createJWKSource(keyPair);
        Set<String> requiredClaims = new HashSet<>(Arrays.asList("scope", "tenant"));
        JWTValidator validator = new JWTValidator(jwkSource).withRequiredClaims(requiredClaims);
        String jwt = new JWTGenerator(keyPair).signJWT(ImmutableMap.of(
            "exp", Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond(),
            "iat", Clock.systemUTC().instant().minus(Duration.ofMinutes(5)).getEpochSecond(),
            "sub", "test-subject",
            "scope", "read write",
            "tenant", "acme"
        ));

        // when
        JWTClaimsSet claimsSet = validator.validate(jwt);

        // then
        assertThat(claimsSet.getClaim("scope"), equalTo("read write"));
        assertThat(claimsSet.getClaim("tenant"), equalTo("acme"));
    }

    @Test
    public void shouldRejectJWTWithMissingRequiredClaims() {
        // given
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        JWKSource<SecurityContext> jwkSource = createJWKSource(keyPair);
        Set<String> requiredClaims = new HashSet<>(Arrays.asList("jti", "scope"));
        JWTValidator validator = new JWTValidator(jwkSource).withRequiredClaims(requiredClaims);
        String jwt = new JWTGenerator(keyPair).signJWT(ImmutableMap.of(
            "exp", Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond(),
            "iat", Clock.systemUTC().instant().minus(Duration.ofMinutes(5)).getEpochSecond(),
            "sub", "test-subject"
        ));

        // when / then
        AuthenticationException exception = assertThrows(AuthenticationException.class, () -> validator.validate(jwt));
        assertThat(exception.getMessage(), containsString("JWT missing required claims"));
        assertThat(exception.getMessage(), containsString("jti"));
        assertThat(exception.getMessage(), containsString("scope"));
    }

    @Test
    public void shouldSkipRequiredClaimsCheckWhenSetIsEmpty() {
        // given
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        JWKSource<SecurityContext> jwkSource = createJWKSource(keyPair);
        JWTValidator validator = new JWTValidator(jwkSource).withRequiredClaims(Collections.emptySet());
        String jwt = new JWTGenerator(keyPair).generateJWT();

        // when
        JWTClaimsSet claimsSet = validator.validate(jwt);

        // then
        assertThat(claimsSet, notNullValue());
    }

    // --- Matching claims ---

    @Test
    public void shouldValidateJWTWithMatchingClaims() {
        // given
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        JWKSource<SecurityContext> jwkSource = createJWKSource(keyPair);
        JWTValidator validator = new JWTValidator(jwkSource).withMatchingClaims(ImmutableMap.of("sub", "expected-subject"));
        String jwt = new JWTGenerator(keyPair).signJWT(ImmutableMap.of(
            "exp", Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond(),
            "iat", Clock.systemUTC().instant().minus(Duration.ofMinutes(5)).getEpochSecond(),
            "sub", "expected-subject"
        ));

        // when
        JWTClaimsSet claimsSet = validator.validate(jwt);

        // then
        assertThat(claimsSet.getSubject(), equalTo("expected-subject"));
    }

    @Test
    public void shouldRejectJWTWithNonMatchingClaims() {
        // given
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        JWKSource<SecurityContext> jwkSource = createJWKSource(keyPair);
        JWTValidator validator = new JWTValidator(jwkSource).withMatchingClaims(ImmutableMap.of("sub", "expected-subject"));
        String jwt = new JWTGenerator(keyPair).signJWT(ImmutableMap.of(
            "exp", Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond(),
            "iat", Clock.systemUTC().instant().minus(Duration.ofMinutes(5)).getEpochSecond(),
            "sub", "actual-subject"
        ));

        // when / then
        AuthenticationException exception = assertThrows(AuthenticationException.class, () -> validator.validate(jwt));
        assertThat(exception.getMessage(), containsString("sub"));
        assertThat(exception.getMessage(), containsString("actual-subject"));
        assertThat(exception.getMessage(), containsString("expected-subject"));
    }

    @Test
    public void shouldSkipMatchingClaimsCheckWhenMapIsEmpty() {
        // given
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        JWKSource<SecurityContext> jwkSource = createJWKSource(keyPair);
        JWTValidator validator = new JWTValidator(jwkSource).withMatchingClaims(Collections.emptyMap());
        String jwt = new JWTGenerator(keyPair).generateJWT();

        // when
        JWTClaimsSet claimsSet = validator.validate(jwt);

        // then
        assertThat(claimsSet, notNullValue());
    }

    // --- Combined: audience + required + matching ---

    @Test
    public void shouldValidateJWTWithAllConstraintsSatisfied() {
        // given
        AsymmetricKeyPair keyPair = AsymmetricKeyGenerator.createAsymmetricKeyPair(AsymmetricKeyPairAlgorithm.RSA2048_SHA256);
        JWKSource<SecurityContext> jwkSource = createJWKSource(keyPair);
        JWTValidator validator = new JWTValidator(jwkSource)
            .withExpectedAudience("api-gateway")
            .withRequiredClaims(new HashSet<>(Collections.singletonList("scope")))
            .withMatchingClaims(ImmutableMap.of("sub", "service-account"));
        String jwt = new JWTGenerator(keyPair).signJWT(ImmutableMap.of(
            "exp", Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond(),
            "iat", Clock.systemUTC().instant().minus(Duration.ofMinutes(5)).getEpochSecond(),
            "sub", "service-account",
            "aud", "api-gateway",
            "scope", "admin"
        ));

        // when
        JWTClaimsSet claimsSet = validator.validate(jwt);

        // then
        assertThat(claimsSet.getSubject(), equalTo("service-account"));
        assertThat(claimsSet.getClaim("scope"), equalTo("admin"));
    }

    // --- Helper methods ---

    private JWKSource<SecurityContext> createJWKSource(AsymmetricKeyPair keyPair) {
        try {
            String jwkJson = new JWKGenerator().generateJWK(keyPair);
            String filePath = TempFileWriter.write(jwkJson);
            JWKSet jwkSet = JWKSet.load(new File(filePath));
            return new ImmutableJWKSet<>(jwkSet);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create JWK source", e);
        }
    }

    private Map<String, Serializable> validClaims() {
        return ImmutableMap.of(
            "exp", Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond(),
            "iat", Clock.systemUTC().instant().minus(Duration.ofMinutes(5)).getEpochSecond(),
            "sub", "test-subject",
            "aud", "https://www.mock-server.com"
        );
    }
}
