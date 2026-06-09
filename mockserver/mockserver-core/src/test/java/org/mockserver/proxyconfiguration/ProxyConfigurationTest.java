package org.mockserver.proxyconfiguration;

import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;

import java.net.InetSocketAddress;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.proxyConfiguration;

public class ProxyConfigurationTest {

    private InetSocketAddress originalForwardHttpProxy;
    private InetSocketAddress originalForwardHttpsProxy;
    private InetSocketAddress originalForwardSocksProxy;
    private String originalForwardProxyAuthenticationUsername;
    private String originalForwardProxyAuthenticationPassword;

    @Before
    public void recordOriginalPropertyValues() {
        originalForwardHttpProxy = ConfigurationProperties.forwardHttpProxy();
        originalForwardHttpsProxy = ConfigurationProperties.forwardHttpsProxy();
        originalForwardSocksProxy = ConfigurationProperties.forwardSocksProxy();
        originalForwardProxyAuthenticationUsername = ConfigurationProperties.forwardProxyAuthenticationUsername();
        originalForwardProxyAuthenticationPassword = ConfigurationProperties.forwardProxyAuthenticationPassword();
    }

    @After
    public void restoreOriginalPropertyValues() {
        ConfigurationProperties.forwardHttpProxy(originalForwardHttpProxy != null ? originalForwardHttpProxy.toString() : "");
        ConfigurationProperties.forwardHttpsProxy(originalForwardHttpsProxy != null ? originalForwardHttpsProxy.toString() : "");
        ConfigurationProperties.forwardSocksProxy(originalForwardSocksProxy != null ? originalForwardSocksProxy.toString() : "");
        ConfigurationProperties.forwardProxyAuthenticationUsername(originalForwardProxyAuthenticationUsername);
        ConfigurationProperties.forwardProxyAuthenticationPassword(originalForwardProxyAuthenticationPassword);
    }

