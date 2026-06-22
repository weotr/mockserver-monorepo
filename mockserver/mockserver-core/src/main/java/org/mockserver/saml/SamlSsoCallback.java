package org.mockserver.saml;

import org.mockserver.mock.action.ExpectationResponseCallback;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

import static org.mockserver.model.HttpResponse.response;

/**
 * Mock SAML 2.0 IdP Single-Sign-On endpoint implementing the SP-initiated Web-Browser-SSO POST
 * profile.
 *
 * <p>There is no login UI — the mock authenticates the configured subject deterministically. It
 * builds a freshly signed SAML {@code <Response>} (see {@link SamlResponseBuilder}), base64-encodes
 * it, and returns a self-submitting HTML form that POSTs the {@code SAMLResponse} (and the echoed
 * {@code RelayState}, if supplied) to the SP's assertion consumer service URL.
 *
 * <p>The {@code RelayState} and {@code SAMLRequest} are read from either the query string (HTTP-GET)
 * or the {@code application/x-www-form-urlencoded} body (HTTP-POST), so both bindings of the initial
 * SP redirect are accepted.
 */
public class SamlSsoCallback implements ExpectationResponseCallback {

    /**
     * Maximum size of the base64-decoded SAMLRequest we are willing to process (64KB). Anything
     * larger is rejected outright to bound work and guard against decompression-bomb inputs.
     */
    private static final int MAX_DECODED_SAML_REQUEST_BYTES = 64 * 1024;

    /**
     * Maximum number of bytes we are willing to inflate from a deflated SAMLRequest (1MB). Inflation
     * is aborted once this cap is exceeded to prevent a decompression bomb.
     */
    private static final int MAX_INFLATED_SAML_REQUEST_BYTES = 1024 * 1024;

