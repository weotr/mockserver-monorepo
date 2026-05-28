package org.mockserver.validator.jsonschema;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.validator.jsonschema.JsonSchemaOpenAPIExpectationValidator.jsonSchemaOpenAPIExpectationValidator;
import static org.mockserver.validator.jsonschema.JsonSchemaValidator.OPEN_API_SPECIFICATION_URL;

/**
 * @author jamesdbloom
 */
public class JsonSchemaOpenAPIExpectationValidatorIntegrationTest {

    private final JsonSchemaValidator jsonSchemaValidator = jsonSchemaOpenAPIExpectationValidator(new MockServerLogger());

    // valid inputs

    @Test
    public void shouldValidateValidOpenAPIExpectationWithStringSpec() {
        // when
        assertThat(jsonSchemaValidator.isValid("{" + NEW_LINE +
            "    \"specUrlOrPayload\" : \"https://raw.githubusercontent.com/mock-server/mockserver/master/mockserver-integration-testing/src/main/resources/org/mockserver/openapi/openapi_petstore_example.json\"" + NEW_LINE +
            "  }"), is(""));
    }

    @Test
    public void shouldValidateValidOpenAPIExpectationWithObjectSpec() {
        // when
        assertThat(jsonSchemaValidator.isValid("{" + NEW_LINE +
            "    \"specUrlOrPayload\" : {" + NEW_LINE +
            "      \"openapi\" : \"3.0.0\"" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }"), is(""));
    }

    @Test
    public void shouldValidateValidOpenAPIExpectationWithOperationsAndResponses() {
        // when
        assertThat(jsonSchemaValidator.isValid("{" + NEW_LINE +
            "    \"specUrlOrPayload\" : \"https://example.com/spec.json\"," + NEW_LINE +
            "    \"operationsAndResponses\" : {" + NEW_LINE +
            "      \"listPets\" : \"200\"," + NEW_LINE +
            "      \"createPets\" : \"201\"" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }"), is(""));
    }

    @Test
    public void shouldValidateValidOpenAPIExpectationWithOperationsAndResponsesAsObjects() {
        // when
        assertThat(jsonSchemaValidator.isValid("{" + NEW_LINE +
            "    \"specUrlOrPayload\" : \"https://example.com/spec.json\"," + NEW_LINE +
            "    \"operationsAndResponses\" : {" + NEW_LINE +
            "      \"listPets\" : {" + NEW_LINE +
            "        \"statusCode\" : \"200\"," + NEW_LINE +
            "        \"exampleName\" : \"exampleOne\"" + NEW_LINE +
            "      }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }"), is(""));
    }

    @Test
    public void shouldValidateValidOpenAPIExpectationWithContextPathPrefix() {
        // when
        assertThat(jsonSchemaValidator.isValid("{" + NEW_LINE +
            "    \"specUrlOrPayload\" : \"https://example.com/spec.json\"," + NEW_LINE +
            "    \"contextPathPrefix\" : \"/api/v1\"" + NEW_LINE +
            "  }"), is(""));
    }

    @Test
    public void shouldValidateValidOpenAPIExpectationWithAllFields() {
        // when
        assertThat(jsonSchemaValidator.isValid("{" + NEW_LINE +
            "    \"specUrlOrPayload\" : \"https://example.com/spec.json\"," + NEW_LINE +
            "    \"operationsAndResponses\" : {" + NEW_LINE +
            "      \"listPets\" : \"200\"" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"contextPathPrefix\" : \"/api/v1\"" + NEW_LINE +
            "  }"), is(""));
    }

    // invalid inputs

    @Test
    public void shouldValidateInvalidOpenAPIExpectationMissingRequired() {
        // when - the validator rewrites specUrlOrPayload missing messages
        assertThat(jsonSchemaValidator.isValid("{}"),
            is(
                "1 error:" + NEW_LINE +
                    " - $.specUrlOrPayload: is missing, but is required, if specifying OpenAPI request matcher" + NEW_LINE +
                    NEW_LINE +
                    OPEN_API_SPECIFICATION_URL
            ));
    }

    @Test
    public void shouldValidateInvalidOpenAPIExpectationWithExtraField() {
        // when
        assertThat(jsonSchemaValidator.isValid("{" + NEW_LINE +
                "    \"specUrlOrPayload\" : \"https://example.com/spec.json\"," + NEW_LINE +
                "    \"invalidField\" : \"invalidValue\"" + NEW_LINE +
                "  }"),
            is(
                "1 error:" + NEW_LINE +
                    " - $.invalidField: is not defined in the schema and the schema does not allow additional properties" + NEW_LINE +
                    NEW_LINE +
                    OPEN_API_SPECIFICATION_URL
            ));
    }

    @Test
    public void shouldValidateInvalidOpenAPIExpectationWithWrongSpecType() {
        // when - specUrlOrPayload uses anyOf (string | object), so two individual errors are reported
        assertThat(jsonSchemaValidator.isValid("{" + NEW_LINE +
                "    \"specUrlOrPayload\" : 123" + NEW_LINE +
                "  }"),
            is(
                "2 errors:" + NEW_LINE +
                    " - $.specUrlOrPayload: should be valid to any of the schemas object" + NEW_LINE +
                    " - $.specUrlOrPayload: should be valid to any of the schemas string" + NEW_LINE +
                    NEW_LINE +
                    OPEN_API_SPECIFICATION_URL
            ));
    }

    @Test
    public void shouldValidateInvalidOpenAPIExpectationWithWrongContextPathPrefixType() {
        // when
        assertThat(jsonSchemaValidator.isValid("{" + NEW_LINE +
                "    \"specUrlOrPayload\" : \"https://example.com/spec.json\"," + NEW_LINE +
                "    \"contextPathPrefix\" : 123" + NEW_LINE +
                "  }"),
            is(
                "1 error:" + NEW_LINE +
                    " - $.contextPathPrefix: integer found, string expected" + NEW_LINE +
                    NEW_LINE +
                    OPEN_API_SPECIFICATION_URL
            ));
    }

    @Test
    public void shouldValidateEmptyBody() {
        // when - empty string returns empty (no validation)
        assertThat(jsonSchemaValidator.isValid(""), is(""));
    }

    @Test
    public void shouldValidateMalformedJson() {
        // when
        String result = jsonSchemaValidator.isValid("not json");
        assertThat(result.contains("JsonParseException"), is(true));
    }

}
