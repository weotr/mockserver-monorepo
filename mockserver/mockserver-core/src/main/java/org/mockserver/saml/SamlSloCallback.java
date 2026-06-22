package org.mockserver.saml;

import org.mockserver.mock.action.ExpectationResponseCallback;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.mockserver.model.HttpResponse.response;

/**
 * Mock SAML 2.0 IdP Single-Logout (SLO) endpoint implementing the HTTP-POST profile.
 *
 * <p>Accepts a {@code LogoutRequest} (read from the query string or the
 * {@code application/x-www-form-urlencoded} body, mirroring {@link SamlSsoCallback}), builds a freshly
 * signed {@code <LogoutResponse>} (see {@link SamlLogoutResponseBuilder}), base64-encodes it, and
 * returns a self-submitting HTML form that POSTs the {@code SAMLResponse} (and the echoed
 * {@code RelayState}, if supplied) to the SP's Single-Logout service URL.
 *
 * <p>The {@code LogoutRequest} ID is extracted with the same XXE-hardened, root-element-only parsing
 * as the SSO callback and echoed as {@code InResponseTo}.
 */
public class SamlSloCallback implements ExpectationResponseCallback {

    @Override
    public HttpResponse handle(HttpRequest request) {
        SamlAssertionStore.Provider provider =
            SamlAssertionStore.getInstance().providerForSloPath(request.getPath().getValue());
        if (provider == null) {
            return response()
                .withStatusCode(404)
                .withHeader("content-type", "text/plain; charset=utf-8")
                .withBody("no SAML provider registered for this SLO endpoint");
        }

        String relayState = SamlSsoCallback.param(request, "RelayState");
        String logoutRequestId = SamlSsoCallback.extractRootElementId(SamlSsoCallback.param(request, "SAMLRequest"));

        String signedLogoutResponseXml =
            new SamlLogoutResponseBuilder().buildSignedLogoutResponse(provider, logoutRequestId);
        String samlResponseB64 = Base64.getEncoder()
            .encodeToString(signedLogoutResponseXml.getBytes(StandardCharsets.UTF_8));

        String html = SamlSsoCallback.buildAutoPostForm(
            provider.spSingleLogoutServiceUrl, "SAMLResponse", samlResponseB64, relayState);

        return response()
            .withStatusCode(200)
            .withHeader("content-type", "text/html; charset=utf-8")
            .withBody(html);
    }
}
