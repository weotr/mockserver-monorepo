package org.mockserver.serialization;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.serialization.model.HttpRequestDTO;
import org.mockserver.serialization.model.HttpResponseDTO;
import org.mockserver.serialization.model.VerificationDTO;
import org.mockserver.serialization.model.VerificationTimesDTO;
import org.mockserver.verify.Verification;
import org.mockserver.verify.VerificationTimes;

import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * @author jamesdbloom
 */
public class VerificationSerializerSchemaValidationTest {

    @Test
    public void shouldDeserializeCompleteObject() {
        // given
        String requestBytes = "{" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"method\" : \"GET\"," + NEW_LINE +
            "    \"path\" : \"somepath\"" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"times\" : {" + NEW_LINE +
            "    \"atLeast\" : 2," + NEW_LINE +
            "    \"atMost\" : 3" + NEW_LINE +
            "  }" + NEW_LINE +
            "}";

        // when
        Verification verification = new VerificationSerializer(new MockServerLogger()).deserialize(requestBytes);

        // then
        assertThat( verification, is(new VerificationDTO()
            .setHttpRequest(new HttpRequestDTO(request().withMethod("GET").withPath("somepath")))
            .setTimes(new VerificationTimesDTO(VerificationTimes.between(2, 3)))
            .buildObject()));
    }

    @Test
    public void shouldDeserializePartialObject() {
        // given
        String requestBytes = "{" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "  }" + NEW_LINE +
            "}";

        // when
        Verification verification = new VerificationSerializer(new MockServerLogger()).deserialize(requestBytes);

        // then
        assertThat( verification, is(new VerificationDTO()
            .setHttpRequest(new HttpRequestDTO(request()))
            .buildObject()));
    }

    @Test
    public void shouldSerializeCompleteObject() {
        // when
        String jsonExpectation = new VerificationSerializer(new MockServerLogger()).serialize(
            new VerificationDTO()
                .setHttpRequest(new HttpRequestDTO(request().withMethod("GET").withPath("somepath")))
                .setTimes(new VerificationTimesDTO(VerificationTimes.between(2, 3)))
                .buildObject()
        );

        // then
        assertThat( jsonExpectation, is("{" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"method\" : \"GET\"," + NEW_LINE +
            "    \"path\" : \"somepath\"" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"times\" : {" + NEW_LINE +
            "    \"atLeast\" : 2," + NEW_LINE +
            "    \"atMost\" : 3" + NEW_LINE +
            "  }" + NEW_LINE +
            "}"));
    }

    @Test
    public void shouldSerializePartialObject() {
        // when
        String jsonExpectation = new VerificationSerializer(new MockServerLogger()).serialize(
            new VerificationDTO()
                .setHttpRequest(new HttpRequestDTO(request()))
                .buildObject()
        );

        // then
        assertThat( jsonExpectation, is("{" + NEW_LINE +
            "  \"httpRequest\" : { }," + NEW_LINE +
            "  \"times\" : {" + NEW_LINE +
            "    \"atLeast\" : 1," + NEW_LINE +
            "    \"atMost\" : 1" + NEW_LINE +
            "  }" + NEW_LINE +
            "}"));
    }

    @Test
    public void shouldDeserializeObjectWithResponse() {
        // given
        String requestBytes = "{" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"path\" : \"/some/path\"" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"httpResponse\" : {" + NEW_LINE +
            "    \"statusCode\" : 200" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"times\" : {" + NEW_LINE +
            "    \"atLeast\" : 1" + NEW_LINE +
            "  }" + NEW_LINE +
            "}";

        // when
        Verification verification = new VerificationSerializer(new MockServerLogger()).deserialize(requestBytes);

        // then
        assertThat(verification.getHttpRequest(), is(request().withPath("/some/path")));
        assertThat(verification.getHttpResponse(), is(response().withStatusCode(200)));
    }

    @Test
    public void shouldDeserializeObjectWithResponseOnly() {
        // given
        String requestBytes = "{" + NEW_LINE +
            "  \"httpResponse\" : {" + NEW_LINE +
            "    \"statusCode\" : 200" + NEW_LINE +
            "  }" + NEW_LINE +
            "}";

        // when
        Verification verification = new VerificationSerializer(new MockServerLogger()).deserialize(requestBytes);

        // then
        assertThat(verification.getHttpResponse(), is(response().withStatusCode(200)));
    }

    @Test
    public void shouldSerializeObjectWithResponse() {
        // when
        String jsonExpectation = new VerificationSerializer(new MockServerLogger()).serialize(
            new VerificationDTO()
                .setHttpRequest(new HttpRequestDTO(request().withPath("/some/path")))
                .setHttpResponse(new HttpResponseDTO(response().withStatusCode(200)))
                .setTimes(new VerificationTimesDTO(VerificationTimes.atLeast(1)))
                .buildObject()
        );

        // then
        assertThat(jsonExpectation, is("{" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"path\" : \"/some/path\"" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"httpResponse\" : {" + NEW_LINE +
            "    \"statusCode\" : 200" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"times\" : {" + NEW_LINE +
            "    \"atLeast\" : 1" + NEW_LINE +
            "  }" + NEW_LINE +
            "}"));
    }
}
