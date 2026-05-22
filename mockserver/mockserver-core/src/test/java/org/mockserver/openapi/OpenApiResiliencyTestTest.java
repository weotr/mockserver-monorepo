package org.mockserver.openapi;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpResponse.response;

public class OpenApiResiliencyTestTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger(OpenApiResiliencyTestTest.class);
    private final OpenApiResiliencyTest resiliencyTest = new OpenApiResiliencyTest(mockServerLogger);

    // Spec with required fields, numeric bounds, string length constraints, and required query params
    private static final String RICH_SPEC = "{\n" +
        "  \"openapi\": \"3.0.0\",\n" +
        "  \"info\": {\"title\": \"Test API\", \"version\": \"1.0\"},\n" +
        "  \"paths\": {\n" +
        "    \"/items\": {\n" +
        "      \"post\": {\n" +
        "        \"operationId\": \"createItem\",\n" +
        "        \"parameters\": [\n" +
        "          {\n" +
        "            \"name\": \"tenant\",\n" +
        "            \"in\": \"query\",\n" +
        "            \"required\": true,\n" +
        "            \"schema\": { \"type\": \"string\", \"default\": \"default-tenant\" }\n" +
        "          }\n" +
        "        ],\n" +
        "        \"requestBody\": {\n" +
        "          \"required\": true,\n" +
        "          \"content\": {\n" +
        "            \"application/json\": {\n" +
        "              \"schema\": {\n" +
        "                \"type\": \"object\",\n" +
        "                \"required\": [\"name\", \"quantity\"],\n" +
        "                \"properties\": {\n" +
        "                  \"name\": {\n" +
        "                    \"type\": \"string\",\n" +
        "                    \"minLength\": 3,\n" +
        "                    \"maxLength\": 50\n" +
        "                  },\n" +
        "                  \"quantity\": {\n" +
        "                    \"type\": \"integer\",\n" +
        "                    \"minimum\": 1,\n" +
        "                    \"maximum\": 1000\n" +
        "                  },\n" +
        "                  \"description\": {\n" +
        "                    \"type\": \"string\"\n" +
        "                  },\n" +
        "                  \"enabled\": {\n" +
        "                    \"type\": \"boolean\"\n" +
        "                  }\n" +
        "                }\n" +
        "              }\n" +
        "            }\n" +
        "          }\n" +
        "        },\n" +
        "        \"responses\": {\n" +
        "          \"201\": { \"description\": \"Created\" },\n" +
        "          \"400\": { \"description\": \"Bad request\" }\n" +
        "        }\n" +
        "      }\n" +
        "    },\n" +
        "    \"/items/{itemId}\": {\n" +
        "      \"get\": {\n" +
        "        \"operationId\": \"getItem\",\n" +
        "        \"parameters\": [\n" +
        "          {\n" +
        "            \"name\": \"itemId\",\n" +
        "            \"in\": \"path\",\n" +
        "            \"required\": true,\n" +
        "            \"schema\": { \"type\": \"string\" }\n" +
        "          }\n" +
        "        ],\n" +
        "        \"responses\": {\n" +
        "          \"200\": { \"description\": \"OK\" },\n" +
        "          \"404\": { \"description\": \"Not found\" }\n" +
        "        }\n" +
        "      }\n" +
        "    }\n" +
        "  }\n" +
        "}";

    // Simple spec with NO request body (GET only)
    private static final String NO_BODY_SPEC = "{\n" +
        "  \"openapi\": \"3.0.0\",\n" +
        "  \"info\": {\"title\": \"Test\", \"version\": \"1.0\"},\n" +
        "  \"paths\": {\n" +
        "    \"/status\": {\n" +
        "      \"get\": {\n" +
        "        \"operationId\": \"getStatus\",\n" +
        "        \"responses\": {\n" +
        "          \"200\": { \"description\": \"OK\" }\n" +
        "        }\n" +
        "      }\n" +
        "    }\n" +
        "  }\n" +
        "}";

    // --- Classification tests ---

    @Test
    public void shouldClassify4xxAsHandled() {
        // given
        Function<HttpRequest, HttpResponse> sender = request -> response().withStatusCode(400);

        // when
        OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
            RICH_SPEC, "http://localhost:8080", "createItem", sender
        );

        // then
        assertThat(report.getResults(), is(not(empty())));
        for (OpenApiResiliencyTest.MutationResult result : report.getResults()) {
            assertThat("4xx should be HANDLED: " + result.getMutationDescription(),
                result.getClassification(), is(OpenApiResiliencyTest.Classification.HANDLED));
        }
    }

    @Test
    public void shouldClassify422AsHandled() {
        // given
        Function<HttpRequest, HttpResponse> sender = request -> response().withStatusCode(422);

        // when
        OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
            RICH_SPEC, "http://localhost:8080", "createItem", sender
        );

        // then
        for (OpenApiResiliencyTest.MutationResult result : report.getResults()) {
            assertThat(result.getClassification(), is(OpenApiResiliencyTest.Classification.HANDLED));
        }
    }

    @Test
    public void shouldClassify5xxAsUnexpected() {
        // given
        Function<HttpRequest, HttpResponse> sender = request -> response().withStatusCode(500);

        // when
        OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
            RICH_SPEC, "http://localhost:8080", "createItem", sender
        );

        // then
        for (OpenApiResiliencyTest.MutationResult result : report.getResults()) {
            assertThat("5xx should be UNEXPECTED: " + result.getMutationDescription(),
                result.getClassification(), is(OpenApiResiliencyTest.Classification.UNEXPECTED));
        }
    }

    @Test
    public void shouldClassify2xxAsUnexpected() {
        // given - service accepts invalid input (2xx)
        Function<HttpRequest, HttpResponse> sender = request -> response().withStatusCode(200);

        // when
        OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
            RICH_SPEC, "http://localhost:8080", "createItem", sender
        );

        // then
        for (OpenApiResiliencyTest.MutationResult result : report.getResults()) {
            assertThat("2xx should be UNEXPECTED: " + result.getMutationDescription(),
                result.getClassification(), is(OpenApiResiliencyTest.Classification.UNEXPECTED));
        }
    }

    @Test
    public void shouldClassifyConnectionErrorAsUnexpected() {
        // given - sender throws exception
        Function<HttpRequest, HttpResponse> sender = request -> {
            throw new RuntimeException("connection refused");
        };

        // when
        OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
            RICH_SPEC, "http://localhost:8080", "createItem", sender
        );

        // then
        for (OpenApiResiliencyTest.MutationResult result : report.getResults()) {
            assertThat("connection error should be UNEXPECTED: " + result.getMutationDescription(),
                result.getClassification(), is(OpenApiResiliencyTest.Classification.UNEXPECTED));
            assertThat(result.getStatusCode(), is(0));
        }
    }

    // --- Mutation generation tests ---

    @Test
    public void shouldGenerateOmitRequiredQueryParamMutation() {
        // given
        final boolean[] sawOmitQueryParam = {false};
        Function<HttpRequest, HttpResponse> sender = request -> {
            return response().withStatusCode(400);
        };

        // when
        OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
            RICH_SPEC, "http://localhost:8080", "createItem", sender
        );

        // then
        boolean found = false;
        for (OpenApiResiliencyTest.MutationResult r : report.getResults()) {
            if (r.getMutationType() == OpenApiResiliencyTest.MutationType.OMIT_REQUIRED_QUERY_PARAM) {
                found = true;
                assertThat(r.getMutationDescription(), containsString("tenant"));
            }
        }
        assertThat("should generate OMIT_REQUIRED_QUERY_PARAM mutation", found, is(true));
    }

    @Test
    public void shouldGenerateOmitRequiredBodyFieldMutations() {
        // given
        Function<HttpRequest, HttpResponse> sender = request -> response().withStatusCode(400);

        // when
        OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
            RICH_SPEC, "http://localhost:8080", "createItem", sender
        );

        // then - should have mutations for 'name' and 'quantity'
        List<OpenApiResiliencyTest.MutationResult> omitFieldResults = new java.util.ArrayList<>();
        for (OpenApiResiliencyTest.MutationResult r : report.getResults()) {
            if (r.getMutationType() == OpenApiResiliencyTest.MutationType.OMIT_REQUIRED_BODY_FIELD) {
                omitFieldResults.add(r);
            }
        }
        assertThat("should generate at least 2 OMIT_REQUIRED_BODY_FIELD mutations", omitFieldResults.size(), greaterThanOrEqualTo(2));
        boolean foundName = false, foundQuantity = false;
        for (OpenApiResiliencyTest.MutationResult r : omitFieldResults) {
            if (r.getMutationDescription().contains("'name'")) foundName = true;
            if (r.getMutationDescription().contains("'quantity'")) foundQuantity = true;
        }
        assertThat("should omit required field 'name'", foundName, is(true));
        assertThat("should omit required field 'quantity'", foundQuantity, is(true));
    }

    @Test
    public void shouldGenerateTypeViolationMutations() {
        // given
        Function<HttpRequest, HttpResponse> sender = request -> response().withStatusCode(400);

        // when
        OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
            RICH_SPEC, "http://localhost:8080", "createItem", sender
        );

        // then
        List<OpenApiResiliencyTest.MutationResult> typeViolations = new java.util.ArrayList<>();
        for (OpenApiResiliencyTest.MutationResult r : report.getResults()) {
            if (r.getMutationType() == OpenApiResiliencyTest.MutationType.TYPE_VIOLATION) {
                typeViolations.add(r);
            }
        }
        // Should have type violations for name(string->int), quantity(int->string), description(string->int), enabled(boolean->string)
        assertThat("should generate type violation mutations", typeViolations.size(), greaterThanOrEqualTo(3));
    }

    @Test
    public void shouldGenerateNumericBoundaryViolations() {
        // given
        Function<HttpRequest, HttpResponse> sender = request -> response().withStatusCode(400);

        // when
        OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
            RICH_SPEC, "http://localhost:8080", "createItem", sender
        );

        // then - quantity has minimum=1 and maximum=1000
        List<OpenApiResiliencyTest.MutationResult> boundaryViolations = new java.util.ArrayList<>();
        for (OpenApiResiliencyTest.MutationResult r : report.getResults()) {
            if (r.getMutationType() == OpenApiResiliencyTest.MutationType.NUMERIC_BOUNDARY_VIOLATION) {
                boundaryViolations.add(r);
            }
        }
        assertThat("should generate 2 numeric boundary violations (min-1, max+1)", boundaryViolations.size(), is(2));
        boolean foundMinViolation = false, foundMaxViolation = false;
        for (OpenApiResiliencyTest.MutationResult r : boundaryViolations) {
            if (r.getMutationDescription().contains("minimum-1")) foundMinViolation = true;
            if (r.getMutationDescription().contains("maximum+1")) foundMaxViolation = true;
        }
        assertThat("should have minimum-1 violation", foundMinViolation, is(true));
        assertThat("should have maximum+1 violation", foundMaxViolation, is(true));
    }

    @Test
    public void shouldGenerateStringLengthBoundaryViolations() {
        // given
        Function<HttpRequest, HttpResponse> sender = request -> response().withStatusCode(400);

        // when
        OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
            RICH_SPEC, "http://localhost:8080", "createItem", sender
        );

        // then - 'name' has minLength=3 and maxLength=50
        List<OpenApiResiliencyTest.MutationResult> stringViolations = new java.util.ArrayList<>();
        for (OpenApiResiliencyTest.MutationResult r : report.getResults()) {
            if (r.getMutationType() == OpenApiResiliencyTest.MutationType.STRING_LENGTH_BOUNDARY_VIOLATION) {
                stringViolations.add(r);
            }
        }
        assertThat("should generate string length violations for 'name'", stringViolations.size(), greaterThanOrEqualTo(2));
        boolean foundMinLen = false, foundMaxLen = false;
        for (OpenApiResiliencyTest.MutationResult r : stringViolations) {
            if (r.getMutationDescription().contains("minLength-1")) foundMinLen = true;
            if (r.getMutationDescription().contains("maxLength+1")) foundMaxLen = true;
        }
        assertThat("should have minLength-1 violation", foundMinLen, is(true));
        assertThat("should have maxLength+1 violation", foundMaxLen, is(true));
    }

    @Test
    public void shouldGenerateOversizedStringFieldMutation() {
        // given
        Function<HttpRequest, HttpResponse> sender = request -> response().withStatusCode(400);

        // when
        OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
            RICH_SPEC, "http://localhost:8080", "createItem", sender
        );

        // then - 'description' is a string with no maxLength, so it should get an oversized mutation
        boolean found = false;
        for (OpenApiResiliencyTest.MutationResult r : report.getResults()) {
            if (r.getMutationType() == OpenApiResiliencyTest.MutationType.OVERSIZED_STRING_FIELD
                && r.getMutationDescription().contains("'description'")) {
                found = true;
            }
        }
        assertThat("should generate oversized string mutation for 'description'", found, is(true));
    }

    @Test
    public void shouldNotGenerateOversizedStringForFieldsWithMaxLength() {
        // given
        Function<HttpRequest, HttpResponse> sender = request -> response().withStatusCode(400);

        // when
        OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
            RICH_SPEC, "http://localhost:8080", "createItem", sender
        );

        // then - 'name' has maxLength, so should NOT get an oversized mutation
        boolean found = false;
        for (OpenApiResiliencyTest.MutationResult r : report.getResults()) {
            if (r.getMutationType() == OpenApiResiliencyTest.MutationType.OVERSIZED_STRING_FIELD
                && r.getMutationDescription().contains("'name'")) {
                found = true;
            }
        }
        assertThat("should NOT generate oversized string for field with maxLength", found, is(false));
    }

    @Test
    public void shouldGenerateMalformedJsonBodyMutation() {
        // given
        Function<HttpRequest, HttpResponse> sender = request -> response().withStatusCode(400);

        // when
        OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
            RICH_SPEC, "http://localhost:8080", "createItem", sender
        );

        // then
        boolean found = false;
        for (OpenApiResiliencyTest.MutationResult r : report.getResults()) {
            if (r.getMutationType() == OpenApiResiliencyTest.MutationType.MALFORMED_JSON_BODY) {
                found = true;
            }
        }
        assertThat("should generate malformed JSON body mutation", found, is(true));
    }

    @Test
    public void shouldGenerateOmitPathParamMutation() {
        // given
        Function<HttpRequest, HttpResponse> sender = request -> response().withStatusCode(400);

        // when
        OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
            RICH_SPEC, "http://localhost:8080", "getItem", sender
        );

        // then
        boolean found = false;
        for (OpenApiResiliencyTest.MutationResult r : report.getResults()) {
            if (r.getMutationType() == OpenApiResiliencyTest.MutationType.OMIT_REQUIRED_PATH_PARAM) {
                found = true;
                assertThat(r.getMutationDescription(), containsString("itemId"));
            }
        }
        assertThat("should generate OMIT_REQUIRED_PATH_PARAM mutation", found, is(true));
    }

    // --- Skip body mutations when no body ---

    @Test
    public void shouldNotGenerateBodyMutationsWhenNoRequestBody() {
        // given
        Function<HttpRequest, HttpResponse> sender = request -> response().withStatusCode(200);

        // when
        OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
            NO_BODY_SPEC, "http://localhost:8080", null, sender
        );

        // then - should have no body-related mutations
        for (OpenApiResiliencyTest.MutationResult r : report.getResults()) {
            assertThat("no body mutations for bodyless operation: " + r.getMutationType(),
                r.getMutationType(), not(is(OpenApiResiliencyTest.MutationType.OMIT_REQUIRED_BODY_FIELD)));
            assertThat("no body mutations for bodyless operation: " + r.getMutationType(),
                r.getMutationType(), not(is(OpenApiResiliencyTest.MutationType.TYPE_VIOLATION)));
            assertThat("no body mutations for bodyless operation: " + r.getMutationType(),
                r.getMutationType(), not(is(OpenApiResiliencyTest.MutationType.MALFORMED_JSON_BODY)));
        }
        // GET /status has no params either, so 0 mutations total
        assertThat("GET with no params and no body should produce 0 mutations",
            report.getTotalMutations(), is(0));
    }

    // --- Summary / report tests ---

    @Test
    public void shouldProduceSummaryCounts() {
        // given - mix of handled and unexpected
        final int[] callCount = {0};
        Function<HttpRequest, HttpResponse> sender = request -> {
            callCount[0]++;
            // alternate between 400 (handled) and 500 (unexpected)
            return response().withStatusCode(callCount[0] % 2 == 0 ? 400 : 500);
        };

        // when
        OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
            RICH_SPEC, "http://localhost:8080", "createItem", sender
        );

        // then
        assertThat(report.getTotalMutations(), greaterThan(0));
        assertThat(report.getHandledCount() + report.getUnexpectedCount(), is(report.getTotalMutations()));
        assertThat(report.getHandledCount(), greaterThan(0));
        assertThat(report.getUnexpectedCount(), greaterThan(0));
    }

    @Test
    public void shouldProducePerOperationSummaries() {
        // given
        Function<HttpRequest, HttpResponse> sender = request -> response().withStatusCode(400);

        // when
        OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
            RICH_SPEC, "http://localhost:8080", null, sender
        );

        // then
        Map<String, OpenApiResiliencyTest.OperationSummary> summaries = report.getOperationSummaries();
        assertThat(summaries.size(), greaterThanOrEqualTo(2));
        assertThat(summaries.containsKey("createItem"), is(true));
        assertThat(summaries.containsKey("getItem"), is(true));

        OpenApiResiliencyTest.OperationSummary createItemSummary = summaries.get("createItem");
        assertThat(createItemSummary.getHandled(), greaterThan(0));
        assertThat(createItemSummary.getUnexpected(), is(0));
    }

    @Test
    public void shouldFilterByOperationId() {
        // given
        Function<HttpRequest, HttpResponse> sender = request -> response().withStatusCode(400);

        // when
        OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
            RICH_SPEC, "http://localhost:8080", "getItem", sender
        );

        // then - should only have mutations for getItem
        for (OpenApiResiliencyTest.MutationResult r : report.getResults()) {
            assertThat(r.getOperationId(), is("getItem"));
        }
    }

    @Test
    public void shouldPopulateAllResultFields() {
        // given
        Function<HttpRequest, HttpResponse> sender = request -> response().withStatusCode(400);

        // when
        OpenApiResiliencyTest.ResiliencyTestReport report = resiliencyTest.runResiliencyTests(
            RICH_SPEC, "http://localhost:8080", "createItem", sender
        );

        // then
        assertThat(report.getResults(), is(not(empty())));
        for (OpenApiResiliencyTest.MutationResult r : report.getResults()) {
            assertThat(r.getOperationId(), is(notNullValue()));
            assertThat(r.getMethod(), is(notNullValue()));
            assertThat(r.getPath(), is(notNullValue()));
            assertThat(r.getMutationType(), is(notNullValue()));
            assertThat(r.getMutationDescription(), is(notNullValue()));
            assertThat(r.getClassification(), is(notNullValue()));
        }
    }
}
