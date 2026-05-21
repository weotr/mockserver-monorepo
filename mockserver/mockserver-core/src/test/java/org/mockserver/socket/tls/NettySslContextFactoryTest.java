package org.mockserver.socket.tls;

import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Verifies that the http2Enabled configuration property controls whether HTTP/2 is advertised via
 * ALPN - the lever that lets a user force HTTP/2 capable clients to fall back to HTTP/1.1 (#2260).
 */
public class NettySslContextFactoryTest {

    @Test
    public void shouldAdvertiseHttp2ViaAlpnByDefault() {
        // given
        SslContext serverSslContext = new NettySslContextFactory(configuration(), new MockServerLogger(), true).createServerSslContext();

        // then
        assertThat(serverSslContext.applicationProtocolNegotiator().protocols(), hasItem(ApplicationProtocolNames.HTTP_2));
        assertThat(serverSslContext.applicationProtocolNegotiator().protocols(), hasItem(ApplicationProtocolNames.HTTP_1_1));
    }

    @Test
    public void shouldNotAdvertiseHttp2ViaAlpnWhenHttp2Disabled() {
        // given
        Configuration configuration = configuration().http2Enabled(false);

        // when
        SslContext serverSslContext = new NettySslContextFactory(configuration, new MockServerLogger(), true).createServerSslContext();

        // then - only http/1.1 is advertised so HTTP/2 capable clients negotiate HTTP/1.1
        assertThat(serverSslContext.applicationProtocolNegotiator().protocols(), not(hasItem(ApplicationProtocolNames.HTTP_2)));
        assertThat(serverSslContext.applicationProtocolNegotiator().protocols(), hasItem(ApplicationProtocolNames.HTTP_1_1));
    }

    @Test
    public void shouldNotAdvertiseHttp2ViaAlpnOnClientContextWhenHttp2Disabled() {
        // given
        Configuration configuration = configuration().http2Enabled(false);

        // when
        SslContext clientSslContext = new NettySslContextFactory(configuration, new MockServerLogger(), false).createClientSslContext(true, true);

        // then - even when the caller requests HTTP/2 the disabled property strips h2 from ALPN
        assertThat(clientSslContext.applicationProtocolNegotiator().protocols(), not(hasItem(ApplicationProtocolNames.HTTP_2)));
    }
}
