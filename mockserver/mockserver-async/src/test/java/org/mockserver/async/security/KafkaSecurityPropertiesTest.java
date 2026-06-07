package org.mockserver.async.security;

import org.junit.Test;

import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link KafkaSecurityProperties} and the {@link KafkaSecurity} model.
 */
public class KafkaSecurityPropertiesTest {

    // ---- KafkaSecurity model ----

    @Test
    public void emptySecurityShouldBeEmpty() {
        assertThat(KafkaSecurity.empty().isEmpty(), is(true));
    }

    @Test
    public void securityWithAnyFieldShouldNotBeEmpty() {
        assertThat(KafkaSecurity.builder().securityProtocol("SSL").build().isEmpty(), is(false));
        assertThat(KafkaSecurity.builder().saslMechanism("PLAIN").build().isEmpty(), is(false));
        assertThat(KafkaSecurity.builder().sslKeyPassword("x").build().isEmpty(), is(false));
    }

    @Test
    public void securityWithBlankFieldsShouldBeEmpty() {
        KafkaSecurity sec = KafkaSecurity.builder()
            .securityProtocol("")
            .saslMechanism("  ")
            .build();
        assertThat(sec.isEmpty(), is(true));
    }

    @Test
    public void gettersShouldReturnConfiguredValues() {
        KafkaSecurity sec = KafkaSecurity.builder()
            .securityProtocol("SASL_SSL")
            .saslMechanism("PLAIN")
            .saslJaasConfig("jaas-config")
            .sslTruststoreLocation("/t.jks")
            .sslTruststorePassword("tpass")
            .sslKeystoreLocation("/k.jks")
            .sslKeystorePassword("kpass")
            .sslKeyPassword("keypass")
            .build();

        assertThat(sec.getSecurityProtocol(), is("SASL_SSL"));
        assertThat(sec.getSaslMechanism(), is("PLAIN"));
        assertThat(sec.getSaslJaasConfig(), is("jaas-config"));
        assertThat(sec.getSslTruststoreLocation(), is("/t.jks"));
        assertThat(sec.getSslTruststorePassword(), is("tpass"));
        assertThat(sec.getSslKeystoreLocation(), is("/k.jks"));
        assertThat(sec.getSslKeystorePassword(), is("kpass"));
        assertThat(sec.getSslKeyPassword(), is("keypass"));
    }

    // ---- applySecurity ----

    @Test
    public void shouldApplyAllSecurityProperties() {
        KafkaSecurity security = KafkaSecurity.builder()
            .securityProtocol("SASL_SSL")
            .saslMechanism("PLAIN")
            .saslJaasConfig("org.apache.kafka.common.security.plain.PlainLoginModule required username=\"u\" password=\"p\";")
            .sslTruststoreLocation("/truststore.jks")
            .sslTruststorePassword("tspass")
            .sslKeystoreLocation("/keystore.jks")
            .sslKeystorePassword("kspass")
            .sslKeyPassword("keypass")
            .build();

        Properties props = new Properties();
        KafkaSecurityProperties.applySecurity(props, security);

        assertThat(props.get("security.protocol"), is("SASL_SSL"));
        assertThat(props.get("sasl.mechanism"), is("PLAIN"));
        assertThat(props.get("sasl.jaas.config"),
            is("org.apache.kafka.common.security.plain.PlainLoginModule required username=\"u\" password=\"p\";"));
        assertThat(props.get("ssl.truststore.location"), is("/truststore.jks"));
        assertThat(props.get("ssl.truststore.password"), is("tspass"));
        assertThat(props.get("ssl.keystore.location"), is("/keystore.jks"));
        assertThat(props.get("ssl.keystore.password"), is("kspass"));
        assertThat(props.get("ssl.key.password"), is("keypass"));
    }

    @Test
    public void shouldApplyPartialSecurity() {
        KafkaSecurity security = KafkaSecurity.builder()
            .securityProtocol("SSL")
            .sslTruststoreLocation("/truststore.jks")
            .sslTruststorePassword("tspass")
            .build();

        Properties props = new Properties();
        KafkaSecurityProperties.applySecurity(props, security);

        assertThat(props.get("security.protocol"), is("SSL"));
        assertThat(props.get("ssl.truststore.location"), is("/truststore.jks"));
        assertThat(props.get("ssl.truststore.password"), is("tspass"));
        // SASL keys should not be present
        assertThat(props.containsKey("sasl.mechanism"), is(false));
        assertThat(props.containsKey("sasl.jaas.config"), is(false));
        assertThat(props.containsKey("ssl.keystore.location"), is(false));
    }

    @Test
    public void shouldSkipBlankValues() {
        KafkaSecurity security = KafkaSecurity.builder()
            .securityProtocol("SASL_PLAINTEXT")
            .saslMechanism("PLAIN")
            .sslTruststoreLocation("") // blank - should not be added
            .sslTruststorePassword(null) // null - should not be added
            .build();

        Properties props = new Properties();
        KafkaSecurityProperties.applySecurity(props, security);

        assertThat(props.get("security.protocol"), is("SASL_PLAINTEXT"));
        assertThat(props.get("sasl.mechanism"), is("PLAIN"));
        assertThat(props.containsKey("ssl.truststore.location"), is(false));
        assertThat(props.containsKey("ssl.truststore.password"), is(false));
    }

    @Test
    public void shouldBeNoOpWithNullSecurity() {
        Properties props = new Properties();
        props.put("existing.key", "value");

        KafkaSecurityProperties.applySecurity(props, null);

        assertThat(props.size(), is(1));
        assertThat(props.get("existing.key"), is("value"));
    }

    @Test
    public void shouldBeNoOpWithEmptySecurity() {
        Properties props = new Properties();
        props.put("existing.key", "value");

        KafkaSecurityProperties.applySecurity(props, KafkaSecurity.empty());

        assertThat(props.size(), is(1));
    }

    @Test
    public void shouldNotOverrideExistingProperties() {
        Properties props = new Properties();
        props.put("bootstrap.servers", "original:9092");
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");

        KafkaSecurity security = KafkaSecurity.builder()
            .securityProtocol("SASL_SSL")
            .build();

        KafkaSecurityProperties.applySecurity(props, security);

        // Existing properties should remain unchanged
        assertThat(props.get("bootstrap.servers"), is("original:9092"));
        assertThat(props.get("key.serializer"), is("org.apache.kafka.common.serialization.StringSerializer"));
        // Security property should be added
        assertThat(props.get("security.protocol"), is("SASL_SSL"));
    }
}
