package org.mockserver.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import org.junit.Test;
import org.mockserver.file.FileReader;
import org.mockserver.logging.MockServerLogger;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;
import static org.mockserver.openapi.OpenAPIParser.buildOpenAPI;

/**
 * Covers {@link OpenAPIParser}'s synthesis of missing operationIds and the global uniqueness
 * guarantee that prevents author-supplied / synthesized / path / webhook id collisions from
 * silently conflating two operations.
 */
public class OpenAPIParserOperationIdTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger(OpenAPIParserOperationIdTest.class);

    private List<String> allOperationIds(OpenAPI openAPI) {
        List<String> ids = new ArrayList<>();
        openAPI.getPaths().forEach((path, pathItem) ->
            OpenAPIParser.mapOperations(pathItem).forEach(p -> ids.add(p.getRight().getOperationId())));
        if (openAPI.getWebhooks() != null) {
            openAPI.getWebhooks().forEach((name, pathItem) ->
                OpenAPIParser.mapOperations(pathItem).forEach(p -> ids.add(p.getRight().getOperationId())));
        }
        return ids;
    }

    @Test
    public void shouldSynthesizeAndDisambiguateCollidingOperationIds() {
        // given
        String specUrlOrPayload = FileReader.readFileFromClassPathOrPath(
            "org/mockserver/openapi/openapi_operationid_collision.yaml"
        );

        // when
        OpenAPI openAPI = buildOpenAPI(specUrlOrPayload, mockServerLogger);

        // then - every operationId is non-blank and globally unique
        List<String> ids = allOperationIds(openAPI);
        assertThat(ids, hasSize(4));
        assertThat(ids, everyItem(not(isEmptyOrNullString())));
        assertThat("operationIds must be globally unique", ids.stream().distinct().count(), is(4L));

        // author-supplied ids reserved first remain unchanged; collisions disambiguated with " (n)"
        assertThat(ids, hasItem("GET /bar"));            // author id on /foo
        assertThat(ids, hasItem("GET /bar (2)"));        // synthesized for GET /bar, disambiguated
        assertThat(ids, hasItem("POST webhook:itemEvent"));       // author id on /widget
        assertThat(ids, hasItem("POST webhook:itemEvent (2)"));   // synthesized webhook, disambiguated
    }

    @Test
    public void shouldLeaveUniqueAuthorOperationIdsUnchanged() {
        // given - a spec whose operations all have unique author-supplied operationIds
        String specUrlOrPayload = FileReader.readFileFromClassPathOrPath(
            "org/mockserver/openapi/openapi_petstore_example_with_examples.yaml"
        );

        // when
        OpenAPI openAPI = buildOpenAPI(specUrlOrPayload, mockServerLogger);

        // then - author ids are untouched (no spurious " (n)" suffixes)
        List<String> ids = allOperationIds(openAPI);
        assertThat(ids, containsInAnyOrder("listPets", "createPets", "showPetById"));
    }
}
