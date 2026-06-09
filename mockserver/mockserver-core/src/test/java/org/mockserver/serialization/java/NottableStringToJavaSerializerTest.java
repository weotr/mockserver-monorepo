package org.mockserver.serialization.java;

import org.junit.Test;

import static org.mockserver.model.NottableString.string;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * @author jamesdbloom
 */
public class NottableStringToJavaSerializerTest {

    @Test
    public void shouldSerializeNottedString() {
        assertThat(
            NottableStringToJavaSerializer.serialize(string("some_value", true), false)
        , is("not(\"some_value\")"));
    }

    @Test
    public void shouldSerializeString() {
        assertThat(
            NottableStringToJavaSerializer.serialize(string("some_value", false), false)
        , is("\"some_value\""));
    }

}
