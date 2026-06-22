package org.mockserver.oidc;

import org.mockserver.mock.action.ExpectationResponseCallback;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import static org.mockserver.model.HttpResponse.response;

/**
 * Mock OIDC end-session ({@code /logout}) endpoint, the {@code end_session_endpoint} advertised in
 * the discovery document.
 *
 * <p>Implements RP-initiated logout: if a {@code post_logout_redirect_uri} is supplied it
 * 302-redirects there (echoing any {@code state}), otherwise it returns a simple {@code 200}. As a
 * mock there is no session to invalidate.
 */
public class OidcLogoutCallback implements ExpectationResponseCallback {

    @Override
    public HttpResponse handle(HttpRequest request) {
        String postLogoutRedirectUri = request.getFirstQueryStringParameter("post_logout_redirect_uri");
        String state = request.getFirstQueryStringParameter("state");

        if (postLogoutRedirectUri != null && !postLogoutRedirectUri.isEmpty()) {
            StringBuilder location = new StringBuilder(postLogoutRedirectUri);
            if (state != null && !state.isEmpty()) {
                location.append(postLogoutRedirectUri.contains("?") ? '&' : '?');
                location.append("state=").append(state);
            }
            return response()
                .withStatusCode(302)
                .withHeader("location", location.toString());
        }

        return response()
            .withStatusCode(200)
            .withHeader("content-type", "text/html; charset=utf-8")
            .withBody("<html><body>Logged out</body></html>");
    }
}
