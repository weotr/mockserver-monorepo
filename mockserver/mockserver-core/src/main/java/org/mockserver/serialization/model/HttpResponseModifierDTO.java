package org.mockserver.serialization.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.mockserver.model.HttpResponseModifier;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

import java.util.List;
import java.util.stream.Collectors;

public class HttpResponseModifierDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<HttpResponseModifier> {

    private HeadersModifierDTO headers;
    private CookiesModifierDTO cookies;
    private HttpResponseModifierConditionDTO condition;
    private List<HttpResponseModifierDTO> modifiers;
    private JsonNode jsonPatch;
    private JsonNode jsonMergePatch;

    public HttpResponseModifierDTO() {
    }

    public HttpResponseModifierDTO(HttpResponseModifier httpResponseModifier) {
        if (httpResponseModifier != null) {
            headers = httpResponseModifier.getHeaders() != null ? new HeadersModifierDTO(httpResponseModifier.getHeaders()) : null;
            cookies = httpResponseModifier.getCookies() != null ? new CookiesModifierDTO(httpResponseModifier.getCookies()) : null;
            condition = httpResponseModifier.getCondition() != null ? new HttpResponseModifierConditionDTO(httpResponseModifier.getCondition()) : null;
            modifiers = httpResponseModifier.getModifiers() != null
                ? httpResponseModifier.getModifiers().stream().map(HttpResponseModifierDTO::new).collect(Collectors.toList())
                : null;
            jsonPatch = httpResponseModifier.getJsonPatch();
            jsonMergePatch = httpResponseModifier.getJsonMergePatch();
        }
    }

    public HttpResponseModifier buildObject() {
        return new HttpResponseModifier()
            .withHeaders(headers != null ? headers.buildObject() : null)
            .withCookies(cookies != null ? cookies.buildObject() : null)
            .withCondition(condition != null ? condition.buildObject() : null)
            .withModifiers(modifiers != null ? modifiers.stream().map(HttpResponseModifierDTO::buildObject).collect(Collectors.toList()) : null)
            .withJsonPatch(jsonPatch)
            .withJsonMergePatch(jsonMergePatch);
    }

    public HeadersModifierDTO getHeaders() {
        return headers;
    }

    public HttpResponseModifierDTO setHeaders(HeadersModifierDTO headers) {
        this.headers = headers;
        return this;
    }

    public CookiesModifierDTO getCookies() {
        return cookies;
    }

    public HttpResponseModifierDTO setCookies(CookiesModifierDTO cookies) {
        this.cookies = cookies;
        return this;
    }

    public HttpResponseModifierConditionDTO getCondition() {
        return condition;
    }

    public HttpResponseModifierDTO setCondition(HttpResponseModifierConditionDTO condition) {
        this.condition = condition;
        return this;
    }

    public List<HttpResponseModifierDTO> getModifiers() {
        return modifiers;
    }

    public HttpResponseModifierDTO setModifiers(List<HttpResponseModifierDTO> modifiers) {
        this.modifiers = modifiers;
        return this;
    }

    public JsonNode getJsonPatch() {
        return jsonPatch;
    }

    public HttpResponseModifierDTO setJsonPatch(JsonNode jsonPatch) {
        this.jsonPatch = jsonPatch;
        return this;
    }

    public JsonNode getJsonMergePatch() {
        return jsonMergePatch;
    }

    public HttpResponseModifierDTO setJsonMergePatch(JsonNode jsonMergePatch) {
        this.jsonMergePatch = jsonMergePatch;
        return this;
    }
}
