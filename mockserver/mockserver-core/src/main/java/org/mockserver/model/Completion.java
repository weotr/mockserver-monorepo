package org.mockserver.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class Completion extends ObjectWithJsonToString {
    private int hashCode;
    private String text;
    private List<ToolUse> toolCalls;
    private String stopReason;
    private Usage usage;
    private Boolean streaming;
    private StreamingPhysics streamingPhysics;
    private String outputSchema;

    public static Completion completion() {
        return new Completion();
    }

    public Completion withText(String text) {
        this.text = text;
        this.hashCode = 0;
        return this;
    }

    public String getText() {
        return text;
    }

    public Completion withToolCalls(List<ToolUse> toolCalls) {
        this.toolCalls = toolCalls;
        this.hashCode = 0;
        return this;
    }

    public Completion withToolCalls(ToolUse... toolCalls) {
        this.toolCalls = Arrays.asList(toolCalls);
        this.hashCode = 0;
        return this;
    }

    public Completion withToolCall(ToolUse toolCall) {
        if (this.toolCalls == null) {
            this.toolCalls = new ArrayList<>();
        }
        this.toolCalls.add(toolCall);
        this.hashCode = 0;
        return this;
    }

    public List<ToolUse> getToolCalls() {
        return toolCalls;
    }

    public Completion withStopReason(String stopReason) {
        this.stopReason = stopReason;
        this.hashCode = 0;
        return this;
    }

    public String getStopReason() {
        return stopReason;
    }

    public Completion withUsage(Usage usage) {
        this.usage = usage;
        this.hashCode = 0;
        return this;
    }

    public Usage getUsage() {
        return usage;
    }

    public Completion withStreaming(Boolean streaming) {
        this.streaming = streaming;
        this.hashCode = 0;
        return this;
    }

    public Completion streaming() {
        return withStreaming(Boolean.TRUE);
    }

    public Boolean getStreaming() {
        return streaming;
    }

    public Completion withStreamingPhysics(StreamingPhysics streamingPhysics) {
        this.streamingPhysics = streamingPhysics;
        this.hashCode = 0;
        return this;
    }

    /**
     * Compose streaming physics from independent values. Accepts any combination of
     * {@link org.mockserver.model.Delay} (interpreted as time-to-first-token) and
     * {@link StreamingPhysics} fragments (each typically carrying a single field —
     * e.g. {@code tokensPerSecond(50)} or {@code jitter(0.2)}). Non-null fields from
     * the fragments are merged left-to-right onto a single {@link StreamingPhysics}
     * instance which is then assigned to this completion. Calling implicitly enables
     * streaming.
     */
    public Completion withStreamingPhysics(Object... parts) {
        StreamingPhysics merged = StreamingPhysics.streamingPhysics();
        for (Object part : parts) {
            if (part == null) {
                continue;
            }
            if (part instanceof Delay) {
                merged.withTimeToFirstToken((Delay) part);
            } else if (part instanceof StreamingPhysics) {
                StreamingPhysics fragment = (StreamingPhysics) part;
                if (fragment.getTimeToFirstToken() != null) {
                    merged.withTimeToFirstToken(fragment.getTimeToFirstToken());
                }
                if (fragment.getTokensPerSecond() != null) {
                    merged.withTokensPerSecond(fragment.getTokensPerSecond());
                }
                if (fragment.getJitter() != null) {
                    merged.withJitter(fragment.getJitter());
                }
                if (fragment.getSeed() != null) {
                    merged.withSeed(fragment.getSeed());
                }
            } else {
                throw new IllegalArgumentException(
                    "withStreamingPhysics accepts Delay or StreamingPhysics fragments; got: "
                        + part.getClass().getName());
            }
        }
        this.streamingPhysics = merged;
        this.hashCode = 0;
        return this;
    }

    public StreamingPhysics getStreamingPhysics() {
        return streamingPhysics;
    }

    /**
     * Optional JSON Schema (as a JSON string) that this completion's {@link #getText() text}
     * is expected to conform to. When set, the LLM response handler validates the configured
     * text against the schema as the response is encoded. Validation is fail-soft: a mismatch
     * does not alter the response body — it adds an {@code x-mockserver-structured-output-invalid}
     * diagnostic header and logs a warning, so a deliberately non-conforming fixture still
     * returns exactly as configured while malformed structured-output fixtures are surfaced.
     */
    public Completion withOutputSchema(String outputSchema) {
        this.outputSchema = outputSchema;
        this.hashCode = 0;
        return this;
    }

    public String getOutputSchema() {
        return outputSchema;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        Completion that = (Completion) o;
        return Objects.equals(text, that.text) &&
            Objects.equals(toolCalls, that.toolCalls) &&
            Objects.equals(stopReason, that.stopReason) &&
            Objects.equals(usage, that.usage) &&
            Objects.equals(streaming, that.streaming) &&
            Objects.equals(streamingPhysics, that.streamingPhysics) &&
            Objects.equals(outputSchema, that.outputSchema);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(text, toolCalls, stopReason, usage, streaming, streamingPhysics, outputSchema);
        }
        return hashCode;
    }
}
