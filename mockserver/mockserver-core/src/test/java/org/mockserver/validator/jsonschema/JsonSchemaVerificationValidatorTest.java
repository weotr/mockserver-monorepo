package org.mockserver.validator.jsonschema;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.Is.is;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.validator.jsonschema.JsonSchemaHttpRequestValidator.jsonSchemaHttpRequestValidator;
import static org.mockserver.validator.jsonschema.JsonSchemaVerificationValidator.jsonSchemaVerificationValidator;

/**
 * @author jamesdbloom
 */
public class JsonSchemaVerificationValidatorTest {

    private final JsonSchemaValidator jsonSchemaValidator = jsonSchemaHttpRequestValidator(new MockServerLogger());
    private final JsonSchemaValidator verificationSchemaValidator = jsonSchemaVerificationValidator(new MockServerLogger());

    @Test
    public void shouldValidateValidCompleteRequestWithStringBody() {
        // when
        assertThat(jsonSchemaValidator.isValid("{" + NEW_LINE +
            "    \"method\" : \"someMethod\"," + NEW_LINE +
            "    \"path\" : \"somePath\"," + NEW_LINE +
            "    \"queryStringParameters\" : [ {" + NEW_LINE +
            "      \"name\" : \"queryStringParameterNameOne\"," + NEW_LINE +
            "      \"values\" : [ \"queryStringParameterValueOne_One\", \"queryStringParameterValueOne_Two\" ]" + NEW_LINE +
            "    }, {" + NEW_LINE +
            "      \"name\" : \"queryStringParameterNameTwo\"," + NEW_LINE +
            "      \"values\" : [ \"queryStringParameterValueTwo_One\" ]" + NEW_LINE +
            "    } ]," + NEW_LINE +
            "    \"body\" : {" + NEW_LINE +
            "      \"type\" : \"STRING\"," + NEW_LINE +
            "      \"string\" : \"someBody\"" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"cookies\" : [ {" + NEW_LINE +
            "      \"name\" : \"someCookieName\"," + NEW_LINE +
            "      \"value\" : \"someCookieValue\"" + NEW_LINE +
            "    } ]," + NEW_LINE +
            "    \"headers\" : [ {" + NEW_LINE +
            "      \"name\" : \"someHeaderName\"," + NEW_LINE +
            "      \"values\" : [ \"someHeaderValue\" ]" + NEW_LINE +
            "    } ]," + NEW_LINE +
            "    \"secure\" : true," + NEW_LINE +
            "    \"keepAlive\" : false," + NEW_LINE +
            "    \"protocol\" : \"HTTP_2\"," + NEW_LINE +
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
            "    \"method\" : 100," + NEW_LINE +
            "    \"path\" : false" + NEW_LINE +
            "  }");

        // then
        assertThat(result, startsWith("4 error"));
        assertThat(result, containsString("$.method: integer found, string expected"));
        assertThat(result, containsString("$.method: should be valid to one and only one schema, but 0 are valid"));
        assertThat(result, containsString("$.path: boolean found, string expected"));
        assertThat(result, containsString("$.path: should be valid to one and only one schema, but 0 are valid"));
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

    // --- Verification schema with httpResponse ---

    @Test
    public void shouldValidateVerificationWithHttpRequestAndHttpResponse() {
        // when
        assertThat(verificationSchemaValidator.isValid("{" + NEW_LINE +
            "    \"httpRequest\" : {" + NEW_LINE +
            "      \"path\" : \"/some/path\"" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"httpResponse\" : {" + NEW_LINE +
            "      \"statusCode\" : 200" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"times\" : {" + NEW_LINE +
            "      \"atLeast\" : 1" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }"), is(""));
    }

    @Test
    public void shouldValidateVerificationWithHttpResponseOnly() {
        // when
        assertThat(verificationSchemaValidator.isValid("{" + NEW_LINE +
            "    \"httpResponse\" : {" + NEW_LINE +
            "      \"statusCode\" : 200" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }"), is(""));
    }

    @Test
    public void shouldValidateVerificationWithHttpResponseAndBody() {
        // when
        assertThat(verificationSchemaValidator.isValid("{" + NEW_LINE +
            "    \"httpResponse\" : {" + NEW_LINE +
            "      \"statusCode\" : 200," + NEW_LINE +
            "      \"body\" : \"some response body\"" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }"), is(""));
    }

}
