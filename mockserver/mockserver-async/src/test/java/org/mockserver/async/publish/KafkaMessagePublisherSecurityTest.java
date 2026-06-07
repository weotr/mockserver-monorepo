package org.mockserver.async.publish;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.Test;
import org.mockserver.async.security.KafkaSecurity;

import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link KafkaMessagePublisher#buildProducerProperties} with security.
 * In the same package to access the package-private static method.
 */
public class KafkaMessagePublisherSecurityTest {

    @Test
    public void shouldBuildProducerPropertiesWithSaslSslSecurity() {
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

        Properties props = KafkaMessagePublisher.buildProducerProperties("broker:9093", security);

        // Core producer properties must still be present
        assertThat(props.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG), is("broker:9093"));
        assertThat(props.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG), is(notNullValue()));
        assertThat(props.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG), is(notNullValue()));

        // Security properties
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
    public void shouldBuildProducerPropertiesWithNullSecurity() {
        Properties props = KafkaMessagePublisher.buildProducerProperties("broker:9092", null);

        assertThat(props.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG), is("broker:9092"));
        // No security keys should be present
        assertThat(props.containsKey("security.protocol"), is(false));
        assertThat(props.containsKey("sasl.mechanism"), is(false));
        assertThat(props.containsKey("sasl.jaas.config"), is(false));
        assertThat(props.containsKey("ssl.truststore.location"), is(false));
    }

    @Test
    public void shouldBuildProducerPropertiesWithEmptySecurity() {
        Properties props = KafkaMessagePublisher.buildProducerProperties("broker:9092", KafkaSecurity.empty());

        assertThat(props.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG), is("broker:9092"));
        assertThat(props.containsKey("security.protocol"), is(false));
        assertThat(props.containsKey("sasl.mechanism"), is(false));
    }

    @Test
    public void shouldBuildProducerPropertiesWithPartialSecurity() {
        KafkaSecurity security = KafkaSecurity.builder()
            .securityProtocol("SSL")
            .sslTruststoreLocation("/truststore.jks")
            .sslTruststorePassword("tspass")
            .build();

        Properties props = KafkaMessagePublisher.buildProducerProperties("broker:9093", security);

        assertThat(props.get("security.protocol"), is("SSL"));
        assertThat(props.get("ssl.truststore.location"), is("/truststore.jks"));
        assertThat(props.get("ssl.truststore.password"), is("tspass"));
        assertThat(props.containsKey("sasl.mechanism"), is(false));
        assertThat(props.containsKey("sasl.jaas.config"), is(false));
        assertThat(props.containsKey("ssl.keystore.location"), is(false));
    }
}
