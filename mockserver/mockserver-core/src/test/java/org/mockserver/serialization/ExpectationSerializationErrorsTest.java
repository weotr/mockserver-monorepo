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
import org.mockserver.mock.Expectation;
import org.mockserver.validator.jsonschema.JsonSchemaExpectationValidator;

import static org.mockito.Mockito.spy;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.character.Character.NEW_LINE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * @author jamesdbloom
 */
public class ExpectationSerializationErrorsTest {

    @Rule
    public final ExpectedException thrown = ExpectedException.none();
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ObjectWriter objectWriter;
    @Mock
    private JsonArraySerializer jsonArraySerializer;
    @Mock
    private JsonSchemaExpectationValidator expectationValidator;
    @InjectMocks
    private ExpectationSerializer expectationSerializer;

    @Before
    public void setupTestFixture() {
        expectationSerializer = spy(new ExpectationSerializer(new MockServerLogger()));

        openMocks(this);
    }

    @Test
    @SuppressWarnings("RedundantArrayCreation")
    public void shouldHandleNullAndEmptyWhileSerializingArray() {
        // when
        assertThat( expectationSerializer.serialize(new Expectation[]{}), is("[]"));
        assertThat( expectationSerializer.serialize((Expectation[]) null), is("[]"));
    }

    @Test
    public void shouldValidateInputForObject() {
        // given
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage("1 error:" + NEW_LINE + " - an expectation is required but value was \"\"");
        // when
        expectationSerializer.deserialize("");
    }

    @Test
    public void shouldValidateInputForArray() {
        // given
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage("1 error:" + NEW_LINE + " - an expectation or expectation array is required but value was \"\"");
        // when
        expectationSerializer.deserializeArray("", false);
    }
}
