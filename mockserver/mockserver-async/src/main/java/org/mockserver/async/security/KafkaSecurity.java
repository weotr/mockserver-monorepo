package org.mockserver.async.security;

/**
 * Immutable carrier for Kafka security configuration (SASL and/or SSL).
 * All fields are optional; when all are null or blank, {@link #isEmpty()}
 * returns {@code true} and no security properties are applied to the
 * Kafka client.
 */
public final class KafkaSecurity {

    private final String securityProtocol;
    private final String saslMechanism;
    private final String saslJaasConfig;
    private final String sslTruststoreLocation;
    private final String sslTruststorePassword;
    private final String sslKeystoreLocation;
    private final String sslKeystorePassword;
    private final String sslKeyPassword;

    private KafkaSecurity(Builder builder) {
        this.securityProtocol = builder.securityProtocol;
        this.saslMechanism = builder.saslMechanism;
        this.saslJaasConfig = builder.saslJaasConfig;
        this.sslTruststoreLocation = builder.sslTruststoreLocation;
        this.sslTruststorePassword = builder.sslTruststorePassword;
        this.sslKeystoreLocation = builder.sslKeystoreLocation;
        this.sslKeystorePassword = builder.sslKeystorePassword;
        this.sslKeyPassword = builder.sslKeyPassword;
    }

    public String getSecurityProtocol() {
        return securityProtocol;
    }

    public String getSaslMechanism() {
        return saslMechanism;
    }

    public String getSaslJaasConfig() {
        return saslJaasConfig;
    }

    public String getSslTruststoreLocation() {
        return sslTruststoreLocation;
    }

    public String getSslTruststorePassword() {
        return sslTruststorePassword;
    }

    public String getSslKeystoreLocation() {
        return sslKeystoreLocation;
    }

    public String getSslKeystorePassword() {
        return sslKeystorePassword;
    }

    public String getSslKeyPassword() {
        return sslKeyPassword;
    }

    /**
     * Returns {@code true} when no security fields are set (all null or blank).
     */
    public boolean isEmpty() {
        return isBlank(securityProtocol)
            && isBlank(saslMechanism)
            && isBlank(saslJaasConfig)
            && isBlank(sslTruststoreLocation)
            && isBlank(sslTruststorePassword)
            && isBlank(sslKeystoreLocation)
            && isBlank(sslKeystorePassword)
            && isBlank(sslKeyPassword);
    }

    /**
     * Returns a new empty (no-op) security instance.
     */
    public static KafkaSecurity empty() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static final class Builder {
        private String securityProtocol;
        private String saslMechanism;
        private String saslJaasConfig;
        private String sslTruststoreLocation;
        private String sslTruststorePassword;
        private String sslKeystoreLocation;
        private String sslKeystorePassword;
        private String sslKeyPassword;

        private Builder() {
        }

        public Builder securityProtocol(String securityProtocol) {
            this.securityProtocol = securityProtocol;
            return this;
        }

        public Builder saslMechanism(String saslMechanism) {
            this.saslMechanism = saslMechanism;
            return this;
        }

        public Builder saslJaasConfig(String saslJaasConfig) {
            this.saslJaasConfig = saslJaasConfig;
            return this;
        }

        public Builder sslTruststoreLocation(String sslTruststoreLocation) {
            this.sslTruststoreLocation = sslTruststoreLocation;
            return this;
        }

        public Builder sslTruststorePassword(String sslTruststorePassword) {
            this.sslTruststorePassword = sslTruststorePassword;
            return this;
        }

        public Builder sslKeystoreLocation(String sslKeystoreLocation) {
            this.sslKeystoreLocation = sslKeystoreLocation;
            return this;
        }

        public Builder sslKeystorePassword(String sslKeystorePassword) {
            this.sslKeystorePassword = sslKeystorePassword;
            return this;
        }

        public Builder sslKeyPassword(String sslKeyPassword) {
            this.sslKeyPassword = sslKeyPassword;
            return this;
        }

        public KafkaSecurity build() {
            return new KafkaSecurity(this);
        }
    }
}
