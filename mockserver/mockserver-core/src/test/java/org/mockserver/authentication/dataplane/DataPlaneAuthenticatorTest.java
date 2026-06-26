package org.mockserver.authentication.dataplane;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.model.HttpRequest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Unit tests for {@link DataPlaneAuthenticator}. These use a local {@link Configuration} instance
 * (never the static {@code ConfigurationProperties}), so they are parallel-safe and do not need to
 * live in the sequential Surefire phase.
 */
public class DataPlaneAuthenticatorTest {

    private static String basicHeader(String username, String password) {
        return "Basic " + Base64.getEncoder()
            .encodeToString((username + ':' + password).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ default off

    @Test
    public void defaultOffIsNotEnabledAndAlwaysAuthenticated() {
        DataPlaneAuthenticator authenticator = new DataPlaneAuthenticator(configuration());
        assertThat(authenticator.isEnabled(), is(false));
        DataPlaneAuthenticator.Outcome outcome = authenticator.authenticate(request().withPath("/some/path"));
        assertThat(outcome.isAuthenticated(), is(true));
        assertThat(outcome.wwwAuthenticate(), is(nullValue()));
    }

    @Test
    public void disabledWithCredentialsConfiguredStillAlwaysAuthenticated() {
        // Credentials configured but the master switch is off → no gate at all.
        Configuration configuration = configuration()
            .dataPlaneBasicAuthenticationUsername("user")
            .dataPlaneBasicAuthenticationPassword("pass");
        DataPlaneAuthenticator authenticator = new DataPlaneAuthenticator(configuration);
        assertThat(authenticator.isEnabled(), is(false));
        assertThat(authenticator.authenticate(request()).isAuthenticated(), is(true));
    }

    // ------------------------------------------------------------------ fail-closed

    @Test
    public void enabledButNoSchemeConfiguredFailsClosed() {
        Configuration configuration = configuration().dataPlaneAuthenticationRequired(true);
        DataPlaneAuthenticator authenticator = new DataPlaneAuthenticator(configuration);
        assertThat(authenticator.isEnabled(), is(true));
        // Every request rejected, even one carrying an Authorization header.
        assertThat(authenticator.authenticate(request()).isAuthenticated(), is(false));
        assertThat(authenticator.authenticate(
            request().withHeader("Authorization", "Bearer anything")).isAuthenticated(), is(false));
    }

    @Test
    public void halfConfiguredBasicIsTreatedAsUnconfiguredAndFailsClosed() {
        // username only (no password) → Basic not active → no scheme → fail closed.
        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBasicAuthenticationUsername("user");
        DataPlaneAuthenticator authenticator = new DataPlaneAuthenticator(configuration);
        assertThat(authenticator.authenticate(
            request().withHeader("Authorization", basicHeader("user", ""))).isAuthenticated(), is(false));
    }

    @Test
    public void halfConfiguredApiKeyIsTreatedAsUnconfiguredAndFailsClosed() {
        // header name without a value → API-key not active → no scheme → fail closed.
        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneApiKeyAuthenticationHeader("X-API-Key");
        DataPlaneAuthenticator authenticator = new DataPlaneAuthenticator(configuration);
        assertThat(authenticator.authenticate(
            request().withHeader("X-API-Key", "")).isAuthenticated(), is(false));
    }

    // ------------------------------------------------------------------ basic

    private DataPlaneAuthenticator basicAuthenticator() {
        return new DataPlaneAuthenticator(configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBasicAuthenticationUsername("user")
            .dataPlaneBasicAuthenticationPassword("secret")
            .dataPlaneBasicAuthenticationRealm("my-realm"));
    }

    @Test
    public void basicValidCredentialsAuthenticated() {
        DataPlaneAuthenticator authenticator = basicAuthenticator();
        DataPlaneAuthenticator.Outcome outcome = authenticator.authenticate(
            request().withHeader("Authorization", basicHeader("user", "secret")));
        assertThat(outcome.isAuthenticated(), is(true));
    }

    @Test
    public void basicSchemeNameIsCaseInsensitive() {
        DataPlaneAuthenticator authenticator = basicAuthenticator();
        String token = Base64.getEncoder().encodeToString("user:secret".getBytes(StandardCharsets.UTF_8));
        assertThat(authenticator.authenticate(
            request().withHeader("Authorization", "basic " + token)).isAuthenticated(), is(true));
    }

    @Test
    public void basicWrongPasswordRejectedWithRealmChallenge() {
        DataPlaneAuthenticator authenticator = basicAuthenticator();
        DataPlaneAuthenticator.Outcome outcome = authenticator.authenticate(
            request().withHeader("Authorization", basicHeader("user", "wrong")));
        assertThat(outcome.isAuthenticated(), is(false));
        assertThat(outcome.wwwAuthenticate(), containsString("Basic realm=\"my-realm\""));
        assertThat(outcome.wwwAuthenticate(), containsString("charset=\"UTF-8\""));
    }

    @Test
    public void basicWrongUsernameRejected() {
        DataPlaneAuthenticator authenticator = basicAuthenticator();
        assertThat(authenticator.authenticate(
            request().withHeader("Authorization", basicHeader("other", "secret"))).isAuthenticated(), is(false));
    }

    @Test
    public void basicMissingHeaderRejectedWithChallenge() {
        DataPlaneAuthenticator authenticator = basicAuthenticator();
        DataPlaneAuthenticator.Outcome outcome = authenticator.authenticate(request());
        assertThat(outcome.isAuthenticated(), is(false));
        assertThat(outcome.wwwAuthenticate(), containsString("Basic realm=\"my-realm\""));
    }

    @Test
    public void basicWrongSchemeRejected() {
        DataPlaneAuthenticator authenticator = basicAuthenticator();
        assertThat(authenticator.authenticate(
            request().withHeader("Authorization", "Bearer " + basicHeader("user", "secret"))).isAuthenticated(), is(false));
    }

    @Test
    public void basicDefaultRealmIsMockServerWhenNotConfigured() {
        DataPlaneAuthenticator authenticator = new DataPlaneAuthenticator(configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBasicAuthenticationUsername("user")
            .dataPlaneBasicAuthenticationPassword("secret"));
        DataPlaneAuthenticator.Outcome outcome = authenticator.authenticate(request());
        assertThat(outcome.wwwAuthenticate(), containsString("Basic realm=\"MockServer\""));
    }

    @Test
    public void basicRealmWithEmbeddedQuoteIsEscaped() {
        DataPlaneAuthenticator authenticator = new DataPlaneAuthenticator(configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBasicAuthenticationUsername("user")
            .dataPlaneBasicAuthenticationPassword("secret")
            .dataPlaneBasicAuthenticationRealm("a\"b"));
        DataPlaneAuthenticator.Outcome outcome = authenticator.authenticate(request());
        assertThat(outcome.wwwAuthenticate(), containsString("realm=\"a\\\"b\""));
    }

    // ------------------------------------------------------------------ bearer

    private DataPlaneAuthenticator bearerAuthenticator() {
        return new DataPlaneAuthenticator(configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken("tok-123"));
    }

    @Test
    public void bearerValidTokenAuthenticated() {
        assertThat(bearerAuthenticator().authenticate(
            request().withHeader("Authorization", "Bearer tok-123")).isAuthenticated(), is(true));
    }

    @Test
    public void bearerSchemeNameIsCaseInsensitive() {
        assertThat(bearerAuthenticator().authenticate(
            request().withHeader("Authorization", "bearer tok-123")).isAuthenticated(), is(true));
    }

    @Test
    public void bearerWrongTokenRejectedWithBearerChallenge() {
        DataPlaneAuthenticator.Outcome outcome = bearerAuthenticator().authenticate(
            request().withHeader("Authorization", "Bearer nope"));
        assertThat(outcome.isAuthenticated(), is(false));
        assertThat(outcome.wwwAuthenticate(), is(equalTo("Bearer")));
    }

    @Test
    public void bearerMissingHeaderRejected() {
        assertThat(bearerAuthenticator().authenticate(request()).isAuthenticated(), is(false));
    }

    @Test
    public void bearerEmptyTokenRejected() {
        assertThat(bearerAuthenticator().authenticate(
            request().withHeader("Authorization", "Bearer ")).isAuthenticated(), is(false));
    }

    // ------------------------------------------------------------------ api-key

    private DataPlaneAuthenticator apiKeyAuthenticator() {
        return new DataPlaneAuthenticator(configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneApiKeyAuthenticationHeader("X-API-Key")
            .dataPlaneApiKeyAuthenticationValue("key-abc"));
    }

    @Test
    public void apiKeyValidAuthenticated() {
        assertThat(apiKeyAuthenticator().authenticate(
            request().withHeader("X-API-Key", "key-abc")).isAuthenticated(), is(true));
    }

    @Test
    public void apiKeyWrongValueRejectedWithNoChallenge() {
        DataPlaneAuthenticator.Outcome outcome = apiKeyAuthenticator().authenticate(
            request().withHeader("X-API-Key", "wrong"));
        assertThat(outcome.isAuthenticated(), is(false));
        // API-key has no standard WWW-Authenticate challenge scheme.
        assertThat(outcome.wwwAuthenticate(), is(nullValue()));
    }

    @Test
    public void apiKeyMissingHeaderRejected() {
        assertThat(apiKeyAuthenticator().authenticate(request()).isAuthenticated(), is(false));
    }

    // ------------------------------------------------------------------ multi-scheme (accept any)

    @Test
    public void multiSchemeAcceptsAnyConfiguredScheme() {
        DataPlaneAuthenticator authenticator = new DataPlaneAuthenticator(configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBasicAuthenticationUsername("user")
            .dataPlaneBasicAuthenticationPassword("secret")
            .dataPlaneBearerAuthenticationToken("tok-123")
            .dataPlaneApiKeyAuthenticationHeader("X-API-Key")
            .dataPlaneApiKeyAuthenticationValue("key-abc"));

        // each scheme on its own satisfies the gate
        assertThat(authenticator.authenticate(
            request().withHeader("Authorization", basicHeader("user", "secret"))).isAuthenticated(), is(true));
        assertThat(authenticator.authenticate(
            request().withHeader("Authorization", "Bearer tok-123")).isAuthenticated(), is(true));
        assertThat(authenticator.authenticate(
            request().withHeader("X-API-Key", "key-abc")).isAuthenticated(), is(true));

        // none present → rejected; Basic challenge advertised (Basic configured)
        DataPlaneAuthenticator.Outcome outcome = authenticator.authenticate(request());
        assertThat(outcome.isAuthenticated(), is(false));
        assertThat(outcome.wwwAuthenticate(), containsString("Basic realm="));
    }

    @Test
    public void multiSchemeBasicAndBearerWrongBasicButRightBearerAccepted() {
        DataPlaneAuthenticator authenticator = new DataPlaneAuthenticator(configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBasicAuthenticationUsername("user")
            .dataPlaneBasicAuthenticationPassword("secret")
            .dataPlaneBearerAuthenticationToken("tok-123"));
        // wrong basic but request actually carries a valid bearer — accepted (OR semantics)
        HttpRequest req = request().withHeader("Authorization", "Bearer tok-123");
        assertThat(authenticator.authenticate(req).isAuthenticated(), is(true));
    }

    // ------------------------------------------------------------------ constant-time path

    @Test
    public void constantTimeCompareExercisedForUnequalLengths() {
        // Exercises the MessageDigest.isEqual path with differing-length inputs (the secret value vs a
        // much shorter supplied value) — proving the unequal branch returns a clean rejection.
        DataPlaneAuthenticator authenticator = new DataPlaneAuthenticator(configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken("a-very-long-expected-token-value-0123456789"));
        assertThat(authenticator.authenticate(
            request().withHeader("Authorization", "Bearer x")).isAuthenticated(), is(false));
        assertThat(authenticator.authenticate(
            request().withHeader("Authorization", "Bearer a-very-long-expected-token-value-0123456789")).isAuthenticated(), is(true));
    }
}
