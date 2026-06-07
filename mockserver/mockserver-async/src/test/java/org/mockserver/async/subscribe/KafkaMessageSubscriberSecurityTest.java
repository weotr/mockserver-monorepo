package org.mockserver.async.subscribe;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.Test;
import org.mockserver.async.security.KafkaSecurity;

import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link KafkaMessageSubscriber#buildConsumerProperties} with security.
 * In the same package to access the package-private static method.
 */
public class KafkaMessageSubscriberSecurityTest {

    @Test
    public void shouldBuildConsumerPropertiesWithSaslSslSecurity() {
        KafkaSecurity security = KafkaSecurity.builder()
            .securityProtocol("SASL_SSL")
            .saslMechanism("SCRAM-SHA-256")
            .saslJaasConfig("org.apache.kafka.common.security.scram.ScramLoginModule required username=\"u\" password=\"p\";")
            .sslTruststoreLocation("/ca.jks")
            .sslTruststorePassword("capass")
            .build();

        Properties props = KafkaMessageSubscriber.buildConsumerProperties("broker:9093", "my-group", security);

        // Core consumer properties
        assertThat(props.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG), is("broker:9093"));
        assertThat(props.get(ConsumerConfig.GROUP_ID_CONFIG), is("my-group"));
        assertThat(props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), is("earliest"));
        assertThat(props.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG), is("true"));
        assertThat(props.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG), is(notNullValue()));
        assertThat(props.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG), is(notNullValue()));

        // Security properties
        assertThat(props.get("security.protocol"), is("SASL_SSL"));
        assertThat(props.get("sasl.mechanism"), is("SCRAM-SHA-256"));
        assertThat(props.get("sasl.jaas.config"),
            is("org.apache.kafka.common.security.scram.ScramLoginModule required username=\"u\" password=\"p\";"));
        assertThat(props.get("ssl.truststore.location"), is("/ca.jks"));
        assertThat(props.get("ssl.truststore.password"), is("capass"));
    }

    @Test
    public void shouldBuildConsumerPropertiesWithNullSecurity() {
        Properties props = KafkaMessageSubscriber.buildConsumerProperties("broker:9092", "group1", null);

        assertThat(props.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG), is("broker:9092"));
        assertThat(props.get(ConsumerConfig.GROUP_ID_CONFIG), is("group1"));
        assertThat(props.containsKey("security.protocol"), is(false));
        assertThat(props.containsKey("sasl.mechanism"), is(false));
    }

    @Test
    public void shouldBuildConsumerPropertiesWithEmptySecurity() {
        Properties props = KafkaMessageSubscriber.buildConsumerProperties("broker:9092", "group1", KafkaSecurity.empty());

        assertThat(props.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG), is("broker:9092"));
        assertThat(props.containsKey("security.protocol"), is(false));
    }

    @Test
    public void shouldBuildConsumerPropertiesWithSaslPlaintext() {
        KafkaSecurity security = KafkaSecurity.builder()
            .securityProtocol("SASL_PLAINTEXT")
            .saslMechanism("PLAIN")
            .saslJaasConfig("org.apache.kafka.common.security.plain.PlainLoginModule required;")
            .build();

        Properties props = KafkaMessageSubscriber.buildConsumerProperties("broker:9092", "grp", security);

        assertThat(props.get("security.protocol"), is("SASL_PLAINTEXT"));
        assertThat(props.get("sasl.mechanism"), is("PLAIN"));
        assertThat(props.get("sasl.jaas.config"), is("org.apache.kafka.common.security.plain.PlainLoginModule required;"));
        // No SSL keys
        assertThat(props.containsKey("ssl.truststore.location"), is(false));
    }
}
