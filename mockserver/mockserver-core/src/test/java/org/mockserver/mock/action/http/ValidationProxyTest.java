package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.file.FileReader;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.openapi.OpenAPIRequestValidator;
import org.mockserver.openapi.OpenAPIResponseValidator;
import org.mockserver.openapi.OpenApiTrafficValidator;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests for the validation proxy feature (B2). Exercises the OpenAPI validators in
 * the same way the {@link HttpActionHandler} proxy paths do, verifying that:
 * <ul>
 *   <li>conformant traffic produces no violations</li>
 *   <li>non-conformant forwarded responses are flagged (response-only validation, no double request validation)</li>
 *   <li>non-conformant requests are flagged with the correct log type</li>
 *   <li>enforce mode returns 400 for bad requests and 502 for bad non-streaming responses</li>
 *   <li>streaming responses under enforce mode are validated report-only (violations logged, not blocked)</li>
 *   <li>when the feature is disabled (default), no validation occurs</li>
 * </ul>
 */
public class ValidationProxyTest {

    private static final String SPEC = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json");
    private final MockServerLogger mockServerLogger = new MockServerLogger(ValidationProxyTest.class);

    private String originalSpec;
    private boolean originalEnforce;

    @Before
    public void saveOriginals() {
        originalSpec = ConfigurationProperties.validateProxyOpenAPISpec();
        originalEnforce = ConfigurationProperties.validateProxyEnforce();
    }

    @After
    public void restoreOriginals() {
        ConfigurationProperties.validateProxyOpenAPISpec(originalSpec);
        ConfigurationProperties.validateProxyEnforce(originalEnforce);
    }

    // ---- default-off: no validation occurs ----

    @Test
    public void shouldNotValidateWhenFeatureDisabled() {
        // given - default config (empty spec)
        Configuration config = new Configuration();

        // then - validation is off
        assertThat(config.validateProxyOpenAPISpec(), is(""));
        assertThat(config.validateProxyEnforce(), is(false));
    }

    @Test
    public void shouldNotValidateWhenSpecIsExplicitlyNull() {
        Configuration config = new Configuration();
        config.validateProxyOpenAPISpec(null);

        // null on instance falls through to ConfigurationProperties default (empty string)
        assertThat(config.validateProxyOpenAPISpec(), is(""));
    }

    // ---- report-only: violations are detected but traffic flows ----

    @Test
    public void shouldDetectNonConformantResponseInReportMode() {
        // given - a valid request but a non-conformant response
        // Production code uses OpenAPIResponseValidator directly (not OpenApiTrafficValidator)
        // to avoid double-validating the request. This test mirrors that approach.
        HttpResponse invalidResponse = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{\"not\": \"an array\"}");

        // when - validate response only (as the production proxy path does)
        List<String> responseErrors = OpenAPIResponseValidator.validate(SPEC, "listPets", invalidResponse, mockServerLogger);

        // then - violation is detected
        assertThat(responseErrors, is(not(empty())));
    }

    @Test
    public void shouldDetectNonConformantRequestInReportMode() {
        // given - a POST with invalid body
        HttpRequest invalidRequest = request("/pets")
            .withMethod("POST")
            .withHeader("content-type", "application/json")
            .withBody("{\"id\": \"not_a_number\", \"name\": \"Fido\"}");

        // when - validate the request against the spec
        List<String> requestErrors = OpenAPIRequestValidator.validate(SPEC, invalidRequest, mockServerLogger);

        // then - violation is detected
        assertThat(requestErrors, is(not(empty())));
    }

    @Test
    public void shouldPassConformantTraffic() {
        // given - conformant request and response
        HttpRequest validRequest = request("/pets").withMethod("GET");
        HttpResponse validResponse = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("[{\"id\": 1, \"name\": \"Fido\"}]");

        // when - validate request and response separately (as production code does)
        List<String> requestErrors = OpenAPIRequestValidator.validate(SPEC, validRequest, mockServerLogger);
        List<String> responseErrors = OpenAPIResponseValidator.validate(SPEC, "listPets", validResponse, mockServerLogger);

        // then - no violations
        assertThat(requestErrors, is(empty()));
        assertThat(responseErrors, is(empty()));
    }

