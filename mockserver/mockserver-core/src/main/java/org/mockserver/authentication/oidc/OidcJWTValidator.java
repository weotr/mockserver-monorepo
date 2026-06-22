package org.mockserver.authentication.oidc;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.mockserver.authentication.AuthenticationException;

import java.text.ParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Verifies a control-plane OIDC bearer token against a remote/immutable JWK set.
 * Installs an {@link OidcJWTClaimsVerifier} so issuer, audience, exp/nbf and
 * required-scope checks are all enforced. On any failure throws
 * {@link AuthenticationException} (-> 401).
 * <p>
 * Only ASYMMETRIC JWS algorithms are accepted (RS*, PS*, ES*, EdDSA). HMAC
 * (HS256/384/512) is deliberately excluded: OIDC IdPs sign with asymmetric keys,
 * and accepting HMAC against a public JWK set is the classic algorithm-confusion
 * footgun (an attacker forges an HMAC token using the public key bytes as the
 * shared secret). The {@code alg=none} (unsecured) JWS is likewise rejected by
 * nimbus because it is not in this set.
 */
public class OidcJWTValidator {

    private static final Set<JWSAlgorithm> JWS_ALGORITHMS = new HashSet<>(Arrays.asList(
        JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
        JWSAlgorithm.ES256, JWSAlgorithm.ES256K, JWSAlgorithm.ES384, JWSAlgorithm.ES512,
        JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512,
        JWSAlgorithm.EdDSA
    ));

    private final ConfigurableJWTProcessor<SecurityContext> jwtProcessor;

    public OidcJWTValidator(JWKSource<SecurityContext> jwkSource, String expectedAudience, String expectedIssuer, String scopeClaim, Set<String> requiredScopes) {
        this.jwtProcessor = new DefaultJWTProcessor<>();
        jwtProcessor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(
            null,
            new JOSEObjectType("at+jwt"),
            JOSEObjectType.JOSE,
            JOSEObjectType.JOSE_JSON,
            JOSEObjectType.JWT
        ));
        jwtProcessor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWS_ALGORITHMS, jwkSource));
        jwtProcessor.setJWTClaimsSetVerifier(new OidcJWTClaimsVerifier(expectedAudience, expectedIssuer, scopeClaim, requiredScopes));
    }

    public JWTClaimsSet validate(String jwt) {
        try {
            return jwtProcessor.process(jwt, null);
        } catch (ParseException | BadJOSEException | JOSEException exception) {
            // clientSafeMessage=false: the detailed reason (expected issuer/audience/scopes,
            // signature failure) is logged server-side only; the client gets a generic 401.
            throw new AuthenticationException(exception.getMessage(), exception, false);
        }
    }
}
