package org.mockserver.serialization.java;

import org.junit.Test;
import org.mockserver.model.HttpClassCallback;

import java.util.concurrent.TimeUnit;

import static org.mockserver.character.Character.NEW_LINE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * @author jamesdbloom
 */
public class HttpClassCallbackToJavaSerializerTest {

    @Test
    public void shouldSerializeFullObjectWithCallbackAsJava() {
        assertThat(
            new HttpClassCallbackToJavaSerializer().serialize(1,
                new HttpClassCallback()
                    .withCallbackClass("some_class")
                    .withDelay(TimeUnit.MILLISECONDS, 100)
            )
        , is(NEW_LINE +
                "        callback()" + NEW_LINE +
                "                .withCallbackClass(\"some_class\")" + NEW_LINE +
                "                .withDelay(new Delay(TimeUnit.MILLISECONDS, 100))"));
    }

}
