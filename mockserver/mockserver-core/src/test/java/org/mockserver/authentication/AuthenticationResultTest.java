package org.mockserver.authentication;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;

public class AuthenticationResultTest {

    @Test
    public void authenticatedFactoryExposesFields() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "alice");
        Set<String> scopes = new HashSet<>();
        scopes.add("mockserver.read");

        AuthenticationResult result = AuthenticationResult.authenticated("alice", "verified-oidc", claims, scopes);

        assertThat(result.isAuthenticated(), is(true));
        assertThat(result.getPrincipal(), is("alice"));
        assertThat(result.getPrincipalSource(), is("verified-oidc"));
        assertThat(result.getClaims().get("sub"), is("alice"));
        assertThat(result.getScopes(), contains("mockserver.read"));
    }

    @Test
    public void unauthenticatedFactory() {
        AuthenticationResult result = AuthenticationResult.unauthenticated();
        assertThat(result.isAuthenticated(), is(false));
        assertThat(result.getPrincipal(), is(nullValue()));
        assertThat(result.getPrincipalSource(), is("none"));
        assertThat(result.getClaims().isEmpty(), is(true));
        assertThat(result.getScopes().isEmpty(), is(true));
    }

    @Test
    public void claimsAndScopesAreImmutable() {
        AuthenticationResult result = AuthenticationResult.authenticated("bob", "verified-jwt", Map.of("sub", "bob"), Set.of("a"));
        assertThrows(UnsupportedOperationException.class, () -> result.getClaims().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> result.getScopes().add("z"));
    }

    @Test
    public void defensiveCopyOfInputCollections() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "carol");
        Set<String> scopes = new HashSet<>();
        scopes.add("a");

        AuthenticationResult result = AuthenticationResult.authenticated("carol", "verified-oidc", claims, scopes);

        // mutating the source collections must not affect the result
        claims.put("injected", "value");
        scopes.add("injected");

        assertThat(result.getClaims().containsKey("injected"), is(false));
        assertThat(result.getScopes().contains("injected"), is(false));
    }

    @Test
    public void nullCollectionsBecomeEmpty() {
        AuthenticationResult result = AuthenticationResult.authenticated(null, null, null, null);
        assertThat(result.isAuthenticated(), is(true));
        assertThat(result.getPrincipal(), is(nullValue()));
        assertThat(result.getPrincipalSource(), is("none"));
        assertThat(result.getClaims().isEmpty(), is(true));
        assertThat(result.getScopes().isEmpty(), is(true));
    }
}