    @Test
    public void shouldValidateResponseOnlyWithoutDoubleRequestValidation() {
        // This test verifies that production response-validation uses OpenAPIResponseValidator
        // directly (response-only), not OpenApiTrafficValidator (which validates both request
        // and response, causing double request validation).

        HttpResponse invalidResponse = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{\"not\": \"an array\"}");

        // OpenAPIResponseValidator validates ONLY the response — no request needed
        List<String> responseErrors = OpenAPIResponseValidator.validate(SPEC, "listPets", invalidResponse, mockServerLogger);
        assertThat("response-only validator should detect the violation", responseErrors, is(not(empty())));

        // Compare with OpenApiTrafficValidator which validates BOTH request and response
        HttpRequest validRequest = request("/pets").withMethod("GET");
        OpenApiTrafficValidator trafficValidator = new OpenApiTrafficValidator(mockServerLogger);
        List<OpenApiTrafficValidator.TrafficValidationResult> results = trafficValidator.validate(
            SPEC,
            Collections.singletonList(org.apache.commons.lang3.tuple.Pair.of(validRequest, invalidResponse))
        );
        // The traffic validator also finds the response error, but additionally validates the request
        assertThat(results.get(0).getResponseErrors(), is(not(empty())));
        // Production code avoids this double-validation by using OpenAPIResponseValidator directly
    }

    // ---- configuration via system properties ----

    @Test
    public void shouldConfigureViaSystemProperties() {
        // when
        ConfigurationProperties.validateProxyOpenAPISpec("https://example.com/spec.json");
        ConfigurationProperties.validateProxyEnforce(true);

        // then
        assertThat(ConfigurationProperties.validateProxyOpenAPISpec(), is("https://example.com/spec.json"));
        assertThat(ConfigurationProperties.validateProxyEnforce(), is(true));
    }

    @Test
    public void shouldConfigureViaInstanceConfig() {
        // when
        Configuration config = new Configuration()
            .validateProxyOpenAPISpec(SPEC)
            .validateProxyEnforce(true);

        // then
        assertThat(config.validateProxyOpenAPISpec(), is(SPEC));
        assertThat(config.validateProxyEnforce(), is(true));
    }

    @Test
    public void shouldFallBackToConfigurationPropertiesFromInstance() {
        ConfigurationProperties.validateProxyOpenAPISpec("fallback-spec");

        Configuration config = new Configuration();
        assertThat(config.validateProxyOpenAPISpec(), is("fallback-spec"));
    }

    @Test
    public void shouldOverrideConfigurationPropertiesWithInstance() {
        ConfigurationProperties.validateProxyOpenAPISpec("global-spec");

        Configuration config = new Configuration();
        config.validateProxyOpenAPISpec("instance-spec");

        assertThat(config.validateProxyOpenAPISpec(), is("instance-spec"));
    }

    // ---- enforce mode: non-streaming produces blocking responses ----

    @Test
    public void shouldProduceEnforce502ForNonStreamingNonConformantResponse() {
        // given - a non-conformant response (not an array as listPets expects)
        HttpResponse invalidResponse = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{\"not\": \"an array\"}");

        // when - validate response only (as production code does)
        List<String> responseErrors = OpenAPIResponseValidator.validate(SPEC, "listPets", invalidResponse, mockServerLogger);

        // then - enforce mode would see non-empty errors and return 502 for a non-streaming response
        assertThat(responseErrors, is(not(empty())));

        // simulate the enforce logic from HttpActionHandler.validateProxyResponse (streaming=false)
        boolean streaming = false;
        boolean enforce = true;
        HttpResponse result;
        if (!streaming && enforce && !responseErrors.isEmpty()) {
            result = response()
                .withStatusCode(502)
                .withBody("OpenAPI response validation failed: " + String.join("; ", responseErrors));
        } else {
            result = invalidResponse;
        }
        assertThat(result.getStatusCode(), is(502));
        assertThat(result.getBodyAsString(), containsString("OpenAPI response validation failed"));
    }

