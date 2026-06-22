package org.mockserver.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.Test;
import org.mockserver.file.FileReader;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.openapi.examples.ExampleBuilder;
import org.mockserver.openapi.examples.models.Example;

import java.util.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;
import static org.mockserver.openapi.OpenAPIParser.buildOpenAPI;

/**
 * Tests that MockServer correctly handles OpenAPI 3.1 constructs:
 * <ol>
 *   <li>{@code type} as an array (e.g. {@code type: [string, "null"]})</li>
 *   <li>{@code $ref} siblings (description alongside $ref)</li>
 *   <li>{@code webhooks} top-level key</li>
 * </ol>
 */
public class OpenAPI31FeaturesTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger(OpenAPI31FeaturesTest.class);

    // --- Parsing ---

    @Test
    public void shouldParseOpenAPI31Spec() {
        // given
        String specUrlOrPayload = FileReader.readFileFromClassPathOrPath(
            "org/mockserver/openapi/openapi_31_features.yaml"
        );

        // when
        OpenAPI openAPI = buildOpenAPI(specUrlOrPayload, mockServerLogger);

        // then - spec parsed without error
        assertThat(openAPI, is(notNullValue()));
        assertThat(openAPI.getInfo().getTitle(), is("OpenAPI 3.1 Features Test"));
        assertThat(openAPI.getOpenapi(), is("3.1.0"));
    }

    // --- Type as Array (OAS 3.1) ---

    @Test
    public void shouldHandleTypeAsArray() {
        // given - a spec where 'name' has type: [string, "null"]
        String specUrlOrPayload = FileReader.readFileFromClassPathOrPath(
            "org/mockserver/openapi/openapi_31_features.yaml"
        );

        // when
        OpenAPI openAPI = buildOpenAPI(specUrlOrPayload, mockServerLogger);

        // then - verify the schema was parsed with nullable type info
        Schema<?> itemSchema = openAPI.getComponents().getSchemas().get("Item");
        assertThat(itemSchema, is(notNullValue()));
        assertThat(itemSchema.getProperties(), is(notNullValue()));
        assertThat(itemSchema.getProperties().containsKey("name"), is(true));

        @SuppressWarnings("unchecked")
        Schema<?> nameSchema = (Schema<?>) itemSchema.getProperties().get("name");
        assertThat(nameSchema, is(notNullValue()));
        // In OAS 3.1, type: [string, "null"] is stored in the types set
        assertThat("name schema should have types set with string and null",
            nameSchema.getTypes(), is(notNullValue()));
        assertThat(nameSchema.getTypes(), hasItems("string", "null"));
    }

    @Test
    public void shouldGenerateExamplesForTypeArraySchema() {
        // given - schema with type: [string, "null"] for the name field
        String specUrlOrPayload = FileReader.readFileFromClassPathOrPath(
            "org/mockserver/openapi/openapi_31_features.yaml"
        );

        // when
        List<Expectation> expectations = new OpenAPIConverter(mockServerLogger)
            .buildExpectations(specUrlOrPayload, null);

        // then - the listItems response should have generated examples
        // with 'name' field present (even though it's nullable via type array)
        Expectation listItemsExpectation = expectations.stream()
            .filter(e -> e.toString().contains("listItems"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("listItems expectation not found"));

        assertThat(listItemsExpectation.getHttpResponse(), is(notNullValue()));
        assertThat(listItemsExpectation.getHttpResponse().getStatusCode(), is(200));

        // The response body should contain "name" with the example value "Widget"
        // (specified as example in the Item schema)
        String body = listItemsExpectation.getHttpResponse().getBodyAsString();
        assertThat("response body should contain 'name' field from type:[string,null] schema",
            body, containsString("\"name\""));
    }

    @Test
    public void shouldGenerateExampleForNullableStringSchemaDirectly() {
        // given - a Schema with types={"string","null"} (OAS 3.1 style)
        Schema<?> schema = new Schema<>();
        schema.setTypes(new LinkedHashSet<>(Arrays.asList("string", "null")));

        // when
        Example example = ExampleBuilder.fromSchema(schema, null);

        // then - should generate a string example, not null
        assertThat(example, is(notNullValue()));
        assertThat(example, is(instanceOf(org.mockserver.openapi.examples.models.StringExample.class)));
    }

    @Test
    public void shouldGenerateExampleForNullableIntegerSchemaDirectly() {
        // given - a Schema with types={"integer","null"} (OAS 3.1 style)
        Schema<?> schema = new Schema<>();
        schema.setTypes(new LinkedHashSet<>(Arrays.asList("integer", "null")));

        // when
        Example example = ExampleBuilder.fromSchema(schema, null);

        // then - should generate an integer example, not null
        assertThat(example, is(notNullValue()));
        assertThat(example, is(instanceOf(org.mockserver.openapi.examples.models.IntegerExample.class)));
    }

    @Test
    public void shouldGenerateExampleForNullableBooleanSchemaDirectly() {
        // given - a Schema with types={"boolean","null"} (OAS 3.1 style)
        Schema<?> schema = new Schema<>();
        schema.setTypes(new LinkedHashSet<>(Arrays.asList("boolean", "null")));

        // when
        Example example = ExampleBuilder.fromSchema(schema, null);

        // then - should generate a boolean example
        assertThat(example, is(notNullValue()));
        assertThat(example, is(instanceOf(org.mockserver.openapi.examples.models.BooleanExample.class)));
    }

    @Test
    public void shouldGenerateExampleForNullableNumberSchemaDirectly() {
        // given - a Schema with types={"number","null"} (OAS 3.1 style)
        Schema<?> schema = new Schema<>();
        schema.setTypes(new LinkedHashSet<>(Arrays.asList("number", "null")));

        // when
        Example example = ExampleBuilder.fromSchema(schema, null);

        // then
        assertThat(example, is(notNullValue()));
        assertThat(example, is(instanceOf(org.mockserver.openapi.examples.models.DecimalExample.class)));
    }

    @Test
    public void shouldGenerateExampleForNullableArraySchemaDirectly() {
        // given - a Schema with types={"array","null"} and items (OAS 3.1 style)
        Schema<?> schema = new Schema<>();
        schema.setTypes(new LinkedHashSet<>(Arrays.asList("array", "null")));
        Schema<?> items = new Schema<>();
        items.setTypes(new LinkedHashSet<>(List.of("string")));
        schema.setItems(items);

        // when
        Example example = ExampleBuilder.fromSchema(schema, null);

        // then
        assertThat(example, is(notNullValue()));
        assertThat(example, is(instanceOf(org.mockserver.openapi.examples.models.ArrayExample.class)));
    }

    @Test
    public void shouldGenerateExampleForNullableObjectSchemaDirectly() {
        // given - a Schema with types={"object","null"} and properties (OAS 3.1 style)
        Schema<Object> schema = new Schema<>();
        schema.setTypes(new LinkedHashSet<>(Arrays.asList("object", "null")));
        Schema<String> nameProp = new Schema<>();
        nameProp.setTypes(new LinkedHashSet<>(List.of("string")));
        schema.addProperty("name", nameProp);

        // when
        Example example = ExampleBuilder.fromSchema(schema, null);

        // then
        assertThat(example, is(notNullValue()));
        assertThat(example, is(instanceOf(org.mockserver.openapi.examples.models.ObjectExample.class)));
    }

    // --- $ref Siblings (OAS 3.1) ---

    @Test
    public void shouldHandleRefSiblings() {
        // given - createItem response uses $ref with a sibling description
        String specUrlOrPayload = FileReader.readFileFromClassPathOrPath(
            "org/mockserver/openapi/openapi_31_features.yaml"
        );

        // when
        List<Expectation> expectations = new OpenAPIConverter(mockServerLogger)
            .buildExpectations(specUrlOrPayload, null);

        // then - createItem should generate a proper 201 response with Item body
        Expectation createItemExpectation = expectations.stream()
            .filter(e -> e.toString().contains("\"createItem\""))
            .findFirst()
            .orElseThrow(() -> new AssertionError("createItem expectation not found"));

        assertThat(createItemExpectation.getHttpResponse(), is(notNullValue()));
        assertThat(createItemExpectation.getHttpResponse().getStatusCode(), is(201));

        // The response body should contain the Item example data
        String responseBody = createItemExpectation.getHttpResponse().getBodyAsString();
        assertThat("response body should contain Item example data",
            responseBody, is(notNullValue()));
        assertThat(responseBody, containsString("\"id\""));
    }

    // --- Webhooks (OAS 3.1) ---

    @Test
    public void shouldParseWebhooksTopLevelKey() {
        // given
        String specUrlOrPayload = FileReader.readFileFromClassPathOrPath(
            "org/mockserver/openapi/openapi_31_features.yaml"
        );

        // when
        OpenAPI openAPI = buildOpenAPI(specUrlOrPayload, mockServerLogger);

        // then - webhooks should be parsed
        Map<String, PathItem> webhooks = openAPI.getWebhooks();
        assertThat("webhooks should be parsed from OAS 3.1 spec",
            webhooks, is(notNullValue()));
        assertThat(webhooks.size(), is(2));
        assertThat(webhooks.containsKey("itemCreated"), is(true));
        assertThat(webhooks.containsKey("itemDeleted"), is(true));

        // Verify webhook operations have the right operationIds
        PathItem itemCreated = webhooks.get("itemCreated");
        assertThat(itemCreated.getPost(), is(notNullValue()));
        assertThat(itemCreated.getPost().getOperationId(), is("onItemCreated"));

        PathItem itemDeleted = webhooks.get("itemDeleted");
        assertThat(itemDeleted.getPost(), is(notNullValue()));
        assertThat(itemDeleted.getPost().getOperationId(), is("onItemDeleted"));
    }

    @Test
    public void shouldIncludeWebhookOperationsInExpectations() {
        // given
        String specUrlOrPayload = FileReader.readFileFromClassPathOrPath(
            "org/mockserver/openapi/openapi_31_features.yaml"
        );

        // when
        List<Expectation> expectations = new OpenAPIConverter(mockServerLogger)
            .buildExpectations(specUrlOrPayload, null);

        // then - webhook operations should also generate expectations
        boolean hasOnItemCreated = expectations.stream().anyMatch(e -> e.toString().contains("onItemCreated"));
        boolean hasOnItemDeleted = expectations.stream().anyMatch(e -> e.toString().contains("onItemDeleted"));
        assertThat("should include onItemCreated webhook operation", hasOnItemCreated, is(true));
        assertThat("should include onItemDeleted webhook operation", hasOnItemDeleted, is(true));

        // Total: 3 path ops + 2 webhook ops = 5
        assertThat(expectations.size(), is(5));
    }

    @Test
    public void shouldFilterWebhookOperationsByOperationId() {
        // given
        String specUrlOrPayload = FileReader.readFileFromClassPathOrPath(
            "org/mockserver/openapi/openapi_31_features.yaml"
        );

        // when - filter to only onItemCreated
        List<Expectation> expectations = new OpenAPIConverter(mockServerLogger)
            .buildExpectations(specUrlOrPayload, Map.of("onItemCreated", "200"));

        // then - only the webhook operation should be included
        assertThat(expectations.size(), is(1));
        assertThat(expectations.get(0).toString(), containsString("onItemCreated"));
    }

    @Test
    public void shouldSerialiseWebhookOperations() {
        // given
        String specUrlOrPayload = FileReader.readFileFromClassPathOrPath(
            "org/mockserver/openapi/openapi_31_features.yaml"
        );

        // when
        OpenAPISerialiser serialiser = new OpenAPISerialiser(mockServerLogger);
        Optional<Pair<String, io.swagger.v3.oas.models.Operation>> operation =
            serialiser.retrieveOperation(specUrlOrPayload, "onItemCreated");

        // then - webhook operation should be retrievable
        assertThat("onItemCreated webhook should be retrievable via retrieveOperation",
            operation.isPresent(), is(true));
        assertThat(operation.get().getRight().getOperationId(), is("onItemCreated"));
    }

    @Test
    public void shouldIncludeWebhookOperationsInRetrieveOperations() {
        // given
        String specUrlOrPayload = FileReader.readFileFromClassPathOrPath(
            "org/mockserver/openapi/openapi_31_features.yaml"
        );

        // when
        OpenAPI openAPI = buildOpenAPI(specUrlOrPayload, mockServerLogger);
        OpenAPISerialiser serialiser = new OpenAPISerialiser(mockServerLogger);
        Map<String, List<Pair<String, io.swagger.v3.oas.models.Operation>>> operations =
            serialiser.retrieveOperations(openAPI, null);

        // then - should contain both path and webhook operations
        // 2 path entries (/items, /items/{itemId}) + 2 webhook entries
        assertThat(operations.size(), is(4));

        // Count total operations across all entries
        long totalOps = operations.values().stream().mapToLong(List::size).sum();
        assertThat("should have 5 total operations (3 path + 2 webhook)", totalOps, is(5L));
    }

    // --- Integration: full spec with all 3.1 features ---

    @Test
    public void shouldBuildExpectationsFromOpenAPI31Spec() {
        // given
        String specUrlOrPayload = FileReader.readFileFromClassPathOrPath(
            "org/mockserver/openapi/openapi_31_features.yaml"
        );

        // when
        List<Expectation> expectations = new OpenAPIConverter(mockServerLogger)
            .buildExpectations(specUrlOrPayload, null);

        // then - expectations generated for all operations (paths + webhooks)
        assertThat(expectations, is(notNullValue()));
        assertThat("should have 5 expectations (3 path ops + 2 webhook ops)",
            expectations.size(), is(5));

        // Verify all operation IDs are present
        List<String> expectedOps = List.of("listItems", "createItem", "getItemById", "onItemCreated", "onItemDeleted");
        for (String op : expectedOps) {
            boolean found = expectations.stream().anyMatch(e -> e.toString().contains(op));
            assertThat("should contain operation: " + op, found, is(true));
        }
    }
}
