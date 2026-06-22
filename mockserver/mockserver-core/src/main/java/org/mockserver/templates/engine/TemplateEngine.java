package org.mockserver.templates.engine;

import org.mockserver.serialization.model.DTO;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

/**
 * @author jamesdbloom
 */
public interface TemplateEngine {

    <T> T executeTemplate(String template, HttpRequest httpRequest, Class<? extends DTO<T>> dtoClass);

    <T> T executeTemplate(String template, HttpRequest httpRequest, HttpResponse httpResponse, Class<? extends DTO<T>> dtoClass);

    /**
     * Renders a template against the request and returns the raw rendered text, without attempting to
     * deserialize it into an {@link org.mockserver.model.HttpResponse} / {@link HttpRequest}. Used to
     * template the contents of a {@link org.mockserver.model.FileBody} so an externally stored response
     * body can contain template placeholders. Supported by the text-based engines (Velocity, Mustache).
     */
    String renderTemplate(String template, HttpRequest httpRequest);

    /**
     * Renders a template against the request with an optional per-iteration load-scenario
     * variable injected under the key {@code "iteration"}, returning the raw rendered text.
     * Used only by the load-generation executor to vary a request step's fields per iteration
     * (e.g. {@code $iteration.index}). When {@code iteration} is {@code null} this is identical
     * to {@link #renderTemplate(String, HttpRequest)}. The default delegates to that method so
     * engines that do not support the iteration variable (JavaScript via this text path) still
     * render the request context unchanged.
     *
     * @param template     the template text
     * @param httpRequest  the request context
     * @param iteration    the per-iteration variable, or {@code null} for no iteration context
     */
    default String renderTemplate(String template, HttpRequest httpRequest, org.mockserver.load.IterationContext iteration) {
        return renderTemplate(template, httpRequest);
    }

}
