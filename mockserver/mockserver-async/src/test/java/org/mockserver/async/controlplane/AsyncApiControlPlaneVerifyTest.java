package org.mockserver.async.controlplane;

import org.junit.After;
import org.junit.Test;
import org.mockserver.async.subscribe.MessageSubscriber;
import org.mockserver.async.subscribe.RecordedMessage;

import java.util.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for {@link AsyncApiControlPlaneImpl#verify(String)}.
 * <p>
 * Seeds a stub subscriber with recorded messages and verifies
 * that the verify method returns null (pass) or a failure description
 * for various count and payload matching criteria.
 */
public class AsyncApiControlPlaneVerifyTest {

    private final AsyncApiControlPlaneImpl controlPlane = new AsyncApiControlPlaneImpl();

    @After
    public void tearDown() {
        controlPlane.reset();
    }

    // ---- Success cases ----

    @Test
    public void shouldPassWhenAtLeastOneMessageMatchesChannel() {
        addMessages(
            msg("orders", "{\"orderId\":1}"),
            msg("orders", "{\"orderId\":2}")
        );

        String result = controlPlane.verify("{\"channel\":\"orders\"}");
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldPassWhenExactlyMatchesCount() {
        addMessages(
            msg("events", "hello"),
            msg("events", "world")
        );

        String result = controlPlane.verify(
            "{\"channel\":\"events\",\"count\":{\"exactly\":2}}");
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldPassWhenAtLeastMatchesCount() {
        addMessages(
            msg("events", "a"),
            msg("events", "b"),
            msg("events", "c")
        );

        String result = controlPlane.verify(
            "{\"channel\":\"events\",\"count\":{\"atLeast\":2}}");
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldPassWhenAtMostMatchesCount() {
        addMessages(
            msg("events", "a"),
            msg("events", "b")
        );

        String result = controlPlane.verify(
            "{\"channel\":\"events\",\"count\":{\"atMost\":3}}");
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldPassWithPayloadSubstringMatch() {
        addMessages(
            msg("orders", "{\"orderId\":42,\"status\":\"completed\"}"),
            msg("orders", "{\"orderId\":99,\"status\":\"pending\"}")
        );

        String result = controlPlane.verify(
            "{\"channel\":\"orders\",\"payloadSubstring\":\"completed\",\"count\":{\"exactly\":1}}");
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldPassWithJsonPathMatch() {
        addMessages(
            msg("orders", "{\"user\":{\"name\":\"Alice\"},\"amount\":100}"),
            msg("orders", "{\"user\":{\"name\":\"Bob\"},\"amount\":200}")
        );

        String result = controlPlane.verify(
            "{\"channel\":\"orders\",\"payloadJsonPath\":\"user.name\",\"expectedValue\":\"Alice\",\"count\":{\"exactly\":1}}");
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldPassWithBothSubstringAndJsonPathMatch() {
        addMessages(
            msg("orders", "{\"user\":{\"name\":\"Alice\"},\"status\":\"completed\"}"),
            msg("orders", "{\"user\":{\"name\":\"Alice\"},\"status\":\"pending\"}")
        );

        String result = controlPlane.verify(
            "{\"channel\":\"orders\",\"payloadSubstring\":\"completed\",\"payloadJsonPath\":\"user.name\",\"expectedValue\":\"Alice\",\"count\":{\"exactly\":1}}");
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldPassWhenExactlyZeroAndNoMessages() {
        // No subscriber added, no messages exist
        String result = controlPlane.verify(
            "{\"channel\":\"orders\",\"count\":{\"exactly\":0}}");
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldPassWithAtMostZeroAndNoMessages() {
        String result = controlPlane.verify(
            "{\"channel\":\"nonexistent\",\"count\":{\"atMost\":0}}");
        assertThat(result, is(nullValue()));
    }

    // ---- Failure cases ----

    @Test
    public void shouldFailWhenNoMessagesMatchChannel() {
        addMessages(
            msg("other-channel", "hello")
        );

        String result = controlPlane.verify("{\"channel\":\"orders\"}");
        assertThat(result, is(notNullValue()));
        assertThat(result, containsString("at least 1"));
        assertThat(result, containsString("found 0"));
        assertThat(result, containsString("orders"));
    }

    @Test
    public void shouldFailWhenExactlyDoesNotMatch() {
        addMessages(
            msg("events", "a"),
            msg("events", "b")
        );

        String result = controlPlane.verify(
            "{\"channel\":\"events\",\"count\":{\"exactly\":3}}");
        assertThat(result, is(notNullValue()));
        assertThat(result, containsString("exactly 3"));
        assertThat(result, containsString("found 2"));
    }

    @Test
    public void shouldFailWhenAtLeastNotMet() {
        addMessages(
            msg("events", "a")
        );

        String result = controlPlane.verify(
            "{\"channel\":\"events\",\"count\":{\"atLeast\":5}}");
        assertThat(result, is(notNullValue()));
        assertThat(result, containsString("at least 5"));
        assertThat(result, containsString("found 1"));
    }

    @Test
    public void shouldFailWhenAtMostExceeded() {
        addMessages(
            msg("events", "a"),
            msg("events", "b"),
            msg("events", "c")
        );

        String result = controlPlane.verify(
            "{\"channel\":\"events\",\"count\":{\"atMost\":1}}");
        assertThat(result, is(notNullValue()));
        assertThat(result, containsString("at most 1"));
        assertThat(result, containsString("found 3"));
    }

    @Test
    public void shouldFailWhenPayloadSubstringNotFound() {
        addMessages(
            msg("orders", "{\"status\":\"pending\"}")
        );

        String result = controlPlane.verify(
            "{\"channel\":\"orders\",\"payloadSubstring\":\"completed\"}");
        assertThat(result, is(notNullValue()));
        assertThat(result, containsString("found 0"));
    }

    @Test
    public void shouldFailWhenJsonPathValueDoesNotMatch() {
        addMessages(
            msg("orders", "{\"user\":{\"name\":\"Bob\"}}")
        );

        String result = controlPlane.verify(
            "{\"channel\":\"orders\",\"payloadJsonPath\":\"user.name\",\"expectedValue\":\"Alice\"}");
        assertThat(result, is(notNullValue()));
        assertThat(result, containsString("found 0"));
    }

    // ---- Combined atLeast + atMost ----

    @Test
    public void shouldPassWhenBothAtLeastAndAtMostSatisfied() {
        addMessages(
            msg("events", "a"),
            msg("events", "b"),
            msg("events", "c")
        );

        String result = controlPlane.verify(
            "{\"channel\":\"events\",\"count\":{\"atLeast\":2,\"atMost\":5}}");
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldFailWhenAtLeastMetButAtMostExceeded() {
        addMessages(
            msg("events", "a"),
            msg("events", "b"),
            msg("events", "c"),
            msg("events", "d")
        );

        String result = controlPlane.verify(
            "{\"channel\":\"events\",\"count\":{\"atLeast\":1,\"atMost\":2}}");
        assertThat(result, is(notNullValue()));
        assertThat(result, containsString("at most 2"));
        assertThat(result, containsString("found 4"));
    }

    // ---- Error cases ----

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowOnNullBody() {
        controlPlane.verify(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowOnEmptyBody() {
        controlPlane.verify("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowOnBlankBody() {
        controlPlane.verify("   ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowOnInvalidJson() {
        controlPlane.verify("not json");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowWhenChannelFieldMissing() {
        controlPlane.verify("{\"payloadSubstring\":\"hello\"}");
    }

    // ---- JSON path edge cases ----

    @Test
    public void shouldHandleJsonPathToNumericValue() {
        addMessages(
            msg("orders", "{\"amount\":42}")
        );

        String result = controlPlane.verify(
            "{\"channel\":\"orders\",\"payloadJsonPath\":\"amount\",\"expectedValue\":\"42\"}");
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldHandleJsonPathToBooleanValue() {
        addMessages(
            msg("orders", "{\"active\":true}")
        );

        String result = controlPlane.verify(
            "{\"channel\":\"orders\",\"payloadJsonPath\":\"active\",\"expectedValue\":\"true\"}");
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldHandleNonExistentJsonPath() {
        addMessages(
            msg("orders", "{\"name\":\"test\"}")
        );

        // Path "missing.field" does not exist — no match
        String result = controlPlane.verify(
            "{\"channel\":\"orders\",\"payloadJsonPath\":\"missing.field\",\"expectedValue\":\"test\"}");
        assertThat(result, is(notNullValue()));
        assertThat(result, containsString("found 0"));
    }

    @Test
    public void shouldHandleNonJsonPayloadGracefully() {
        addMessages(
            msg("events", "this is plain text, not json")
        );

        // JSON path extraction fails silently — message does not match
        String result = controlPlane.verify(
            "{\"channel\":\"events\",\"payloadJsonPath\":\"field\",\"expectedValue\":\"value\"}");
        assertThat(result, is(notNullValue()));
        assertThat(result, containsString("found 0"));
    }

    @Test
    public void shouldHandleNullPayloadMessage() {
        addMessages(
            new RecordedMessage("events", null, null, Collections.emptyMap())
        );

        // Substring match on null payload — treated as empty string
        String result = controlPlane.verify(
            "{\"channel\":\"events\",\"payloadSubstring\":\"hello\"}");
        assertThat(result, is(notNullValue()));
        assertThat(result, containsString("found 0"));

        // But atLeast:1 with no payload criteria should still match
        String result2 = controlPlane.verify(
            "{\"channel\":\"events\"}");
        assertThat(result2, is(nullValue()));
    }

    // ---- Default count behavior ----

    @Test
    public void shouldDefaultToAtLeastOneWhenNoCountSpecified() {
        // No messages at all
        String result = controlPlane.verify("{\"channel\":\"orders\"}");
        assertThat(result, is(notNullValue()));
        assertThat(result, containsString("at least 1"));
    }

    // ---- Helpers ----

    private void addMessages(RecordedMessage... messages) {
        StubSubscriber subscriber = new StubSubscriber();
        for (RecordedMessage msg : messages) {
            subscriber.addMessage(msg);
        }
        controlPlane.addSubscriberForTesting(subscriber);
    }

    private static RecordedMessage msg(String channel, String payload) {
        return new RecordedMessage(channel, null, payload, Collections.emptyMap());
    }

    /**
     * A minimal subscriber stub that stores messages in memory without any broker.
     */
    private static class StubSubscriber implements MessageSubscriber {
        private final Map<String, List<RecordedMessage>> messages = new LinkedHashMap<>();

        void addMessage(RecordedMessage msg) {
            messages.computeIfAbsent(msg.getChannel(), k -> new ArrayList<>()).add(msg);
        }

        @Override
        public void subscribe(String channel) {
            messages.putIfAbsent(channel, new ArrayList<>());
        }

        @Override
        public void unsubscribe(String channel) {
            // no-op
        }

        @Override
        public List<RecordedMessage> getRecordedMessages(String channel) {
            return messages.getOrDefault(channel, Collections.emptyList());
        }

        @Override
        public List<RecordedMessage> getAllRecordedMessages() {
            List<RecordedMessage> all = new ArrayList<>();
            messages.values().forEach(all::addAll);
            return all;
        }

        @Override
        public void close() {
            messages.clear();
        }
    }
}
