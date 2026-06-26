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
        // given - showPetById declares a required X-Request-ID header; supply it so the request is
        // valid and this test asserts template-path matching rather than parameter validation
        HttpRequest request = request("/pets/123")
            .withMethod("GET")
            .withHeader("X-Request-ID", "3fa85f64-5717-4562-b3fc-2c963f66afa6");
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

    private static final String CONCRETE_BEFORE_TEMPLATED = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_concrete_before_templated.yaml");

    @Test
    public void shouldPreferConcretePathOverTemplatedPath() {
        // given - spec declares "/users/{id}" BEFORE concrete "/users/me"; the concrete path must win.
        // A body valid only for the concrete "/users/me" schema proves which operation was selected.
        HttpRequest request = request("/users/me").withMethod("GET");
        HttpResponse response = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{\"me\": \"value\"}");

        // when
        List<OpenApiTrafficValidator.TrafficValidationResult> results = validator.validate(
            CONCRETE_BEFORE_TEMPLATED,
            Collections.singletonList(Pair.of(request, response))
        );

        // then - the concrete /users/me operation is matched and the body validates cleanly
        assertThat(results, hasSize(1));
        assertThat(results.get(0).getMatchedOperation(), containsString("/users/me"));
        assertThat(results.get(0).isPassed(), is(true));
    }

    @Test
    public void shouldContinueBatchWhenOnePairThrows() {
        // given - a first pair with a null request triggers an unexpected throwable inside validatePair;
        // the batch must record a failed result for it and continue validating the remaining pairs
        HttpRequest validRequest = request("/pets").withMethod("GET");
        HttpResponse validResponse = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("[{\"id\": 1, \"name\": \"Fido\"}]");

        // when
        List<OpenApiTrafficValidator.TrafficValidationResult> results = validator.validate(
            SPEC,
            Arrays.asList(Pair.of(null, validResponse), Pair.of(validRequest, validResponse))
        );

        // then - the throwing pair is recorded as failed, and the valid pair is still validated
        assertThat(results, hasSize(2));
        assertThat(results.get(0).isPassed(), is(false));
        assertThat(results.get(0).getRequestErrors(), is(not(empty())));
        // the error names what was being validated and the exception type, never a bare "null"
        String error = results.get(0).getRequestErrors().get(0);
        assertThat(error, containsString("OpenAPI traffic validation"));
        assertThat(error, containsString("NullPointerException"));
        // must never degrade to a bare "...: null" (the empty-message case)
        assertThat(error, not(endsWith(": null")));
        assertThat(results.get(1).isPassed(), is(true));
    }

    private static final String WEBHOOKS_ONLY = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_31_webhooks_only.yaml");

    @Test
    public void shouldNotNpeForWebhooksOnlySpec() {
        // given - a valid OAS 3.1 spec with webhooks and NO paths; getPaths() returns null
        HttpRequest request = request("/pets").withMethod("GET");
        HttpResponse response = response().withStatusCode(200);

        // when
        List<OpenApiTrafficValidator.TrafficValidationResult> results = validator.validate(
            WEBHOOKS_ONLY,
            Collections.singletonList(Pair.of(request, response))
        );

        // then - no NPE; just an ordinary unmatched-operation result
        assertThat(results, hasSize(1));
        assertThat(results.get(0).getMatchedOperation(), is(nullValue()));
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
