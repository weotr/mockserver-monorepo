package org.mockserver.validator.jsonschema;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.Is.is;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.validator.jsonschema.JsonSchemaHttpRequestAndHttpResponseValidator.jsonSchemaHttpRequestAndHttpResponseValidator;

/**
 * @author jamesdbloom
 */
public class JsonSchemaHttpRequestAndHttpResponseValidatorTest {

    private final JsonSchemaValidator jsonSchemaValidator = jsonSchemaHttpRequestAndHttpResponseValidator(new MockServerLogger());

    private final String completeSerialisedHttpRequestAndHttpResponse = "{" + NEW_LINE +
        "  \"httpRequest\" : {" + NEW_LINE +
        "    \"method\" : \"GET\"," + NEW_LINE +
        "    \"path\" : \"somepath\"," + NEW_LINE +
        "    \"queryStringParameters\" : {" + NEW_LINE +
        "      \"queryStringParameterNameOne\" : [ \"queryStringParameterValueOne_One\", \"queryStringParameterValueOne_Two\" ]," + NEW_LINE +
        "      \"queryStringParameterNameTwo\" : [ \"queryStringParameterValueTwo_One\" ]" + NEW_LINE +
        "    }," + NEW_LINE +
        "    \"headers\" : {" + NEW_LINE +
        "      \"headerName\" : [ \"headerValue\" ]" + NEW_LINE +
        "    }," + NEW_LINE +
        "    \"cookies\" : {" + NEW_LINE +
        "      \"cookieName\" : \"cookieValue\"" + NEW_LINE +
        "    }," + NEW_LINE +
        "    \"secure\" : true," + NEW_LINE +
        "    \"keepAlive\" : true," + NEW_LINE +
        "    \"protocol\" : \"HTTP_2\"," + NEW_LINE +
        "    \"socketAddress\" : {" + NEW_LINE +
        "      \"host\" : \"someHost\"," + NEW_LINE +
        "      \"port\" : 1234," + NEW_LINE +
        "      \"scheme\" : \"HTTPS\"" + NEW_LINE +
        "    }," + NEW_LINE +
        "    \"body\" : \"someBody\"" + NEW_LINE +
        "  }," + NEW_LINE +
        "  \"httpResponse\" : {" + NEW_LINE +
        "    \"statusCode\" : 123," + NEW_LINE +
        "    \"reasonPhrase\" : \"randomPhrase\"," + NEW_LINE +
        "    \"headers\" : {" + NEW_LINE +
        "      \"headerName\" : [ \"headerValue\" ]" + NEW_LINE +
        "    }," + NEW_LINE +
        "    \"cookies\" : {" + NEW_LINE +
        "      \"cookieName\" : \"cookieValue\"" + NEW_LINE +
        "    }," + NEW_LINE +
        "    \"body\" : \"somebody\"," + NEW_LINE +
        "    \"delay\" : {" + NEW_LINE +
        "      \"timeUnit\" : \"MICROSECONDS\"," + NEW_LINE +
        "      \"value\" : 3" + NEW_LINE +
        "    }" + NEW_LINE +
        "  }" + NEW_LINE +
        "}";

    @Test
    public void shouldValidateValidCompleteRequestFromRawJson() {
        // when
        assertThat(jsonSchemaValidator.isValid(completeSerialisedHttpRequestAndHttpResponse), is(""));
    }

    @Test
    public void shouldValidateInvalidBodyType() {
        // when
        String result = jsonSchemaValidator.isValid("{" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"body\" : 1" + NEW_LINE +
            "  }" + NEW_LINE +
            "  }");

        // then
        assertThat(result, startsWith("1 error:"));
        assertThat(result, containsString("$.httpRequest.body: should match one of its valid types"));
    }

    @Test
    public void shouldValidateInvalidExtraField() {
        // when
        String result = jsonSchemaValidator.isValid("{" + NEW_LINE +
            "  \"invalidField\" : {" + NEW_LINE +
            "    \"type\" : \"STRING\"," + NEW_LINE +
            "    \"value\" : \"someBody\"" + NEW_LINE +
            "  }" + NEW_LINE +
            "  }");

        // then
        assertThat(result, startsWith("1 error:"));
        assertThat(result, containsString("$.invalidField: is not defined in the schema and the schema does not allow additional properties"));
    }

    @Test
    public void shouldValidateMultipleInvalidFieldTypes() {
        // when
        String result = jsonSchemaValidator.isValid("{" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"body\" : 1" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"method\" : 100," + NEW_LINE +
            "  \"path\" : false" + NEW_LINE +
            "  }");

        // then
        assertThat(result, startsWith("3 error"));
        assertThat(result, containsString("$.httpRequest.body: should match one of its valid types"));
        assertThat(result, containsString("$.method: is not defined in the schema and the schema does not allow additional properties"));
        assertThat(result, containsString("$.path: is not defined in the schema and the schema does not allow additional properties"));
    }

}