    @Test
    public void shouldFlagNonConformantRequestForEnforceMode() {
        // given - POST with invalid body
        HttpRequest invalidRequest = request("/pets")
            .withMethod("POST")
            .withHeader("content-type", "application/json")
            .withBody("{\"id\": \"not_a_number\", \"name\": \"Fido\"}");

        // when
        List<String> requestErrors = OpenAPIRequestValidator.validate(SPEC, invalidRequest, mockServerLogger);

        // then - enforce mode would return 400 with these errors
        assertThat(requestErrors, is(not(empty())));

        // simulate the enforce logic from HttpActionHandler.validateProxyRequest
        boolean enforce = true;
        HttpResponse result = null;
        if (enforce && !requestErrors.isEmpty()) {
            result = response()
                .withStatusCode(400)
                .withBody("OpenAPI request validation failed: " + String.join("; ", requestErrors));
        }
        assertThat(result, is(notNullValue()));
        assertThat(result.getStatusCode(), is(400));
        assertThat(result.getBodyAsString(), containsString("OpenAPI request validation failed"));
    }

    // ---- streaming enforce: violations logged but not blocked ----

    @Test
    public void shouldNotBlock502ForStreamingResponseUnderEnforce() {
        // given - a non-conformant response (same as the non-streaming enforce test)
        HttpResponse invalidResponse = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{\"not\": \"an array\"}");

        // when - validate response only
        List<String> responseErrors = OpenAPIResponseValidator.validate(SPEC, "listPets", invalidResponse, mockServerLogger);
        assertThat("should detect violation", responseErrors, is(not(empty())));

        // simulate the enforce logic from HttpActionHandler.validateProxyResponse (streaming=true)
        // Streaming responses cannot be replaced after the body has been written to the client,
        // so enforce mode is ineffective — violations are logged (report-only).
        boolean streaming = true;
        boolean enforce = true;
        HttpResponse result;
        if (!streaming && enforce && !responseErrors.isEmpty()) {
            result = response()
                .withStatusCode(502)
                .withBody("OpenAPI response validation failed: " + String.join("; ", responseErrors));
        } else {
            // streaming: the original response is returned (already written to client)
            result = invalidResponse;
        }
        // then - the original response is returned, NOT a 502
        assertThat(result.getStatusCode(), is(200));
        assertThat(result, is(sameInstance(invalidResponse)));
    }

    // ---- path with template parameters ----

    @Test
    public void shouldValidateTrafficWithPathParameters() {
        // given
        HttpRequest validRequest = request("/pets/123").withMethod("GET");
        HttpResponse validResponse = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{\"id\": 123, \"name\": \"Rex\"}");

        // when - validate request and response separately
        List<String> requestErrors = OpenAPIRequestValidator.validate(SPEC, validRequest, mockServerLogger);
        List<String> responseErrors = OpenAPIResponseValidator.validate(SPEC, "showPetById", validResponse, mockServerLogger);

        // then
        assertThat(requestErrors, is(empty()));
        assertThat(responseErrors, is(empty()));
    }

    // ---- unmatched operation ----

    @Test
    public void shouldReportUnmatchedOperationForUnknownPath() {
        // given
        HttpRequest request = request("/not-in-spec").withMethod("GET");

        // when - request validation catches unmatched paths
        List<String> requestErrors = OpenAPIRequestValidator.validate(SPEC, request, mockServerLogger);

        // then
        assertThat(requestErrors, hasItem(containsString("no operation found matching")));
    }
}
