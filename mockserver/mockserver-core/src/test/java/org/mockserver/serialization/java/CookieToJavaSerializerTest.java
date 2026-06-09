package org.mockserver.serialization.java;

import org.junit.Test;
import org.mockserver.model.Cookie;

import java.util.Arrays;

import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.NottableOptionalString.optional;
import static org.mockserver.model.NottableString.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class CookieToJavaSerializerTest {

    @Test
    public void shouldSerializeCookie() {
        assertThat(
            new CookieToJavaSerializer().serialize(1, new Cookie("requestCookieNameOne", "requestCookieValueOne"))
        , is(NEW_LINE +
                "        new Cookie(\"requestCookieNameOne\", \"requestCookieValueOne\")"));
    }

    @Test
    public void shouldSerializeMultipleCookies() {
        assertThat(
            new CookieToJavaSerializer().serializeAsJava(1, new Cookie("requestCookieNameOne", "requestCookieValueOne"), new Cookie("requestCookieNameTwo", "requestCookieValueTwo"))
        , is(NEW_LINE +
                "        new Cookie(\"requestCookieNameOne\", \"requestCookieValueOne\")," +
                NEW_LINE +
                "        new Cookie(\"requestCookieNameTwo\", \"requestCookieValueTwo\")"));
    }

    @Test
    public void shouldSerializeListOfCookies() {
        assertThat(
            new CookieToJavaSerializer().serializeAsJava(1, Arrays.asList(
                new Cookie("requestCookieNameOne", "requestCookieValueOne"),
                new Cookie("requestCookieNameTwo", "requestCookieValueTwo")
            ))
        , is(NEW_LINE +
                "        new Cookie(\"requestCookieNameOne\", \"requestCookieValueOne\")," +
                NEW_LINE +
                "        new Cookie(\"requestCookieNameTwo\", \"requestCookieValueTwo\")"));
    }

    @Test
    public void shouldSerializeListOfNottedAndOptionalCookies() {
        assertThat(
            new CookieToJavaSerializer().serializeAsJava(1, Arrays.asList(
                new Cookie(not("requestCookieNameOne"), "requestCookieValueOne"),
                new Cookie(optional("requestCookieNameTwo"), "requestCookieValueTwo")
            ))
        , is(NEW_LINE +
                "        new Cookie(not(\"requestCookieNameOne\"), \"requestCookieValueOne\")," +
                NEW_LINE +
                "        new Cookie(optional(\"requestCookieNameTwo\"), \"requestCookieValueTwo\")"));
    }

}