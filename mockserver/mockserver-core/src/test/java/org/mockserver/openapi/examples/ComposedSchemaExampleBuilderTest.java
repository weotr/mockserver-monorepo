package org.mockserver.openapi.examples;

import io.swagger.v3.oas.models.media.*;
import org.junit.Test;
import org.mockserver.file.FileReader;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.OpenAPIDefinition;
import org.mockserver.openapi.OpenAPIConverter;
import org.mockserver.openapi.examples.models.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Regression tests for example generation from {@code allOf}/{@code anyOf}/{@code oneOf}
 * ({@link io.swagger.v3.oas.models.media.ComposedSchema}).
 *
 * <p>Guards against #2357: a property whose schema is {@code allOf: [ $ref to a scalar ]}
 * must generate the scalar value (e.g. a string), not an object/array wrapper.
 */
public class ComposedSchemaExampleBuilderTest {

    private static Map<String, Schema> definitionsWithStringBar() {
        StringSchema bar = new StringSchema();
        bar.setExample("hello");
        Map<String, Schema> definitions = new HashMap<>();
        definitions.put("bar", bar);
        return definitions;
    }

    private static Schema<?> refTo(String name) {
        Schema<?> ref = new Schema<>();
        ref.set$ref("#/components/schemas/" + name);
        return ref;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldGenerateStringForAllOfWithSingleScalarRef() {
        // given - baz: { allOf: [ $ref bar ] } where bar is a string with example "hello"
        ComposedSchema baz = new ComposedSchema();
        baz.addAllOfItem(refTo("bar"));

        ObjectSchema foo = new ObjectSchema();
        foo.addProperty("baz", baz);

        // when
        Example example = ExampleBuilder.fromSchema(foo, definitionsWithStringBar());

        // then - { "baz": "hello" }, NOT { "baz": ["hello"] } or { "baz": {} }
        assertThat(example, is(instanceOf(ObjectExample.class)));
        Object bazExample = ((ObjectExample) example).get("baz");
        assertThat("allOf with a single string $ref must resolve to a string, not a wrapper",
            bazExample, is(instanceOf(StringExample.class)));
        assertThat(((StringExample) bazExample).getValue(), is("hello"));
    }

    @Test
    public void shouldGenerateStringForTopLevelAllOfWithSingleScalarRef() {
        // given - top-level schema is allOf: [ $ref bar ]
        ComposedSchema schema = new ComposedSchema();
        schema.addAllOfItem(refTo("bar"));

        // when
        Example example = ExampleBuilder.fromSchema(schema, definitionsWithStringBar());

        // then
        assertThat(example, is(instanceOf(StringExample.class)));
        assertThat(((StringExample) example).getValue(), is("hello"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldStillMergeObjectAllOfMembers() {
        // given - allOf of two object schemas must still merge into one object
        ObjectSchema a = new ObjectSchema();
        a.addProperty("x", new StringSchema());
        ObjectSchema b = new ObjectSchema();
        b.addProperty("y", new IntegerSchema());

        ComposedSchema composed = new ComposedSchema();
        composed.addAllOfItem(a);
        composed.addAllOfItem(b);

        // when
        Example example = ExampleBuilder.fromSchema(composed, new HashMap<>());

        // then - merged object with both properties
        assertThat(example, is(instanceOf(ObjectExample.class)));
        ObjectExample object = (ObjectExample) example;
        assertThat(object.keySet(), hasItems("x", "y"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldMergeObjectAllOfMembersAlongsideOwnProperties() {
        // given - allOf of an object plus the composed schema's own properties
        ObjectSchema a = new ObjectSchema();
        a.addProperty("x", new StringSchema());

        ComposedSchema composed = new ComposedSchema();
        composed.addAllOfItem(a);
        composed.addProperty("z", new StringSchema());

        // when
        Example example = ExampleBuilder.fromSchema(composed, new HashMap<>());

        // then
        assertThat(example, is(instanceOf(ObjectExample.class)));
        assertThat(((ObjectExample) example).keySet(), hasItems("x", "z"));
    }

    @Test
    public void shouldGenerateStringBodyForAllOfScalarRefEndToEnd() {
        // given - the minimal spec from issue #2357 (foo.baz is allOf a single string $ref)
        String spec = FileReader.readFileFromClassPathOrPath(
            "org/mockserver/openapi/openapi_allof_scalar_ref.yaml"
        );

        // when - the full OpenAPI -> expectation conversion runs
        List<Expectation> expectations = new OpenAPIConverter(new MockServerLogger(ComposedSchemaExampleBuilderTest.class))
            .buildExpectations(spec, null);

        // then - the generated response body must be { "baz": "hello" }, not { "baz": ["hello"] }
        String body = bodyForOperation(expectations, "getFoo");
        assertThat("baz must be the string \"hello\", not an array or object wrapper",
            body, containsString("\"baz\" : \"hello\""));
        assertThat("baz must not be wrapped in an array", body, not(containsString("[")));
    }

    @Test
    public void shouldGenerateStringBodyForTopLevelAllOfScalarRefEndToEnd() {
        // given - a response whose schema is directly allOf: [ $ref to a string ]
        String spec = FileReader.readFileFromClassPathOrPath(
            "org/mockserver/openapi/openapi_allof_scalar_ref.yaml"
        );

        // when
        List<Expectation> expectations = new OpenAPIConverter(new MockServerLogger(ComposedSchemaExampleBuilderTest.class))
            .buildExpectations(spec, null);

        // then - body is the JSON string "hello" (quoted scalar), not the array ["hello"]
        assertThat(bodyForOperation(expectations, "getBaz"), is("\"hello\""));
    }

    /**
     * Selects the response body of the expectation generated for a given operationId. The expectation's
     * request is an {@link OpenAPIDefinition}, so match on its operationId rather than on
     * {@code toString()} (which embeds the whole spec and therefore mentions every operationId).
     */
    private static String bodyForOperation(List<Expectation> expectations, String operationId) {
        return expectations.stream()
            .filter(expectation -> expectation.getHttpRequest() instanceof OpenAPIDefinition openAPIDefinition
                && operationId.equals(openAPIDefinition.getOperationId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(operationId + " expectation not found"))
            .getHttpResponse()
            .getBodyAsString();
    }
}
