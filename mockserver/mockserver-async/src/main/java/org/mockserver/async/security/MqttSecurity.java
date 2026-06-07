package org.mockserver.async.security;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable carrier for MQTT security configuration (username/password and/or SSL).
 * All fields are optional; when all are null or blank, {@link #isEmpty()}
 * returns {@code true} and no security is applied to the MQTT connection.
 */
public final class MqttSecurity {

    private final String username;
    private final String password;
    private final Map<String, String> sslProperties;

    private MqttSecurity(Builder builder) {
        this.username = builder.username;
        this.password = builder.password;
        this.sslProperties = builder.sslProperties != null
            ? Collections.unmodifiableMap(new LinkedHashMap<>(builder.sslProperties))
            : Collections.emptyMap();
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    /**
     * Returns the SSL properties to pass through to Paho's
     * {@code MqttConnectOptions.setSSLProperties()}. Keys are typically
     * {@code com.ibm.ssl.keyStore}, {@code com.ibm.ssl.trustStore}, etc.
     *
     * @return an unmodifiable (possibly empty) map of SSL properties
     */
    public Map<String, String> getSslProperties() {
        return sslProperties;
    }

    /**
     * Returns {@code true} when no security fields are set.
     */
    public boolean isEmpty() {
        return isBlank(username)
            && isBlank(password)
            && sslProperties.isEmpty();
    }

    /**
     * Returns a new empty (no-op) security instance.
     */
    public static MqttSecurity empty() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static final class Builder {
        private String username;
        private String password;
        private Map<String, String> sslProperties;

        private Builder() {
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder sslProperties(Map<String, String> sslProperties) {
            this.sslProperties = sslProperties;
            return this;
        }

        public MqttSecurity build() {
            return new MqttSecurity(this);
        }
    }
}
