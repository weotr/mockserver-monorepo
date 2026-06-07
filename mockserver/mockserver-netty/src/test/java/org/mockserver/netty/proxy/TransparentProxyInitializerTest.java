package org.mockserver.netty.proxy;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class TransparentProxyInitializerTest {

    private final MockServerLogger logger = new MockServerLogger(TransparentProxyInitializerTest.class);

    @Test
    public void shouldBeDisabledByDefault() {
        // given
        Configuration configuration = new Configuration();
        TransparentProxyInitializer initializer = new TransparentProxyInitializer(configuration, logger);

        // then
        assertThat(initializer.isEnabled(), is(false));
    }

    @Test
    public void shouldBeEnabledWhenConfigured() {
        // given
        Configuration configuration = new Configuration();
        configuration.transparentProxyEnabled(true);
        TransparentProxyInitializer initializer = new TransparentProxyInitializer(configuration, logger);

        // then
        assertThat(initializer.isEnabled(), is(true));
    }

    @Test
    public void shouldResolveTargetHostFromSimpleHeader() {
        // given
        Configuration configuration = new Configuration();
        configuration.transparentProxyEnabled(true);
        TransparentProxyInitializer initializer = new TransparentProxyInitializer(configuration, logger);

        // then
        assertThat(initializer.resolveTargetHost("example.com"), is("example.com"));
    }

    @Test
    public void shouldResolveTargetHostStrippingPort() {
        // given
        Configuration configuration = new Configuration();
        configuration.transparentProxyEnabled(true);
        TransparentProxyInitializer initializer = new TransparentProxyInitializer(configuration, logger);

        // then
        assertThat(initializer.resolveTargetHost("example.com:8080"), is("example.com"));
    }

    @Test
    public void shouldResolveTargetHostHandlingNull() {
        // given
        Configuration configuration = new Configuration();
        TransparentProxyInitializer initializer = new TransparentProxyInitializer(configuration, logger);

        // then
        assertThat(initializer.resolveTargetHost(null), is(nullValue()));
        assertThat(initializer.resolveTargetHost(""), is(nullValue()));
    }

    @Test
    public void shouldResolveTargetHostWithIpv6() {
        // given
        Configuration configuration = new Configuration();
        TransparentProxyInitializer initializer = new TransparentProxyInitializer(configuration, logger);

        // then
        assertThat(initializer.resolveTargetHost("[::1]:8080"), is("[::1]"));
        assertThat(initializer.resolveTargetHost("[::1]"), is("[::1]"));
    }

    @Test
    public void shouldResolveTargetPortFromHeader() {
        // given
        Configuration configuration = new Configuration();
        TransparentProxyInitializer initializer = new TransparentProxyInitializer(configuration, logger);

        // then
        assertThat(initializer.resolveTargetPort("example.com:8080", false), is(8080));
        assertThat(initializer.resolveTargetPort("example.com:443", true), is(443));
    }

    @Test
    public void shouldResolveTargetPortWithDefault() {
        // given
        Configuration configuration = new Configuration();
        TransparentProxyInitializer initializer = new TransparentProxyInitializer(configuration, logger);

        // then
        assertThat(initializer.resolveTargetPort("example.com", false), is(80));
        assertThat(initializer.resolveTargetPort("example.com", true), is(443));
    }

    @Test
    public void shouldResolveTargetPortWithNullHeader() {
        // given
        Configuration configuration = new Configuration();
        TransparentProxyInitializer initializer = new TransparentProxyInitializer(configuration, logger);

        // then
        assertThat(initializer.resolveTargetPort(null, false), is(80));
        assertThat(initializer.resolveTargetPort(null, true), is(443));
    }

    @Test
    public void shouldResolveTargetPortWithIpv6() {
        // given
        Configuration configuration = new Configuration();
        TransparentProxyInitializer initializer = new TransparentProxyInitializer(configuration, logger);

        // then
        assertThat(initializer.resolveTargetPort("[::1]:9090", false), is(9090));
        assertThat(initializer.resolveTargetPort("[::1]", false), is(80));
        assertThat(initializer.resolveTargetPort("[::1]", true), is(443));
    }

    @Test
    public void shouldHandleInvalidPortGracefully() {
        // given
        Configuration configuration = new Configuration();
        TransparentProxyInitializer initializer = new TransparentProxyInitializer(configuration, logger);

        // then - invalid port falls back to default
        assertThat(initializer.resolveTargetPort("example.com:abc", false), is(80));
        assertThat(initializer.resolveTargetPort("example.com:abc", true), is(443));
    }
}
