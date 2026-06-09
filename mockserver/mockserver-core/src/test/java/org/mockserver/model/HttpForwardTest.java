package org.mockserver.model;

import org.json.JSONException;
import org.junit.Test;
import org.skyscreamer.jsonassert.JSONAssert;

import java.util.concurrent.TimeUnit;

import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.HttpForward.forward;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsSame.sameInstance;
import static org.hamcrest.CoreMatchers.not;
/**
 * @author jamesdbloom
 */
@SuppressWarnings({"RedundantSuppression", "deprecation"})
public class HttpForwardTest {

    public void assertJsonEqualsNonStrict(String json1, String json2) {
        try {
            JSONAssert.assertEquals(json1, json2, false);
        } catch (JSONException jse) {
            throw new IllegalArgumentException(jse.getMessage());
        }
    }

    @Test
    public void shouldAlwaysCreateNewObject() {
        assertThat(forward(), is(forward()));
        assertThat(forward(), not(sameInstance(forward())));
    }

    @Test
    public void returnsPort() {
        assertThat(new HttpForward().withPort(9090).getPort(), is(new Integer(9090)));
    }

    @Test
    public void returnsHost() {
        assertThat(new HttpForward().withHost("some_host").getHost(), is("some_host"));
    }

    @Test
    public void returnsDelay() {
        assertThat(new HttpForward().withDelay(new Delay(TimeUnit.HOURS, 1)).getDelay(), is(new Delay(TimeUnit.HOURS, 1)));
        assertThat(new HttpForward().withDelay(TimeUnit.HOURS, 1).getDelay(), is(new Delay(TimeUnit.HOURS, 1)));
    }

    @Test
    public void returnsScheme() {
        assertThat(new HttpForward().withScheme(HttpForward.Scheme.HTTPS).getScheme(), is(HttpForward.Scheme.HTTPS));
    }

    @Test
    public void shouldReturnFormattedRequestInToString() {
        assertJsonEqualsNonStrict("{" + NEW_LINE +
                "  \"delay\" : {" + NEW_LINE +
                "    \"timeUnit\" : \"HOURS\"," + NEW_LINE +
                "    \"value\" : 1" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"host\" : \"some_host\"," + NEW_LINE +
                "  \"port\" : 9090," + NEW_LINE +
                "  \"scheme\" : \"HTTPS\"" + NEW_LINE +
                "}",
            forward()
                .withHost("some_host")
                .withPort(9090)
                .withScheme(HttpForward.Scheme.HTTPS)
                .withDelay(TimeUnit.HOURS, 1)
                .toString()
        );
    }
}
