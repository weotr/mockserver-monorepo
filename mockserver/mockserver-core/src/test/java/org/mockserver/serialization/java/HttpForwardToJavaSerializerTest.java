package org.mockserver.serialization.java;

import org.junit.Test;
import org.mockserver.model.HttpForward;

import java.util.concurrent.TimeUnit;

import static org.mockserver.character.Character.NEW_LINE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * @author jamesdbloom
 */
public class HttpForwardToJavaSerializerTest {

    @Test
    public void shouldSerializeFullObjectWithForwardAsJava() {
        assertThat(
            new HttpForwardToJavaSerializer().serialize(1,
                new HttpForward()
                    .withHost("some_host")
                    .withPort(9090)
                    .withScheme(HttpForward.Scheme.HTTPS)
                    .withDelay(TimeUnit.MILLISECONDS, 100)
            )
        , is(NEW_LINE +
                "        forward()" + NEW_LINE +
                "                .withHost(\"some_host\")" + NEW_LINE +
                "                .withPort(9090)" + NEW_LINE +
                "                .withScheme(HttpForward.Scheme.HTTPS)" + NEW_LINE +
                "                .withDelay(new Delay(TimeUnit.MILLISECONDS, 100))"));
    }

}
