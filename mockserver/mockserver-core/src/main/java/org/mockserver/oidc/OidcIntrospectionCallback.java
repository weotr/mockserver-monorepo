package org.mockserver.oidc;

import com.fasterxml.jackson.databind.ObjectWriter;
import org.mockserver.mock.action.ExpectationResponseCallback;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockserver.model.HttpResponse.response;

/**
 * Mock OIDC/OAuth2 introspection endpoint (RFC 7662).
 *
 * <p>When the provider issues opaque access tokens
 * ({@link OidcProviderConfiguration#isOpaqueAccessToken()}), the only way to validate them is
 * introspection. This callback resolves the presented {@code token} form parameter against the
 * opaque tokens recorded by {@link OidcTokenMinter}:
 * <ul>
 *   <li>known, unexpired opaque token → {@code {"active":true, ...claims}};</li>
 *   <li>unknown or expired opaque token → {@code {"active":false}};</li>
 *   <li>no opaque token presented (or no opaque tokens issued) → the provider's static introspection
 *       result, with {@code active} driven by {@code issueExpiredToken} (preserving Wave-1 behaviour).</li>
 * </ul>
 */
public class OidcIntrospectionCallback implements ExpectationResponseCallback {

    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    // ObjectWriter is immutable and thread-safe once configured, and the configuration here is
    // stateless (pretty=true, serialiseDefaultValues=false), so a single shared instance is reused
    // across all requests rather than re-created per callback construction.
    private static final ObjectWriter OBJECT_WRITER = ObjectMapperFactory.createObjectMapper(true, false);

    @Override
    public HttpResponse handle(HttpRequest request) {
        OidcAuthorizationStore store = OidcAuthorizationStore.getInstance();
        OidcAuthorizationStore.Provider provider = store.providerForIntrospectPath(request.getPath().getValue());

        Map<String, String> form = parseFormBody(request.getBodyAsString());
        String token = emptyToNull(form.get("token"));

        // Opaque-token resolution path: if a token was presented and it matches a stored opaque token,
        // report it active with its claims; if it was presented but unknown/expired, report inactive.
        if (token != null) {
            OidcAuthorizationStore.OpaqueToken opaque = store.lookupOpaqueToken(token);
            if (opaque != null && !opaque.isExpired(System.currentTimeMillis())) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("active", true);
                body.putAll(opaque.claims);
                return json(body);
            }
            if (provider != null && provider.config.isOpaqueAccessToken()) {
                // The provider issues opaque tokens but this one is unknown/expired → inactive.
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("active", false);
                return json(body);
            }
        }

        // Fallback: static introspection result (Wave-1 behaviour).
        return json(staticIntrospection(provider));
    }

    private Map<String, Object> staticIntrospection(OidcAuthorizationStore.Provider provider) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (provider == null) {
            body.put("active", false);
            return body;
        }
        OidcProviderConfiguration config = provider.config;
        body.put("active", !config.isIssueExpiredToken());
        body.put("sub", config.getSubject());
        body.put("iss", config.getIssuer());
        body.put("aud", config.getAudience());
        body.put("scope", String.join(" ", config.getScopes()));
        if (config.getAdditionalClaims() != null) {
            body.putAll(config.getAdditionalClaims());
        }
        return body;
    }

    private HttpResponse json(Object body) {
        return response()
            .withStatusCode(200)
            .withHeader("content-type", JSON_CONTENT_TYPE)
            .withBody(serializeToJson(body));
    }

    private String serializeToJson(Object value) {
        try {
            return OBJECT_WRITER.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize OIDC introspection response to JSON", e);
        }
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }

    private static Map<String, String> parseFormBody(String body) {
        Map<String, String> form = new HashMap<>();
        if (body == null || body.isEmpty()) {
            return form;
        }
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                form.put(urlDecode(pair), "");
            } else {
                form.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
            }
        }
        return form;
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
