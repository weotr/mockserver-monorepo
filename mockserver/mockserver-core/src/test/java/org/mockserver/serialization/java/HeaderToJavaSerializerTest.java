package org.mockserver.serialization.java;

import org.junit.Test;
import org.mockserver.model.Header;

import java.util.Arrays;

import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.NottableOptionalString.optional;
import static org.mockserver.model.NottableString.not;
import static org.mockserver.model.NottableString.string;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class HeaderToJavaSerializerTest {

    @Test
    public void shouldSerializeHeader() {
        assertThat(
            new HeaderToJavaSerializer().serialize(1, new Header("requestHeaderNameOne", "requestHeaderValueOneOne", "requestHeaderValueOneTwo"))
        , is(NEW_LINE +
                "        new Header(\"requestHeaderNameOne\", \"requestHeaderValueOneOne\", \"requestHeaderValueOneTwo\")"));
    }

    @Test
    public void shouldSerializeMultipleHeaders() {
        assertThat(
            new HeaderToJavaSerializer().serializeAsJava(1, new Header("requestHeaderNameOne", "requestHeaderValueOneOne", "requestHeaderValueOneTwo"), new Header("requestHeaderNameTwo", "requestHeaderValueTwo"))
        , is(NEW_LINE +
                "        new Header(\"requestHeaderNameOne\", \"requestHeaderValueOneOne\", \"requestHeaderValueOneTwo\")," +
                NEW_LINE +
                "        new Header(\"requestHeaderNameTwo\", \"requestHeaderValueTwo\")"));
    }

    @Test
    public void shouldSerializeListOfHeaders() {
        assertThat(
            new HeaderToJavaSerializer().serializeAsJava(1, Arrays.asList(
                new Header("requestHeaderNameOne", "requestHeaderValueOneOne", "requestHeaderValueOneTwo"),
                new Header("requestHeaderNameTwo", "requestHeaderValueTwo")
            ))
        , is(NEW_LINE +
                "        new Header(\"requestHeaderNameOne\", \"requestHeaderValueOneOne\", \"requestHeaderValueOneTwo\")," +
                NEW_LINE +
                "        new Header(\"requestHeaderNameTwo\", \"requestHeaderValueTwo\")"));
    }

    @Test
    public void shouldSerializeListOfNottedAndOptionalHeaders() {
        assertThat(
            new HeaderToJavaSerializer().serializeAsJava(1, Arrays.asList(
                new Header(not("requestHeaderNameOne"), not("requestHeaderValueOneOne"), string("requestHeaderValueOneTwo")),
                new Header(optional("requestHeaderNameTwo"), not("requestHeaderValueTwo"))
            ))
        , is(NEW_LINE +
                "        new Header(not(\"requestHeaderNameOne\"), not(\"requestHeaderValueOneOne\"), string(\"requestHeaderValueOneTwo\"))," +
                NEW_LINE +
                "        new Header(optional(\"requestHeaderNameTwo\"), not(\"requestHeaderValueTwo\"))"));
    }

}