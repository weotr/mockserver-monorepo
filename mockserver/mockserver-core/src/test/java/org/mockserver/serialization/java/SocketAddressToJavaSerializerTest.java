package org.mockserver.serialization.java;

import org.junit.Test;
import org.mockserver.model.SocketAddress;

import static org.mockserver.character.Character.NEW_LINE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * @author jamesdbloom
 */
public class SocketAddressToJavaSerializerTest {

    @Test
    public void shouldSerializeFullObjectWithForwardAsJava() {
        assertThat(
            new SocketAddressToJavaSerializer().serialize(1,
                SocketAddress
                    .socketAddress()
                    .withHost("some_host")
                    .withPort(9090)
                    .withScheme(SocketAddress.Scheme.HTTPS)
            )
        , is(NEW_LINE +
                "        new SocketAddress()" + NEW_LINE +
                "                .withHost(\"some_host\")" + NEW_LINE +
                "                .withPort(9090)" + NEW_LINE +
                "                .withScheme(SocketAddress.Scheme.HTTPS)"));
    }

}
