package org.mockserver.validator.jsonschema;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.validator.jsonschema.JsonSchemaExpectationValidator.jsonSchemaExpectationValidator;

/**
 * Control-plane JSON Schema validation for the conditional (if-then-else)
 * request matcher. Guards against the schema rejecting its own new shape.
 *
 * @author jamesdbloom
 */
public class JsonSchemaConditionalRequestDefinitionValidatorTest {

    private final JsonSchemaValidator jsonSchemaValidator = jsonSchemaExpectationValidator(new MockServerLogger());

    @Test
    public void shouldValidateValidConditionalRequestWithIfThenElse() {
        // when
        String result = jsonSchemaValidator.isValid("{" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"if\" : { \"method\" : \"GET\" }," + NEW_LINE +
            "    \"then\" : { \"path\" : \"/admin\" }," + NEW_LINE +
            "    \"else\" : { \"path\" : \"/public\" }" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"httpResponse\" : {" + NEW_LINE +
            "    \"statusCode\" : 200" + NEW_LINE +
            "  }" + NEW_LINE +
            "}");

        // then
        assertThat(result, is(""));
    }

    @Test
    public void shouldValidateValidConditionalRequestWithIfThenOnly() {
        // when
        String result = jsonSchemaValidator.isValid("{" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"if\" : { \"method\" : \"GET\" }," + NEW_LINE +
            "    \"then\" : { \"path\" : \"/admin\" }" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"httpResponse\" : {" + NEW_LINE +
            "    \"statusCode\" : 200" + NEW_LINE +
            "  }" + NEW_LINE +
            "}");

        // then
        assertThat(result, is(""));
    }

    @Test
    public void shouldValidateValidNestedConditionalRequest() {
        // when
        String result = jsonSchemaValidator.isValid("{" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"if\" : { \"method\" : \"GET\" }," + NEW_LINE +
            "    \"then\" : {" + NEW_LINE +
            "      \"if\" : { \"headers\" : [ { \"name\" : \"X-Env\", \"values\" : [ \"prod\" ] } ] }," + NEW_LINE +
            "      \"then\" : { \"path\" : \"/admin\" }" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"httpResponse\" : {" + NEW_LINE +
            "    \"statusCode\" : 200" + NEW_LINE +
            "  }" + NEW_LINE +
            "}");

        // then
        assertThat(result, is(""));
    }

    @Test
    public void shouldRejectConditionalRequestWithUnknownProperty() {
        // when - an unknown property inside the conditional matcher object
        String result = jsonSchemaValidator.isValid("{" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"if\" : { \"method\" : \"GET\" }," + NEW_LINE +
            "    \"unknownConditionalField\" : \"someValue\"" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"httpResponse\" : {" + NEW_LINE +
            "    \"statusCode\" : 200" + NEW_LINE +
            "  }" + NEW_LINE +
            "}");

        // then - the conditional branch must reject the unknown property
        assertThat(result, not(is("")));
        assertThat(result, containsString("unknownConditionalField"));
    }
}
