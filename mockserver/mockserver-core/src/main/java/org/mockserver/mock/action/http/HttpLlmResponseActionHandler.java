package org.mockserver.mock.action.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.ProviderCodecRegistry;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.*;
import org.mockserver.validator.jsonschema.JsonSchemaValidator;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;
import static org.mockserver.model.HttpResponse.response;

public class HttpLlmResponseActionHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final MockServerLogger mockServerLogger;

    public HttpLlmResponseActionHandler(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
    }

    public HttpResponse handle(HttpLlmResponse httpLlmResponse, HttpRequest request) {
        Provider provider = httpLlmResponse.getProvider();
        if (provider == null) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXPECTATION_RESPONSE)
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setMessageFormat("no provider specified for LLM response action for request:{}")
                        .setArguments(request)
                );
            }
            return response()
                .withStatusCode(400)
                .withBody("{\"error\":\"unsupported LLM provider: null\",\"supported\":" + supportedProvidersJson() + "}");
        }

        ProviderCodecRegistry registry = ProviderCodecRegistry.getInstance();
        Optional<ProviderCodec> codec = registry.lookup(provider);
        if (!codec.isPresent()) {
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXPECTATION_RESPONSE)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setMessageFormat("no codec registered for provider {} for LLM response action for request:{}")
                        .setArguments(provider, request)
                );
            }
            return response()
                .withStatusCode(400)
                .withBody("{\"error\":\"unsupported LLM provider: " + provider + "\",\"supported\":" + supportedProvidersJson() + "}");
        }

        String model = httpLlmResponse.getModel();
        ProviderCodec codecInstance = codec.get();

        try {
            // Embedding path
            if (httpLlmResponse.getEmbedding() != null) {
                String inputText = extractInputFromRequest(request);
                return codecInstance.encodeEmbedding(httpLlmResponse.getEmbedding(), inputText);
            }

            // Non-streaming completion path
            Completion completion = httpLlmResponse.getCompletion();
            if (completion != null && !Boolean.TRUE.equals(completion.getStreaming())) {
                HttpResponse encoded = codecInstance.encode(completion, model);
                validateStructuredOutput(completion, encoded, provider, request);
                org.mockserver.telemetry.GenAiSpans.recordCompletion(provider, model, completion);
                return encoded;
            }

            // Streaming completion path: this method only handles non-streaming.
            // Streaming is dispatched via handleStreaming() from HttpActionHandler,
            // so reaching here means the caller bypassed the normal dispatch.
            if (completion != null && Boolean.TRUE.equals(completion.getStreaming())) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(EXPECTATION_RESPONSE)
                            .setLogLevel(Level.WARN)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("streaming LLM response reached the non-streaming handle() path for provider {} — dispatcher bug; returning 501")
                            .setArguments(provider)
                    );
                }
                return response()
                    .withStatusCode(501)
                    .withBody("{\"error\":\"streaming LLM responses must be dispatched through the SSE handler\"}");
            }

            // No completion or embedding configured
            return response()
                .withStatusCode(400)
                .withBody("{\"error\":\"httpLlmResponse must have either a completion or embedding configured\"}");
        } catch (UnsupportedOperationException e) {
            return response()
                .withStatusCode(400)
                .withBody("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXPECTATION_RESPONSE)
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setMessageFormat("llm codec encode failed for provider {} for request:{}")
                        .setArguments(provider, request)
                        .setThrowable(e)
                );
            }
            return response()
                .withStatusCode(502)
                .withBody("{\"error\":\"llm codec encode failed\",\"provider\":\"" + provider.name() + "\"}");
        }
    }

    static final String STRUCTURED_OUTPUT_INVALID_HEADER = "x-mockserver-structured-output-invalid";

    /**
     * Validate the completion's configured text against its declared
     * {@link Completion#getOutputSchema() output schema}, if any. Fail-soft: a
     * mismatch never alters the response body — it adds the
     * {@code x-mockserver-structured-output-invalid} diagnostic header (when an
     * {@code encoded} response is supplied) and logs a warning. A blank schema,
     * absent text, or a malformed schema are all treated as "nothing to check"
     * and never throw, so structured-output validation can never break an LLM
     * response. {@code encoded} may be {@code null} (e.g. streaming) — then only
     * the warning is logged.
     */
    void validateStructuredOutput(Completion completion, HttpResponse encoded, Provider provider, HttpRequest request) {
        if (completion == null) {
            return;
        }
        String schema = completion.getOutputSchema();
        String text = completion.getText();
        if (schema == null || schema.trim().isEmpty() || text == null) {
            return;
        }
        try {
            String error = new JsonSchemaValidator(mockServerLogger, schema).isValid(text, false);
            if (error != null && !error.isEmpty()) {
                if (encoded != null) {
                    encoded.withHeader(STRUCTURED_OUTPUT_INVALID_HEADER, compactHeaderValue(error));
                }
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(EXPECTATION_RESPONSE)
                            .setLogLevel(Level.WARN)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("configured LLM completion text for provider {} does not conform to its declared outputSchema:{}")
                            .setArguments(provider, error)
                    );
                }
            }
        } catch (Exception e) {
            // a malformed schema must never break the response — surface it as a warning only
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXPECTATION_RESPONSE)
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setMessageFormat("could not validate LLM completion text against outputSchema for provider {} — treating schema as a no-op:{}")
                        .setArguments(provider, e.getMessage())
                );
            }
        }
    }

    /**
     * Flatten a (possibly multi-line) validation message into a single header-safe
     * line, collapsing CR/LF — HTTP header values must not contain line breaks.
     */
    private static String compactHeaderValue(String message) {
        return message.replaceAll("[\\r\\n]+", "; ").trim();
    }

    /**
     * Handle streaming LLM response by producing a list of SSE events.
     * Called by HttpActionHandler when streaming is detected.
     *
     * @param httpLlmResponse the LLM response action
     * @param request         the original HTTP request
     * @return list of SSE events to be sent through the SSE handler
     */
    public List<SseEvent> handleStreaming(HttpLlmResponse httpLlmResponse, HttpRequest request) {
        Provider provider = httpLlmResponse.getProvider();
        ProviderCodecRegistry registry = ProviderCodecRegistry.getInstance();
        ProviderCodec codecInstance = registry.lookup(provider)
            .orElseThrow(() -> new IllegalStateException("no codec registered for provider " + provider));

        Completion completion = httpLlmResponse.getCompletion();
        String model = httpLlmResponse.getModel();
        StreamingPhysics physics = completion.getStreamingPhysics();

        List<SseEvent> events = codecInstance.encodeStreaming(completion, model, physics);
        validateStructuredOutput(completion, null, provider, request);
        org.mockserver.telemetry.GenAiSpans.recordCompletion(provider, model, completion);
        return applyStreamingChaos(events, httpLlmResponse.getChaos());
    }

    /**
     * Returns an error {@link HttpResponse} (status + optional {@code Retry-After})
     * when the chaos profile triggers a probabilistic error, or {@code null}
     * otherwise. An {@code errorStatus} with no {@code errorProbability} always
     * fires; a fractional probability draws once (reproducible via {@code seed}).
     * Applies to both streaming and non-streaming responses — a provider error is
     * a normal HTTP response, not an SSE stream.
     */
    public HttpResponse chaosErrorResponseOrNull(HttpLlmResponse httpLlmResponse) {
        LlmChaosProfile chaos = httpLlmResponse.getChaos();
        if (chaos == null || chaos.getErrorStatus() == null) {
            return null;
        }
        if (!ChaosProbability.shouldInject(chaos.getErrorProbability(), chaos.getSeed())) {
            return null;
        }
        HttpResponse errorResponse = response()
            .withStatusCode(chaos.getErrorStatus())
            .withHeader("content-type", "application/json")
            .withBody("{\"error\":{\"type\":\"chaos_injected\",\"message\":\"injected chaos error\"}}");
        if (chaos.getRetryAfter() != null && !chaos.getRetryAfter().isEmpty()) {
            errorResponse.withHeader("Retry-After", chaos.getRetryAfter());
        }
        return errorResponse;
    }

    /**
     * Apply streaming chaos to the SSE event list: mid-stream truncation (keep a
     * leading fraction of events) and/or a malformed (broken-JSON) trailing
     * chunk. Deterministic. Returns the input unchanged when no streaming chaos
     * is configured.
     */
    List<SseEvent> applyStreamingChaos(List<SseEvent> events, LlmChaosProfile chaos) {
        if (chaos == null || events == null || events.isEmpty()) {
            return events;
        }
        List<SseEvent> result = events;
        if (chaos.getTruncateMode() == LlmChaosProfile.TruncateMode.MID_STREAM) {
            double fraction = chaos.getTruncateAtFraction() != null ? chaos.getTruncateAtFraction() : 0.5;
            if (fraction < 0.0) {
                fraction = 0.0;
            }
            if (fraction > 1.0) {
                fraction = 1.0;
            }
            int keep = (int) Math.floor(events.size() * fraction);
            result = new ArrayList<>(events.subList(0, keep));
        }
        if (Boolean.TRUE.equals(chaos.getMalformedSse())) {
            // append a deliberately broken-JSON chunk so the client must handle a
            // corrupt mid-stream event (missing closing brace)
            result = new ArrayList<>(result);
            result.add(SseEvent.sseEvent().withData("{\"malformed\":true"));
        }
        return result;
    }

    private String extractInputFromRequest(HttpRequest request) {
        if (request.getBody() != null) {
            String bodyString = request.getBody().toString();
            try {
                JsonNode bodyNode = OBJECT_MAPPER.readTree(bodyString);
                JsonNode inputNode = bodyNode.get("input");
                if (inputNode != null) {
                    if (inputNode.isTextual()) {
                        return inputNode.asText();
                    }
                    return inputNode.toString();
                }
            } catch (Exception e) {
                // not parseable, return empty
            }
        }
        return "";
    }

    private String supportedProvidersJson() {
        List<String> names = ProviderCodecRegistry.getInstance().supportedProviderNames();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(names.get(i)).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }
}
