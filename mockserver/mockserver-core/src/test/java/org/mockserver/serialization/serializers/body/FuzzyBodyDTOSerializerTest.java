package org.mockserver.serialization.serializers.body;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.Test;
import org.mockserver.model.FuzzyBody;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.BodyDTO;
import org.mockserver.serialization.model.FuzzyBodyDTO;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class FuzzyBodyDTOSerializerTest {

    @Test
    public void shouldSerializeFuzzyBodyDTO() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new FuzzyBodyDTO(new FuzzyBody("some_value"))),
            is("{\"type\":\"FUZZY\",\"fuzzy\":\"some_value\",\"threshold\":0.8}"));
    }

    @Test
    public void shouldSerializeFuzzyBodyDTOWithThresholdAndIgnoreCase() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new FuzzyBodyDTO(new FuzzyBody("some_value", 0.6d, true))),
            is("{\"type\":\"FUZZY\",\"fuzzy\":\"some_value\",\"threshold\":0.6,\"ignoreCase\":true}"));
    }

    @Test
    public void shouldSerializeFuzzyBodyDTOWithNot() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new FuzzyBodyDTO(new FuzzyBody("some_value"), true)),
            is("{\"not\":true,\"type\":\"FUZZY\",\"fuzzy\":\"some_value\",\"threshold\":0.8}"));
    }

    @Test
    public void shouldSerializeFuzzyBodyDTOWithOptional() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new FuzzyBodyDTO(new FuzzyBody("some_value")).withOptional(true)),
            is("{\"optional\":true,\"type\":\"FUZZY\",\"fuzzy\":\"some_value\",\"threshold\":0.8}"));
    }

    @Test
    public void shouldRoundTripFuzzyBodyThroughDeserialization() throws JsonProcessingException {
        // given
        FuzzyBody original = new FuzzyBody("some_value", 0.65d, true);
        String json = ObjectMapperFactory.createObjectMapper().writeValueAsString(new FuzzyBodyDTO(original));

        // when
        BodyDTO deserialized = ObjectMapperFactory.createObjectMapper().readValue(json, BodyDTO.class);

        // then
        assertThat(deserialized, is(new FuzzyBodyDTO(original)));
        assertThat(deserialized.buildObject(), is(original));
    }

    @Test
    public void shouldDeserializeFuzzyBodyFromJsonWithExplicitFields() throws JsonProcessingException {
        // when
        BodyDTO deserialized = ObjectMapperFactory.createObjectMapper().readValue(
            "{\"type\":\"FUZZY\",\"fuzzy\":\"some_value\",\"threshold\":0.6,\"ignoreCase\":true}", BodyDTO.class);

        // then
        assertThat(deserialized.buildObject(), is(new FuzzyBody("some_value", 0.6d, true)));
    }

    @Test
    public void shouldDeserializeFuzzyBodyFromFieldNameAlone() throws JsonProcessingException {
        // when - the "fuzzy" field name alone selects the FUZZY type, default threshold applies
        BodyDTO deserialized = ObjectMapperFactory.createObjectMapper().readValue(
            "{\"fuzzy\":\"some_value\"}", BodyDTO.class);

        // then
        assertThat(deserialized.buildObject(), is(new FuzzyBody("some_value")));
    }
}