    @Test
    public void shouldConfigureForwardHttpProxy() {
        // given
        String proxyAddress = "127.0.0.1:1090";

        // when
        assertThat(ConfigurationProperties.forwardHttpProxy(), nullValue());
        ConfigurationProperties.forwardHttpProxy(proxyAddress);

        // then
        assertThat(ConfigurationProperties.forwardHttpProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardHttpProxy"), is(proxyAddress));
        assertThat(proxyConfiguration(configuration()), equalTo(ImmutableList.of(proxyConfiguration(ProxyConfiguration.Type.HTTP, proxyAddress, "", ""))));
    }

    @Test
    public void shouldConfigureForwardHttpsProxy() {
        // given
        String proxyAddress = "127.0.0.1:1090";

        // when
        assertThat(ConfigurationProperties.forwardHttpsProxy(), nullValue());
        ConfigurationProperties.forwardHttpsProxy(proxyAddress);

        // then
        assertThat(ConfigurationProperties.forwardHttpsProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardHttpsProxy"), is(proxyAddress));
        assertThat(proxyConfiguration(configuration()), equalTo(ImmutableList.of(proxyConfiguration(ProxyConfiguration.Type.HTTPS, proxyAddress, "", ""))));
    }

    @Test
    public void shouldConfigureForwardHttpAndHttpsProxy() {
        // given
        String proxyAddress = "127.0.0.1:1090";

        // when
        assertThat(ConfigurationProperties.forwardHttpProxy(), nullValue());
        ConfigurationProperties.forwardHttpProxy(proxyAddress);
        assertThat(ConfigurationProperties.forwardHttpsProxy(), nullValue());
        ConfigurationProperties.forwardHttpsProxy(proxyAddress);

        // then
        assertThat(ConfigurationProperties.forwardHttpProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardHttpProxy"), is(proxyAddress));
        assertThat(ConfigurationProperties.forwardHttpsProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardHttpsProxy"), is(proxyAddress));
        assertThat(proxyConfiguration(configuration()), equalTo(ImmutableList.of(
                proxyConfiguration(ProxyConfiguration.Type.HTTP, proxyAddress, "", ""),
                proxyConfiguration(ProxyConfiguration.Type.HTTPS, proxyAddress, "", "")
        )));
    }

    @Test
    public void shouldConfigureForwardHttpsProxyWithAuthentication() {
        // given
        String proxyAddress = "127.0.0.1:1090";
        String userName = "userName";
        String password = "password";

        // when
        assertThat(ConfigurationProperties.forwardHttpsProxy(), nullValue());
        ConfigurationProperties.forwardHttpsProxy(proxyAddress);
        assertThat(ConfigurationProperties.forwardProxyAuthenticationUsername(), equalTo(""));
        ConfigurationProperties.forwardProxyAuthenticationUsername(userName);
        assertThat(ConfigurationProperties.forwardProxyAuthenticationPassword(), equalTo(""));
        ConfigurationProperties.forwardProxyAuthenticationPassword(password);

        // then
        assertThat(ConfigurationProperties.forwardHttpsProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardHttpsProxy"), is(proxyAddress));
        assertThat(ConfigurationProperties.forwardProxyAuthenticationUsername(), is(userName));
        assertThat(System.getProperty("mockserver.forwardProxyAuthenticationUsername"), is(userName));
        assertThat(ConfigurationProperties.forwardProxyAuthenticationPassword(), is(password));
        assertThat(System.getProperty("mockserver.forwardProxyAuthenticationPassword"), is(password));
        assertThat(proxyConfiguration(configuration()), equalTo(ImmutableList.of(proxyConfiguration(ProxyConfiguration.Type.HTTPS, proxyAddress, userName, password))));
    }

    @Test
    public void shouldConfigureForwardSocksProxy() {
        // given
        String proxyAddress = "127.0.0.1:1090";

        // when
        assertThat(ConfigurationProperties.forwardSocksProxy(), nullValue());
        ConfigurationProperties.forwardSocksProxy(proxyAddress);

        // then
        assertThat(ConfigurationProperties.forwardSocksProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardSocksProxy"), is(proxyAddress));
        assertThat(proxyConfiguration(configuration()), equalTo(ImmutableList.of(proxyConfiguration(ProxyConfiguration.Type.SOCKS5, proxyAddress, "", ""))));
    }

    @Test
    public void shouldConfigureForwardSocksProxyWithAuthentication() {
        // given
        String proxyAddress = "127.0.0.1:1090";
        String userName = "userName";
        String password = "password";

        // when
        assertThat(ConfigurationProperties.forwardSocksProxy(), nullValue());
        ConfigurationProperties.forwardSocksProxy(proxyAddress);
        assertThat(ConfigurationProperties.forwardProxyAuthenticationUsername(), equalTo(""));
        ConfigurationProperties.forwardProxyAuthenticationUsername(userName);
        assertThat(ConfigurationProperties.forwardProxyAuthenticationPassword(), equalTo(""));
        ConfigurationProperties.forwardProxyAuthenticationPassword(password);

        // then
        assertThat(ConfigurationProperties.forwardSocksProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardSocksProxy"), is(proxyAddress));
        assertThat(ConfigurationProperties.forwardProxyAuthenticationUsername(), is(userName));
        assertThat(System.getProperty("mockserver.forwardProxyAuthenticationUsername"), is(userName));
        assertThat(ConfigurationProperties.forwardProxyAuthenticationPassword(), is(password));
        assertThat(System.getProperty("mockserver.forwardProxyAuthenticationPassword"), is(password));
        assertThat(proxyConfiguration(configuration()), equalTo(ImmutableList.of(proxyConfiguration(ProxyConfiguration.Type.SOCKS5, proxyAddress, userName, password))));
    }

    @Test
    public void shouldNotAllowConfigurationOfForwardHttpProxyAndSocksProxy() {
        // given
        String proxyAddress = "127.0.0.1:1090";

        // when
        assertThat(ConfigurationProperties.forwardHttpProxy(), nullValue());
        ConfigurationProperties.forwardHttpProxy(proxyAddress);
        assertThat(ConfigurationProperties.forwardSocksProxy(), nullValue());
        ConfigurationProperties.forwardSocksProxy(proxyAddress);

        // then
        assertThat(ConfigurationProperties.forwardHttpProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardHttpProxy"), is(proxyAddress));
        assertThat(ConfigurationProperties.forwardSocksProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardSocksProxy"), is(proxyAddress));
        IllegalArgumentException illegalArgumentException = assertThrows(IllegalArgumentException.class, () -> proxyConfiguration(configuration()));
        assertThat(illegalArgumentException.getMessage(), equalTo("Invalid proxy configuration it is not possible to configure HTTP or HTTPS proxy at the same time as a SOCKS proxy, please choose either HTTP(S) proxy OR a SOCKS proxy"));
    }

    @Test
    public void shouldNotAllowConfigurationOfForwardHttpsProxyAndSocksProxy() {
        // given
        String proxyAddress = "127.0.0.1:1090";

        // when
        assertThat(ConfigurationProperties.forwardHttpsProxy(), nullValue());
        ConfigurationProperties.forwardHttpsProxy(proxyAddress);
        assertThat(ConfigurationProperties.forwardSocksProxy(), nullValue());
        ConfigurationProperties.forwardSocksProxy(proxyAddress);

        // then
        assertThat(ConfigurationProperties.forwardHttpsProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardHttpsProxy"), is(proxyAddress));
        assertThat(ConfigurationProperties.forwardSocksProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardSocksProxy"), is(proxyAddress));
        IllegalArgumentException illegalArgumentException = assertThrows(IllegalArgumentException.class, () -> proxyConfiguration(configuration()));
        assertThat(illegalArgumentException.getMessage(), equalTo("Invalid proxy configuration it is not possible to configure HTTP or HTTPS proxy at the same time as a SOCKS proxy, please choose either HTTP(S) proxy OR a SOCKS proxy"));
    }

    @Test
    public void shouldNotAllowConfigurationOfForwardHttpAndHttpsProxyAndSocksProxy() {
        // given
        String proxyAddress = "127.0.0.1:1090";

        // when
        assertThat(ConfigurationProperties.forwardHttpProxy(), nullValue());
        ConfigurationProperties.forwardHttpProxy(proxyAddress);
        assertThat(ConfigurationProperties.forwardHttpsProxy(), nullValue());
        ConfigurationProperties.forwardHttpsProxy(proxyAddress);
        assertThat(ConfigurationProperties.forwardSocksProxy(), nullValue());
        ConfigurationProperties.forwardSocksProxy(proxyAddress);

        // then
        assertThat(ConfigurationProperties.forwardHttpProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardHttpProxy"), is(proxyAddress));
        assertThat(ConfigurationProperties.forwardHttpsProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardHttpsProxy"), is(proxyAddress));
        assertThat(ConfigurationProperties.forwardSocksProxy().toString(), is("/" + proxyAddress));
        assertThat(System.getProperty("mockserver.forwardSocksProxy"), is(proxyAddress));
        IllegalArgumentException illegalArgumentException = assertThrows(IllegalArgumentException.class, () -> proxyConfiguration(configuration()));
        assertThat(illegalArgumentException.getMessage(), equalTo("Invalid proxy configuration it is not possible to configure HTTP or HTTPS proxy at the same time as a SOCKS proxy, please choose either HTTP(S) proxy OR a SOCKS proxy"));
    }

}