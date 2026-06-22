package org.mockserver.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpResponseModifier.responseModifier;
import static org.mockserver.model.HttpResponseModifierCondition.responseModifierCondition;

public class HttpResponseModifierTest {

    private static JsonNode json(String value) {
        try {
            return ObjectMapperFactory.createObjectMapper().readTree(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JsonNode tree(HttpResponse response) {
        return json(response.getBodyAsString());
    }

    // ---- backward-compatible single-modifier behaviour -------------------------------------

    @Test
    public void unconditionalModifierAddsHeader() {
        HttpResponse response = response().withStatusCode(200);
        responseModifier()
            .withHeaders(Collections.singletonList(header("X-Added", "value")), null, null)
            .applyTo(response, null);

        assertThat(response.getFirstHeader("X-Added"), is("value"));
    }

    @Test
    public void unconditionalModifierMatchesLegacyUpdateBehaviour() {
        // legacy two-arg update() must behave identically to before
        HttpResponse response = response().withStatusCode(200).withHeader("X-Original", "keep");
        response.update(null, responseModifier()
            .withHeaders(Collections.singletonList(header("X-Added", "value")), null, null));

        assertThat(response.getFirstHeader("X-Added"), is("value"));
        assertThat(response.getFirstHeader("X-Original"), is("keep"));
    }

    // ---- conditional application -----------------------------------------------------------

    @Test
    public void conditionTrueAppliesModifier() {
        HttpResponse response = response().withStatusCode(503);
        responseModifier()
            .withCondition(responseModifierCondition().withStatusCodeRange("5xx"))
            .withHeaders(Collections.singletonList(header("X-Server-Error", "true")), null, null)
            .applyTo(response, null);

        assertThat(response.getFirstHeader("X-Server-Error"), is("true"));
    }

    @Test
    public void conditionFalseSkipsModifier() {
        HttpResponse response = response().withStatusCode(200);
        responseModifier()
            .withCondition(responseModifierCondition().withStatusCodeRange("5xx"))
            .withHeaders(Collections.singletonList(header("X-Server-Error", "true")), null, null)
            .applyTo(response, null);

        assertThat(response.containsHeader("X-Server-Error"), is(false));
    }

    @Test
    public void conditionOnExactStatusCode() {
        HttpResponse hit = response().withStatusCode(404);
        HttpResponse miss = response().withStatusCode(200);
        HttpResponseModifier modifier = responseModifier()
            .withCondition(responseModifierCondition().withStatusCode(404))
            .withHeaders(Collections.singletonList(header("X-Not-Found", "yes")), null, null);

        modifier.applyTo(hit, null);
        modifier.applyTo(miss, null);

        assertThat(hit.getFirstHeader("X-Not-Found"), is("yes"));
        assertThat(miss.containsHeader("X-Not-Found"), is(false));
    }

    @Test
    public void conditionOnResponseHeaderPresence() {
        HttpResponse hit = response().withStatusCode(200).withHeader("Content-Type", "application/json");
        HttpResponse miss = response().withStatusCode(200);
        HttpResponseModifier modifier = responseModifier()
            .withCondition(responseModifierCondition().withResponseHasHeader("Content-Type"))
            .withHeaders(Collections.singletonList(header("X-Json", "true")), null, null);

        modifier.applyTo(hit, null);
        modifier.applyTo(miss, null);

        assertThat(hit.getFirstHeader("X-Json"), is("true"));
        assertThat(miss.containsHeader("X-Json"), is(false));
    }

    @Test
    public void conditionOnRequestHeaderPresence() {
        HttpResponse response = response().withStatusCode(200);
        HttpRequest request = HttpRequest.request().withHeader("X-Debug", "1");
        responseModifier()
            .withCondition(responseModifierCondition().withRequestHasHeader("X-Debug"))
            .withHeaders(Collections.singletonList(header("X-Debug-Echo", "1")), null, null)
            .applyTo(response, request);

        assertThat(response.getFirstHeader("X-Debug-Echo"), is("1"));
    }

    @Test
    public void requestConditionSkippedWhenNoRequestAvailable() {
        HttpResponse response = response().withStatusCode(200);
        responseModifier()
            .withCondition(responseModifierCondition().withRequestHasHeader("X-Debug"))
            .withHeaders(Collections.singletonList(header("X-Debug-Echo", "1")), null, null)
            .applyTo(response, null);

        assertThat(response.containsHeader("X-Debug-Echo"), is(false));
    }

    @Test
    public void multipleCriteriaAreLogicalAnd() {
        HttpResponse response = response().withStatusCode(503).withHeader("Content-Type", "text/plain");
        // status matches but header criterion does not -> no apply
        responseModifier()
            .withCondition(responseModifierCondition().withStatusCodeRange("5xx").withResponseHasHeader("X-Missing"))
            .withHeaders(Collections.singletonList(header("X-Applied", "yes")), null, null)
            .applyTo(response, null);

        assertThat(response.containsHeader("X-Applied"), is(false));
    }

    // ---- ordered chain ---------------------------------------------------------------------

    @Test
    public void chainAppliesModifiersInOrderAndLaterSeesEarlierOutput() {
        HttpResponse response = response().withStatusCode(200);

        HttpResponseModifier modifierA = responseModifier()
            .withHeaders(Collections.singletonList(header("X-Stage", "a")), null, null);
        // modifier B replaces X-Stage, which only exists because A added it
        HttpResponseModifier modifierB = responseModifier()
            .withHeaders(null, Collections.singletonList(header("X-Stage", "b")), null);

        responseModifier()
            .withModifiers(Arrays.asList(modifierA, modifierB))
            .applyTo(response, null);

        // B saw A's output and replaced it
        assertThat(response.getFirstHeader("X-Stage"), is("b"));
    }

    @Test
    public void chainRespectsPerModifierConditions() {
        HttpResponse response = response().withStatusCode(204);

        HttpResponseModifier always = responseModifier()
            .withHeaders(Collections.singletonList(header("X-Always", "1")), null, null);
        HttpResponseModifier onlyOn5xx = responseModifier()
            .withCondition(responseModifierCondition().withStatusCodeRange("5xx"))
            .withHeaders(Collections.singletonList(header("X-Only5xx", "1")), null, null);

        responseModifier()
            .withModifiers(Arrays.asList(always, onlyOn5xx))
            .applyTo(response, null);

        assertThat(response.getFirstHeader("X-Always"), is("1"));
        assertThat(response.containsHeader("X-Only5xx"), is(false));
    }

    @Test
    public void wrappingConditionGatesWholeChain() {
        HttpResponse response = response().withStatusCode(200);
        HttpResponseModifier child = responseModifier()
            .withHeaders(Collections.singletonList(header("X-Child", "1")), null, null);

        responseModifier()
            .withCondition(responseModifierCondition().withStatusCodeRange("5xx"))
            .withModifiers(Collections.singletonList(child))
            .applyTo(response, null);

        assertThat(response.containsHeader("X-Child"), is(false));
    }

    @Test
    public void nullResponseIsNoOp() {
        // must not throw
        responseModifier()
            .withHeaders(Collections.singletonList(header("X", "y")), null, null)
            .applyTo(null, null);
    }

    // ---- RFC 6902 JSON Patch ---------------------------------------------------------------

    @Test
    public void jsonPatchReplacesOneField() {
        HttpResponse response = response().withStatusCode(200).withBody(JsonBody.json("{\"name\":\"old\",\"keep\":true}"));
        responseModifier()
            .withJsonPatch(json("[{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"new\"}]"))
            .applyTo(response, null);

        JsonNode patched = tree(response);
        assertThat(patched.get("name").asText(), is("new"));
        assertThat(patched.get("keep").asBoolean(), is(true));
    }

    @Test
    public void jsonPatchAddsField() {
        HttpResponse response = response().withStatusCode(200).withBody(JsonBody.json("{\"a\":1}"));
        responseModifier()
            .withJsonPatch(json("[{\"op\":\"add\",\"path\":\"/b\",\"value\":2}]"))
            .applyTo(response, null);

        JsonNode patched = tree(response);
        assertThat(patched.get("a").asInt(), is(1));
        assertThat(patched.get("b").asInt(), is(2));
    }

    @Test
    public void jsonPatchRemovesField() {
        HttpResponse response = response().withStatusCode(200).withBody(JsonBody.json("{\"a\":1,\"secret\":\"hide\"}"));
        responseModifier()
            .withJsonPatch(json("[{\"op\":\"remove\",\"path\":\"/secret\"}]"))
            .applyTo(response, null);

        JsonNode patched = tree(response);
        assertThat(patched.has("secret"), is(false));
        assertThat(patched.get("a").asInt(), is(1));
    }

    @Test
    public void jsonPatchMovesField() {
        HttpResponse response = response().withStatusCode(200).withBody(JsonBody.json("{\"from\":\"v\"}"));
        responseModifier()
            .withJsonPatch(json("[{\"op\":\"move\",\"from\":\"/from\",\"path\":\"/to\"}]"))
            .applyTo(response, null);

        JsonNode patched = tree(response);
        assertThat(patched.has("from"), is(false));
        assertThat(patched.get("to").asText(), is("v"));
    }

    @Test
    public void jsonPatchFailingTestOperationLeavesBodyUnchanged() {
        HttpResponse response = response().withStatusCode(200).withBody(JsonBody.json("{\"a\":1}"));
        responseModifier()
            .withJsonPatch(json("[{\"op\":\"test\",\"path\":\"/a\",\"value\":999},{\"op\":\"add\",\"path\":\"/b\",\"value\":2}]"))
            .applyTo(response, null);

        // the test op fails so the whole patch is rejected and the body is untouched
        JsonNode patched = tree(response);
        assertThat(patched.get("a").asInt(), is(1));
        assertThat(patched.has("b"), is(false));
    }

    // ---- RFC 7386 JSON Merge Patch ---------------------------------------------------------

    @Test
    public void jsonMergePatchSetsAndAddsFields() {
        HttpResponse response = response().withStatusCode(200).withBody(JsonBody.json("{\"name\":\"old\",\"nested\":{\"a\":1}}"));
        responseModifier()
            .withJsonMergePatch(json("{\"name\":\"new\",\"nested\":{\"b\":2},\"extra\":true}"))
            .applyTo(response, null);

        JsonNode patched = tree(response);
        assertThat(patched.get("name").asText(), is("new"));
        // merge patch recurses into nested objects: a is kept, b is added
        assertThat(patched.get("nested").get("a").asInt(), is(1));
        assertThat(patched.get("nested").get("b").asInt(), is(2));
        assertThat(patched.get("extra").asBoolean(), is(true));
    }

    @Test
    public void jsonMergePatchNullDeletesField() {
        HttpResponse response = response().withStatusCode(200).withBody(JsonBody.json("{\"a\":1,\"secret\":\"hide\"}"));
        responseModifier()
            .withJsonMergePatch(json("{\"secret\":null}"))
            .applyTo(response, null);

        JsonNode patched = tree(response);
        assertThat(patched.has("secret"), is(false));
        assertThat(patched.get("a").asInt(), is(1));
    }

    // ---- combined / negative / charset behaviour -------------------------------------------

    @Test
    public void jsonPatchAppliedBeforeMergePatchWhenBothPresent() {
        HttpResponse response = response().withStatusCode(200).withBody(JsonBody.json("{\"a\":1}"));
        responseModifier()
            .withJsonPatch(json("[{\"op\":\"add\",\"path\":\"/b\",\"value\":2}]"))
            .withJsonMergePatch(json("{\"a\":10,\"b\":null,\"c\":3}"))
            .applyTo(response, null);

        JsonNode patched = tree(response);
        assertThat(patched.get("a").asInt(), is(10));
        // b was added by patch then deleted by merge patch
        assertThat(patched.has("b"), is(false));
        assertThat(patched.get("c").asInt(), is(3));
    }

    @Test
    public void nonJsonBodyLeftUntouchedByPatch() {
        HttpResponse response = response().withStatusCode(200).withBody("this is not json");
        responseModifier()
            .withJsonPatch(json("[{\"op\":\"add\",\"path\":\"/b\",\"value\":2}]"))
            .applyTo(response, null);

        assertThat(response.getBodyAsString(), is("this is not json"));
    }

    @Test
    public void absentPatchLeavesBodyByteForByteUnchanged() {
        HttpResponse response = response().withStatusCode(200).withBody(JsonBody.json("{\"a\":1}"));
        BodyWithContentType originalBody = response.getBody();
        responseModifier()
            .withHeaders(Collections.singletonList(header("X-Added", "value")), null, null)
            .applyTo(response, null);

        assertThat(response.getBody(), is(originalBody));
        assertThat(response.getFirstHeader("X-Added"), is("value"));
    }

    @Test
    public void emptyBodyLeftUntouchedByPatch() {
        HttpResponse response = response().withStatusCode(204);
        responseModifier()
            .withJsonMergePatch(json("{\"a\":1}"))
            .applyTo(response, null);

        assertThat(response.getBodyAsString(), nullValue());
    }

    @Test
    public void patchPreservesContentTypeCharset() {
        HttpResponse response = response().withStatusCode(200)
            .withBody(new JsonBody("{\"name\":\"old\"}", null, MediaType.parse("application/json; charset=utf-16"), JsonBody.DEFAULT_MATCH_TYPE));
        responseModifier()
            .withJsonMergePatch(json("{\"name\":\"new\"}"))
            .applyTo(response, null);

        BodyWithContentType body = response.getBody();
        assertThat(body.getContentType(), containsString("utf-16"));
        // raw bytes are encoded with the preserved charset, not the default
        byte[] utf16 = "{\"name\":\"new\"}".getBytes(StandardCharsets.UTF_16);
        assertThat(body.getRawBytes().length, is(utf16.length));
        assertThat(tree(response).get("name").asText(), is("new"));
    }

    @Test
    public void patchAppliedWithinChain() {
        HttpResponse response = response().withStatusCode(200).withBody(JsonBody.json("{\"a\":1}"));
        HttpResponseModifier patchChild = responseModifier()
            .withJsonMergePatch(json("{\"a\":2}"));
        responseModifier()
            .withModifiers(Collections.singletonList(patchChild))
            .applyTo(response, null);

        assertThat(tree(response).get("a").asInt(), is(2));
        assertThat(response.getBodyAsString(), not(containsString("\"a\":1")));
    }
}
