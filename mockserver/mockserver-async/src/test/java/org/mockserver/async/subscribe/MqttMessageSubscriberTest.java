package org.mockserver.async.subscribe;

import org.eclipse.paho.client.mqttv3.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Tests for {@link MqttMessageSubscriber} using a mock MqttClient.
 */
public class MqttMessageSubscriberTest {

    @Mock
    private MqttClient mockClient;

    private MqttMessageSubscriber subscriber;

    @Before
    public void setUp() {
        openMocks(this);
        subscriber = new MqttMessageSubscriber(mockClient, 1);
    }

    @After
    public void tearDown() {
        when(mockClient.isConnected()).thenReturn(false);
        subscriber.close();
    }

    @Test
    public void shouldSubscribeToTopic() throws MqttException {
        subscriber.subscribe("sensor/temp");
        verify(mockClient).subscribe(eq("sensor/temp"), eq(1));
    }

    @Test
    public void shouldRecordMessagesViaCallback() throws Exception {
        // Capture the callback installed by the subscriber
        ArgumentCaptor<MqttCallback> callbackCaptor = ArgumentCaptor.forClass(MqttCallback.class);
        verify(mockClient).setCallback(callbackCaptor.capture());
        MqttCallback callback = callbackCaptor.getValue();

        subscriber.subscribe("sensor/temp");

        // Simulate receiving a message
        MqttMessage message = new MqttMessage("{\"temp\":22.5}".getBytes(StandardCharsets.UTF_8));
        callback.messageArrived("sensor/temp", message);

        List<RecordedMessage> messages = subscriber.getRecordedMessages("sensor/temp");
        assertThat(messages, hasSize(1));
        assertThat(messages.get(0).getChannel(), is("sensor/temp"));
        assertThat(messages.get(0).getPayload(), is("{\"temp\":22.5}"));
        assertThat(messages.get(0).getKey(), is(nullValue()));
        assertThat(messages.get(0).getHeaders(), is(anEmptyMap()));
    }

    @Test
    public void shouldReturnEmptyListForUnknownTopic() {
        List<RecordedMessage> messages = subscriber.getRecordedMessages("unknown");
        assertThat(messages, is(empty()));
    }

    @Test
    public void shouldUnsubscribeFromTopic() throws MqttException {
        subscriber.subscribe("sensor/temp");
        subscriber.unsubscribe("sensor/temp");
        verify(mockClient).unsubscribe(eq("sensor/temp"));
    }

    @Test
    public void shouldReturnAllRecordedMessages() throws Exception {
        ArgumentCaptor<MqttCallback> callbackCaptor = ArgumentCaptor.forClass(MqttCallback.class);
        verify(mockClient).setCallback(callbackCaptor.capture());
        MqttCallback callback = callbackCaptor.getValue();

        subscriber.subscribe("topic-a");
        subscriber.subscribe("topic-b");

        callback.messageArrived("topic-a", new MqttMessage("{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        callback.messageArrived("topic-b", new MqttMessage("{\"b\":2}".getBytes(StandardCharsets.UTF_8)));

        List<RecordedMessage> all = subscriber.getAllRecordedMessages();
        assertThat(all, hasSize(2));
    }

    @Test
    public void shouldClearOnClose() throws Exception {
        ArgumentCaptor<MqttCallback> callbackCaptor = ArgumentCaptor.forClass(MqttCallback.class);
        verify(mockClient).setCallback(callbackCaptor.capture());
        MqttCallback callback = callbackCaptor.getValue();

        subscriber.subscribe("topic-x");
        callback.messageArrived("topic-x", new MqttMessage("{\"x\":1}".getBytes(StandardCharsets.UTF_8)));

        when(mockClient.isConnected()).thenReturn(true);
        subscriber.close();

        assertThat(subscriber.getRecordedMessages("topic-x"), is(empty()));
    }

    @Test
    public void shouldEvictOldestWhenCapReached() throws Exception {
        // Close default subscriber and create one with small cap
        when(mockClient.isConnected()).thenReturn(false);
        subscriber.close();
        subscriber = new MqttMessageSubscriber(mockClient, 1, 3);

        ArgumentCaptor<MqttCallback> callbackCaptor = ArgumentCaptor.forClass(MqttCallback.class);
        // The new subscriber installs a callback in constructor
        verify(mockClient, atLeast(1)).setCallback(callbackCaptor.capture());
        MqttCallback callback = callbackCaptor.getValue();

        subscriber.subscribe("bounded-topic");

        // Send 5 messages
        for (int i = 0; i < 5; i++) {
            callback.messageArrived("bounded-topic",
                new MqttMessage(("value" + i).getBytes(StandardCharsets.UTF_8)));
        }

        List<RecordedMessage> messages = subscriber.getRecordedMessages("bounded-topic");
        // Should only have the last 3 messages (evicted 0, 1)
        assertThat(messages, hasSize(3));
        assertThat(messages.get(0).getPayload(), is("value2"));
        assertThat(messages.get(1).getPayload(), is("value3"));
        assertThat(messages.get(2).getPayload(), is("value4"));
    }
}
