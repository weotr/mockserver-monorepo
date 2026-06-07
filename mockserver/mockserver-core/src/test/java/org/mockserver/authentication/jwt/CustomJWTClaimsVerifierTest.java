package org.mockserver.authentication.jwt;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.BadJWTException;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThrows;

public class CustomJWTClaimsVerifierTest {

    // --- Audience verification ---

    @Test
    public void shouldPassWhenAudienceMatches() throws BadJWTException {
        // given
        CustomJWTClaimsVerifier verifier = new CustomJWTClaimsVerifier("expected-audience", new JWTClaimsSet.Builder().build(), null);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .audience("expected-audience")
            .subject("test")
            .build();

        // when / then - no exception means pass
        verifier.verify(claims, null);
    }

    @Test
    public void shouldRejectWhenAudienceDoesNotMatch() {
        // given
        CustomJWTClaimsVerifier verifier = new CustomJWTClaimsVerifier("expected-audience", new JWTClaimsSet.Builder().build(), null);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .audience("wrong-audience")
            .subject("test")
            .build();

        // when / then
        BadJWTException exception = assertThrows(BadJWTException.class, () -> verifier.verify(claims, null));
        assertThat(exception.getMessage(), containsString("JWT audience rejected"));
        assertThat(exception.getMessage(), containsString("wrong-audience"));
    }

    @Test
    public void shouldRejectWhenAudienceIsNull() {
        // given
        CustomJWTClaimsVerifier verifier = new CustomJWTClaimsVerifier("expected-audience", new JWTClaimsSet.Builder().build(), null);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("test")
            .build();

        // when / then
        BadJWTException exception = assertThrows(BadJWTException.class, () -> verifier.verify(claims, null));
        assertThat(exception.getMessage(), containsString("JWT audience rejected"));
    }

    @Test
    public void shouldPassWhenAudienceMatchesAmongMultiple() throws BadJWTException {
        // given
        CustomJWTClaimsVerifier verifier = new CustomJWTClaimsVerifier("target-aud", new JWTClaimsSet.Builder().build(), null);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .audience(Arrays.asList("other-aud", "target-aud"))
            .subject("test")
            .build();

        // when / then - no exception
        verifier.verify(claims, null);
    }

    @Test
    public void shouldSkipAudienceCheckWhenExpectedAudienceIsNull() throws BadJWTException {
        // given
        CustomJWTClaimsVerifier verifier = new CustomJWTClaimsVerifier(null, new JWTClaimsSet.Builder().build(), null);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("test")
            .build();

        // when / then - no exception
        verifier.verify(claims, null);
    }

    // --- Required claims verification ---

    @Test
    public void shouldPassWhenAllRequiredClaimsArePresent() throws BadJWTException {
        // given
        Set<String> requiredClaims = new HashSet<>(Arrays.asList("scope", "tenant"));
        CustomJWTClaimsVerifier verifier = new CustomJWTClaimsVerifier(null, new JWTClaimsSet.Builder().build(), requiredClaims);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("test")
            .claim("scope", "read")
            .claim("tenant", "acme")
            .build();

        // when / then - no exception
        verifier.verify(claims, null);
    }

    @Test
    public void shouldRejectWhenRequiredClaimsAreMissing() {
        // given
        Set<String> requiredClaims = new HashSet<>(Arrays.asList("jti", "scope"));
        CustomJWTClaimsVerifier verifier = new CustomJWTClaimsVerifier(null, new JWTClaimsSet.Builder().build(), requiredClaims);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("test")
            .build();

        // when / then
        BadJWTException exception = assertThrows(BadJWTException.class, () -> verifier.verify(claims, null));
        assertThat(exception.getMessage(), containsString("JWT missing required claims"));
        assertThat(exception.getMessage(), containsString("jti"));
        assertThat(exception.getMessage(), containsString("scope"));
    }

    @Test
    public void shouldRejectWhenSomeRequiredClaimsAreMissing() {
        // given
        Set<String> requiredClaims = new HashSet<>(Arrays.asList("scope", "tenant"));
        CustomJWTClaimsVerifier verifier = new CustomJWTClaimsVerifier(null, new JWTClaimsSet.Builder().build(), requiredClaims);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("test")
            .claim("scope", "read")
            .build();

        // when / then
        BadJWTException exception = assertThrows(BadJWTException.class, () -> verifier.verify(claims, null));
        assertThat(exception.getMessage(), containsString("JWT missing required claims"));
        assertThat(exception.getMessage(), containsString("tenant"));
    }

    @Test
    public void shouldPassWhenRequiredClaimsSetIsNull() throws BadJWTException {
        // given
        CustomJWTClaimsVerifier verifier = new CustomJWTClaimsVerifier(null, new JWTClaimsSet.Builder().build(), null);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("test")
            .build();

        // when / then - no exception
        verifier.verify(claims, null);
    }

    @Test
    public void shouldPassWhenRequiredClaimsSetIsEmpty() throws BadJWTException {
        // given
        CustomJWTClaimsVerifier verifier = new CustomJWTClaimsVerifier(null, new JWTClaimsSet.Builder().build(), Collections.emptySet());
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("test")
            .build();

        // when / then - no exception
        verifier.verify(claims, null);
    }

    // --- Exact match claims verification ---

