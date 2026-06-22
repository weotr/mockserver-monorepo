package org.mockserver.templates.engine;

import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpTemplate;
import org.mockserver.templates.engine.mustache.MustacheTemplateEngine;
import org.mockserver.templates.engine.velocity.VelocityTemplateEngine;

import static org.slf4j.event.Level.WARN;

/**
 * Resolves an opt-in template {@link Delay} into a concrete millisecond {@link Delay} by rendering its
 * template against the incoming request (the same request context exposed to response templates).
 * <p>
 * Resolution is deliberately fail-safe: any rendering failure, an unsupported template type, or a
 * rendered value that does not parse to a non-negative whole number of milliseconds falls back to the
 * delay's static {@code value}/{@code timeUnit} pair (zero by default) rather than failing the response.
 * A {@link Delay} without a template is returned unchanged, so static and statistical delays behave
 * byte-for-byte as before.
 *
 * @author jamesdbloom
 */
public class DelayTemplateResolver {

    private final MockServerLogger mockServerLogger;
    private final Configuration configuration;
    private VelocityTemplateEngine velocityTemplateEngine;
    private MustacheTemplateEngine mustacheTemplateEngine;

    public DelayTemplateResolver(MockServerLogger mockServerLogger, Configuration configuration) {
        this.mockServerLogger = mockServerLogger;
        this.configuration = configuration;
    }

    /**
     * Returns a delay whose duration is fixed for the supplied request. When {@code delay} carries a
     * template it is rendered and parsed into a millisecond delay; otherwise (or on any failure) the
     * original {@code delay} is returned unchanged.
     */
    public Delay resolve(Delay delay, HttpRequest request) {
        if (delay == null || !delay.hasTemplate() || request == null) {
            return delay;
        }
        TemplateEngine templateEngine = templateEngineFor(delay.getTemplateType());
        if (templateEngine == null) {
            return fallback(delay, "unsupported delay templateType " + delay.getTemplateType(), null, request);
        }
        try {
            String rendered = templateEngine.renderTemplate(delay.getTemplate(), request);
            Long millis = parseMillis(rendered);
            if (millis == null) {
                return fallback(delay, "delay template rendered non-numeric value \"" + rendered + "\"", null, request);
            }
            return Delay.milliseconds(millis);
        } catch (Throwable throwable) {
            return fallback(delay, "exception rendering delay template", throwable, request);
        }
    }

    private TemplateEngine templateEngineFor(HttpTemplate.TemplateType templateType) {
        switch (templateType) {
            case VELOCITY:
                if (velocityTemplateEngine == null) {
                    velocityTemplateEngine = new VelocityTemplateEngine(mockServerLogger, configuration);
                }
                return velocityTemplateEngine;
            case MUSTACHE:
                if (mustacheTemplateEngine == null) {
                    mustacheTemplateEngine = new MustacheTemplateEngine(mockServerLogger, configuration);
                }
                return mustacheTemplateEngine;
            default:
                // JavaScript is not supported for delay templating (see TemplateEngine.renderTemplate).
                return null;
        }
    }

    /**
     * Parses the rendered template text into a non-negative whole number of milliseconds. A blank value,
     * a value that is not a whole number, or a negative value is rejected (returns {@code null}); decimal
     * outputs (e.g. {@code "250.0"}) are truncated towards zero so a numeric expression result still parses.
     */
    private static Long parseMillis(String rendered) {
        if (rendered == null) {
            return null;
        }
        String trimmed = rendered.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            long millis = Long.parseLong(trimmed);
            return millis >= 0 ? millis : null;
        } catch (NumberFormatException notALong) {
            try {
                double asDouble = Double.parseDouble(trimmed);
                if (Double.isNaN(asDouble) || Double.isInfinite(asDouble) || asDouble < 0 || asDouble > Long.MAX_VALUE) {
                    return null;
                }
                return (long) asDouble;
            } catch (NumberFormatException notADouble) {
                return null;
            }
        }
    }

    private Delay fallback(Delay delay, String reason, Throwable throwable, HttpRequest request) {
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
            LogEntry logEntry = new LogEntry()
                .setLogLevel(WARN)
                .setHttpRequest(request)
                .setMessageFormat("falling back to static delay because:{}")
                .setArguments(reason);
            if (throwable != null) {
                logEntry.setThrowable(throwable);
            }
            mockServerLogger.logEvent(logEntry);
        }
        // Strip the template so the fallback delay applies its static value/timeUnit only.
        return new Delay(delay.getTimeUnit(), delay.getValue(), delay.getDistribution());
    }
}
