package org.mockserver.async.publish;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Tests for {@link MqttMessagePublisher} using a mock MqttClient.
 */
public class MqttMessagePublisherTest {

    @Mock
    private MqttClient mockClient;

    private MqttMessagePublisher publisher;

    @Before
    public void setUp() {
        openMocks(this);
        publisher = new MqttMessagePublisher(mockClient, 1);
    }

    @Test
    public void shouldPublishMqttMessageWithTopicAndPayload() throws MqttException {
        publisher.publish("sensor/temperature", "{\"temp\": 22.5}");

        ArgumentCaptor<MqttMessage> captor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(mockClient).publish(eq("sensor/temperature"), captor.capture());

        MqttMessage message = captor.getValue();
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        assertThat(payload, is("{\"temp\": 22.5}"));
        assertThat(message.getQos(), is(1));
    }

    @Test
    public void shouldDisconnectAndCloseClient() throws MqttException {
        when(mockClient.isConnected()).thenReturn(true);
        publisher.close();

        verify(mockClient).disconnect();
        verify(mockClient).close();
    }

    @Test
    public void shouldCloseWithoutDisconnectIfNotConnected() throws MqttException {
        when(mockClient.isConnected()).thenReturn(false);
        publisher.close();

        verify(mockClient).close();
    }
}
