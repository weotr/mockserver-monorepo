package org.mockserver.serialization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

/**
 * Pretty-prints an already-parsed {@link JsonNode} tree to a JSON string.
 *
 * <p>Replaces the single {@code com.github.fge.jackson.JacksonUtils.prettyPrint(JsonNode)} call site that
 * was the only use of the {@code com.github.fge} (json-tools) library on a code path reachable from the Java
 * client. That library was pulled in transitively by the Swagger/OpenAPI parser; removing this dependency lets
 * {@code mockserver-client-java} exclude the parser entirely. The output matches the previous behaviour: a
 * two-space, line-feed indented document with {@code BigDecimal} values written in plain (non-scientific) form.
 * The result is only logged and re-parsed, so exact whitespace is not contractual.</p>
 *
 * @author jamesdbloom
 */
public class JsonPrettyPrinter {

    private static final ObjectWriter PRETTY_WRITER;

    static {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter()
            .withObjectIndenter(indenter)
            .withArrayIndenter(indenter);
        PRETTY_WRITER = new ObjectMapper()
            .writer(prettyPrinter)
            .with(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
    }

    public static String prettyPrint(JsonNode node) {
        try {
            return PRETTY_WRITER.writeValueAsString(node);
        } catch (JsonProcessingException throwable) {
            throw new IllegalArgumentException("exception pretty printing JSON", throwable);
        }
    }

}
