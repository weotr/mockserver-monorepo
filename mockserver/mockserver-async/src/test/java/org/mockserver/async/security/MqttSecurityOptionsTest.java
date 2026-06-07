package org.mockserver.async.security;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link MqttSecurityOptions} and the {@link MqttSecurity} model.
 */
public class MqttSecurityOptionsTest {

    // ---- MqttSecurity model ----

    @Test
    public void emptySecurityShouldBeEmpty() {
        assertThat(MqttSecurity.empty().isEmpty(), is(true));
    }

    @Test
    public void securityWithUsernameShouldNotBeEmpty() {
        MqttSecurity sec = MqttSecurity.builder().username("user").build();
        assertThat(sec.isEmpty(), is(false));
    }

    @Test
    public void securityWithPasswordShouldNotBeEmpty() {
        MqttSecurity sec = MqttSecurity.builder().password("pass").build();
        assertThat(sec.isEmpty(), is(false));
    }

    @Test
    public void securityWithSslPropertiesShouldNotBeEmpty() {
        MqttSecurity sec = MqttSecurity.builder()
            .sslProperties(Map.of("com.ibm.ssl.trustStore", "/t.jks"))
            .build();
        assertThat(sec.isEmpty(), is(false));
    }

    @Test
    public void securityWithBlankFieldsShouldBeEmpty() {
        MqttSecurity sec = MqttSecurity.builder()
            .username("")
            .password("  ")
            .build();
        assertThat(sec.isEmpty(), is(true));
    }

    @Test
    public void sslPropertiesShouldBeUnmodifiable() {
        Map<String, String> mutableMap = new java.util.LinkedHashMap<>();
        mutableMap.put("key", "value");
        MqttSecurity sec = MqttSecurity.builder().sslProperties(mutableMap).build();

        // Modifying the original map should not affect the security object
        mutableMap.put("key2", "value2");
        assertThat(sec.getSslProperties().size(), is(1));

        // The returned map should be unmodifiable
        try {
            sec.getSslProperties().put("key3", "value3");
            // Should not reach here
            assertThat("Expected UnsupportedOperationException", false, is(true));
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    // ---- buildConnectOptions ----

    @Test
    public void shouldReturnNullForNullSecurity() {
        MqttConnectOptions options = MqttSecurityOptions.buildConnectOptions(null);
        assertThat(options, is(nullValue()));
    }

    @Test
    public void shouldReturnNullForEmptySecurity() {
        MqttConnectOptions options = MqttSecurityOptions.buildConnectOptions(MqttSecurity.empty());
        assertThat(options, is(nullValue()));
    }

    @Test
    public void shouldSetUsernameAndPassword() {
        MqttSecurity security = MqttSecurity.builder()
            .username("myuser")
            .password("mypass")
            .build();

        MqttConnectOptions options = MqttSecurityOptions.buildConnectOptions(security);

        assertThat(options, is(notNullValue()));
        assertThat(options.getUserName(), is("myuser"));
        assertThat(options.getPassword(), is("mypass".toCharArray()));
    }

    @Test
    public void shouldSetOnlyUsername() {
        MqttSecurity security = MqttSecurity.builder()
            .username("myuser")
            .build();

        MqttConnectOptions options = MqttSecurityOptions.buildConnectOptions(security);

        assertThat(options, is(notNullValue()));
        assertThat(options.getUserName(), is("myuser"));
        // Password should remain at Paho default (null)
        assertThat(options.getPassword(), is(nullValue()));
    }

    @Test
    public void shouldSetSslProperties() {
        MqttSecurity security = MqttSecurity.builder()
            .sslProperties(Map.of(
                "com.ibm.ssl.trustStore", "/truststore.jks",
                "com.ibm.ssl.trustStorePassword", "tspass",
                "com.ibm.ssl.protocol", "TLSv1.2"
            ))
            .build();

        MqttConnectOptions options = MqttSecurityOptions.buildConnectOptions(security);

        assertThat(options, is(notNullValue()));
        assertThat(options.getSSLProperties().getProperty("com.ibm.ssl.trustStore"), is("/truststore.jks"));
        assertThat(options.getSSLProperties().getProperty("com.ibm.ssl.trustStorePassword"), is("tspass"));
        assertThat(options.getSSLProperties().getProperty("com.ibm.ssl.protocol"), is("TLSv1.2"));
    }

    @Test
    public void shouldSetUsernamePasswordAndSslProperties() {
        MqttSecurity security = MqttSecurity.builder()
            .username("u")
            .password("p")
            .sslProperties(Map.of(
                "com.ibm.ssl.trustStore", "/t.jks",
                "com.ibm.ssl.trustStorePassword", "x"
            ))
            .build();

        MqttConnectOptions options = MqttSecurityOptions.buildConnectOptions(security);

        assertThat(options, is(notNullValue()));
        assertThat(options.getUserName(), is("u"));
        assertThat(options.getPassword(), is("p".toCharArray()));
        assertThat(options.getSSLProperties().getProperty("com.ibm.ssl.trustStore"), is("/t.jks"));
        assertThat(options.getSSLProperties().getProperty("com.ibm.ssl.trustStorePassword"), is("x"));
    }

    @Test
    public void shouldNotSetBlankUsername() {
        MqttSecurity security = MqttSecurity.builder()
            .username("")
            .password("pass")
            .build();

        MqttConnectOptions options = MqttSecurityOptions.buildConnectOptions(security);

        assertThat(options, is(notNullValue()));
        // Blank username should not be set
        assertThat(options.getUserName(), is(nullValue()));
        assertThat(options.getPassword(), is("pass".toCharArray()));
    }
}
