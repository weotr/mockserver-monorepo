package org.mockserver.llm;

import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class StubGenerationResultTest {

    @Test
    public void shouldSetAndGetSuggestions() {
        Expectation expectation = new Expectation(
            HttpRequest.request().withMethod("GET").withPath("/api/test")
        ).thenRespond(HttpResponse.response().withStatusCode(200));
        List<Expectation> suggestions = Collections.singletonList(expectation);

        StubGenerationResult result = new StubGenerationResult()
            .setSuggestions(suggestions)
            .setConfidence(0.75)
            .setExplanation("test explanation")
            .setRawLlmResponse("{\"test\":true}");

        assertThat(result.getSuggestions(), hasSize(1));
        assertThat(result.getConfidence(), is(0.75));
        assertThat(result.getExplanation(), is("test explanation"));
        assertThat(result.getRawLlmResponse(), is("{\"test\":true}"));
    }

    @Test
    public void shouldSupportFluentSetters() {
        StubGenerationResult result = new StubGenerationResult()
            .setConfidence(0.5)
            .setExplanation("generated from template");

        assertThat(result.getConfidence(), is(0.5));
        assertThat(result.getExplanation(), is("generated from template"));
        assertThat(result.getSuggestions(), is(nullValue()));
        assertThat(result.getRawLlmResponse(), is(nullValue()));
    }
}
