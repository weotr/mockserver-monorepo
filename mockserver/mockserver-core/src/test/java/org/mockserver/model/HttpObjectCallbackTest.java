package org.mockserver.model;

import org.junit.Test;

import static org.mockserver.character.Character.NEW_LINE;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
/**
 * @author jamesdbloom
 */
public class HttpObjectCallbackTest {

    @Test
    public void returnsCallbackClass() {
        assertThat(new HttpObjectCallback().withClientId("some_client_id").getClientId(), is("some_client_id"));
    }

    @Test
    public void shouldReturnFormattedRequestInToString() {
        assertThat(new HttpObjectCallback()
                .withClientId("some_client_id")
                .toString(), is("{" + NEW_LINE +
                "  \"clientId\" : \"some_client_id\"" + NEW_LINE +
                "}"));
    }
}
