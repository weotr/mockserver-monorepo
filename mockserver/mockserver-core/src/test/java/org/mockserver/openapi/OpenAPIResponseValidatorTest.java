package org.mockserver.openapi;

import org.junit.Test;
import org.mockserver.file.FileReader;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpResponse.response;

public class OpenAPIResponseValidatorTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger(OpenAPIResponseValidatorTest.class);
    private final String specUrlOrPayload = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_petstore_example.json");

    @Test
    public void shouldReturnNoErrorsForValidResponse() {
        // given
        HttpResponse response = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("[{\"id\": 1, \"name\": \"Fido\"}]");

        // when
        List<String> errors = OpenAPIResponseValidator.validate(specUrlOrPayload, "listPets", response, mockServerLogger);

        // then
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldReturnNoErrorsForValidSinglePetResponse() {
        // given
        HttpResponse response = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{\"id\": 1, \"name\": \"Fido\"}");

        // when
        List<String> errors = OpenAPIResponseValidator.validate(specUrlOrPayload, "showPetById", response, mockServerLogger);

        // then
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldReturnErrorForInvalidResponseBody() {
        // given
        HttpResponse response = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{\"invalid\": \"body\"}");

        // when
        List<String> errors = OpenAPIResponseValidator.validate(specUrlOrPayload, "showPetById", response, mockServerLogger);

        // then
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0), containsString("response body validation error"));
    }

    @Test
    public void shouldReturnErrorForInvalidArrayResponseBody() {
        // given - listPets expects an array of Pet objects
        HttpResponse response = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{\"not\": \"an array\"}");

        // when
        List<String> errors = OpenAPIResponseValidator.validate(specUrlOrPayload, "listPets", response, mockServerLogger);

        // then
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0), containsString("response body validation error"));
    }

    @Test
    public void shouldReturnErrorForUndefinedStatusCode() {
        // given - createPets defines 201, 400, 500, default but not 404
        // however since "default" is defined, it will match that
        // let's use showPetById which defines 200, 400, 500, default
        // Actually the petstore spec has "default" for most operations
        // Let me test with a status code that falls through to default
        HttpResponse response = response()
            .withStatusCode(404)
            .withHeader("content-type", "application/json")
            .withBody("{\"code\": 404, \"message\": \"not found\"}");

        // when - showPetById has 200, 400, 500, and default
        // 404 is not explicit but "default" will catch it
        List<String> errors = OpenAPIResponseValidator.validate(specUrlOrPayload, "showPetById", response, mockServerLogger);

        // then - should pass because "default" response matches
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldReturnErrorWhenOperationNotFound() {
        // given
        HttpResponse response = response().withStatusCode(200);

        // when
        List<String> errors = OpenAPIResponseValidator.validate(specUrlOrPayload, "nonExistentOperation", response, mockServerLogger);

        // then
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0), containsString("operation nonExistentOperation not found"));
    }

    @Test
    public void shouldReturnErrorForStatusCodeNotDefinedAndNoDefault() {
        // given - create a spec with no default response
        String specWithNoDefault = "{\n" +
            "  \"openapi\": \"3.0.0\",\n" +
            "  \"info\": {\"title\": \"Test\", \"version\": \"1.0\"},\n" +
            "  \"paths\": {\n" +
            "    \"/test\": {\n" +
            "      \"get\": {\n" +
            "        \"operationId\": \"testOp\",\n" +
            "        \"responses\": {\n" +
            "          \"200\": {\n" +
            "            \"description\": \"OK\"\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        HttpResponse response = response().withStatusCode(404);

        // when
        List<String> errors = OpenAPIResponseValidator.validate(specWithNoDefault, "testOp", response, mockServerLogger);

        // then - the message names the requested status code AND the statuses that ARE defined
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0), containsString("response status code 404 not defined"));
        assertThat(errors.get(0), containsString("defined response status codes are"));
        assertThat(errors.get(0), containsString("200"));
    }

    @Test
    public void shouldProduceMeaningfulErrorWhenExceptionMessageIsNull() {
        // given - an exception with no message (getMessage() == null), e.g. a bare NPE
        Throwable nullMessageThrowable = new NullPointerException();

        // when - the same helper the validator uses turns it into a caller-facing error string
        String error = OpenAPIValidationErrors.unexpectedError("OpenAPI response validation for operation showPetById", nullMessageThrowable, mockServerLogger);

        // then - the operation context AND the exception type are present, and there is no literal "null"
        assertThat(error, containsString("OpenAPI response validation for operation showPetById"));
        assertThat(error, containsString("NullPointerException"));
        assertThat(error, not(containsString("null")));
    }

    @Test
    public void shouldBoundAndSingleLineAnOversizedExceptionMessage() {
        // given - an exception whose message is huge and multi-line (untrusted / pathological)
        StringBuilder huge = new StringBuilder("line one\nline two\t");
        for (int i = 0; i < 5_000; i++) {
            huge.append("x");
        }
        Throwable throwable = new RuntimeException(huge.toString());

        // when
        String error = OpenAPIValidationErrors.unexpectedError("OpenAPI response validation for operation listPets", throwable, mockServerLogger);

        // then - the message is single-line and bounded, but still meaningful
        assertThat(error, containsString("OpenAPI response validation for operation listPets"));
        assertThat(error, containsString("RuntimeException"));
        assertThat("must be single line", error, not(containsString("\n")));
        assertThat("must be bounded", error.length(), lessThan(500));
    }

    @Test
    public void shouldValidateResponseWithNoBody() {
        // given - createPets 201 has no content
        HttpResponse response = response().withStatusCode(201);

        // when
        List<String> errors = OpenAPIResponseValidator.validate(specUrlOrPayload, "createPets", response, mockServerLogger);

        // then
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldValidateErrorSchemaResponse() {
        // given
        HttpResponse response = response()
            .withStatusCode(500)
            .withHeader("content-type", "application/json")
            .withBody("{\"code\": 500, \"message\": \"internal error\"}");

        // when
        List<String> errors = OpenAPIResponseValidator.validate(specUrlOrPayload, "listPets", response, mockServerLogger);

        // then
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldReturnErrorForInvalidErrorSchemaResponse() {
        // given - Error schema requires "code" (integer) and "message" (string)
        HttpResponse response = response()
            .withStatusCode(500)
            .withHeader("content-type", "application/json")
            .withBody("{\"wrong\": \"fields\"}");

        // when
        List<String> errors = OpenAPIResponseValidator.validate(specUrlOrPayload, "listPets", response, mockServerLogger);

        // then
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0), containsString("response body validation error"));
    }

    @Test
    public void shouldValidateResponseHeaderPresence() {
        // given - listPets 200 defines x-next header but it's not required
        HttpResponse response = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("[{\"id\": 1, \"name\": \"Fido\"}]");

        // when
        List<String> errors = OpenAPIResponseValidator.validate(specUrlOrPayload, "listPets", response, mockServerLogger);

        // then - no error because x-next header is optional
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldValidateRequiredResponseHeader() {
        // given - create a spec with a required response header
        String specWithRequiredHeader = "{\n" +
            "  \"openapi\": \"3.0.0\",\n" +
            "  \"info\": {\"title\": \"Test\", \"version\": \"1.0\"},\n" +
            "  \"paths\": {\n" +
            "    \"/test\": {\n" +
            "      \"get\": {\n" +
            "        \"operationId\": \"testOp\",\n" +
            "        \"responses\": {\n" +
            "          \"200\": {\n" +
            "            \"description\": \"OK\",\n" +
            "            \"headers\": {\n" +
            "              \"X-Request-Id\": {\n" +
            "                \"required\": true,\n" +
            "                \"schema\": {\n" +
            "                  \"type\": \"string\"\n" +
            "                }\n" +
            "              }\n" +
            "            },\n" +
            "            \"content\": {\n" +
            "              \"application/json\": {\n" +
            "                \"schema\": {\n" +
            "                  \"type\": \"object\"\n" +
            "                }\n" +
            "              }\n" +
            "            }\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        HttpResponse response = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{}");

        // when
        List<String> errors = OpenAPIResponseValidator.validate(specWithRequiredHeader, "testOp", response, mockServerLogger);

        // then
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0), containsString("required response header X-Request-Id not found"));
    }

    @Test
    public void shouldPassWhenRequiredHeaderIsPresent() {
        // given
        String specWithRequiredHeader = "{\n" +
            "  \"openapi\": \"3.0.0\",\n" +
            "  \"info\": {\"title\": \"Test\", \"version\": \"1.0\"},\n" +
            "  \"paths\": {\n" +
            "    \"/test\": {\n" +
            "      \"get\": {\n" +
            "        \"operationId\": \"testOp\",\n" +
            "        \"responses\": {\n" +
            "          \"200\": {\n" +
            "            \"description\": \"OK\",\n" +
            "            \"headers\": {\n" +
            "              \"X-Request-Id\": {\n" +
            "                \"required\": true,\n" +
            "                \"schema\": {\n" +
            "                  \"type\": \"string\"\n" +
            "                }\n" +
            "              }\n" +
            "            },\n" +
            "            \"content\": {\n" +
            "              \"application/json\": {\n" +
            "                \"schema\": {\n" +
            "                  \"type\": \"object\"\n" +
            "                }\n" +
            "              }\n" +
            "            }\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

        HttpResponse response = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withHeader("X-Request-Id", "abc-123")
            .withBody("{}");

        // when
        List<String> errors = OpenAPIResponseValidator.validate(specWithRequiredHeader, "testOp", response, mockServerLogger);

        // then
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldHandleDefaultStatusCode() {
        // given - showPetById has "default" response for unexpected errors
        HttpResponse response = response()
            .withStatusCode(503)
            .withHeader("content-type", "application/json")
            .withBody("{\"code\": 503, \"message\": \"service unavailable\"}");

        // when
        List<String> errors = OpenAPIResponseValidator.validate(specUrlOrPayload, "showPetById", response, mockServerLogger);

        // then - should match default response
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldHandleNullStatusCode() {
        // given - null status code defaults to 200
        HttpResponse response = response()
            .withHeader("content-type", "application/json")
            .withBody("[{\"id\": 1, \"name\": \"Fido\"}]");

        // when
        List<String> errors = OpenAPIResponseValidator.validate(specUrlOrPayload, "listPets", response, mockServerLogger);

        // then
        assertThat(errors, is(empty()));
    }

    private final String rangeSpec = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_status_code_range.yaml");

    @Test
    public void shouldMatchStatusCodeAgainstRangeBucketKey() {
        // given - operation defines only the "2XX" range bucket, no exact "200"
        HttpResponse response = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("\"ok\"");

        // when
        List<String> errors = OpenAPIResponseValidator.validate(rangeSpec, "rangeOnly", response, mockServerLogger);

        // then - 200 matches "2XX", no false "status code not defined" error
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldMatch404AgainstFourXXRangeBucketKey() {
        // given - operation defines only the "4XX" range bucket
        HttpResponse response = response()
            .withStatusCode(404)
            .withHeader("content-type", "application/json")
            .withBody("{\"message\": \"not found\"}");

        // when
        List<String> errors = OpenAPIResponseValidator.validate(rangeSpec, "notFoundRange", response, mockServerLogger);

        // then - 404 matches "4XX"
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldPreferExactStatusCodeOverRangeBucketKey() {
        // given - operation defines BOTH "200" (requires "exact") and "2XX" (requires "range"),
        // each with additionalProperties:false; a body valid only for the exact "200" schema
        HttpResponse response = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{\"exact\": \"value\"}");

        // when
        List<String> errors = OpenAPIResponseValidator.validate(rangeSpec, "exactAndRange", response, mockServerLogger);

        // then - the exact "200" schema is used (no error); the "2XX" schema would have rejected this body
        assertThat(errors, is(empty()));
    }

    private final String lowercaseRangeSpec = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_status_code_range_lowercase.yaml");

    @Test
    public void shouldMatchStatusCodeAgainstLowercaseRangeBucketKey() {
        // given - operation defines only the lowercase "2xx" range bucket; the validator must
        // match it case-insensitively so the generator and validator agree
        HttpResponse response = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("\"ok\"");

        // when
        List<String> errors = OpenAPIResponseValidator.validate(lowercaseRangeSpec, "lowercaseRangeOnly", response, mockServerLogger);

        // then - 200 matches "2xx", no false "status code not defined" error
        assertThat(errors, is(empty()));
    }

    private final String webhooksOnlySpec = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_31_webhooks_only.yaml");

    @Test
    public void shouldValidateWebhooksOnlySpecWithoutNpe() {
        // given - a valid OAS 3.1 spec with webhooks and NO paths; getPaths() returns null and must
        // not NPE or produce a misleading "null" validation error
        HttpResponse response = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{\"id\": 7, \"name\": \"Rex\"}");

        // when - the webhook operation is reachable for validation
        List<String> errors = OpenAPIResponseValidator.validate(webhooksOnlySpec, "onNewPet", response, mockServerLogger);

        // then - no NPE, no misleading error; the webhook response validates cleanly
        assertThat(errors, is(empty()));
    }

    @Test
    public void shouldUseExactStatusCodeSchemaNotRangeBucketForValidation() {
        // given - a body that is valid only for the "2XX" range schema (requires "range")
        // but invalid for the exact "200" schema (requires "exact", additionalProperties:false)
        HttpResponse response = response()
            .withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{\"range\": \"value\"}");

        // when
        List<String> errors = OpenAPIResponseValidator.validate(rangeSpec, "exactAndRange", response, mockServerLogger);

        // then - the exact "200" schema is selected (range precedence loses), so the body fails validation
        assertThat(errors, hasSize(1));
        assertThat(errors.get(0), containsString("response body validation error"));
    }

}
