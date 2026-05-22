package org.mockserver.openapi;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.Test;
import org.mockserver.file.FileReader;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class OpenApiTrafficValidatorTest {

    private static final String SPEC = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json");
    private final MockServerLogger mockServerLogger = new MockServerLogger(OpenApiTrafficValidatorTest.class);
    private final OpenApiTrafficValidator validator = new OpenApiTrafficValidator(mockServerLogger);

    @Test
    public void shouldPassForConformingGetRequest() {
        // given
        HttpRequest request = request("/pets").withMethod("GET");
        HttpResponse validResponse = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("[{\"id\": 1, \"name\": \"Fido\"}]");

        // when
        List<OpenApiTrafficValidator.TrafficValidationResult> results = validator.validate(
            SPEC,
            Collections.singletonList(Pair.of(request, validResponse))
        );

        // then
        assertThat(results, hasSize(1));
        assertThat(results.get(0).isPassed(), is(true));
        assertThat(results.get(0).getMatchedOperation(), is(notNullValue()));
        assertThat(results.get(0).getRequestErrors(), is(empty()));
        assertThat(results.get(0).getResponseErrors(), is(empty()));
    }

    @Test
    public void shouldPassForConformingPostRequest() {
        // given
        HttpRequest request = request("/pets")
            .withMethod("POST")
            .withHeader("content-type", "application/json")
            .withBody("{\"id\": 1, \"name\": \"Fido\"}");
        HttpResponse validResponse = response()
            .withStatusCode(201);

        // when
        List<OpenApiTrafficValidator.TrafficValidationResult> results = validator.validate(
            SPEC,
            Collections.singletonList(Pair.of(request, validResponse))
        );

        // then
        assertThat(results, hasSize(1));
        assertThat(results.get(0).isPassed(), is(true));
    }

    @Test
    public void shouldFailForNonConformingResponseBody() {
        // given
        HttpRequest request = request("/pets").withMethod("GET");
        HttpResponse invalidResponse = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{\"not\": \"an array\"}");

        // when
        List<OpenApiTrafficValidator.TrafficValidationResult> results = validator.validate(
            SPEC,
            Collections.singletonList(Pair.of(request, invalidResponse))
        );

        // then
        assertThat(results, hasSize(1));
        assertThat(results.get(0).isPassed(), is(false));
        assertThat(results.get(0).getResponseErrors(), is(not(empty())));
    }

    @Test
    public void shouldFailForNonConformingRequestBody() {
        // given - createPets requires a body with id (integer) and name (string)
        HttpRequest request = request("/pets")
            .withMethod("POST")
            .withHeader("content-type", "application/json")
            .withBody("{\"id\": \"not_a_number\", \"name\": \"Fido\"}");
        HttpResponse validResponse = response()
            .withStatusCode(201);

        // when
        List<OpenApiTrafficValidator.TrafficValidationResult> results = validator.validate(
            SPEC,
            Collections.singletonList(Pair.of(request, validResponse))
        );

        // then
        assertThat(results, hasSize(1));
        assertThat(results.get(0).isPassed(), is(false));
        assertThat(results.get(0).getRequestErrors(), is(not(empty())));
    }

    @Test
    public void shouldReportUnmatchedOperation() {
        // given
        HttpRequest request = request("/unknown/path").withMethod("DELETE");
        HttpResponse anyResponse = response().withStatusCode(200);

        // when
        List<OpenApiTrafficValidator.TrafficValidationResult> results = validator.validate(
            SPEC,
            Collections.singletonList(Pair.of(request, anyResponse))
        );

        // then
        assertThat(results, hasSize(1));
        assertThat(results.get(0).isPassed(), is(false));
        assertThat(results.get(0).getMatchedOperation(), is(nullValue()));
        assertThat(results.get(0).getRequestErrors(), hasSize(1));
        assertThat(results.get(0).getRequestErrors().get(0), containsString("no matching operation"));
    }

    @Test
    public void shouldValidateMultiplePairs() {
        // given
        HttpRequest validRequest = request("/pets").withMethod("GET");
        HttpResponse validResponse = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("[{\"id\": 1, \"name\": \"Fido\"}]");

        HttpRequest invalidRequest = request("/unknown").withMethod("GET");
        HttpResponse anyResponse = response().withStatusCode(404);

        // when
        List<OpenApiTrafficValidator.TrafficValidationResult> results = validator.validate(
            SPEC,
            Arrays.asList(Pair.of(validRequest, validResponse), Pair.of(invalidRequest, anyResponse))
        );

        // then
        assertThat(results, hasSize(2));
        assertThat(results.get(0).isPassed(), is(true));
        assertThat(results.get(1).isPassed(), is(false));
    }

    @Test
    public void shouldMatchPathWithTemplateParameters() {
        // given
        HttpRequest request = request("/pets/123").withMethod("GET");
        HttpResponse validResponse = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{\"id\": 123, \"name\": \"Rex\"}");

        // when
        List<OpenApiTrafficValidator.TrafficValidationResult> results = validator.validate(
            SPEC,
            Collections.singletonList(Pair.of(request, validResponse))
        );

        // then
        assertThat(results, hasSize(1));
        assertThat(results.get(0).isPassed(), is(true));
        assertThat(results.get(0).getMatchedOperation(), containsString("/pets/{petId}"));
    }

    @Test
    public void shouldHandleEmptyPairsList() {
        // when
        List<OpenApiTrafficValidator.TrafficValidationResult> results = validator.validate(
            SPEC,
            Collections.emptyList()
        );

        // then
        assertThat(results, is(empty()));
    }
}