    @Test
    public void shouldPassWhenExactMatchClaimsMatch() throws BadJWTException {
        // given
        JWTClaimsSet exactMatchClaims = new JWTClaimsSet.Builder()
            .claim("sub", "expected-subject")
            .build();
        CustomJWTClaimsVerifier verifier = new CustomJWTClaimsVerifier(null, exactMatchClaims, null);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("expected-subject")
            .claim("extra", "value")
            .build();

        // when / then - no exception
        verifier.verify(claims, null);
    }

    @Test
    public void shouldRejectWhenExactMatchClaimsDoNotMatch() {
        // given
        JWTClaimsSet exactMatchClaims = new JWTClaimsSet.Builder()
            .claim("sub", "expected-subject")
            .build();
        CustomJWTClaimsVerifier verifier = new CustomJWTClaimsVerifier(null, exactMatchClaims, null);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("actual-subject")
            .build();

        // when / then
        BadJWTException exception = assertThrows(BadJWTException.class, () -> verifier.verify(claims, null));
        assertThat(exception.getMessage(), containsString("sub"));
        assertThat(exception.getMessage(), containsString("actual-subject"));
        assertThat(exception.getMessage(), containsString("expected-subject"));
    }

    @Test
    public void shouldRejectWhenExactMatchClaimIsMissingFromJWT() {
        // given
        JWTClaimsSet exactMatchClaims = new JWTClaimsSet.Builder()
            .claim("custom_claim", "expected_value")
            .build();
        CustomJWTClaimsVerifier verifier = new CustomJWTClaimsVerifier(null, exactMatchClaims, null);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("test")
            .build();

        // when / then
        BadJWTException exception = assertThrows(BadJWTException.class, () -> verifier.verify(claims, null));
        assertThat(exception.getMessage(), containsString("custom_claim"));
        assertThat(exception.getMessage(), containsString("null"));
        assertThat(exception.getMessage(), containsString("expected_value"));
    }

    @Test
    public void shouldPassWhenExactMatchClaimsIsEmpty() throws BadJWTException {
        // given
        JWTClaimsSet exactMatchClaims = new JWTClaimsSet.Builder().build();
        CustomJWTClaimsVerifier verifier = new CustomJWTClaimsVerifier(null, exactMatchClaims, null);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("test")
            .build();

        // when / then - no exception
        verifier.verify(claims, null);
    }

    @Test
    public void shouldVerifyMultipleExactMatchClaims() {
        // given
        JWTClaimsSet exactMatchClaims = new JWTClaimsSet.Builder()
            .claim("role", "admin")
            .claim("env", "production")
            .build();
        CustomJWTClaimsVerifier verifier = new CustomJWTClaimsVerifier(null, exactMatchClaims, null);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("test")
            .claim("role", "admin")
            .claim("env", "staging")
            .build();

        // when / then
        BadJWTException exception = assertThrows(BadJWTException.class, () -> verifier.verify(claims, null));
        assertThat(exception.getMessage(), containsString("env"));
        assertThat(exception.getMessage(), containsString("staging"));
        assertThat(exception.getMessage(), containsString("production"));
    }

    // --- Combined: audience + required + matching ---

    @Test
    public void shouldRejectOnAudienceBeforeCheckingOtherConstraints() {
        // given - wrong audience but also missing required claims and wrong matching claim
        Set<String> requiredClaims = new HashSet<>(Collections.singletonList("missing_claim"));
        JWTClaimsSet exactMatchClaims = new JWTClaimsSet.Builder()
            .claim("sub", "expected")
            .build();
        CustomJWTClaimsVerifier verifier = new CustomJWTClaimsVerifier("correct-aud", exactMatchClaims, requiredClaims);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .audience("wrong-aud")
            .subject("actual")
            .build();

        // when / then - audience check fires first
        BadJWTException exception = assertThrows(BadJWTException.class, () -> verifier.verify(claims, null));
        assertThat(exception.getMessage(), containsString("JWT audience rejected"));
    }

    @Test
    public void shouldRejectOnRequiredClaimsBeforeCheckingMatchingClaims() {
        // given - correct audience, missing required claims, wrong matching claim
        Set<String> requiredClaims = new HashSet<>(Collections.singletonList("scope"));
        JWTClaimsSet exactMatchClaims = new JWTClaimsSet.Builder()
            .claim("sub", "expected")
            .build();
        CustomJWTClaimsVerifier verifier = new CustomJWTClaimsVerifier("my-aud", exactMatchClaims, requiredClaims);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .audience("my-aud")
            .subject("wrong")
            .build();

        // when / then - required claims check fires before matching claims
        BadJWTException exception = assertThrows(BadJWTException.class, () -> verifier.verify(claims, null));
        assertThat(exception.getMessage(), containsString("JWT missing required claims"));
    }

    @Test
    public void shouldReportMissingRequiredClaimsSorted() {
        // given
        Set<String> requiredClaims = new HashSet<>(Arrays.asList("zebra", "alpha", "middle"));
        CustomJWTClaimsVerifier verifier = new CustomJWTClaimsVerifier(null, new JWTClaimsSet.Builder().build(), requiredClaims);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("test")
            .build();

        // when / then
        BadJWTException exception = assertThrows(BadJWTException.class, () -> verifier.verify(claims, null));
        assertThat(exception.getMessage(), containsString("[alpha, middle, zebra]"));
    }
}
