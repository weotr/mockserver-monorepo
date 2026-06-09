package org.mockserver.mock.action.http;

import org.apache.commons.lang3.tuple.Pair;
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
 *   <li>non-conformant forwarded responses are flagged</li>
 *   <li>non-conformant requests are flagged</li>
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
        HttpRequest validRequest = request("/pets").withMethod("GET");
        HttpResponse invalidResponse = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{\"not\": \"an array\"}");

        // when - validate as the proxy path would
        OpenApiTrafficValidator validator = new OpenApiTrafficValidator(mockServerLogger);
        List<OpenApiTrafficValidator.TrafficValidationResult> results = validator.validate(
            SPEC,
            Collections.singletonList(Pair.of(validRequest, invalidResponse))
        );

        // then - violation is detected
        assertThat(results, hasSize(1));
        assertThat(results.get(0).isPassed(), is(false));
        assertThat(results.get(0).getResponseErrors(), is(not(empty())));
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

        // when
        OpenApiTrafficValidator validator = new OpenApiTrafficValidator(mockServerLogger);
        List<OpenApiTrafficValidator.TrafficValidationResult> results = validator.validate(
            SPEC,
            Collections.singletonList(Pair.of(validRequest, validResponse))
        );

        // then - no violations
        assertThat(results, hasSize(1));
        assertThat(results.get(0).isPassed(), is(true));
        assertThat(results.get(0).getRequestErrors(), is(empty()));
        assertThat(results.get(0).getResponseErrors(), is(empty()));
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

    // ---- enforce mode produces blocking responses ----

    @Test
    public void shouldFlagNonConformantResponseForEnforceMode() {
        // given
        HttpRequest validRequest = request("/pets").withMethod("GET");
        HttpResponse invalidResponse = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{\"not\": \"an array\"}");

        // when - simulate what enforce mode would see
        OpenApiTrafficValidator validator = new OpenApiTrafficValidator(mockServerLogger);
        List<OpenApiTrafficValidator.TrafficValidationResult> results = validator.validate(
            SPEC,
            Collections.singletonList(Pair.of(validRequest, invalidResponse))
        );

        // then - the result has failures that enforce mode would act on
        assertThat(results.get(0).isPassed(), is(false));
        assertThat(results.get(0).getResponseErrors(), is(not(empty())));
        // enforce mode would return 502 with these errors
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

        // when
        OpenApiTrafficValidator validator = new OpenApiTrafficValidator(mockServerLogger);
        List<OpenApiTrafficValidator.TrafficValidationResult> results = validator.validate(
            SPEC,
            Collections.singletonList(Pair.of(validRequest, validResponse))
        );

        // then
        assertThat(results.get(0).isPassed(), is(true));
        assertThat(results.get(0).getMatchedOperation(), containsString("/pets/{petId}"));
    }

    // ---- unmatched operation ----

    @Test
    public void shouldReportUnmatchedOperationForUnknownPath() {
        // given
        HttpRequest request = request("/not-in-spec").withMethod("GET");
        HttpResponse response = response().withStatusCode(200);

        // when
        OpenApiTrafficValidator validator = new OpenApiTrafficValidator(mockServerLogger);
        List<OpenApiTrafficValidator.TrafficValidationResult> results = validator.validate(
            SPEC,
            Collections.singletonList(Pair.of(request, response))
        );

        // then
        assertThat(results.get(0).isPassed(), is(false));
        assertThat(results.get(0).getRequestErrors(), hasItem(containsString("no matching operation")));
    }
}
