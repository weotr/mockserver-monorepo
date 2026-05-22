package org.mockserver.openapi;

import org.junit.Test;
import org.mockserver.file.FileReader;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpResponse.response;

public class OpenApiContractTestTest {

    private static final String SPEC = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json");
    private final MockServerLogger mockServerLogger = new MockServerLogger(OpenApiContractTestTest.class);
    private final OpenApiContractTest contractTest = new OpenApiContractTest(mockServerLogger);

    @Test
    public void shouldPassWhenResponsesConform() {
        // given - a fake HTTP sender that returns conforming responses
        Function<HttpRequest, HttpResponse> fakeSender = request -> {
            String path = request.getPath() != null ? request.getPath().getValue() : "/";
            String method = request.getMethod() != null ? request.getMethod().getValue().toUpperCase() : "GET";

            if ("GET".equals(method) && path.startsWith("/pets") && !path.equals("/pets")) {
                // showPetById
                return response()
                    .withStatusCode(200)
                    .withHeader("content-type", "application/json")
                    .withBody("{\"id\": 1, \"name\": \"Fido\"}");
            } else if ("GET".equals(method) && path.equals("/pets")) {
                // listPets
                return response()
                    .withStatusCode(200)
                    .withHeader("content-type", "application/json")
                    .withBody("[{\"id\": 1, \"name\": \"Fido\"}]");
            } else if ("POST".equals(method) && path.equals("/pets")) {
                // createPets
                return response().withStatusCode(201);
            } else if ("GET".equals(method) && path.equals("/some/path")) {
                // somePath
                return response()
                    .withStatusCode(200)
                    .withHeader("content-type", "application/json")
                    .withBody("{\"id\": 1, \"name\": \"Fido\"}");
            }
            return response().withStatusCode(404);
        };

        // when
        List<OpenApiContractTest.ContractTestResult> results = contractTest.runContractTests(
            SPEC, "http://localhost:8080", null, fakeSender
        );

        // then
        assertThat(results, hasSize(4)); // listPets, createPets, showPetById, somePath
        for (OpenApiContractTest.ContractTestResult result : results) {
            assertThat("operation " + result.getOperationId() + " should pass but had errors: " + result.getValidationErrors(),
                result.isPassed(), is(true));
        }
    }

    @Test
    public void shouldFailWhenResponseDoesNotConform() {
        // given - a fake HTTP sender that returns non-conforming responses
        Function<HttpRequest, HttpResponse> fakeSender = request -> {
            String path = request.getPath() != null ? request.getPath().getValue() : "/";
            String method = request.getMethod() != null ? request.getMethod().getValue().toUpperCase() : "GET";

            if ("GET".equals(method) && path.equals("/pets")) {
                // Return a non-array response for listPets (expects array)
                return response()
                    .withStatusCode(200)
                    .withHeader("content-type", "application/json")
                    .withBody("{\"not\": \"an array\"}");
            }
            // All other operations return conforming responses
            if ("POST".equals(method) && path.equals("/pets")) {
                return response().withStatusCode(201);
            }
            return response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("{\"id\": 1, \"name\": \"Fido\"}");
        };

        // when
        List<OpenApiContractTest.ContractTestResult> results = contractTest.runContractTests(
            SPEC, "http://localhost:8080", null, fakeSender
        );

        // then
        assertThat(results, hasSize(4));
        boolean foundFailedListPets = false;
        for (OpenApiContractTest.ContractTestResult result : results) {
            if ("listPets".equals(result.getOperationId())) {
                foundFailedListPets = true;
                assertThat(result.isPassed(), is(false));
                assertThat(result.getValidationErrors(), is(not(empty())));
            }
        }
        assertThat("should find a failed listPets result", foundFailedListPets, is(true));
    }

    @Test
    public void shouldFilterByOperationId() {
        // given
        Function<HttpRequest, HttpResponse> fakeSender = request ->
            response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("[{\"id\": 1, \"name\": \"Fido\"}]");

        // when
        List<OpenApiContractTest.ContractTestResult> results = contractTest.runContractTests(
            SPEC, "http://localhost:8080", "listPets", fakeSender
        );

        // then
        assertThat(results, hasSize(1));
        assertThat(results.get(0).getOperationId(), is("listPets"));
        assertThat(results.get(0).isPassed(), is(true));
    }

