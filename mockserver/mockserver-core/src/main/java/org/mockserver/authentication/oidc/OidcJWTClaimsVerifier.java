package org.mockserver.authentication.oidc;

import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.BadJWTException;
import org.mockserver.authentication.jwt.CustomJWTClaimsVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Extends {@link CustomJWTClaimsVerifier} (which already enforces audience, exp/nbf
 * with skew, required claims and exact-match claims) to additionally assert, for an
 * external OIDC IdP:
 * <ul>
 *   <li>the {@code iss} claim equals the configured issuer, and</li>
 *   <li>the token's granted scopes (parsed from the configured scope claim) contain
 *       every required scope.</li>
 * </ul>
 * Issuer and scope checks run BEFORE delegating to the superclass so a wrong issuer
 * or insufficient scope is reported with a precise message.
 * <p>
 * The {@code exp} claim is REQUIRED (passed as a required claim to the superclass):
 * nimbus only checks expiry when the claim is present, so without this a token minted
 * with no {@code exp} would be accepted forever. A real OIDC token always carries
 * {@code exp}, so requiring it is secure-by-default with no legitimate downside.
 */
public class OidcJWTClaimsVerifier extends CustomJWTClaimsVerifier {

    private final String expectedIssuer;
    private final String scopeClaim;
    private final Set<String> requiredScopes;

    public OidcJWTClaimsVerifier(String expectedAudience, String expectedIssuer, String scopeClaim, Set<String> requiredScopes) {
        super(expectedAudience, new JWTClaimsSet.Builder().build(), Set.of("exp"));
        this.expectedIssuer = expectedIssuer;
        this.scopeClaim = scopeClaim;
        this.requiredScopes = requiredScopes;
    }

    @Override
    public void verify(JWTClaimsSet claimsSet, SecurityContext context) throws BadJWTException {
        if (expectedIssuer != null) {
            String issuer = claimsSet.getIssuer();
            if (issuer == null || !expectedIssuer.equals(issuer)) {
                throw new BadJWTException("JWT iss claim has value " + issuer + ", must be " + expectedIssuer);
            }
        }

        if (requiredScopes != null && !requiredScopes.isEmpty()) {
            Set<String> grantedScopes = OidcScopeParser.parseScopes(claimsSet, scopeClaim);
            List<String> missingScopes = new ArrayList<>();
            for (String requiredScope : requiredScopes) {
                if (!grantedScopes.contains(requiredScope)) {
                    missingScopes.add(requiredScope);
                }
            }
            if (!missingScopes.isEmpty()) {
                missingScopes.sort(String::compareTo);
                throw new BadJWTException("JWT missing required scopes: " + missingScopes);
            }
        }

        super.verify(claimsSet, context);
    }
}
