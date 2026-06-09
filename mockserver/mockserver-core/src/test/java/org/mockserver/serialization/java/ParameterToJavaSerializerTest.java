package org.mockserver.serialization.java;

import org.junit.Test;
import org.mockserver.model.Parameter;

import java.util.Arrays;

import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.NottableOptionalString.optional;
import static org.mockserver.model.NottableString.not;
import static org.mockserver.model.NottableString.string;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ParameterToJavaSerializerTest {

    @Test
    public void shouldSerializeParameter() {
        assertThat(
            new ParameterToJavaSerializer().serialize(1, new Parameter("requestParameterNameOne", "requestParameterValueOneOne", "requestParameterValueOneTwo"))
        , is(NEW_LINE +
                "        new Parameter(\"requestParameterNameOne\", \"requestParameterValueOneOne\", \"requestParameterValueOneTwo\")"));
    }

    @Test
    public void shouldSerializeMultipleParameters() {
        assertThat(
            new ParameterToJavaSerializer().serializeAsJava(1, new Parameter("requestParameterNameOne", "requestParameterValueOneOne", "requestParameterValueOneTwo"), new Parameter("requestParameterNameTwo", "requestParameterValueTwo"))
        , is(NEW_LINE +
                "        new Parameter(\"requestParameterNameOne\", \"requestParameterValueOneOne\", \"requestParameterValueOneTwo\")," +
                NEW_LINE +
                "        new Parameter(\"requestParameterNameTwo\", \"requestParameterValueTwo\")"));
    }

    @Test
    public void shouldSerializeListOfParameters() {
        assertThat(
            new ParameterToJavaSerializer().serializeAsJava(1, Arrays.asList(
                new Parameter("requestParameterNameOne", "requestParameterValueOneOne", "requestParameterValueOneTwo"),
                new Parameter("requestParameterNameTwo", "requestParameterValueTwo")
            ))
        , is(NEW_LINE +
                "        new Parameter(\"requestParameterNameOne\", \"requestParameterValueOneOne\", \"requestParameterValueOneTwo\")," +
                NEW_LINE +
                "        new Parameter(\"requestParameterNameTwo\", \"requestParameterValueTwo\")"));
    }

    @Test
    public void shouldSerializeListOfNottedAndOptionalParameters() {
        assertThat(
            new ParameterToJavaSerializer().serializeAsJava(1, Arrays.asList(
                new Parameter(not("requestParameterNameOne"), not("requestParameterValueOneOne"), string("requestParameterValueOneTwo")),
                new Parameter(optional("requestParameterNameTwo"), not("requestParameterValueTwo"))
            ))
        , is(NEW_LINE +
                "        new Parameter(not(\"requestParameterNameOne\"), not(\"requestParameterValueOneOne\"), string(\"requestParameterValueOneTwo\"))," +
                NEW_LINE +
                "        new Parameter(optional(\"requestParameterNameTwo\"), not(\"requestParameterValueTwo\"))"));
    }

}