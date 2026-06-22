package org.mockserver.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.Test;
import org.mockserver.file.FileReader;
import org.mockserver.logging.MockServerLogger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.openapi.OpenAPIParser.buildOpenAPI;
import static org.mockserver.openapi.OpenAPISerialiser.normalizeContextPathPrefix;

public class OpenAPISerialiserContextPathPrefixTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger(OpenAPISerialiserContextPathPrefixTest.class);

    @Test
    public void shouldNormalizeBlankPrefix() {
        assertThat(normalizeContextPathPrefix(""), is(""));
        assertThat(normalizeContextPathPrefix(null), is(""));
        assertThat(normalizeContextPathPrefix("  "), is(""));
    }

    @Test
    public void shouldNormalizePrefixWithLeadingSlash() {
        assertThat(normalizeContextPathPrefix("/api/v1"), is("/api/v1"));
    }

    @Test
    public void shouldNormalizePrefixWithoutLeadingSlash() {
        assertThat(normalizeContextPathPrefix("api/v1"), is("/api/v1"));
    }

    @Test
    public void shouldNormalizePrefixWithTrailingSlash() {
        assertThat(normalizeContextPathPrefix("/api/v1/"), is("/api/v1"));
    }

    @Test
    public void shouldNormalizePrefixWithWhitespace() {
        assertThat(normalizeContextPathPrefix("  /api/v1  "), is("/api/v1"));
    }

    @Test
    public void shouldNormalizePrefixSingleSlash() {
        assertThat(normalizeContextPathPrefix("/"), is(""));
    }

    @Test
    public void shouldAddContextPathPrefixToOperationPaths() {
        // given
        OpenAPISerialiser serialiser = new OpenAPISerialiser(mockServerLogger);
        String spec = "---" + NEW_LINE +
            "openapi: 3.0.0" + NEW_LINE +
            "paths:" + NEW_LINE +
            "  \"/pets\":" + NEW_LINE +
            "    get:" + NEW_LINE +
            "      operationId: listPets" + NEW_LINE +
            "  \"/pets/{petId}\":" + NEW_LINE +
            "    get:" + NEW_LINE +
            "      operationId: showPetById" + NEW_LINE;
        OpenAPI openAPI = buildOpenAPI(spec, mockServerLogger);

        // when
        Map<String, List<Pair<String, Operation>>> operations = serialiser.retrieveOperations(openAPI, null, "/api/v1");

        // then
        assertThat(operations, hasKey("/api/v1/pets"));
        assertThat(operations, hasKey("/api/v1/pets/{petId}"));
        assertThat(operations, not(hasKey("/pets")));
        assertThat(operations, not(hasKey("/pets/{petId}")));
    }

    @Test
    public void shouldNotAddContextPathPrefixWhenBlank() {
        // given
        OpenAPISerialiser serialiser = new OpenAPISerialiser(mockServerLogger);
        String spec = "---" + NEW_LINE +
            "openapi: 3.0.0" + NEW_LINE +
            "paths:" + NEW_LINE +
            "  \"/pets\":" + NEW_LINE +
            "    get:" + NEW_LINE +
            "      operationId: listPets" + NEW_LINE;
        OpenAPI openAPI = buildOpenAPI(spec, mockServerLogger);

        // when
        Map<String, List<Pair<String, Operation>>> operations = serialiser.retrieveOperations(openAPI, null, "");

        // then
        assertThat(operations, hasKey("/pets"));
    }

    @Test
    public void shouldNotAddContextPathPrefixWhenNull() {
        // given
        OpenAPISerialiser serialiser = new OpenAPISerialiser(mockServerLogger);
        String spec = "---" + NEW_LINE +
            "openapi: 3.0.0" + NEW_LINE +
            "paths:" + NEW_LINE +
            "  \"/pets\":" + NEW_LINE +
            "    get:" + NEW_LINE +
            "      operationId: listPets" + NEW_LINE;
        OpenAPI openAPI = buildOpenAPI(spec, mockServerLogger);

        // when
        Map<String, List<Pair<String, Operation>>> operations = serialiser.retrieveOperations(openAPI, null, null);

        // then
        assertThat(operations, hasKey("/pets"));
    }

    @Test
    public void shouldCombineContextPathPrefixWithServerPath() {
        // given
        OpenAPISerialiser serialiser = new OpenAPISerialiser(mockServerLogger);
        String spec = "---" + NEW_LINE +
            "openapi: 3.0.0" + NEW_LINE +
            "servers:" + NEW_LINE +
            "  - url: http://localhost:8080/server-base" + NEW_LINE +
            "paths:" + NEW_LINE +
            "  \"/pets\":" + NEW_LINE +
            "    get:" + NEW_LINE +
            "      operationId: listPets" + NEW_LINE;
        OpenAPI openAPI = buildOpenAPI(spec, mockServerLogger);

        // when
        Map<String, List<Pair<String, Operation>>> operations = serialiser.retrieveOperations(openAPI, null, "/api/v1");

        // then
        assertThat(operations, hasKey("/api/v1/server-base/pets"));
    }

    @Test
    public void shouldPreserveBackwardCompatibilityWithTwoArgOverload() {
        // given
        OpenAPISerialiser serialiser = new OpenAPISerialiser(mockServerLogger);
        String spec = "---" + NEW_LINE +
            "openapi: 3.0.0" + NEW_LINE +
            "paths:" + NEW_LINE +
            "  \"/pets\":" + NEW_LINE +
            "    get:" + NEW_LINE +
            "      operationId: listPets" + NEW_LINE;
        OpenAPI openAPI = buildOpenAPI(spec, mockServerLogger);

        // when - using two-arg overload
        Map<String, List<Pair<String, Operation>>> operations = serialiser.retrieveOperations(openAPI, null);

        // then - no prefix applied
        assertThat(operations, hasKey("/pets"));
    }

    @Test
    public void shouldRetrieveOperationsForWebhooksOnlySpecWithoutNullPointer() {
        // given - a valid OAS 3.1 spec that omits paths entirely (webhooks only)
        OpenAPISerialiser serialiser = new OpenAPISerialiser(mockServerLogger);
        OpenAPI openAPI = buildOpenAPI(
            FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_31_webhooks_only.yaml"),
            mockServerLogger
        );

        // when - getPaths() is null; must not NPE
        Map<String, List<Pair<String, Operation>>> operations = serialiser.retrieveOperations(openAPI, null, "");

        // then - the webhook operation is returned under its webhook path key
        assertThat(operations, hasKey("/webhook:newPet"));
    }

    @Test
    public void shouldRetrieveOperationByIdForWebhooksOnlySpecWithoutNullPointer() {
        // given
        OpenAPISerialiser serialiser = new OpenAPISerialiser(mockServerLogger);
        String spec = FileReader.readFileFromClassPathOrPath("org/mockserver/openapi/openapi_31_webhooks_only.yaml");

        // when - getPaths() is null; must not NPE
        Optional<Pair<String, Operation>> operation = serialiser.retrieveOperation(spec, "onNewPet");

        // then
        assertThat(operation.isPresent(), is(true));
        assertThat(operation.get().getRight(), is(notNullValue()));
    }
}
