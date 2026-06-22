package org.mockserver.grpc;

import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.GrpcStreamMessage;
import org.mockserver.model.HttpRequest;
import org.mockserver.templates.engine.TemplateEngine;
import org.mockserver.templates.engine.mustache.MustacheTemplateEngine;
import org.mockserver.templates.engine.velocity.VelocityTemplateEngine;

/**
 * Transport-neutral renderer that turns a {@link GrpcStreamMessage} bidi response into the JSON
 * string to emit on the wire, applying optional response templating against the matched inbound
 * gRPC message.
 * <p>
 * Shared by the HTTP/2 bidi handler ({@code GrpcBidiStreamHandler}) and the HTTP/3 bidi handler
 * ({@code Http3GrpcBidiStreamHandler}) so templating semantics are identical across transports.
 * <p>
 * <strong>Opt-in, static-preserving:</strong> when {@link GrpcStreamMessage#getTemplateType()} is
 * {@code null} (the default), {@link #render} returns {@link GrpcStreamMessage#getJson()} verbatim
 * &mdash; static (non-template) bidi responses are byte-for-byte unchanged. Templating is only
 * applied when a {@code templateType} is explicitly set.
 * <p>
 * <strong>Engine reuse:</strong> this reuses the same Velocity / Mustache template engines that
 * the HTTP response-template path uses, via {@link TemplateEngine#renderTemplate(String, HttpRequest)}.
 * The inbound message JSON is exposed as the request body so a template can echo/derive fields with
 * {@code $!request.body}, {@code $jsonPath(...)}, the built-in helpers, and the {@code scenario}
 * helper (so a bidi match can also transition scenario state via {@code $scenario.set(...)}).
 * {@link org.mockserver.model.HttpTemplate.TemplateType#JAVASCRIPT} is rejected because JavaScript
 * templates construct a full response object rather than a renderable text fragment (mirroring
 * {@code TemplateEngine.renderTemplate}'s file-body templating restriction).
 * <p>
 * Engines are created lazily and cached per renderer instance (one renderer per bidi handler /
 * per stream), mirroring {@code HttpResponseTemplateActionHandler}'s lazy engine caching.
 */
public class GrpcStreamMessageTemplateRenderer {

    private final MockServerLogger mockServerLogger;
    private final Configuration configuration;
    private VelocityTemplateEngine velocityTemplateEngine;
    private MustacheTemplateEngine mustacheTemplateEngine;

    public GrpcStreamMessageTemplateRenderer(MockServerLogger mockServerLogger, Configuration configuration) {
        this.configuration = configuration != null ? configuration : Configuration.configuration();
        this.mockServerLogger = mockServerLogger != null ? mockServerLogger : new MockServerLogger(GrpcStreamMessageTemplateRenderer.class);
    }

    /**
     * Returns the JSON to emit for {@code message}. If the message has no {@code templateType}, the
     * raw {@link GrpcStreamMessage#getJson()} is returned unchanged. Otherwise the JSON is rendered
     * as a template against {@code inboundJson} (exposed as the request body).
     *
     * @param message     the configured bidi response message
     * @param inboundJson the matched inbound gRPC message converted to JSON (may be {@code null})
     * @return the JSON string to encode and write to the stream
     */
    public String render(GrpcStreamMessage message, String inboundJson) {
        if (message.getTemplateType() == null) {
            return message.getJson();
        }
        TemplateEngine templateEngine;
        switch (message.getTemplateType()) {
            case VELOCITY:
                templateEngine = getVelocityTemplateEngine();
                break;
            case MUSTACHE:
                templateEngine = getMustacheTemplateEngine();
                break;
            case JAVASCRIPT:
            default:
                throw new IllegalArgumentException(
                    "JavaScript templates are not supported for gRPC bidi stream responses; use a VELOCITY or MUSTACHE templateType");
        }
        HttpRequest inboundRequest = HttpRequest.request();
        if (inboundJson != null) {
            inboundRequest.withBody(inboundJson);
        }
        return templateEngine.renderTemplate(message.getJson(), inboundRequest);
    }

    private VelocityTemplateEngine getVelocityTemplateEngine() {
        if (velocityTemplateEngine == null) {
            velocityTemplateEngine = new VelocityTemplateEngine(mockServerLogger, configuration);
        }
        return velocityTemplateEngine;
    }

    private MustacheTemplateEngine getMustacheTemplateEngine() {
        if (mustacheTemplateEngine == null) {
            mustacheTemplateEngine = new MustacheTemplateEngine(mockServerLogger, configuration);
        }
        return mustacheTemplateEngine;
    }
}
