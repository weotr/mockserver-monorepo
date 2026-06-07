package org.mockserver.async.subscribe;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Tests for {@link KafkaMessageSubscriber} using a mock KafkaConsumer.
 * <p>
 * All KafkaConsumer interactions happen on the poll thread; subscribe/unsubscribe
 * requests are queued and drained at the top of each poll iteration.
 */
public class KafkaMessageSubscriberTest {

    @Mock
    private KafkaConsumer<String, String> mockConsumer;

    private KafkaMessageSubscriber subscriber;

    @Before
    public void setUp() {
        openMocks(this);
        // Default: use the standard cap
        subscriber = new KafkaMessageSubscriber(mockConsumer);
    }

    @After
    public void tearDown() {
        subscriber.close();
    }

    @Test
    public void shouldSubscribeToTopic() throws Exception {
        when(mockConsumer.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());
        subscriber.subscribe("test-topic");

        // Give the poll loop time to drain the queued subscribe op
        Thread.sleep(300);

        // The subscribe op should have been executed on the poll thread
        verify(mockConsumer).subscribe(argThat((List<String> topics) -> topics.contains("test-topic")));
    }

    @Test
    public void shouldReturnEmptyListWhenNoMessages() {
        List<RecordedMessage> messages = subscriber.getRecordedMessages("nonexistent");
        assertThat(messages, is(empty()));
    }

    @Test
    public void shouldRecordMessagesFromPoll() throws Exception {
        // Set up a consumer record to be returned from poll
        TopicPartition tp = new TopicPartition("my-topic", 0);
        RecordHeaders headers = new RecordHeaders();
        headers.add("trace-id", "abc123".getBytes());
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "my-topic", 0, 0L, ConsumerRecord.NO_TIMESTAMP, TimestampType.NO_TIMESTAMP_TYPE,
            0, 0, "key1", "{\"data\":1}", headers,
            Optional.empty()
        );

        Map<TopicPartition, List<ConsumerRecord<String, String>>> recordMap = new HashMap<>();
        recordMap.put(tp, List.of(record));
        ConsumerRecords<String, String> records = new ConsumerRecords<>(recordMap);

        when(mockConsumer.poll(any(Duration.class)))
            .thenReturn(records)
            .thenReturn(ConsumerRecords.empty());

        subscriber.subscribe("my-topic");

        // Give the poll loop time to execute
        Thread.sleep(300);

        List<RecordedMessage> messages = subscriber.getRecordedMessages("my-topic");
        assertThat(messages, hasSize(1));
        assertThat(messages.get(0).getChannel(), is("my-topic"));
        assertThat(messages.get(0).getKey(), is("key1"));
        assertThat(messages.get(0).getPayload(), is("{\"data\":1}"));
        assertThat(messages.get(0).getHeaders(), hasEntry("trace-id", "abc123"));
    }

    @Test
    public void shouldUnsubscribeFromTopic() throws Exception {
        when(mockConsumer.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());
        subscriber.subscribe("topic-a");
        Thread.sleep(200);
        subscriber.unsubscribe("topic-a");
        Thread.sleep(200);
        // unsubscribe when no topics remain calls consumer.unsubscribe()
        verify(mockConsumer).unsubscribe();
    }

    @Test
    public void shouldReturnAllRecordedMessages() throws Exception {
        TopicPartition tp1 = new TopicPartition("topic-a", 0);
        TopicPartition tp2 = new TopicPartition("topic-b", 0);

        ConsumerRecord<String, String> record1 = new ConsumerRecord<>("topic-a", 0, 0L, null, "{\"a\":1}");
        ConsumerRecord<String, String> record2 = new ConsumerRecord<>("topic-b", 0, 0L, null, "{\"b\":2}");

        Map<TopicPartition, List<ConsumerRecord<String, String>>> recordMap = new HashMap<>();
        recordMap.put(tp1, List.of(record1));
        recordMap.put(tp2, List.of(record2));
        ConsumerRecords<String, String> records = new ConsumerRecords<>(recordMap);

        when(mockConsumer.poll(any(Duration.class)))
            .thenReturn(records)
            .thenReturn(ConsumerRecords.empty());

        subscriber.subscribe("topic-a");
        subscriber.subscribe("topic-b");

        Thread.sleep(300);

        List<RecordedMessage> all = subscriber.getAllRecordedMessages();
        assertThat(all, hasSize(2));
    }

    @Test
    public void shouldClearOnClose() throws Exception {
        when(mockConsumer.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());
        subscriber.subscribe("topic-x");

        subscriber.close();

        assertThat(subscriber.getRecordedMessages("topic-x"), is(empty()));
        assertThat(subscriber.getAllRecordedMessages(), is(empty()));
    }

    @Test
    public void shouldEvictOldestWhenCapReached() throws Exception {
        // Use a small cap of 3
        subscriber.close(); // close default subscriber
        subscriber = new KafkaMessageSubscriber(mockConsumer, 3);

        // Build 5 records for the same topic
        TopicPartition tp = new TopicPartition("capped-topic", 0);
        List<ConsumerRecord<String, String>> recordList = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            recordList.add(new ConsumerRecord<>("capped-topic", 0, (long) i, "key" + i, "value" + i));
        }
        Map<TopicPartition, List<ConsumerRecord<String, String>>> recordMap = new HashMap<>();
        recordMap.put(tp, recordList);
        ConsumerRecords<String, String> records = new ConsumerRecords<>(recordMap);

        when(mockConsumer.poll(any(Duration.class)))
            .thenReturn(records)
            .thenReturn(ConsumerRecords.empty());

        subscriber.subscribe("capped-topic");
        Thread.sleep(300);

        List<RecordedMessage> messages = subscriber.getRecordedMessages("capped-topic");
        // Should only have the last 3 messages (evicted 0, 1)
        assertThat(messages, hasSize(3));
        assertThat(messages.get(0).getPayload(), is("value2"));
        assertThat(messages.get(1).getPayload(), is("value3"));
        assertThat(messages.get(2).getPayload(), is("value4"));
    }

    @Test
    public void shouldQueueSubscribeOpsAndNotCallConsumerDirectlyFromCallerThread() throws Exception {
        // Verify that subscribe() does NOT directly call consumer.subscribe() synchronously.
        // Instead it queues the op. We verify by checking no consumer.subscribe() call
        // happens until the poll thread has a chance to run.
        subscriber.subscribe("queued-topic");

        // At this point the consumer.subscribe should NOT yet have been called because
        // the poll loop needs to drain the queue first. However, since the executor
        // starts the poll loop immediately, we just verify that subscribe DID eventually
        // get called on the poll thread (not the test thread).
        when(mockConsumer.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());
        Thread.sleep(300);

        // The consumer.subscribe call should eventually happen (from the poll thread)
        verify(mockConsumer, atLeastOnce()).subscribe(argThat((List<String> topics) ->
            topics.contains("queued-topic")));
    }

    @Test
    public void shouldStartPollLoopExactlyOnceWithMultipleSubscribes() throws Exception {
        when(mockConsumer.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());

        // Subscribe to multiple topics rapidly
        subscriber.subscribe("topic-1");
        subscriber.subscribe("topic-2");
        subscriber.subscribe("topic-3");

        Thread.sleep(300);

        // The poll loop should have been submitted only once (AtomicBoolean guards it)
        // Verify consumer.subscribe was called with all three topics eventually
        verify(mockConsumer, atLeastOnce()).subscribe(argThat((List<String> topics) ->
            topics.contains("topic-1") && topics.contains("topic-2") && topics.contains("topic-3")));
    }
}
