package org.mockserver.openapi;

import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.mockserver.file.FileReader;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpResponse;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;

/**
 * Correctness tests for OpenAPI -> expectation example resolution and operationId synthesis,
 * covering the defects fixed in {@link OpenAPIConverter} and {@link OpenAPIParser}:
 * <ol>
 *   <li>silent wrong selection when a requested status code / example name is absent (falls back, not empty 200);</li>
 *   <li>an object where only some properties carry an inline example still generates a COMPLETE example;</li>
 *   <li>an unresolvable internal {@code $ref} inside an example must not leak {@code {"$ref": ...}};</li>
 *   <li>colliding operationIds (author vs synthesized, path vs webhook) must stay distinct.</li>
 * </ol>
 */
public class OpenAPIConverterCorrectnessTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger(OpenAPIConverterCorrectnessTest.class);

    private Expectation expectationFor(List<Expectation> expectations, String operationId) {
        Optional<Expectation> match = expectations.stream()
            .filter(e -> operationId.equals(((org.mockserver.model.OpenAPIDefinition) e.getHttpRequest()).getOperationId()))
            .findFirst();
        assertThat("expected an expectation for operationId " + operationId, match.isPresent(), is(true));
        return match.get();
    }

    // --- Fix 1: absent statusCode falls back to a defined response, not an empty 200 ---

    @Test
    public void shouldFallBackToDefinedResponseWhenRequestedStatusCodeAbsent() {
        // given - showPetById defines 200, 500, default but NOT 418
        String specUrlOrPayload = FileReader.readFileFromClassPathOrPath(
            "org/mockserver/openapi/openapi_petstore_example_with_examples.yaml"
        );

        // when - pinning an undefined status code
        List<Expectation> expectations = new OpenAPIConverter(mockServerLogger).buildExpectations(
            specUrlOrPayload,
            ImmutableMap.<String, Object>of("showPetById", "418")
        );

        // then - falls back to the first defined response (200 with a body), NOT an empty 200
        HttpResponse response = expectations.get(0).getHttpResponse();
        assertThat(response.getStatusCode(), is(200));
        assertThat("should fall back to a defined response with a body, not an empty 200",
            response.getBodyAsString(), is(notNullValue()));
        assertThat(response.getBodyAsString(), containsString("name"));
    }

    // --- Fix 1: absent exampleName falls back without silently substituting a different named example ---

    @Test
    public void shouldFallBackWhenRequestedExampleNameAbsent() {
        // given - showPetById 200 has named examples Crumble and Boots but not "Nonexistent"
        String specUrlOrPayload = FileReader.readFileFromClassPathOrPath(
            "org/mockserver/openapi/openapi_petstore_example_with_examples.yaml"
        );

        // when - request a present status code but an absent example name
        List<Expectation> expectations = new OpenAPIConverter(mockServerLogger).buildExpectations(
            specUrlOrPayload,
            ImmutableMap.<String, Object>of(
                "showPetById", ImmutableMap.of("statusCode", "200", "exampleName", "Nonexistent")
            )
        );

        // then - still produces a body (falls back to the first defined example) and a 200
        HttpResponse response = expectations.get(0).getHttpResponse();
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is(notNullValue()));
        // the fallback is the first defined example (Crumble), reached deliberately via the WARN path
        assertThat(response.getBodyAsString(), containsString("Crumble"));
    }

    @Test
    public void shouldUseRequestedExampleNameWhenPresent() {
        // given
        String specUrlOrPayload = FileReader.readFileFromClassPathOrPath(
            "org/mockserver/openapi/openapi_petstore_example_with_examples.yaml"
        );

        // when - request the second defined example explicitly
        List<Expectation> expectations = new OpenAPIConverter(mockServerLogger).buildExpectations(
            specUrlOrPayload,
            ImmutableMap.<String, Object>of(
                "showPetById", ImmutableMap.of("statusCode", "200", "exampleName", "Boots")
            )
        );

        // then - the requested example is used unchanged
        HttpResponse response = expectations.get(0).getHttpResponse();
        assertThat(response.getBodyAsString(), containsString("Boots"));
        assertThat(response.getBodyAsString(), not(containsString("Crumble")));
    }

    // --- An object where only some properties carry an inline example must still generate a COMPLETE
    //     example: properties with an example keep it, properties without one get a generated sample.
    //     (resolveSchemaExample returns null for the whole object so ExampleBuilder.fromSchema fills in
    //     the rest — keeping a partial object instead would silently drop the sample-only properties.) ---

    @Test
    public void shouldGenerateCompleteExampleWhenSomePropertiesLackExample() {
        // given - Widget has id and name with examples, colour with none
        String specUrlOrPayload = FileReader.readFileFromClassPathOrPath(
            "org/mockserver/openapi/openapi_partial_property_example.yaml"
        );

        // when
        List<Expectation> expectations = new OpenAPIConverter(mockServerLogger).buildExpectations(
            specUrlOrPayload,
            null
        );

        // then - id and name keep their explicit examples AND colour (no example) is still present with a
        // generated sample value (the body must NOT silently drop the sample-only property)
        HttpResponse response = expectations.get(0).getHttpResponse();
        String body = response.getBodyAsString();
        assertThat(body, is(notNullValue()));
        assertThat(body, containsString("\"id\""));
        assertThat(body, containsString("42"));
        assertThat(body, containsString("\"name\""));
        assertThat(body, containsString("gadget"));
        // properties without an inline example must still appear with a generated sample — a scalar, an
        // array, and the first enum value (this is the exact class that regressed: petstore array/enum
        // properties silently vanished from generated bodies)
        assertThat("a scalar property without an example must appear", body, containsString("\"colour\""));
        assertThat("an array property without an example must appear", body, containsString("\"tags\""));
        assertThat("an enum property without an example must appear with its first value",
            body, containsString("\"status\""));
        assertThat(body, containsString("available"));
    }

    // --- Fix 3: unresolvable internal $ref must not leak a literal {"$ref": ...} into the body ---

    @Test
    public void shouldNotLeakUnresolvableRefIntoBody() {
        // given - the example's "nested" property is an internal $ref to a non-existent component
        String specUrlOrPayload = FileReader.readFileFromClassPathOrPath(
            "org/mockserver/openapi/openapi_unresolvable_ref_example.yaml"
        );

        // when
        List<Expectation> expectations = new OpenAPIConverter(mockServerLogger).buildExpectations(
            specUrlOrPayload,
            null
        );

        // then - the literal $ref node must NOT appear in the generated body
        HttpResponse response = expectations.get(0).getHttpResponse();
        String body = response.getBodyAsString();
        assertThat(body, is(notNullValue()));
        assertThat("unresolvable $ref must not leak into the response body",
            body, not(containsString("$ref")));
        assertThat("unresolvable $ref target must not leak into the response body",
            body, not(containsString("doesNotExist")));
        // the sibling property that did resolve is kept
        assertThat(body, containsString("\"id\""));
    }

    // --- Fix 4: colliding operationIds stay distinct and correctly targeted ---

    @Test
    public void shouldDisambiguateCollidingOperationIds() {
        // given - /foo author id "GET /bar" collides with synthesized id of GET /bar;
        //         /widget author id "POST webhook:itemEvent" collides with the webhook synthesis
        String specUrlOrPayload = FileReader.readFileFromClassPathOrPath(
            "org/mockserver/openapi/openapi_operationid_collision.yaml"
        );

        // when
        List<Expectation> expectations = new OpenAPIConverter(mockServerLogger).buildExpectations(
            specUrlOrPayload,
            null
        );

        // then - four distinct operations -> four distinct operationIds
        long distinctIds = expectations.stream()
            .map(e -> ((org.mockserver.model.OpenAPIDefinition) e.getHttpRequest()).getOperationId())
            .distinct()
            .count();
        assertThat("expected 4 expectations", expectations.size(), is(4));
        assertThat("all operationIds must be distinct (no conflation)", distinctIds, is(4L));

        // the author-supplied id is reserved first and stays unchanged; the path synthesis is disambiguated
        Expectation foo = expectationFor(expectations, "GET /bar");
        assertThat(foo.getHttpResponse().getStatusCode(), is(200));
        assertThat(foo.getHttpResponse().getBodyAsString(), containsString("foo"));

        Expectation bar = expectationFor(expectations, "GET /bar (2)");
        assertThat(bar.getHttpResponse().getStatusCode(), is(201));
        assertThat(bar.getHttpResponse().getBodyAsString(), containsString("bar"));

        // path/webhook id collision is disambiguated too
        Expectation widget = expectationFor(expectations, "POST webhook:itemEvent");
        assertThat(widget.getHttpResponse().getStatusCode(), is(202));
        Expectation webhook = expectationFor(expectations, "POST webhook:itemEvent (2)");
        assertThat(webhook.getHttpResponse().getStatusCode(), is(200));
    }
}
