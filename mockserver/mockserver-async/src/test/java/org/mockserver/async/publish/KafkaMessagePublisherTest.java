package org.mockserver.async.publish;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Tests for {@link KafkaMessagePublisher} using a mock KafkaProducer.
 */
public class KafkaMessagePublisherTest {

    @Mock
    private KafkaProducer<String, String> mockProducer;

    private KafkaMessagePublisher publisher;

    @Before
    public void setUp() {
        openMocks(this);
        publisher = new KafkaMessagePublisher(mockProducer);
    }

    @Test
    public void shouldSendProducerRecordWithTopicAndPayload() {
        publisher.publish("my-topic", "{\"key\":\"value\"}");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture());

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic(), is("my-topic"));
        assertThat(record.value(), is("{\"key\":\"value\"}"));
    }

    @Test
    public void shouldCloseUnderlyingProducer() {
        publisher.close();
        verify(mockProducer).close();
    }
}
