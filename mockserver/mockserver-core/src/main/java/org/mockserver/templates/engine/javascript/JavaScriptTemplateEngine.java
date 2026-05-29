package org.mockserver.templates.engine.javascript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.DTO;
import org.mockserver.templates.engine.TemplateEngine;
import org.mockserver.templates.engine.TemplateFunctions;
import org.mockserver.templates.engine.model.HttpRequestTemplateObject;
import org.mockserver.templates.engine.model.HttpResponseTemplateObject;
import org.mockserver.templates.engine.serializer.HttpTemplateOutputDeserializer;
import org.slf4j.event.Level;

import java.util.function.Predicate;
import java.util.stream.StreamSupport;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.formatting.StringFormatter.formatLogMessage;
import static org.mockserver.formatting.StringFormatter.indentAndToString;

/**
 * @author jamesdbloom
 */
@SuppressWarnings({"RedundantSuppression", "FieldMayBeFinal"})
public class JavaScriptTemplateEngine implements TemplateEngine {

    private static final boolean POLYGLOT_AVAILABLE;

    static {
        boolean available;
        try {
            Class.forName("org.graalvm.polyglot.Context");
            available = true;
        } catch (ClassNotFoundException e) {
            available = false;
        }
        POLYGLOT_AVAILABLE = available;
    }

    private final ObjectMapper objectMapper;
    private final MockServerLogger mockServerLogger;
    private final HttpTemplateOutputDeserializer httpTemplateOutputDeserializer;
    private final Configuration configuration;
    private final Predicate<String> classFilter;

    public JavaScriptTemplateEngine(MockServerLogger mockServerLogger, Configuration configuration) {
        this.configuration = (configuration == null) ? configuration() : configuration;
        this.mockServerLogger = mockServerLogger;
        this.httpTemplateOutputDeserializer = new HttpTemplateOutputDeserializer(mockServerLogger);
        this.objectMapper = ObjectMapperFactory.createObjectMapper();
        this.classFilter = className -> isClassAllowed(className, this.configuration);
        if (mockServerLogger != null
            && mockServerLogger.isEnabledForInstance(Level.WARN)
            && !isNotBlank(this.configuration.javascriptDisallowedClasses())) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("JavaScript template engine has no class restrictions (mockserver.javascriptDisallowedClasses is empty). Templates can use Java.type(\"...\") to instantiate arbitrary Java classes including Runtime — only use JavaScript templates from trusted sources, or populate mockserver.javascriptDisallowedClasses with at least java.lang.Runtime,java.lang.ProcessBuilder,java.lang.System.")
            );
        }
    }

    public static boolean isPolyglotAvailable() {
        return POLYGLOT_AVAILABLE;
    }

    private static boolean isClassAllowed(String className, Configuration configuration) {
        if (isNotBlank(configuration.javascriptDisallowedClasses())) {
            Iterable<String> restrictedClasses = Splitter.on(",").trimResults().split(configuration.javascriptDisallowedClasses());
            return StreamSupport.stream(restrictedClasses.spliterator(), false)
                .noneMatch(restrictedClass -> restrictedClass.equalsIgnoreCase(className));
        }
        return true;
    }

    @Override
    public synchronized <T> T executeTemplate(String template, HttpRequest request, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, request, null, dtoClass, false);
    }

    @Override
    public synchronized <T> T executeTemplate(String template, HttpRequest request, HttpResponse response, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, request, response, dtoClass, true);
    }

    private <T> T executeTemplateInternal(String template, HttpRequest request, HttpResponse response, Class<? extends DTO<T>> dtoClass, boolean includeResponse) {
        String script = includeResponse ? wrapTemplateWithResponse(template) : wrapTemplate(template);
        try {
            validateTemplate(template);
            if (POLYGLOT_AVAILABLE) {
                // Delegate to PolyglotRunner (nested holder class). The JVM only resolves the
                // org.graalvm.polyglot.* references inside PolyglotRunner when this branch is
                // taken, so the standard distribution (no GraalVM on classpath) loads this class
                // and degrades gracefully via the else branch instead of failing with NoClassDefFoundError.
                return PolyglotRunner.run(
                    script,
                    includeResponse,
                    request,
                    response,
                    classFilter,
                    objectMapper,
                    mockServerLogger,
                    httpTemplateOutputDeserializer,
                    dtoClass
                );
            } else {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setHttpRequest(request)
                        .setMessageFormat(
                            "JavaScript based templating requires GraalVM Polyglot on the classpath, " +
                                "please add org.graalvm.polyglot:polyglot and org.graalvm.polyglot:js to the classpath, " +
                                "or use the MockServer 'graaljs' Docker image variant"
                        )
                        .setArguments(new RuntimeException("GraalVM Polyglot API not on classpath"))
                );
                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException(formatLogMessage("Exception:{}transforming template:{}for request:{}", isNotBlank(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName(), template, request), e);
        }
    }

    static String wrapTemplate(String template) {
        return "function handle(request) {" + indentAndToString(template)[0] + "}";
    }

    static String wrapTemplateWithResponse(String template) {
        return "function handle(request, response) {" + indentAndToString(template)[0] + "}";
    }

    private void validateTemplate(String template) {
        if (isNotBlank(template) && isNotBlank(configuration.javascriptDisallowedText())) {
            Iterable<String> deniedStrings = Splitter.on(",").trimResults().split(configuration.javascriptDisallowedText());
            for (String deniedString : deniedStrings) {
                if (template.contains(deniedString)) {
                    throw new UnsupportedOperationException("Found disallowed string \"" + deniedString + "\" in template: " + template);
                }
            }
        }
    }

}
