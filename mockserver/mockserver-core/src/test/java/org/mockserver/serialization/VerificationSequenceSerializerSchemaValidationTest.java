package org.mockserver.serialization;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.ExpectationId;
import org.mockserver.serialization.model.HttpRequestDTO;
import org.mockserver.serialization.model.VerificationSequenceDTO;
import org.mockserver.verify.VerificationSequence;

import java.util.Arrays;
import java.util.Collections;

import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.HttpRequest.request;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * @author jamesdbloom
 */
public class VerificationSequenceSerializerSchemaValidationTest {

    @Test
    public void shouldDeserializeCompleteObjectWithRequests() {
        // given
        String requestBytes = "{" + NEW_LINE +
            "  \"httpRequests\" : [ {" + NEW_LINE +
            "    \"path\" : \"some_path_one\"," + NEW_LINE +
            "    \"body\" : \"some_body_one\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"path\" : \"some_body_multiple\"," + NEW_LINE +
            "    \"body\" : \"some_body_multiple\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"path\" : \"some_path_three\"," + NEW_LINE +
            "    \"body\" : \"some_body_three\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"path\" : \"some_body_multiple\"," + NEW_LINE +
            "    \"body\" : \"some_body_multiple\"" + NEW_LINE +
            "  } ]" + NEW_LINE +
            "}";

        // when
        VerificationSequence verificationSequence = new VerificationSequenceSerializer(new MockServerLogger()).deserialize(requestBytes);

        // then
        assertThat( verificationSequence, is(new VerificationSequenceDTO()
            .setHttpRequests(Arrays.asList(
                new HttpRequestDTO(request("some_path_one").withBody("some_body_one")),
                new HttpRequestDTO(request("some_body_multiple").withBody("some_body_multiple")),
                new HttpRequestDTO(request("some_path_three").withBody("some_body_three")),
                new HttpRequestDTO(request("some_body_multiple").withBody("some_body_multiple"))
            ))
            .buildObject()));
    }

    @Test
    public void shouldDeserializeCompleteObjectWithExpectationIds() {
        // given
        String requestBytes = "{" + NEW_LINE +
            "  \"expectationIds\" : [ {" + NEW_LINE +
            "    \"id\" : \"one\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"id\" : \"two\"" + NEW_LINE +
            "  } ]" + NEW_LINE +
            "}";

        // when
        VerificationSequence verificationSequence = new VerificationSequenceSerializer(new MockServerLogger()).deserialize(requestBytes);

        // then
        assertThat( verificationSequence, is(new VerificationSequenceDTO()
            .setExpectationIds(Arrays.asList(
                new ExpectationId().withId("one"),
                new ExpectationId().withId("two")
            ))
            .buildObject()));
    }

    @Test
    public void shouldDeserializeEmptyObject() {
        // given
        String requestBytes = "{" + NEW_LINE +
            "  \"httpRequests\" : [ ]" + NEW_LINE +
            "}";

        // when
        VerificationSequence verificationSequence = new VerificationSequenceSerializer(new MockServerLogger()).deserialize(requestBytes);

        // then
        assertThat( verificationSequence, is(new VerificationSequenceDTO()
            .setHttpRequests(Collections.emptyList())
            .buildObject()));
    }

    @Test
    public void shouldDeserializePartialObject() {
        // given
        String requestBytes = "{" + NEW_LINE +
            "  \"httpRequests\" : [ {" + NEW_LINE +
            "    \"path\" : \"some_path_one\"" + NEW_LINE +
            "  } ]" + NEW_LINE +
            "}";

        // when
        VerificationSequence verificationSequence = new VerificationSequenceSerializer(new MockServerLogger()).deserialize(requestBytes);

        // then
        assertThat( verificationSequence, is(new VerificationSequenceDTO()
            .setHttpRequests(Collections.singletonList(
                new HttpRequestDTO(request("some_path_one"))
            ))
            .buildObject()));
    }

    @Test
    public void shouldSerializeCompleteObject() {
        // when
        String jsonExpectation = new VerificationSequenceSerializer(new MockServerLogger()).serialize(
            new VerificationSequenceDTO()
                .setHttpRequests(Arrays.asList(
                    new HttpRequestDTO(request("some_path_one").withBody("some_body_one")),
                    new HttpRequestDTO(request("some_body_multiple").withBody("some_body_multiple")),
                    new HttpRequestDTO(request("some_path_three").withBody("some_body_three")),
                    new HttpRequestDTO(request("some_body_multiple").withBody("some_body_multiple"))
                ))
                .buildObject()
        );

        // then
        assertThat( jsonExpectation, is("{" + NEW_LINE +
            "  \"httpRequests\" : [ {" + NEW_LINE +
            "    \"path\" : \"some_path_one\"," + NEW_LINE +
            "    \"body\" : \"some_body_one\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"path\" : \"some_body_multiple\"," + NEW_LINE +
            "    \"body\" : \"some_body_multiple\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"path\" : \"some_path_three\"," + NEW_LINE +
            "    \"body\" : \"some_body_three\"" + NEW_LINE +
            "  }, {" + NEW_LINE +
            "    \"path\" : \"some_body_multiple\"," + NEW_LINE +
            "    \"body\" : \"some_body_multiple\"" + NEW_LINE +
            "  } ]" + NEW_LINE +
            "}"));
    }

    @Test
    public void shouldSerializePartialObject() {
        // when
        String jsonExpectation = new VerificationSequenceSerializer(new MockServerLogger()).serialize(
            new VerificationSequenceDTO()
                .setHttpRequests(Collections.singletonList(
                    new HttpRequestDTO(request("some_path_one").withBody("some_body_one"))
                ))
                .buildObject()
        );

        // then
        assertThat( jsonExpectation, is("{" + NEW_LINE +
            "  \"httpRequests\" : [ {" + NEW_LINE +
            "    \"path\" : \"some_path_one\"," + NEW_LINE +
            "    \"body\" : \"some_body_one\"" + NEW_LINE +
            "  } ]" + NEW_LINE +
            "}"));
    }

    @Test
    public void shouldSerializeEmptyObject() {
        // when
        String jsonExpectation = new VerificationSequenceSerializer(new MockServerLogger()).serialize(
            new VerificationSequenceDTO()
                .setHttpRequests(Collections.emptyList())
                .buildObject()
        );

        // then
        assertThat( jsonExpectation, is("{ }"));
    }
}
