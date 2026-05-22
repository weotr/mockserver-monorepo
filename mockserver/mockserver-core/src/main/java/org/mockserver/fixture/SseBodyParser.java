package org.mockserver.fixture;

import org.mockserver.model.Delay;
import org.mockserver.model.SseEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockserver.model.SseEvent.sseEvent;

/**
 * Parses raw {@code text/event-stream} bytes into a list of {@link SseEvent} objects
 * suitable for constructing an {@link org.mockserver.model.HttpSseResponse}.
 * <p>
 * The parser follows the SSE specification (W3C Server-Sent Events):
 * <ul>
 *   <li>Events are separated by blank lines ({@code \n\n})</li>
 *   <li>Lines starting with {@code data:} set the data field</li>
 *   <li>Lines starting with {@code event:} set the event type</li>
 *   <li>Lines starting with {@code id:} set the last event ID</li>
 *   <li>Lines starting with {@code retry:} set the reconnection time</li>
 *   <li>Lines starting with {@code :} are comments and are ignored</li>
 *   <li>Multiple {@code data:} lines within one event are joined with {@code \n}</li>
 * </ul>
 * <p>
 * Since per-chunk timestamps are not captured by the streaming relay, a fixed inter-event
 * delay is applied to each event (except the first). The default is 50 milliseconds.
 */
public class SseBodyParser {

    /** Default inter-event delay in milliseconds when per-chunk timestamps are not available. */
    public static final long DEFAULT_INTER_EVENT_DELAY_MS = 50;

    private final long interEventDelayMs;

    /**
     * Create a parser with the default inter-event delay.
     */
    public SseBodyParser() {
        this(DEFAULT_INTER_EVENT_DELAY_MS);
    }

    /**
     * Create a parser with a custom inter-event delay.
     *
     * @param interEventDelayMs delay in milliseconds between events on replay
     */
    public SseBodyParser(long interEventDelayMs) {
        this.interEventDelayMs = Math.max(0, interEventDelayMs);
    }

    /**
     * Parse raw SSE body text into a list of {@link SseEvent} objects.
     *
     * @param sseText the raw SSE body text (e.g., captured from a forwarded streaming response)
     * @return the parsed events; empty list if the input is null or blank
     */
    public List<SseEvent> parse(String sseText) {
        List<SseEvent> events = new ArrayList<>();
        if (sseText == null || sseText.isEmpty()) {
            return events;
        }

        // Split on blank lines (event boundaries in SSE). Handle both \n\n and \r\n\r\n.
        String[] rawBlocks = sseText.split("\\r?\\n\\r?\\n");
        boolean isFirst = true;

        for (String block : rawBlocks) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            SseEvent event = parseBlock(trimmed);
            if (event != null) {
                if (!isFirst && interEventDelayMs > 0) {
                    event.withDelay(new Delay(TimeUnit.MILLISECONDS, interEventDelayMs));
                }
                events.add(event);
                isFirst = false;
            }
        }

        return events;
    }

    private SseEvent parseBlock(String block) {
        String eventType = null;
        String id = null;
        Integer retry = null;
        StringBuilder data = new StringBuilder();
        boolean hasData = false;

        String[] lines = block.split("\\r?\\n");
        for (String line : lines) {
            if (line.startsWith(":")) {
                // Comment line; skip
                continue;
            }

            int colonIndex = line.indexOf(':');
            String field;
            String value;
            if (colonIndex >= 0) {
                field = line.substring(0, colonIndex);
                value = line.substring(colonIndex + 1);
                // SSE spec: if value starts with a space, strip it
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
            } else {
                field = line;
                value = "";
            }

            switch (field) {
                case "data":
                    if (hasData) {
                        data.append('\n');
                    }
                    data.append(value);
                    hasData = true;
                    break;
                case "event":
                    eventType = value;
                    break;
                case "id":
                    id = value;
                    break;
                case "retry":
                    try {
                        retry = Integer.parseInt(value.trim());
                    } catch (NumberFormatException ignored) {
                        // SSE spec says non-numeric retry values are ignored
                    }
                    break;
                default:
                    // Unknown field; ignore per SSE spec
                    break;
            }
        }

        // An event block with no data and no event type is not a valid SSE event
        if (!hasData && eventType == null && id == null && retry == null) {
            return null;
        }

        SseEvent event = sseEvent();
        if (hasData) {
            event.withData(data.toString());
        }
        if (eventType != null) {
            event.withEvent(eventType);
        }
        if (id != null) {
            event.withId(id);
        }
        if (retry != null) {
            event.withRetry(retry);
        }

        return event;
    }
}
