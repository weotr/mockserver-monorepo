package org.mockserver.serialization.java;

import org.junit.Test;
import org.mockserver.matchers.TimeToLive;

import java.util.concurrent.TimeUnit;

import static org.mockserver.character.Character.NEW_LINE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * @author jamesdbloom
 */
public class TimeToLiveToJavaSerializerTest {

    @Test
    public void shouldSerializeUnlimitedTimeToLiveAsJava() {
        assertThat(
            new TimeToLiveToJavaSerializer().serialize(1,
                TimeToLive.unlimited()
            )
        , is(NEW_LINE +
                "        TimeToLive.unlimited()"));
    }

    @Test
    public void shouldSerializeExactlyTimeToLiveAsJava() {
        assertThat(
            new TimeToLiveToJavaSerializer().serialize(1,
                TimeToLive.exactly(TimeUnit.SECONDS, 100L)
            )
        , is(NEW_LINE +
                "        TimeToLive.exactly(TimeUnit.SECONDS, 100L)"));
    }

}
