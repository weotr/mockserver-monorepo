package org.mockserver.serialization.java;

import org.junit.Test;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpResponseModifier.responseModifier;
import static org.mockserver.model.HttpResponseModifierCondition.responseModifierCondition;

public class HttpResponseModifierToJavaSerializerTest {

    @Test
    public void serializesLegacyHeadersModifier() {
        String java = new HttpResponseModifierToJavaSerializer().serialize(0, responseModifier()
            .withHeaders(Collections.singletonList(header("X-Added", "value")), null, null));

        assertThat(java, containsString("responseModifier()"));
        assertThat(java, containsString(".withHeaders("));
        assertThat(java, containsString("X-Added"));
    }

    @Test
    public void serializesConditionFields() {
        String java = new HttpResponseModifierToJavaSerializer().serialize(0, responseModifier()
            .withCondition(responseModifierCondition()
                .withStatusCode(503)
                .withStatusCodeRange("5xx")
                .withResponseHasHeader("Content-Type")
                .withRequestHasHeader("X-Debug"))
            .withHeaders(Collections.singletonList(header("X-Server-Error", "true")), null, null));

        assertThat(java, containsString(".withCondition("));
        assertThat(java, containsString("responseModifierCondition()"));
        assertThat(java, containsString(".withStatusCode(503)"));
        assertThat(java, containsString(".withStatusCodeRange(\"5xx\")"));
        assertThat(java, containsString(".withResponseHasHeader(\"Content-Type\")"));
        assertThat(java, containsString(".withRequestHasHeader(\"X-Debug\")"));
    }

    @Test
    public void serializesChainOfModifiers() {
        String java = new HttpResponseModifierToJavaSerializer().serialize(0, responseModifier()
            .withModifiers(Collections.singletonList(responseModifier()
                .withHeaders(Collections.singletonList(header("X-Child", "1")), null, null))));

        assertThat(java, containsString(".withModifiers(Arrays.asList("));
        assertThat(java, containsString("X-Child"));
    }

    @Test
    public void serializesJsonPatchAndMergePatch() {
        String java = new HttpResponseModifierToJavaSerializer().serialize(0, responseModifier()
            .withJsonPatch("[ { \"op\": \"replace\", \"path\": \"/name\", \"value\": \"masked\" } ]")
            .withJsonMergePatch("{ \"active\": false }"));

        // emitted via the String overloads so the generated DSL compiles and round-trips
        assertThat(java, containsString(".withJsonPatch(\""));
        assertThat(java, containsString("\\\"op\\\":\\\"replace\\\""));
        assertThat(java, containsString(".withJsonMergePatch(\""));
        assertThat(java, containsString("\\\"active\\\":false"));
    }
}
