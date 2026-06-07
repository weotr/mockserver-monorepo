package org.mockserver.async.security;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;

import java.util.Properties;

/**
 * Utility for building {@link MqttConnectOptions} from {@link MqttSecurity}
 * configuration.
 */
public final class MqttSecurityOptions {

    private MqttSecurityOptions() {
        // utility class
    }

    /**
     * Build connect options with security settings applied.
     * <p>
     * When security is null or empty, returns {@code null} so the caller
     * can use the no-arg {@code MqttClient.connect()} (preserving exact
     * backward-compatible behaviour with Paho defaults).
     *
     * @param security the MQTT security configuration (may be null)
     * @return configured connect options, or {@code null} when no security is needed
     */
    public static MqttConnectOptions buildConnectOptions(MqttSecurity security) {
        if (security == null || security.isEmpty()) {
            return null;
        }

        MqttConnectOptions options = new MqttConnectOptions();

        if (security.getUsername() != null && !security.getUsername().isBlank()) {
            options.setUserName(security.getUsername());
        }
        if (security.getPassword() != null && !security.getPassword().isBlank()) {
            options.setPassword(security.getPassword().toCharArray());
        }
        if (!security.getSslProperties().isEmpty()) {
            Properties sslProps = new Properties();
            sslProps.putAll(security.getSslProperties());
            options.setSSLProperties(sslProps);
        }

        return options;
    }
}
