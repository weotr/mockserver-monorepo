package org.mockserver.async.security;

import java.util.Properties;

/**
 * Utility for applying {@link KafkaSecurity} settings to Kafka client
 * {@link Properties}. Each non-blank field maps to the standard Kafka
 * configuration key.
 */
public final class KafkaSecurityProperties {

    // Kafka config keys (using literal strings to avoid importing CommonClientConfigs
    // which may not be on the compile classpath for all callers; the values are
    // identical to the constants in org.apache.kafka.clients.CommonClientConfigs
    // and org.apache.kafka.common.config.SslConfigs / SaslConfigs)
    static final String SECURITY_PROTOCOL = "security.protocol";
    static final String SASL_MECHANISM = "sasl.mechanism";
    static final String SASL_JAAS_CONFIG = "sasl.jaas.config";
    static final String SSL_TRUSTSTORE_LOCATION = "ssl.truststore.location";
    static final String SSL_TRUSTSTORE_PASSWORD = "ssl.truststore.password";
    static final String SSL_KEYSTORE_LOCATION = "ssl.keystore.location";
    static final String SSL_KEYSTORE_PASSWORD = "ssl.keystore.password";
    static final String SSL_KEY_PASSWORD = "ssl.key.password";

    private KafkaSecurityProperties() {
        // utility class
    }

    /**
     * Apply security settings to the given Kafka client properties.
     * Only non-blank fields from the {@link KafkaSecurity} are set;
     * if security is empty or null, the properties are left unchanged
     * (backward-compatible plaintext behaviour).
     *
     * @param props    the Kafka client properties to modify in-place
     * @param security the security configuration (may be null)
     */
    public static void applySecurity(Properties props, KafkaSecurity security) {
        if (security == null || security.isEmpty()) {
            return;
        }
        setIfNotBlank(props, SECURITY_PROTOCOL, security.getSecurityProtocol());
        setIfNotBlank(props, SASL_MECHANISM, security.getSaslMechanism());
        setIfNotBlank(props, SASL_JAAS_CONFIG, security.getSaslJaasConfig());
        setIfNotBlank(props, SSL_TRUSTSTORE_LOCATION, security.getSslTruststoreLocation());
        setIfNotBlank(props, SSL_TRUSTSTORE_PASSWORD, security.getSslTruststorePassword());
        setIfNotBlank(props, SSL_KEYSTORE_LOCATION, security.getSslKeystoreLocation());
        setIfNotBlank(props, SSL_KEYSTORE_PASSWORD, security.getSslKeystorePassword());
        setIfNotBlank(props, SSL_KEY_PASSWORD, security.getSslKeyPassword());
    }

    private static void setIfNotBlank(Properties props, String key, String value) {
        if (value != null && !value.isBlank()) {
            props.put(key, value);
        }
    }
}
