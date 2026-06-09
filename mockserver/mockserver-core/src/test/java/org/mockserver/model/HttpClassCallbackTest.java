package org.mockserver.model;

import org.junit.Test;

import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.HttpClassCallback.callback;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsSame.sameInstance;
import static org.hamcrest.CoreMatchers.not;
/**
 * @author jamesdbloom
 */
public class HttpClassCallbackTest {

    @Test
    @SuppressWarnings("AccessStaticViaInstance")
    public void shouldAlwaysCreateNewObject() {
        assertThat(callback(), is(callback()));
        assertThat(callback(), not(sameInstance(callback())));
    }

    @Test
    public void returnsCallbackClass() {
        assertThat(new HttpClassCallback().withCallbackClass("some_class").getCallbackClass(), is("some_class"));
        assertThat(callback().withCallbackClass("some_class").getCallbackClass(), is("some_class"));
        assertThat(callback("some_class").getCallbackClass(), is("some_class"));
    }

    @Test
    public void shouldReturnFormattedRequestInToString() {
        assertThat(callback()
                .withCallbackClass("some_class")
                .toString(), is("{" + NEW_LINE +
                "  \"callbackClass\" : \"some_class\"" + NEW_LINE +
                "}"));
    }
}
