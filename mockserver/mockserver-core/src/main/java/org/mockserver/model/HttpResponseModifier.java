package org.mockserver.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonpatch.JsonPatch;
import com.github.fge.jsonpatch.mergepatch.JsonMergePatch;
import org.mockserver.serialization.ObjectMapperFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;

public class HttpResponseModifier extends ObjectWithJsonToString {

    private static final Logger LOG = LoggerFactory.getLogger(HttpResponseModifier.class);
    private static ObjectMapper objectMapper;

    private int hashCode;
    private HeadersModifier headers;
    private CookiesModifier cookies;
    private HttpResponseModifierCondition condition;
    private List<HttpResponseModifier> modifiers;
    private JsonNode jsonPatch;
    private JsonNode jsonMergePatch;

    public static HttpResponseModifier responseModifier() {
        return new HttpResponseModifier();
    }

    public HeadersModifier getHeaders() {
        return headers;
    }

    public HttpResponseModifier withHeaders(HeadersModifier headers) {
        this.headers = headers;
        this.hashCode = 0;
        return this;
    }

    public HttpResponseModifier withHeaders(List<Header> add, List<Header> replace, List<String> remove) {
        this.headers = new HeadersModifier()
            .withAdd(new Headers(add))
            .withReplace(new Headers(replace))
            .withRemove(remove);
        this.hashCode = 0;
        return this;
    }

    public CookiesModifier getCookies() {
        return cookies;
    }

    public HttpResponseModifier withCookies(CookiesModifier cookies) {
        this.cookies = cookies;
        this.hashCode = 0;
        return this;
    }

    public HttpResponseModifier withCookies(List<Cookie> add, List<Cookie> replace, List<String> remove) {
        this.cookies = new CookiesModifier()
            .withAdd(new Cookies(add))
            .withReplace(new Cookies(replace))
            .withRemove(remove);
        this.hashCode = 0;
        return this;
    }

    public HttpResponseModifierCondition getCondition() {
        return condition;
    }

    /**
     * Restrict this modifier so it only applies when the supplied condition holds against the
     * in-flight response (and, where available, the original request). When {@code null} the
     * modifier always applies — the historical behaviour.
     */
    public HttpResponseModifier withCondition(HttpResponseModifierCondition condition) {
        this.condition = condition;
        this.hashCode = 0;
        return this;
    }

    public List<HttpResponseModifier> getModifiers() {
        return modifiers;
    }

    /**
     * Configure an ordered chain of modifiers. Each is applied in order to the same response, so a
     * later modifier observes the output of the earlier ones. When a chain is present the
     * {@code headers}/{@code cookies} of this (wrapping) modifier are ignored — the chain is the
     * unit of work; the wrapping modifier's {@code condition} still gates the whole chain.
     */
    public HttpResponseModifier withModifiers(List<HttpResponseModifier> modifiers) {
        this.modifiers = modifiers;
        this.hashCode = 0;
        return this;
    }

    public JsonNode getJsonPatch() {
        return jsonPatch;
    }

    /**
     * Apply an RFC 6902 JSON Patch document (an array of {@code add}/{@code remove}/{@code replace}/
     * {@code move}/{@code copy}/{@code test} operations) to the forwarded response body when that body
     * is valid JSON. When {@code null} the body is left untouched. If the body is not valid JSON, or the
     * patch cannot be applied (e.g. a {@code test} operation fails), the body is left unchanged.
     */
    public HttpResponseModifier withJsonPatch(JsonNode jsonPatch) {
        this.jsonPatch = jsonPatch;
        this.hashCode = 0;
        return this;
    }

    /**
     * Convenience overload accepting the RFC 6902 JSON Patch document as a JSON string.
     *
     * @throws IllegalArgumentException if the string is not valid JSON
     */
    public HttpResponseModifier withJsonPatch(String jsonPatch) {
        return withJsonPatch(parseJson(jsonPatch));
    }

    public JsonNode getJsonMergePatch() {
        return jsonMergePatch;
    }

    /**
     * Apply an RFC 7386 JSON Merge Patch document to the forwarded response body when that body is valid
     * JSON. Members present in the patch overwrite (or, when {@code null}, delete) the corresponding
     * members of the body; everything else is left as-is. When {@code null} the body is left untouched.
     * If the body is not valid JSON, or the merge cannot be applied, the body is left unchanged.
     */
    public HttpResponseModifier withJsonMergePatch(JsonNode jsonMergePatch) {
        this.jsonMergePatch = jsonMergePatch;
        this.hashCode = 0;
        return this;
    }

