package org.mockserver.openapi;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;

public class OpenAPIRequestValidatorTest {

    private static final String SPEC = "org/mockserver/openapi/openapi_petstore_example.json";
    private final MockServerLogger logger = new MockServerLogger();

    @Test
    public void shouldPassForValidGetRequest() {
        List<String> errors = OpenAPIRequestValidator.validate(SPEC,
            request("/pets").withMethod("GET"),
            logger
        );
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldFailForUnknownPathAndMethod() {
        List<String> errors = OpenAPIRequestValidator.validate(SPEC,
            request("/unknown/path").withMethod("DELETE"),
            logger
        );
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0), containsString("no operation found"));
    }

    @Test
    public void shouldFailForMissingRequiredBody() {
        List<String> errors = OpenAPIRequestValidator.validate(SPEC,
            request("/pets").withMethod("POST"),
            logger
        );
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0), containsString("request body is required but was empty"));
    }

    @Test
    public void shouldPassForValidPostBody() {
        List<String> errors = OpenAPIRequestValidator.validate(SPEC,
            request("/pets")
                .withMethod("POST")
                .withHeader("content-type", "application/json")
                .withBody("{\"id\": 1, \"name\": \"Fido\"}"),
            logger
        );
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldFailForInvalidPostBody() {
        List<String> errors = OpenAPIRequestValidator.validate(SPEC,
            request("/pets")
                .withMethod("POST")
                .withHeader("content-type", "application/json")
                .withBody("{\"id\": \"not_a_number\", \"name\": \"Fido\"}"),
            logger
        );
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0), containsString("request body validation error"));
    }

    @Test
    public void shouldMatchPathWithTemplateParameters() {
        List<String> errors = OpenAPIRequestValidator.validate(SPEC,
            request("/pets/123").withMethod("GET"),
            logger
        );
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldFallbackToApplicationJsonContentType() {
        List<String> errors = OpenAPIRequestValidator.validate(SPEC,
            request("/pets")
                .withMethod("POST")
                .withBody("{\"id\": 1, \"name\": \"Fido\"}"),
            logger
        );
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldHandleContentTypeWithCharset() {
        List<String> errors = OpenAPIRequestValidator.validate(SPEC,
            request("/pets")
                .withMethod("POST")
                .withHeader("content-type", "application/json; charset=utf-8")
                .withBody("{\"id\": 1, \"name\": \"Fido\"}"),
            logger
        );
        assertThat(errors, is(empty()));
    }

    private static final String CONCRETE_BEFORE_TEMPLATED = "org/mockserver/openapi/openapi_concrete_before_templated.yaml";

    @Test
    public void shouldPreferConcretePathOverTemplatedPath() {
        // given - spec declares "/users/{id}" BEFORE the concrete "/users/me"; declaration-order
        // matching would wrongly select the templated operation. The concrete path must win.
        // A body valid only for the concrete "/users/me" schema (requires "me") and invalid for the
        // templated "/users/{id}" schema (requires "byId", additionalProperties:false) proves which
        // operation was selected.
        List<String> errors = OpenAPIRequestValidator.validate(CONCRETE_BEFORE_TEMPLATED,
            request("/users/me")
                .withMethod("GET"),
            logger
        );

        // then - the concrete /users/me operation is selected (it has no request body), so no errors
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldStillMatchTemplatedPathWhenNoConcretePathMatches() {
        // given - "/users/123" only matches the templated "/users/{id}" operation
        List<String> errors = OpenAPIRequestValidator.validate(CONCRETE_BEFORE_TEMPLATED,
            request("/users/123").withMethod("GET"),
            logger
        );
        assertThat(errors, is(empty()));
    }

    private static final String WEBHOOKS_ONLY = "org/mockserver/openapi/openapi_31_webhooks_only.yaml";

    @Test
    public void shouldNotNpeForWebhooksOnlySpec() {
        // given - a valid OAS 3.1 spec with webhooks and NO paths; getPaths() returns null
        List<String> errors = OpenAPIRequestValidator.validate(WEBHOOKS_ONLY,
            request("/pets").withMethod("GET"),
            logger
        );

        // then - no NPE / no "OpenAPI request validation error"; just an ordinary unmatched-operation result
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0), containsString("no operation found"));
    }

    @Test
    public void shouldHandleInvalidSpec() {
        List<String> errors = OpenAPIRequestValidator.validate("not_a_valid_spec",
            request("/pets").withMethod("GET"),
            logger
        );
        assertThat(errors, hasSize(1));
        // the message names what was being validated and the exception type, and never echoes a bare "null"
        assertThat(errors.get(0), containsString("OpenAPI request validation failed"));
        assertThat(errors.get(0), not(endsWith(": null")));
    }

    @Test
    public void shouldProduceMeaningfulErrorWhenExceptionMessageIsNull() {
        // given - an exception with no message (getMessage() == null), e.g. a bare NPE
        Throwable nullMessageThrowable = new NullPointerException();

        // when - the same helper the validator uses turns it into a caller-facing error string
        String error = OpenAPIValidationErrors.unexpectedError("OpenAPI request validation", nullMessageThrowable, logger);

        // then - the context AND the exception type are present, and there is no literal "null"
        assertThat(error, containsString("OpenAPI request validation"));
        assertThat(error, containsString("NullPointerException"));
        assertThat(error, not(containsString("null")));
    }
}
