package org.mockserver.validator.jsonschema;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.Is.is;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.validator.jsonschema.JsonSchemaOpenAPIExpectationValidator.jsonSchemaOpenAPIExpectationValidator;

/**
 * @author jamesdbloom
 */
public class JsonSchemaOpenAPIExpectationValidatorTest {

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
        String result = jsonSchemaValidator.isValid("{}");

        // then
        assertThat(result, startsWith("1 error:"));
        assertThat(result, containsString("$.specUrlOrPayload: is missing, but is required, if specifying OpenAPI request matcher"));
    }

    @Test
    public void shouldValidateInvalidOpenAPIExpectationWithExtraField() {
        // when
        String result = jsonSchemaValidator.isValid("{" + NEW_LINE +
            "    \"specUrlOrPayload\" : \"https://example.com/spec.json\"," + NEW_LINE +
            "    \"invalidField\" : \"invalidValue\"" + NEW_LINE +
            "  }");

        // then
        assertThat(result, startsWith("1 error:"));
        assertThat(result, containsString("$.invalidField: is not defined in the schema and the schema does not allow additional properties"));
    }

    @Test
    public void shouldValidateInvalidOpenAPIExpectationWithWrongSpecType() {
        // when
        String result = jsonSchemaValidator.isValid("{" + NEW_LINE +
            "    \"specUrlOrPayload\" : 123" + NEW_LINE +
            "  }");

        // then
        assertThat(result, startsWith("1 error:"));
        assertThat(result, containsString("$.specUrlOrPayload: integer found, string expected"));
    }

    @Test
    public void shouldValidateInvalidOpenAPIExpectationWithWrongContextPathPrefixType() {
        // when
        String result = jsonSchemaValidator.isValid("{" + NEW_LINE +
            "    \"specUrlOrPayload\" : \"https://example.com/spec.json\"," + NEW_LINE +
            "    \"contextPathPrefix\" : 123" + NEW_LINE +
            "  }");

        // then
        assertThat(result, startsWith("1 error:"));
        assertThat(result, containsString("$.contextPathPrefix: integer found, string expected"));
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
        assertThat(result, containsString("JsonParseException"));
    }

}
