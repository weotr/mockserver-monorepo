package org.mockserver.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.serialization.model.VerificationDTO;
import org.mockserver.verify.Verification;

import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.character.Character.NEW_LINE;
import static org.junit.Assert.fail;

/**
 * @author jamesdbloom
 */
public class VerificationSerializationErrorsTest {

    @Rule
    public final ExpectedException thrown = ExpectedException.none();
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ObjectWriter objectWriter;
    @InjectMocks
    private VerificationSerializer verificationSerializer;

    @Before
    public void setupTestFixture() {
        verificationSerializer = spy(new VerificationSerializer(new MockServerLogger()));

        openMocks(this);
    }

    @Test
    public void shouldHandleExceptionWhileSerializingObject() throws IOException {
        // given
        thrown.expect(RuntimeException.class);
        thrown.expectMessage("Exception while serializing verification to JSON with value {" + NEW_LINE +
            "  \"times\" : {" + NEW_LINE +
            "    \"atLeast\" : 1" + NEW_LINE +
            "  }" + NEW_LINE +
            "}");
        // and
        when(objectWriter.writeValueAsString(any(VerificationDTO.class))).thenThrow(new RuntimeException("TEST EXCEPTION"));

        // when
        verificationSerializer.serialize(new Verification());
    }

    @Test
    public void shouldHandleExceptionWhileDeserializingObject() {
        try {
            // when
            verificationSerializer.deserialize("requestBytes");
            fail("expected exception");
        } catch (IllegalArgumentException iae) {
            // then
            assertThat(iae.getMessage(), containsString("incorrect verification json format for:"));
            assertThat(iae.getMessage(), containsString("requestBytes"));
            assertThat(iae.getMessage(), containsString("schema validation errors:"));
            assertThat(iae.getMessage(), containsString("Unrecognized token"));
        }
    }

    @Test
    public void shouldHandleExceptionWhileDeserializingObjectWithExpectationIdsAndRequests() {
        // given
        String requestBytes = "{" + NEW_LINE +
            "  \"expectationId\" : {" + NEW_LINE +
            "    \"id\" : \"one\"" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"path\" : \"some_path_one\"," + NEW_LINE +
            "    \"body\" : \"some_body_one\"" + NEW_LINE +
            "  }" + NEW_LINE +
            "}";
        try {
            // when
            new VerificationSerializer(new MockServerLogger()).deserialize(requestBytes);
            fail("expected exception");
        } catch (IllegalArgumentException iae) {
            // then
            assertThat(iae.getMessage(), containsString("incorrect verification json format for:"));
            assertThat(iae.getMessage(), containsString("schema validation errors:"));
            assertThat(iae.getMessage(), containsString("1 error:"));
            assertThat(iae.getMessage(), containsString("should be valid to one and only one schema, but 2 are valid"));
        }
    }

}
