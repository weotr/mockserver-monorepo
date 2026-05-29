package org.mockserver.templates.engine.javascript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.model.DTO;
import org.mockserver.templates.engine.TemplateFunctions;
import org.mockserver.templates.engine.model.HttpRequestTemplateObject;
import org.mockserver.templates.engine.model.HttpResponseTemplateObject;
import org.mockserver.templates.engine.serializer.HttpTemplateOutputDeserializer;
import org.slf4j.event.Level;

import java.util.function.Predicate;

import static org.mockserver.log.model.LogEntry.LogMessageType.TEMPLATE_GENERATED;
import static org.mockserver.log.model.LogEntryMessages.TEMPLATE_GENERATED_MESSAGE_FORMAT;

/**
 * Holder class for the GraalVM Polyglot API. Loaded lazily by {@link JavaScriptTemplateEngine#executeTemplate}
 * only when {@code POLYGLOT_AVAILABLE} is true. Keeping the {@code org.graalvm.polyglot.*} static
 * references in a separate class ensures the standard MockServer distribution (which does not bundle
 * GraalVM) can still load {@code JavaScriptTemplateEngine} and degrade gracefully.
 */
final class PolyglotRunner {

    private PolyglotRunner() {
    }

    static <T> T run(
        String script,
        boolean includeResponse,
        HttpRequest request,
        HttpResponse response,
        Predicate<String> classFilter,
        ObjectMapper objectMapper,
        MockServerLogger mockServerLogger,
        HttpTemplateOutputDeserializer httpTemplateOutputDeserializer,
        Class<? extends DTO<T>> dtoClass
    ) {
        String serialiseFunction = includeResponse
            ? " function serialise(request, response) { return JSON.stringify(handle(JSON.parse(request), JSON.parse(response)), null, 2); }"
            : " function serialise(request) { return JSON.stringify(handle(JSON.parse(request)), null, 2); }";
        String fullScript = script + serialiseFunction;

        // HostAccess.ALL is equivalent to the previous JSR-223 polyglot.js.allowHostAccess=true.
        // The security boundary is allowHostClassLookup(classFilter), which gates which classes
        // templates can resolve via Java.type(...). HostAccess.EXPLICIT/CONSTRAINED would narrow
        // the attack surface further but require annotating template helper classes.
        try (Context context = Context.newBuilder("js")
            .allowHostAccess(HostAccess.ALL)
            .allowHostClassLookup(classFilter)
            .build()) {

            // In GraalVM Polyglot, context.getBindings("js") returns the JavaScript global scope
            // (not a separate host bindings layer). Both putMember (to inject host values like
            // BUILT_IN_HELPERS) and getMember (to retrieve JS-defined functions after context.eval)
            // operate on the same JS global object. Do not switch this to context.getPolyglotBindings()
            // — that's a different scope and serialise() would be invisible.
            Value jsBindings = context.getBindings("js");
            // BUILT_IN_FUNCTIONS suppliers are evaluated once per template execution. Previously
            // (via JSR-223 ScriptBindings), they were evaluated lazily on each JS variable access,
            // so a template reading $uuid twice would get two different UUIDs. This is a behavioural
            // change documented in the changelog; templates relying on per-access freshness should
            // call the supplier explicitly via a host helper.
            TemplateFunctions.BUILT_IN_FUNCTIONS.forEach((key, supplier) ->
                jsBindings.putMember(key, supplier.get()));
            TemplateFunctions.BUILT_IN_HELPERS.forEach(jsBindings::putMember);

            Source source = Source.create("js", fullScript);
            context.eval(source);

            Value serialiseFunc = jsBindings.getMember("serialise");
            Value stringifiedResult;
            if (includeResponse) {
                stringifiedResult = serialiseFunc.execute(
                    new HttpRequestTemplateObject(request),
                    new HttpResponseTemplateObject(response)
                );
            } else {
                stringifiedResult = serialiseFunc.execute(
                    new HttpRequestTemplateObject(request)
                );
            }

            String stringifiedResponse = stringifiedResult.asString();

            JsonNode generatedObject = null;
            try {
                generatedObject = objectMapper.readTree(stringifiedResponse);
            } catch (Throwable throwable) {
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.INFO)
                            .setHttpRequest(request)
                            .setMessageFormat("exception deserialising generated content:{}into json node for request:{}")
                            .setArguments(stringifiedResponse, request)
                    );
                }
            }
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(TEMPLATE_GENERATED)
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat(TEMPLATE_GENERATED_MESSAGE_FORMAT)
                        .setArguments(generatedObject != null ? generatedObject : stringifiedResponse, script, request)
                );
            }
            return httpTemplateOutputDeserializer.deserializer(request, stringifiedResponse, dtoClass);
        }
    }
}