    /** SAML {@code xs:ID} lexical form — the only shape we will echo as {@code InResponseTo}. */
    private static final Pattern SAML_ID_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9._-]*$");

    @Override
    public HttpResponse handle(HttpRequest request) {
        SamlAssertionStore.Provider provider =
            SamlAssertionStore.getInstance().providerForSsoPath(request.getPath().getValue());
        if (provider == null) {
            return response()
                .withStatusCode(404)
                .withHeader("content-type", "text/plain; charset=utf-8")
                .withBody("no SAML provider registered for this SSO endpoint");
        }

        String relayState = param(request, "RelayState");
        String samlRequestId = extractRootElementId(param(request, "SAMLRequest"));

        String signedResponseXml = new SamlResponseBuilder().buildSignedResponse(provider, samlRequestId);
        String samlResponseB64 = Base64.getEncoder().encodeToString(signedResponseXml.getBytes(StandardCharsets.UTF_8));

        String html = buildAutoPostForm(provider.assertionConsumerServiceUrl, "SAMLResponse", samlResponseB64, relayState);

        return response()
            .withStatusCode(200)
            .withHeader("content-type", "text/html; charset=utf-8")
            .withBody(html);
    }

    /**
     * Builds a self-submitting HTML form that POSTs the given SAML message (under the supplied field
     * name, e.g. {@code SAMLResponse}) and an optional echoed {@code RelayState} to {@code actionUrl}.
     * Shared by the SSO and SLO callbacks.
     */
    static String buildAutoPostForm(String actionUrl, String fieldName, String samlMessageB64, String relayState) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n")
            .append("<html>\n<head><title>SAML 2.0</title></head>\n")
            .append("<body onload=\"document.forms[0].submit()\">\n")
            .append("<noscript><p>Your browser does not support JavaScript. ")
            .append("Please click the button below to continue.</p></noscript>\n")
            .append("<form method=\"post\" action=\"").append(escapeAttr(actionUrl)).append("\">\n")
            .append("<input type=\"hidden\" name=\"").append(escapeAttr(fieldName)).append("\" value=\"").append(escapeAttr(samlMessageB64)).append("\"/>\n");
        if (relayState != null && !relayState.isEmpty()) {
            html.append("<input type=\"hidden\" name=\"RelayState\" value=\"").append(escapeAttr(relayState)).append("\"/>\n");
        }
        html.append("<noscript><input type=\"submit\" value=\"Continue\"/></noscript>\n")
            .append("</form>\n</body>\n</html>");
        return html.toString();
    }

    static String param(HttpRequest request, String name) {
        String value = request.getFirstQueryStringParameter(name);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        // fall back to application/x-www-form-urlencoded body parameters (HTTP-POST binding)
        String body = request.getBodyAsString();
        if (body != null && !body.isEmpty()) {
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(name)) {
                    return urlDecode(pair.substring(eq + 1));
                }
            }
        }
        return null;
    }

    /**
     * Best-effort extraction of the AuthnRequest ID from a base64 (optionally deflated) SAMLRequest,
     * to echo as {@code InResponseTo}. Returns {@code null} if it cannot be parsed — the mock still
     * produces a valid IdP-initiated style response in that case.
     *
     * <p>Security: the SAMLRequest is attacker-supplied, so the XML is parsed with an XXE-hardened
     * DOM parser (no DOCTYPE, no external entities) and the {@code InResponseTo} is taken strictly
     * from the <em>root</em> element's {@code ID} attribute — never by string-scanning, which a
     * decoy comment/child {@code ID="..."} could forge. The value is validated against the SAML
     * {@code xs:ID} lexical form before being echoed.
     */
    static String extractRootElementId(String samlRequest) {
        if (samlRequest == null || samlRequest.isEmpty()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(samlRequest);
            String xml = inflateIfNeeded(decoded);
            if (xml == null || xml.isEmpty()) {
                return null;
            }
            Document document = parseHardened(xml);
            if (document == null) {
                return null;
            }
            Element root = document.getDocumentElement();
            if (root == null) {
                return null;
            }
            String id = root.getAttribute("ID");
            if (id != null && SAML_ID_PATTERN.matcher(id).matches()) {
                return id;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse the (untrusted) SAMLRequest XML with an XXE-hardened {@link DocumentBuilderFactory},
     * configured exactly like {@code org.mockserver.xml.StringToXmlDocumentParser}. Returns
     * {@code null} (rather than throwing) on any malformed input so the caller simply omits
     * {@code InResponseTo}.
     */
    private static Document parseHardened(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Decode an (optionally raw-DEFLATE compressed) SAMLRequest payload to a string. Returns
     * {@code null} when the decoded payload exceeds {@link #MAX_DECODED_SAML_REQUEST_BYTES}, and
     * aborts inflation once {@link #MAX_INFLATED_SAML_REQUEST_BYTES} would be exceeded (decompression
     * bomb guard). The {@link java.util.zip.Inflater} is always released via try-finally.
     */
    private static String inflateIfNeeded(byte[] decoded) {
        if (decoded == null) {
            return null;
        }
        if (decoded.length > MAX_DECODED_SAML_REQUEST_BYTES) {
            // oversized input — refuse to process
            return null;
        }
        // Try raw DEFLATE (HTTP-Redirect binding) first, then treat as plain UTF-8 (POST binding).
        java.util.zip.Inflater inflater = new java.util.zip.Inflater(true);
        try {
            inflater.setInput(decoded);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.min(decoded.length * 4, 8192));
            byte[] buffer = new byte[8192];
            int total = 0;
            while (!inflater.finished()) {
                int len = inflater.inflate(buffer);
                if (len == 0) {
                    // needs more input we do not have, or is not deflated — give up on inflation
                    break;
                }
                total += len;
                if (total > MAX_INFLATED_SAML_REQUEST_BYTES) {
                    // decompression bomb — abort and fall through to plain decode
                    out.reset();
                    total = 0;
                    break;
                }
                out.write(buffer, 0, len);
            }
            if (total > 0) {
                return new String(out.toByteArray(), 0, total, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // not deflated — fall through
        } finally {
            inflater.end();
        }
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private static String urlDecode(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }

    private static String escapeAttr(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }
}
