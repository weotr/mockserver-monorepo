package org.mockserver.fixture;

import org.junit.Test;
import org.mockserver.model.SseEvent;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;

/**
 * Tests for per-chunk replay timing in {@link SseBodyParser}. These verify that
 * captured inter-chunk delays are correctly applied to parsed SSE events, and that
 * the fallback to fixed delays works when per-chunk timing is absent.
 */
public class SseBodyParserChunkTimingTest {

    @Test
    public void shouldApplyPerChunkDelaysWhenProvided() {
        // given
        SseBodyParser parser = new SseBodyParser(50); // 50ms default fallback
        String sseText =
            "data: first\n\n" +
            "data: second\n\n" +
            "data: third\n\n";
        List<Long> perChunkDelays = Arrays.asList(0L, 120L, 45L);

        // when
        List<SseEvent> events = parser.parse(sseText, perChunkDelays);

        // then
        assertThat(events, hasSize(3));
        assertThat(events.get(0).getDelay(), nullValue()); // first event: no delay
        assertThat(events.get(1).getDelay().getValue(), is(120L)); // captured delay, not 50ms default
        assertThat(events.get(2).getDelay().getValue(), is(45L)); // captured delay, not 50ms default
    }

    @Test
    public void shouldFallBackToFixedDelayWhenNoPerChunkDelays() {
        // given
        SseBodyParser parser = new SseBodyParser(50);
        String sseText =
            "data: first\n\n" +
            "data: second\n\n" +
            "data: third\n\n";

        // when -- null perChunkDelays = fallback
        List<SseEvent> events = parser.parse(sseText, null);

        // then
        assertThat(events, hasSize(3));
        assertThat(events.get(0).getDelay(), nullValue());
        assertThat(events.get(1).getDelay().getValue(), is(50L)); // fixed default
        assertThat(events.get(2).getDelay().getValue(), is(50L)); // fixed default
    }

    @Test
    public void shouldFallBackToFixedDelayWhenPerChunkDelaysTooShort() {
        // given -- only 2 delays but 3 events
        SseBodyParser parser = new SseBodyParser(50);
        String sseText =
            "data: first\n\n" +
            "data: second\n\n" +
            "data: third\n\n";
        List<Long> perChunkDelays = Arrays.asList(0L, 100L); // missing third

        // when
        List<SseEvent> events = parser.parse(sseText, perChunkDelays);

        // then
        assertThat(events, hasSize(3));
        assertThat(events.get(0).getDelay(), nullValue());
        assertThat(events.get(1).getDelay().getValue(), is(100L)); // captured
        assertThat(events.get(2).getDelay().getValue(), is(50L)); // fallback to default
    }

    @Test
    public void shouldHandleZeroCapturedDelay() {
        // given -- zero captured delay should result in no delay being set
        SseBodyParser parser = new SseBodyParser(50);
        String sseText =
            "data: first\n\n" +
            "data: second\n\n";
        List<Long> perChunkDelays = Arrays.asList(0L, 0L);

        // when
        List<SseEvent> events = parser.parse(sseText, perChunkDelays);

        // then
        assertThat(events, hasSize(2));
        assertThat(events.get(0).getDelay(), nullValue());
        assertThat(events.get(1).getDelay(), nullValue()); // 0ms = no delay
    }

    @Test
    public void shouldHandlePerChunkDelaysLongerThanEvents() {
        // given -- more delays than events (extra delays are harmlessly ignored)
        SseBodyParser parser = new SseBodyParser(50);
        String sseText = "data: only\n\n";
        List<Long> perChunkDelays = Arrays.asList(0L, 100L, 200L);

        // when
        List<SseEvent> events = parser.parse(sseText, perChunkDelays);

        // then
        assertThat(events, hasSize(1));
        assertThat(events.get(0).getDelay(), nullValue());
    }

    @Test
    public void shouldPreserveBackwardCompatibilityWithNoArgParse() {
        // given -- calling the original parse(String) method
        SseBodyParser parser = new SseBodyParser(50);
        String sseText =
            "data: first\n\n" +
            "data: second\n\n";

        // when
        List<SseEvent> events = parser.parse(sseText);

        // then -- same behaviour as before
        assertThat(events, hasSize(2));
        assertThat(events.get(0).getDelay(), nullValue());
        assertThat(events.get(1).getDelay().getValue(), is(50L));
    }

    @Test
    public void shouldHandleRealisticCapturedTimingValues() {
        // given -- realistic inter-chunk delays from an LLM streaming response
        SseBodyParser parser = new SseBodyParser();
        String sseText =
            "event: message_start\n" +
            "data: {\"type\":\"message_start\"}\n\n" +
            "event: content_block_delta\n" +
            "data: {\"type\":\"delta\",\"text\":\"Hello\"}\n\n" +
            "event: content_block_delta\n" +
            "data: {\"type\":\"delta\",\"text\":\" world\"}\n\n" +
            "event: message_stop\n" +
            "data: {\"type\":\"message_stop\"}\n\n";
        // Realistic timing: initial latency then fast token streaming
        List<Long> perChunkDelays = Arrays.asList(0L, 350L, 15L, 12L);

        // when
        List<SseEvent> events = parser.parse(sseText, perChunkDelays);

        // then
        assertThat(events, hasSize(4));
        assertThat(events.get(0).getDelay(), nullValue());
        assertThat(events.get(1).getDelay().getValue(), is(350L)); // initial latency
        assertThat(events.get(2).getDelay().getValue(), is(15L));  // fast token
        assertThat(events.get(3).getDelay().getValue(), is(12L));  // fast token
    }
}