    @Test
    public void shouldBuildExampleRequestWithPathParameters() {
        // given - the petstore spec has showPetById with path param {petId}

        // when
        Function<HttpRequest, HttpResponse> capturingSender = request -> {
            // Verify the path parameter was resolved
            String path = request.getPath() != null ? request.getPath().getValue() : "";
            assertThat("path should not contain template variable", path, not(containsString("{")));
            return response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("{\"id\": 1, \"name\": \"Fido\"}");
        };

        List<OpenApiContractTest.ContractTestResult> results = contractTest.runContractTests(
            SPEC, "http://localhost:8080", "showPetById", capturingSender
        );

        // then
        assertThat(results, hasSize(1));
        assertThat(results.get(0).getRequestSent(), is(notNullValue()));
        String requestPath = results.get(0).getRequestSent().getPath().getValue();
        assertThat(requestPath, not(containsString("{")));
    }

    @Test
    public void shouldBuildExampleRequestWithRequestBody() {
        // given - createPets requires a request body with Pet schema
        Function<HttpRequest, HttpResponse> capturingSender = request -> {
            String body = request.getBodyAsString();
            assertThat("request should have a body for POST /pets", body, is(notNullValue()));
            assertThat("request body should not be empty", body.isEmpty(), is(false));
            return response().withStatusCode(201);
        };

        // when
        List<OpenApiContractTest.ContractTestResult> results = contractTest.runContractTests(
            SPEC, "http://localhost:8080", "createPets", capturingSender
        );

        // then
        assertThat(results, hasSize(1));
        assertThat(results.get(0).getRequestSent(), is(notNullValue()));
        assertThat(results.get(0).getRequestSent().getBodyAsString(), is(notNullValue()));
    }

    @Test
    public void shouldBuildExampleRequestWithQueryParameters() {
        // given - listPets has an optional 'limit' query parameter

        // Use simple spec to test query param
        String simpleSpec = "{\n" +
            "  \"openapi\": \"3.0.0\",\n" +
            "  \"info\": {\"title\": \"Test\", \"version\": \"1.0\"},\n" +
            "  \"paths\": {\n" +
            "    \"/items\": {\n" +
            "      \"get\": {\n" +
            "        \"operationId\": \"listItems\",\n" +
            "        \"parameters\": [\n" +
            "          {\n" +
            "            \"name\": \"limit\",\n" +
            "            \"in\": \"query\",\n" +
            "            \"required\": true,\n" +
            "            \"schema\": { \"type\": \"integer\", \"default\": 10 }\n" +
            "          }\n" +
            "        ],\n" +
            "        \"responses\": {\n" +
            "          \"200\": {\n" +
            "            \"description\": \"OK\",\n" +
            "            \"content\": {\n" +
            "              \"application/json\": {\n" +
            "                \"schema\": { \"type\": \"array\", \"items\": { \"type\": \"string\" } }\n" +
            "              }\n" +
            "            }\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        final boolean[] queryParamFound = {false};
        Function<HttpRequest, HttpResponse> capturingSender = request -> {
            if (request.getQueryStringParameterList() != null && !request.getQueryStringParameterList().isEmpty()) {
                queryParamFound[0] = true;
            }
            return response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("[\"item1\"]");
        };

        // when
        List<OpenApiContractTest.ContractTestResult> results = contractTest.runContractTests(
            simpleSpec, "http://localhost:8080", null, capturingSender
        );

        // then
        assertThat(results, hasSize(1));
        assertThat("query parameter should be included", queryParamFound[0], is(true));
    }

    @Test
    public void shouldHandleSenderException() {
        // given - a sender that throws
        Function<HttpRequest, HttpResponse> failingSender = request -> {
            throw new RuntimeException("connection refused");
        };

        // when
        List<OpenApiContractTest.ContractTestResult> results = contractTest.runContractTests(
            SPEC, "http://localhost:8080", "listPets", failingSender
        );

        // then
        assertThat(results, hasSize(1));
        assertThat(results.get(0).isPassed(), is(false));
        assertThat(results.get(0).getValidationErrors(), hasSize(1));
        assertThat(results.get(0).getValidationErrors().get(0), containsString("contract test error"));
    }
}
