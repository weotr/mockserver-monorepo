package org.mockserver.async.publish;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import org.mockserver.async.publish.PublishOptions;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Tests for the enhanced {@link KafkaMessagePublisher} with key and header support.
 */
public class KafkaMessagePublisherKeyHeadersTest {

    @Mock
    private KafkaProducer<String, String> mockProducer;

    private KafkaMessagePublisher publisher;

    @Before
    public void setUp() {
        openMocks(this);
        publisher = new KafkaMessagePublisher(mockProducer);
    }

    @Test
    public void shouldSendRecordWithKey() {
        publisher.publish("my-topic", "my-key", "{\"data\":1}", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture(), any(Callback.class));

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic(), is("my-topic"));
        assertThat(record.key(), is("my-key"));
        assertThat(record.value(), is("{\"data\":1}"));
    }

    @Test
    public void shouldSendRecordWithNullKey() {
        publisher.publish("my-topic", null, "{\"data\":1}", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture(), any(Callback.class));

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.key(), is(nullValue()));
    }

    @Test
    public void shouldSendRecordWithHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("trace-id", "abc123");
        headers.put("content-type", "application/json");

        publisher.publish("my-topic", "key1", "{\"data\":1}", headers);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture(), any(Callback.class));

        ProducerRecord<String, String> record = captor.getValue();
        Header traceHeader = record.headers().lastHeader("trace-id");
        assertThat(traceHeader, is(notNullValue()));
        assertThat(new String(traceHeader.value(), StandardCharsets.UTF_8), is("abc123"));

        Header contentTypeHeader = record.headers().lastHeader("content-type");
        assertThat(contentTypeHeader, is(notNullValue()));
        assertThat(new String(contentTypeHeader.value(), StandardCharsets.UTF_8), is("application/json"));
    }

    @Test
    public void shouldSendWithoutHeadersWhenNull() {
        publisher.publish("my-topic", "key1", "{\"data\":1}", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture(), any(Callback.class));

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.headers().toArray().length, is(0));
    }

    @Test
    public void shouldSendViaDefaultInterfaceMethod() {
        // The simple publish(channel, payload) should still work
        publisher.publish("my-topic", "{\"simple\":true}");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture(), any(Callback.class));

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic(), is("my-topic"));
        assertThat(record.key(), is(nullValue()));
        assertThat(record.value(), is("{\"simple\":true}"));
    }

    // ---- PublishOptions tests ----

    @Test
    public void shouldSendWithKeyFromPublishOptions() {
        PublishOptions options = new PublishOptions("options-key", null, null);
        publisher.publish("my-topic", "{\"data\":1}", options);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture(), any(Callback.class));

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic(), is("my-topic"));
        assertThat(record.key(), is("options-key"));
        assertThat(record.value(), is("{\"data\":1}"));
    }

    @Test
    public void shouldSendWithNullKeyWhenPublishOptionsHasNoKey() {
        PublishOptions options = new PublishOptions(null, 2, true);
        publisher.publish("my-topic", "{\"data\":1}", options);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture(), any(Callback.class));

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.key(), is(nullValue()));
    }

    @Test
    public void shouldSendWithNullKeyWhenPublishOptionsAreNull() {
        publisher.publish("my-topic", "{\"data\":1}", (PublishOptions) null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture(), any(Callback.class));

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.key(), is(nullValue()));
    }

    @Test
    public void shouldSendWithNullKeyWhenPublishOptionsNone() {
        publisher.publish("my-topic", "{\"data\":1}", PublishOptions.none());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture(), any(Callback.class));

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.key(), is(nullValue()));
    }

    // ---- PublishOptions with headers tests ----

    @Test
    public void shouldSendHeadersFromPublishOptions() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("correlationId", "corr-123");
        PublishOptions options = new PublishOptions("key1", null, null, headers);
        publisher.publish("my-topic", "{\"data\":1}", options);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture(), any(Callback.class));

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.key(), is("key1"));
        Header corrHeader = record.headers().lastHeader("correlationId");
        assertThat(corrHeader, is(notNullValue()));
        assertThat(new String(corrHeader.value(), StandardCharsets.UTF_8), is("corr-123"));
    }

    @Test
    public void shouldNotAddHeadersFromPublishOptionsWhenEmpty() {
        PublishOptions options = new PublishOptions("key1", null, null, Map.of());
        publisher.publish("my-topic", "{\"data\":1}", options);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture(), any(Callback.class));

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.headers().toArray().length, is(0));
    }

    @Test
    public void shouldCombineKeyAndHeadersFromPublishOptions() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-corr-id", "abc");
        headers.put("x-trace-id", "xyz");
        PublishOptions options = new PublishOptions("my-key", null, null, headers);
        publisher.publish("my-topic", "{\"data\":1}", options);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture(), any(Callback.class));

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.key(), is("my-key"));
        assertThat(record.headers().toArray().length, is(2));
        assertThat(new String(record.headers().lastHeader("x-corr-id").value(), StandardCharsets.UTF_8),
            is("abc"));
        assertThat(new String(record.headers().lastHeader("x-trace-id").value(), StandardCharsets.UTF_8),
            is("xyz"));
    }
}
