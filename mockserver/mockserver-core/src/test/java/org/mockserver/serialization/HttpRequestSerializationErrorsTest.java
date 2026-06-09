package org.mockserver.serialization;

import com.fasterxml.jackson.databind.ObjectWriter;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.serialization.model.HttpRequestDTO;

import java.io.IOException;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.character.Character.NEW_LINE;
import static org.junit.Assert.fail;

/**
 * @author jamesdbloom
 */
public class HttpRequestSerializationErrorsTest {

    @Rule
    public final ExpectedException thrown = ExpectedException.none();
    @Mock
    private ObjectWriter objectWriter;
    @InjectMocks
    private HttpRequestSerializer httpRequestSerializer;

    @Before
    public void setupTestFixture() {
        httpRequestSerializer = spy(new HttpRequestSerializer(new MockServerLogger()));

        openMocks(this);
    }

    @Test
    public void shouldHandleExceptionWhileSerializingObject() throws IOException {
        // given
        thrown.expect(RuntimeException.class);
        thrown.expectMessage("Exception while serializing HttpRequest to JSON with value { }");
        // and
        when(objectWriter.writeValueAsString(any(HttpRequestDTO.class))).thenThrow(new RuntimeException("TEST EXCEPTION"));

        // when
        httpRequestSerializer.serialize(new HttpRequest());
    }

    @Test
    public void shouldHandleExceptionWhileSerializingArray() throws IOException {
        // given
        thrown.expect(RuntimeException.class);
        thrown.expectMessage("Exception while serializing HttpRequest to JSON with value [{ }]");
        // and
        when(objectWriter.writeValueAsString(any(HttpRequestDTO[].class))).thenThrow(new RuntimeException("TEST EXCEPTION"));

        // when
        httpRequestSerializer.serialize(new HttpRequest[]{new HttpRequest()});
    }

    @Test
    @SuppressWarnings("RedundantArrayCreation")
    public void shouldHandleNullAndEmptyWhileSerializingArray() {
        // when
        assertThat( httpRequestSerializer.serialize(new HttpRequest[]{}), is("[]"));
        assertThat( httpRequestSerializer.serialize((HttpRequest[]) null), is("[]"));
    }

    @Test
    public void shouldHandleExceptionWhileDeserializingObject() {
        try {
            // when
            httpRequestSerializer.deserialize("requestBytes");
            fail("expected exception to be thrown");
        } catch (IllegalArgumentException iae) {
            // then
            assertThat(iae.getMessage(), containsString("incorrect request json format for:"));
            assertThat(iae.getMessage(), containsString("requestBytes"));
            assertThat(iae.getMessage(), containsString("schema validation errors:"));
            assertThat(iae.getMessage(), containsString("Unrecognized token"));
        }
    }

    @Test
    public void shouldHandleExceptionWhileDeserializingArray() {
        // when
        try {
            httpRequestSerializer.deserializeArray("requestBytes");
            fail("expected exception");
        } catch (IllegalArgumentException iae) {
            // then
            assertThat(iae.getMessage(), containsString("Unrecognized token"));
            assertThat(iae.getMessage(), containsString("requestBytes"));
        }
    }

    @Test
    public void shouldValidateInputForArray() {
        // given
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage("1 error:" + NEW_LINE +
            " - a request or request array is required but value was \"\"");

        // when
        assertArrayEquals(new HttpRequest[]{}, httpRequestSerializer.deserializeArray(""));
    }
}
