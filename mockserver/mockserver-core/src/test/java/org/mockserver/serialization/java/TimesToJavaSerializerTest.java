package org.mockserver.serialization.java;

import org.junit.Test;
import org.mockserver.matchers.Times;

import static org.mockserver.character.Character.NEW_LINE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * @author jamesdbloom
 */
public class TimesToJavaSerializerTest {

    @Test
    public void shouldSerializeUnlimitedTimesAsJava() {
        assertThat(
            new TimesToJavaSerializer().serialize(1,
                Times.unlimited()
            )
        , is(NEW_LINE +
                "        Times.unlimited()"));
    }

    @Test
    public void shouldSerializeOnceTimesAsJava() {
        assertThat(
            new TimesToJavaSerializer().serialize(1,
                Times.once()
            )
        , is(NEW_LINE +
                "        Times.once()"));
    }

    @Test
    public void shouldSerializeExactlyTimesAsJava() {
        assertThat(
            new TimesToJavaSerializer().serialize(1,
                Times.exactly(2)
            )
        , is(NEW_LINE +
                "        Times.exactly(2)"));
    }

}
