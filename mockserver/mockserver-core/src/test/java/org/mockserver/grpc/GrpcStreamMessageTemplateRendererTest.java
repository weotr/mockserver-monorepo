package org.mockserver.grpc;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.GrpcStreamMessage;
import org.mockserver.model.HttpTemplate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * WS2.6: gRPC bidi-stream response templating.
 * <p>
 * Verifies the transport-neutral {@link GrpcStreamMessageTemplateRenderer} seam: a response
 * message with no {@code templateType} is emitted verbatim (static, byte-for-byte unchanged),
 * while a message with a {@code VELOCITY} / {@code MUSTACHE} template type is rendered against
 * the matched inbound message (exposed as the request body). JavaScript is rejected.
 */
public class GrpcStreamMessageTemplateRendererTest {

    private final GrpcStreamMessageTemplateRenderer renderer =
        new GrpcStreamMessageTemplateRenderer(new MockServerLogger(GrpcStreamMessageTemplateRendererTest.class), configuration());

    @Test
    public void shouldReturnStaticJsonUnchangedWhenNoTemplateType() {
        // given — a static (non-template) response message
        String json = "{\"greeting\": \"Hi Alice\", \"$ref\": \"#/not/a/template\"}";
        GrpcStreamMessage message = GrpcStreamMessage.grpcStreamMessage(json);

        // when
        String rendered = renderer.render(message, "{\"name\": \"Alice\"}");

        // then — emitted byte-for-byte unchanged
        assertThat(rendered, is(json));
    }

    @Test
    public void shouldReturnNullJsonUnchangedWhenNoTemplateType() {
        GrpcStreamMessage message = GrpcStreamMessage.grpcStreamMessage();

        assertThat(renderer.render(message, "{\"name\": \"Alice\"}"), is((String) null));
    }

    @Test
    public void shouldRenderVelocityTemplateAgainstInboundMessage() {
        // given — a Velocity template that echoes a field from the inbound message
        GrpcStreamMessage message = GrpcStreamMessage
            .grpcStreamMessage("{\"greeting\": \"Hello $jsonPath.find(\"$.name\")\"}")
            .withTemplateType(HttpTemplate.TemplateType.VELOCITY);

        // when
        String rendered = renderer.render(message, "{\"name\": \"Alice\"}");

        // then
        assertThat(rendered, is("{\"greeting\": \"Hello Alice\"}"));
    }

    @Test
    public void shouldRenderVelocityTemplateUsingRequestBody() {
        // given — a Velocity template that echoes the whole inbound message body
        GrpcStreamMessage message = GrpcStreamMessage
            .grpcStreamMessage("{\"echo\": $!request.body}")
            .withTemplateType(HttpTemplate.TemplateType.VELOCITY);

        // when
        String rendered = renderer.render(message, "{\"name\":\"Bob\"}");

        // then
        assertThat(rendered, is("{\"echo\": {\"name\":\"Bob\"}}"));
    }

    @Test
    public void shouldRenderMustacheTemplateAgainstInboundMessage() {
        // given — a Mustache template that extracts a scalar field via the jsonPath lambda
        // (the section sets jsonPathResult, which is then emitted)
        GrpcStreamMessage message = GrpcStreamMessage
            .grpcStreamMessage("{\"greeting\": \"Hello {{#jsonPath}}$.name{{/jsonPath}}{{jsonPathResult}}\"}")
            .withTemplateType(HttpTemplate.TemplateType.MUSTACHE);

        // when
        String rendered = renderer.render(message, "{\"name\": \"Carol\"}");

        // then
        assertThat(rendered, is("{\"greeting\": \"Hello Carol\"}"));
    }

    @Test
    public void shouldRejectJavaScriptTemplateType() {
        GrpcStreamMessage message = GrpcStreamMessage
            .grpcStreamMessage("return { greeting: 'hi' };")
            .withTemplateType(HttpTemplate.TemplateType.JAVASCRIPT);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> renderer.render(message, "{\"name\": \"Alice\"}"));
        assertThat(exception.getMessage(), containsString("JavaScript templates are not supported"));
    }

    @Test
    public void shouldRenderTemplateWithNullInboundMessage() {
        // given — an eager templated message has no matched inbound message
        GrpcStreamMessage message = GrpcStreamMessage
            .grpcStreamMessage("{\"greeting\": \"static-from-template\"}")
            .withTemplateType(HttpTemplate.TemplateType.VELOCITY);

        // when — null inbound (eager path) renders against an empty body without throwing
        String rendered = renderer.render(message, null);

        // then
        assertThat(rendered, is("{\"greeting\": \"static-from-template\"}"));
    }

    @Test
    public void shouldTolerateNullLoggerAndConfiguration() {
        // given — handlers may construct the renderer with a null logger / configuration
        GrpcStreamMessageTemplateRenderer lenient = new GrpcStreamMessageTemplateRenderer((MockServerLogger) null, (Configuration) null);
        GrpcStreamMessage message = GrpcStreamMessage
            .grpcStreamMessage("{\"greeting\": \"Hello $jsonPath.find(\"$.name\")\"}")
            .withTemplateType(HttpTemplate.TemplateType.VELOCITY);

        // when / then
        assertThat(lenient.render(message, "{\"name\": \"Dave\"}"), is("{\"greeting\": \"Hello Dave\"}"));
    }
}
