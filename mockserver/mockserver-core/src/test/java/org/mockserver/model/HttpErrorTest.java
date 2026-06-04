package org.mockserver.model;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.HttpError.error;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsSame.sameInstance;
import static org.hamcrest.CoreMatchers.not;
/**
 * @author jamesdbloom
 */
public class HttpErrorTest {

    @Test
    @SuppressWarnings("AccessStaticViaInstance")
    public void shouldAlwaysCreateNewObject() {
        assertThat(error(), is(error()));
        assertThat(error(), not(sameInstance(error())));
    }

    @Test
    public void returnsDelay() {
        assertThat(new HttpError().withDelay(TimeUnit.DAYS, 10).getDelay(), is(new Delay(TimeUnit.DAYS, 10)));
    }

    @Test
    public void returnsDropConnection() {
        assertThat(new HttpError().withDropConnection(true).getDropConnection(), is(Boolean.TRUE));
    }

    @Test
    public void returnsResponseBytes() {
        assertArrayEquals("some_bytes".getBytes(UTF_8), new HttpError().withResponseBytes("some_bytes".getBytes(UTF_8)).getResponseBytes());
    }

    @Test
    public void shouldReturnFormattedRequestInToString() {
        assertThat(error()
                .withDelay(TimeUnit.DAYS, 10)
                .withDropConnection(true)
                .withResponseBytes("some_bytes".getBytes(UTF_8))
                .toString(), is("{" + NEW_LINE +
                "  \"delay\" : {" + NEW_LINE +
                "    \"timeUnit\" : \"DAYS\"," + NEW_LINE +
                "    \"value\" : 10" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"dropConnection\" : true," + NEW_LINE +
                "  \"responseBytes\" : \"c29tZV9ieXRlcw==\"" + NEW_LINE +
                "}"));
    }
}
