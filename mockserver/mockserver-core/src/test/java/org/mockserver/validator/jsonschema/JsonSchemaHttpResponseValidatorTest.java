package org.mockserver.validator.jsonschema;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.Is.is;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.validator.jsonschema.JsonSchemaHttpResponseValidator.jsonSchemaHttpResponseValidator;

/**
 * @author jamesdbloom
 */
public class JsonSchemaHttpResponseValidatorTest {

    // given
    private final JsonSchemaValidator jsonSchemaValidator = jsonSchemaHttpResponseValidator(new MockServerLogger());

    @Test
    public void shouldValidateValidCompleteRequestWithStringBody() {
        // when
        assertThat(jsonSchemaValidator.isValid("{" + NEW_LINE +
            "    \"statusCode\" : 304," + NEW_LINE +
            "    \"body\" : \"someBody\"," + NEW_LINE +
            "    \"cookies\" : [ {" + NEW_LINE +
            "      \"name\" : \"someCookieName\"," + NEW_LINE +
            "      \"value\" : \"someCookieValue\"" + NEW_LINE +
            "    } ]," + NEW_LINE +
            "    \"headers\" : [ {" + NEW_LINE +
            "      \"name\" : \"someHeaderName\"," + NEW_LINE +
            "      \"values\" : [ \"someHeaderValue\" ]" + NEW_LINE +
            "    } ]," + NEW_LINE +
            "    \"delay\" : {" + NEW_LINE +
            "      \"timeUnit\" : \"MICROSECONDS\"," + NEW_LINE +
            "      \"value\" : 1" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"connectionOptions\" : {" + NEW_LINE +
            "      \"suppressContentLengthHeader\" : true," + NEW_LINE +
            "      \"contentLengthHeaderOverride\" : 50," + NEW_LINE +
            "      \"suppressConnectionHeader\" : true," + NEW_LINE +
            "      \"keepAliveOverride\" : true," + NEW_LINE +
            "      \"closeSocket\" : true" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }"), is(""));
    }

    @Test
    public void shouldValidateValidShortHandJsonObjectBodyType() {
        // when
        assertThat(jsonSchemaValidator.isValid("{" + NEW_LINE +
                "    \"body\" : {\"foo\":\"bar\"}" + NEW_LINE +
                "  }"),
            is(""));
    }

    @Test
    public void shouldValidateValidShortHandJsonArrayBodyType() {
        // when
        assertThat(jsonSchemaValidator.isValid("{" + NEW_LINE +
                "    \"body\" : [{\"foo\":\"bar\"},{\"bar\":\"foo\"}]" + NEW_LINE +
                "  }"),
            is(""));
    }

    @Test
    public void shouldValidateInvalidBodyType() {
        // when
        String result = jsonSchemaValidator.isValid("{" + NEW_LINE +
            "    \"body\" : 1" + NEW_LINE +
            "  }");

        // then
        assertThat(result, startsWith("1 error:"));
        assertThat(result, containsString("$.body: should match one of its valid types"));
    }

    @Test
    public void shouldValidateInvalidExtraField() {
        // when
        String result = jsonSchemaValidator.isValid("{" + NEW_LINE +
            "    \"invalidField\" : {" + NEW_LINE +
            "      \"type\" : \"STRING\"," + NEW_LINE +
            "      \"value\" : \"someBody\"" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }");

        // then
        assertThat(result, startsWith("1 error:"));
        assertThat(result, containsString("$.invalidField: is not defined in the schema and the schema does not allow additional properties"));
    }

    @Test
    public void shouldValidateMultipleInvalidFieldTypes() {
        // when
        String result = jsonSchemaValidator.isValid("{" + NEW_LINE +
            "    \"statusCode\" : \"100\"," + NEW_LINE +
            "    \"body\" : false" + NEW_LINE +
            "  }");

        // then
        assertThat(result, startsWith("2 error"));
        assertThat(result, containsString("$.body: should match one of its valid types"));
        assertThat(result, containsString("$.statusCode: string found, integer expected"));
    }

    @Test
    public void shouldValidateInvalidListItemType() {
        // when
        String result = jsonSchemaValidator.isValid("{" + NEW_LINE +
            "    \"headers\" : [ \"invalidValueOne\", \"invalidValueTwo\" ]" + NEW_LINE +
            "  }");

        // then
        assertThat(result, startsWith("4 error"));
        assertThat(result, containsString("$.headers: array found, object expected"));
        assertThat(result, containsString("$.headers: should be valid to one and only one schema, but 0 are valid"));
        assertThat(result, containsString("$.headers[0]: string found, object expected"));
        assertThat(result, containsString("$.headers[1]: string found, object expected"));
    }

}
