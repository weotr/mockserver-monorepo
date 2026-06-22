package org.mockserver.saml;

import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpClassCallback;

import java.util.ArrayList;
import java.util.List;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Generates MockServer {@link Expectation}s that stand up a complete mock SAML 2.0 Identity
 * Provider implementing the SP-initiated Web-Browser-SSO POST profile, so applications using SAML
 * SSO can be tested without a real IdP.
 *
 * <p>Up to three endpoints are generated:
 * <ul>
 *   <li>{@code GET <metadataUrl>} — returns SAML 2.0 IdP metadata XML (an {@code EntityDescriptor}
 *       with an {@code IDPSSODescriptor}, the signing X.509 certificate, a {@code SingleSignOnService}
 *       and (when an SLO path is configured) a {@code SingleLogoutService}, both with the HTTP-POST
 *       binding).</li>
 *   <li>{@code GET}/{@code POST <ssoServiceUrl>} — a {@link SamlSsoCallback} class callback that
 *       returns an auto-submitting HTML form POSTing a base64-encoded, XML-DSig-signed SAML
 *       {@code Response} to the SP's assertion consumer service, echoing {@code RelayState}.</li>
 *   <li>{@code GET}/{@code POST <sloServiceUrl>} — a {@link SamlSloCallback} class callback that
 *       returns an auto-submitting HTML form POSTing a signed SAML {@code LogoutResponse} to the SP's
 *       Single-Logout service, echoing {@code RelayState} (omitted when no SLO path is configured).</li>
 * </ul>
 *
 * <p>The signing credential is taken from the configuration if supplied, otherwise a self-signed
 * RSA credential is generated (see {@link SamlSigningCredential}). All SAML XML is built with the
 * JDK XML APIs and enveloped-signed with the JDK XML Digital Signature API — no OpenSAML dependency.
 *
 * <p>Usage mirrors {@link org.mockserver.oidc.OidcProviderGenerator}: call
 * {@link #generate(SamlProviderConfiguration)} and upsert the returned expectations.
 */
public class SamlProviderGenerator {

    private static final String METADATA_NS = "urn:oasis:names:tc:SAML:2.0:metadata";
    private static final String DS_NS = "http://www.w3.org/2000/09/xmldsig#";
    private static final String ASSERTION_NS = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final String BINDING_POST = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST";

    /**
     * Generates SAML IdP expectations from the given configuration.
     *
     * @param config the provider configuration (must not be null)
     * @return the generated expectations (metadata + SSO endpoint)
     */
    public List<Expectation> generate(SamlProviderConfiguration config) {
        if (config == null) {
            throw new IllegalArgumentException("SAML provider configuration is required");
        }

        SamlSigningCredential credential = SamlSigningCredential.from(config);

        List<Expectation> expectations = new ArrayList<>();

        // 1. Metadata endpoint (static XML)
        String metadataXml = buildMetadataXml(config, credential.getCertificateBase64());
        expectations.add(new Expectation(
            request()
                .withMethod("GET")
                .withPath(config.getMetadataUrl())
        )
            .withId("saml.metadata")
            .thenRespond(response()
                .withStatusCode(200)
                .withHeader("content-type", "application/samlmetadata+xml; charset=utf-8")
                .withBody(metadataXml)));

        // 2. SSO endpoint (class callback — builds + signs a fresh Response per request).
        //    Accept both GET and POST so either initial-redirect binding works.
        expectations.add(new Expectation(
            request()
                .withPath(config.getSsoServiceUrl())
        )
            .withId("saml.sso")
            .thenRespond(HttpClassCallback.callback(SamlSsoCallback.class.getName())));

        // 3. Single-Logout (SLO) endpoint (class callback — builds + signs a fresh LogoutResponse).
        //    Only generated when an SLO path is configured (it is by default).
        if (config.getSloServiceUrl() != null && !config.getSloServiceUrl().trim().isEmpty()) {
            expectations.add(new Expectation(
                request()
                    .withPath(config.getSloServiceUrl())
            )
                .withId("saml.slo")
                .thenRespond(HttpClassCallback.callback(SamlSloCallback.class.getName())));
        }

        // Register provider state so the SSO/SLO callbacks can build + sign the Response per request.
        SamlAssertionStore.getInstance().registerProvider(new SamlAssertionStore.Provider(
            config.getSsoServiceUrl(),
            config.getSloServiceUrl(),
            config.getSpSingleLogoutServiceUrl(),
            config.getIdpEntityId(),
            config.getSpEntityId(),
            config.getAssertionConsumerServiceUrl(),
            config.getSubjectNameId(),
            config.getNameIdFormat(),
            config.getAttributes(),
            config.getSessionDurationSeconds(),
            credential.getPrivateKey(),
            credential.getCertificate(),
            credential.getAlgorithm(),
            config.isExpiredAssertion(),
            config.isWrongAudience(),
            config.isTamperedSignature()
        ));

        return expectations;
    }

    /**
     * Builds SAML 2.0 IdP metadata XML. Built as a string (rather than DOM) because it is a fixed,
     * unsigned document with only two dynamic values (entityId + base64 certificate), both of which
     * are XML-escaped.
     */
    private String buildMetadataXml(SamlProviderConfiguration config, String certificateBase64) {
        String idpEntityId = escape(config.getIdpEntityId());
        String ssoUrl = escape(buildAbsoluteLocation(config.getSsoServiceUrl(), config.getIdpEntityId()));

        StringBuilder sloElement = new StringBuilder();
        if (config.getSloServiceUrl() != null && !config.getSloServiceUrl().trim().isEmpty()) {
            String sloUrl = escape(buildAbsoluteLocation(config.getSloServiceUrl(), config.getIdpEntityId()));
            // SingleLogoutService must appear before NameIDFormat in the metadata schema ordering.
            sloElement.append("    <md:SingleLogoutService Binding=\"")
                .append(BINDING_POST).append("\" Location=\"").append(sloUrl).append("\"/>\n");
        }

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<md:EntityDescriptor xmlns:md=\"" + METADATA_NS + "\""
            + " xmlns:ds=\"" + DS_NS + "\""
            + " entityID=\"" + idpEntityId + "\">\n"
            + "  <md:IDPSSODescriptor WantAuthnRequestsSigned=\"false\""
            + " protocolSupportEnumeration=\"urn:oasis:names:tc:SAML:2.0:protocol\">\n"
            + "    <md:KeyDescriptor use=\"signing\">\n"
            + "      <ds:KeyInfo>\n"
            + "        <ds:X509Data>\n"
            + "          <ds:X509Certificate>" + certificateBase64 + "</ds:X509Certificate>\n"
            + "        </ds:X509Data>\n"
            + "      </ds:KeyInfo>\n"
            + "    </md:KeyDescriptor>\n"
            + sloElement
            + "    <md:NameIDFormat>" + escape(config.getNameIdFormat()) + "</md:NameIDFormat>\n"
            + "    <md:SingleSignOnService Binding=\"" + BINDING_POST + "\" Location=\"" + ssoUrl + "\"/>\n"
            + "  </md:IDPSSODescriptor>\n"
            + "</md:EntityDescriptor>\n";
    }

    /**
     * Resolves a service path to an absolute metadata {@code Location}. If the path is already
     * absolute it is used as-is; otherwise it is resolved against the IdP entity id's origin when that
     * is an absolute URL, so the published metadata points at a usable absolute endpoint.
     */
    private String buildAbsoluteLocation(String path, String entityId) {
        if (path == null) {
            return "";
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        if (entityId != null && (entityId.startsWith("http://") || entityId.startsWith("https://"))) {
            try {
                java.net.URI uri = java.net.URI.create(entityId);
                String origin = uri.getScheme() + "://" + uri.getAuthority();
                return origin + (path.startsWith("/") ? path : "/" + path);
            } catch (Exception ignored) {
                // fall through to the raw path
            }
        }
        return path;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
