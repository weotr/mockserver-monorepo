package org.mockserver.validator.jsonschema;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.validator.jsonschema.JsonSchemaExpectationIdValidator.jsonSchemaExpectationIdValidator;
import static org.mockserver.validator.jsonschema.JsonSchemaValidator.OPEN_API_SPECIFICATION_URL;

/**
 * @author jamesdbloom
 */
public class JsonSchemaExpectationIdValidatorIntegrationTest {

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
        assertThat(jsonSchemaValidator.isValid("{}"),
            is(
                "1 error:" + NEW_LINE +
                    " - $.id: is missing but it is required" + NEW_LINE +
                    NEW_LINE +
                    OPEN_API_SPECIFICATION_URL
            ));
    }

    @Test
    public void shouldValidateInvalidExpectationIdWithWrongIdType() {
        // when
        assertThat(jsonSchemaValidator.isValid("{" + NEW_LINE +
                "    \"id\" : 123" + NEW_LINE +
                "  }"),
            is(
                "1 error:" + NEW_LINE +
                    " - $.id: integer found, string expected" + NEW_LINE +
                    NEW_LINE +
                    OPEN_API_SPECIFICATION_URL
            ));
    }

    @Test
    public void shouldValidateInvalidExpectationIdWithExtraField() {
        // when
        assertThat(jsonSchemaValidator.isValid("{" + NEW_LINE +
                "    \"id\" : \"someExpectationId\"," + NEW_LINE +
                "    \"extraField\" : \"extraValue\"" + NEW_LINE +
                "  }"),
            is(
                "1 error:" + NEW_LINE +
                    " - $.extraField: is not defined in the schema and the schema does not allow additional properties" + NEW_LINE +
                    NEW_LINE +
                    OPEN_API_SPECIFICATION_URL
            ));
    }

    @Test
    public void shouldValidateInvalidExpectationIdWithBooleanId() {
        // when
        assertThat(jsonSchemaValidator.isValid("{" + NEW_LINE +
                "    \"id\" : true" + NEW_LINE +
                "  }"),
            is(
                "1 error:" + NEW_LINE +
                    " - $.id: boolean found, string expected" + NEW_LINE +
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
