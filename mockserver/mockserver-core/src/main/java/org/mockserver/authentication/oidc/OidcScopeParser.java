package org.mockserver.authentication.oidc;

import com.nimbusds.jwt.JWTClaimsSet;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Normalises an OIDC token's granted scopes into a flat {@link Set} of strings.
 * <p>
 * The configured scope claim (default {@code "scope"}) is read first: a String value is
 * split on whitespace (OAuth2 {@code scope} convention), an array/collection value is
 * flattened element-by-element. This handles the common {@code scope} (space-delimited),
 * {@code scp} (array), {@code roles} (array) and {@code groups} (array) shapes uniformly.
 */
public final class OidcScopeParser {

    private OidcScopeParser() {
    }

    public static Set<String> parseScopes(JWTClaimsSet claimsSet, String scopeClaim) {
        Set<String> scopes = new LinkedHashSet<>();
        if (claimsSet == null) {
            return scopes;
        }
        String claimName = isBlank(scopeClaim) ? "scope" : scopeClaim;
        Object value = claimsSet.getClaim(claimName);
        addValue(scopes, value);
        return scopes;
    }

    private static void addValue(Set<String> scopes, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String) {
            for (String part : ((String) value).trim().split("\\s+")) {
                if (!part.isEmpty()) {
                    scopes.add(part);
                }
            }
        } else if (value instanceof List) {
            for (Object element : (List<?>) value) {
                addValue(scopes, element);
            }
        } else if (value instanceof Object[]) {
            for (Object element : (Object[]) value) {
                addValue(scopes, element);
            }
        } else {
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                scopes.add(text);
            }
        }
    }
}