    /**
     * Convenience overload accepting the RFC 7386 JSON Merge Patch document as a JSON string.
     *
     * @throws IllegalArgumentException if the string is not valid JSON
     */
    public HttpResponseModifier withJsonMergePatch(String jsonMergePatch) {
        return withJsonMergePatch(parseJson(jsonMergePatch));
    }

    private static JsonNode parseJson(String json) {
        try {
            return objectMapper().readTree(json);
        } catch (Throwable throwable) {
            throw new IllegalArgumentException("Unable to parse JSON [" + json + "]", throwable);
        }
    }

    /**
     * Apply this modifier to {@code response}, honouring any condition and chain.
     *
     * <p>Evaluation order:
     * <ol>
     *   <li>if a {@link #getCondition() condition} is configured and does not match, do nothing;</li>
     *   <li>if a {@link #getModifiers() chain} is configured, apply each child in order (each sees the
     *       prior child's output);</li>
     *   <li>otherwise apply this modifier's own header/cookie edits and then its body patch.</li>
     * </ol>
     *
     * @param response the in-flight response to mutate in place
     * @param request  the original request (may be {@code null} when not available)
     */
    public void applyTo(HttpResponse response, HttpRequest request) {
        if (response == null) {
            return;
        }
        if (condition != null && !condition.matches(response, request)) {
            return;
        }
        if (modifiers != null && !modifiers.isEmpty()) {
            for (HttpResponseModifier modifier : modifiers) {
                if (modifier != null) {
                    modifier.applyTo(response, request);
                }
            }
            return;
        }
        if (headers != null) {
            response.withHeaders(headers.update(response.getHeaders()));
        }
        if (cookies != null) {
            response.withCookies(cookies.update(response.getCookies()));
        }
        applyBodyPatch(response);
    }

    /**
     * Apply the configured {@link #getJsonPatch() JSON Patch} and/or {@link #getJsonMergePatch() JSON
     * Merge Patch} to the response body. The patch is only applied when the body parses as JSON; any
     * failure (non-JSON body, malformed patch, failed {@code test} operation, etc.) leaves the body
     * unchanged so a forward never errors because of a patch. JSON Patch is applied before JSON Merge
     * Patch when both are present.
     */
    private void applyBodyPatch(HttpResponse response) {
        if (jsonPatch == null && jsonMergePatch == null) {
            return;
        }
        String bodyString = response.getBodyAsString();
        if (bodyString == null || bodyString.isEmpty()) {
            return;
        }
        ObjectMapper mapper = objectMapper();
        JsonNode document;
        try {
            document = mapper.readTree(bodyString);
        } catch (Throwable throwable) {
            LOG.debug("response body is not valid JSON so JSON patch is not applied", throwable);
            return;
        }
        JsonNode patched = document;
        try {
            if (jsonPatch != null) {
                patched = JsonPatch.fromJson(jsonPatch).apply(patched);
            }
            if (jsonMergePatch != null) {
                patched = JsonMergePatch.fromJson(jsonMergePatch).apply(patched);
            }
        } catch (Throwable throwable) {
            // a failed apply is an expected control path (e.g. an RFC 6902 "test" op that does not
            // hold, or a path that does not exist) so log at debug and leave the body unchanged
            LOG.debug("failed to apply JSON patch to response body so the body is left unchanged", throwable);
            return;
        }
        Charset charset = null;
        BodyWithContentType existingBody = response.getBody();
        if (existingBody != null && existingBody.getContentType() != null) {
            charset = MediaType.parse(existingBody.getContentType()).getCharsetOrDefault();
        }
        String patchedJson;
        try {
            patchedJson = mapper.writeValueAsString(patched);
        } catch (Throwable throwable) {
            LOG.warn("failed to serialise patched response body so the body is left unchanged", throwable);
            return;
        }
        response.withBody(new JsonBody(patchedJson, charset, JsonBody.DEFAULT_MATCH_TYPE));
    }

    private static ObjectMapper objectMapper() {
        if (objectMapper == null) {
            objectMapper = ObjectMapperFactory.createObjectMapper();
        }
        return objectMapper;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        HttpResponseModifier that = (HttpResponseModifier) o;
        return Objects.equals(headers, that.headers) &&
            Objects.equals(cookies, that.cookies) &&
            Objects.equals(condition, that.condition) &&
            Objects.equals(modifiers, that.modifiers) &&
            Objects.equals(jsonPatch, that.jsonPatch) &&
            Objects.equals(jsonMergePatch, that.jsonMergePatch);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(headers, cookies, condition, modifiers, jsonPatch, jsonMergePatch);
        }
        return hashCode;
    }

}
