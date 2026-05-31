package org.mockserver.llm;

import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class StubGenerationPromptBuilderTest {

    private final StubGenerationPromptBuilder builder = new StubGenerationPromptBuilder();

    @Test
    public void shouldIncludeMethodAndPath() {
        HttpRequest request = HttpRequest.request().withMethod("GET").withPath("/api/users");
        String prompt = builder.build(request, Collections.emptyList());
        assertThat(prompt, containsString("GET"));
        assertThat(prompt, containsString("/api/users"));
    }

    @Test
    public void shouldIncludeRequestBody() {
        HttpRequest request = HttpRequest.request().withMethod("POST").withPath("/api/users")
            .withBody("{\"name\":\"Alice\"}");
        String prompt = builder.build(request, Collections.emptyList());
        assertThat(prompt, containsString("Alice"));
    }

    @Test
    public void shouldTruncateLongBody() {
        String longBody = "x".repeat(3000);
        HttpRequest request = HttpRequest.request().withMethod("POST").withPath("/api/data")
            .withBody(longBody);
        String prompt = builder.build(request, Collections.emptyList());
        assertThat(prompt, containsString("truncated"));
        assertThat(prompt.length(), lessThan(10000));
    }

    @Test
    public void shouldIncludeContextExpectationsUpToLimit() {
        HttpRequest request = HttpRequest.request().withMethod("GET").withPath("/api/items");
        List<Expectation> context = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            context.add(new Expectation(
                HttpRequest.request().withMethod("GET").withPath("/api/item/" + i)
            ));
        }
        String prompt = builder.build(request, context);
        assertThat(prompt, containsString("EXISTING EXPECTATIONS"));
        assertThat(prompt, containsString("/api/item/0"));
        assertThat(prompt, containsString("/api/item/9"));
        // 10th (0-indexed) and beyond should be excluded by MAX_EXPECTATIONS_CONTEXT = 10
        assertThat(prompt, not(containsString("/api/item/10")));
        assertThat(prompt, not(containsString("/api/item/14")));
    }

    @Test
    public void shouldHandleEmptyContextExpectations() {
        HttpRequest request = HttpRequest.request().withMethod("DELETE").withPath("/api/users/42");
        String prompt = builder.build(request, Collections.emptyList());
        assertThat(prompt, not(containsString("EXISTING EXPECTATIONS")));
        assertThat(prompt, containsString("DELETE"));
        assertThat(prompt, containsString("/api/users/42"));
    }

    @Test
    public void shouldHandleNullContextExpectations() {
        HttpRequest request = HttpRequest.request().withMethod("PUT").withPath("/api/config");
        String prompt = builder.build(request, null);
        assertThat(prompt, not(containsString("EXISTING EXPECTATIONS")));
        assertThat(prompt, containsString("PUT"));
        assertThat(prompt, containsString("/api/config"));
    }

    @Test
    public void shouldHandleRequestWithNullMethodAndPath() {
        HttpRequest request = HttpRequest.request();
        String prompt = builder.build(request, Collections.emptyList());
        // defaults to GET and /
        assertThat(prompt, containsString("Method: GET"));
        assertThat(prompt, containsString("Path: /"));
    }

    @Test
    public void shouldIncludeQueryParameters() {
        HttpRequest request = HttpRequest.request().withMethod("GET").withPath("/api/search")
            .withQueryStringParameter("q", "test");
        String prompt = builder.build(request, Collections.emptyList());
        assertThat(prompt, containsString("Query Parameters"));
    }

    @Test
    public void shouldIncludeInstructionsForMockServerFormat() {
        HttpRequest request = HttpRequest.request().withMethod("GET").withPath("/api/data");
        String prompt = builder.build(request, Collections.emptyList());
        assertThat(prompt, containsString("httpRequest/httpResponse"));
        assertThat(prompt, containsString("MockServer"));
        assertThat(prompt, containsString("Return ONLY the JSON"));
    }
}
