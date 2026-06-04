package org.mockserver.validator.jsonschema;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.Is.is;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.validator.jsonschema.JsonSchemaExpectationIdValidator.jsonSchemaExpectationIdValidator;

/**
 * @author jamesdbloom
 */
public class JsonSchemaExpectationIdValidatorTest {

    private final JsonSchemaValidator jsonSchemaValidator = jsonSchemaExpectationIdValidator(new MockServerLogger());

    // valid inputs

    @Test
    public void shouldValidateValidExpectationId() {
        // when
        assertThat(jsonSchemaValidator.isValid("{" + NEW_LINE +
            "    \"id\" : \"someExpectationId\"" + NEW_LINE +
            "  }"), is(""));
    }

    @Test
    public void shouldValidateValidExpectationIdWithEmptyStringId() {
        // when
        assertThat(jsonSchemaValidator.isValid("{" + NEW_LINE +
            "    \"id\" : \"\"" + NEW_LINE +
            "  }"), is(""));
    }

    // invalid inputs

    @Test
    public void shouldValidateInvalidExpectationIdMissingRequiredField() {
        // when
        String result = jsonSchemaValidator.isValid("{}");

        // then
        assertThat(result, startsWith("1 error:"));
        assertThat(result, containsString("$.id: is missing but it is required"));
    }

    @Test
    public void shouldValidateInvalidExpectationIdWithWrongIdType() {
        // when
        String result = jsonSchemaValidator.isValid("{" + NEW_LINE +
            "    \"id\" : 123" + NEW_LINE +
            "  }");

        // then
        assertThat(result, startsWith("1 error:"));
        assertThat(result, containsString("$.id: integer found, string expected"));
    }

    @Test
    public void shouldValidateInvalidExpectationIdWithExtraField() {
        // when
        String result = jsonSchemaValidator.isValid("{" + NEW_LINE +
            "    \"id\" : \"someExpectationId\"," + NEW_LINE +
            "    \"extraField\" : \"extraValue\"" + NEW_LINE +
            "  }");

        // then
        assertThat(result, startsWith("1 error:"));
        assertThat(result, containsString("$.extraField: is not defined in the schema and the schema does not allow additional properties"));
    }

    @Test
    public void shouldValidateInvalidExpectationIdWithBooleanId() {
        // when
        String result = jsonSchemaValidator.isValid("{" + NEW_LINE +
            "    \"id\" : true" + NEW_LINE +
            "  }");

        // then
        assertThat(result, startsWith("1 error:"));
        assertThat(result, containsString("$.id: boolean found, string expected"));
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
