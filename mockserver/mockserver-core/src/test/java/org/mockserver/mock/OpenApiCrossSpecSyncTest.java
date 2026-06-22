package org.mockserver.mock;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.openapi.OpenAPIParser;
import org.mockserver.scheduler.Scheduler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.OpenAPIExpectation.openAPIExpectation;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * End-to-end (HttpState-level) tests for the OpenAPI incremental-sync prune path, proving the
 * cross-spec data-loss invariants that the pure-function planner tests cannot:
 *
 * <ol>
 *   <li><b>Invariant 1</b> — importing spec B never deletes expectations generated from a
 *       DIFFERENT spec A, even when A and B share the same {@code info.title}.</li>
 *   <li><b>Invariant 2</b> — a manual (non-openapi-id) expectation survives importing and
 *       re-importing an OpenAPI spec.</li>
 *   <li><b>Invariant 3</b> — re-importing the SAME inline payload still prunes operations removed
 *       from it (incremental sync keeps working).</li>
 * </ol>
 */
public class OpenApiCrossSpecSyncTest {

    private HttpState httpState;

    // Two DIFFERENT specs that share the same info.title "Shared Title".
    private static final String SPEC_A =
        "openapi: 3.0.0\n" +
            "info:\n" +
            "  title: Shared Title\n" +
            "  version: 1.0.0\n" +
            "paths:\n" +
            "  /alpha:\n" +
            "    get:\n" +
            "      operationId: getAlpha\n" +
            "      responses:\n" +
            "        '200':\n" +
            "          description: OK\n";

    private static final String SPEC_B =
        "openapi: 3.0.0\n" +
            "info:\n" +
            "  title: Shared Title\n" +
            "  version: 1.0.0\n" +
            "paths:\n" +
            "  /beta:\n" +
            "    get:\n" +
            "      operationId: getBeta\n" +
            "      responses:\n" +
            "        '200':\n" +
            "          description: OK\n";

    // A spec whose second version removes an operation (for invariant 3).
    private static final String SPEC_C_V1 =
        "openapi: 3.0.0\n" +
            "info:\n" +
            "  title: Evolving API\n" +
            "  version: 1.0.0\n" +
            "paths:\n" +
            "  /one:\n" +
            "    get:\n" +
            "      operationId: opOne\n" +
            "      responses:\n" +
            "        '200':\n" +
            "          description: OK\n" +
            "  /two:\n" +
            "    get:\n" +
            "      operationId: opTwo\n" +
            "      responses:\n" +
            "        '200':\n" +
            "          description: OK\n";

    // Same payload as V1 but with /two removed (opTwo gone). Title unchanged.
    private static final String SPEC_C_V2 =
        "openapi: 3.0.0\n" +
            "info:\n" +
            "  title: Evolving API\n" +
            "  version: 1.0.0\n" +
            "paths:\n" +
            "  /one:\n" +
            "    get:\n" +
            "      operationId: opOne\n" +
            "      responses:\n" +
            "        '200':\n" +
            "          description: OK\n";

    @Before
    public void setUp() {
        Configuration configuration = configuration();
        Scheduler scheduler = mock(Scheduler.class);
        httpState = new HttpState(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler);
    }

    private Set<String> activeIds() {
        return httpState.getRequestMatchers().retrieveActiveExpectations(null).stream()
            .map(Expectation::getId)
            .collect(Collectors.toSet());
    }

    @Test
    public void invariant1_importingSpecBMustNotDeleteSpecAWithSameTitle() {
        // given - import spec A
        httpState.add(openAPIExpectation(SPEC_A));
        Set<String> afterA = activeIds();
        String specAId = afterA.stream().filter(id -> id.endsWith(":getAlpha")).findFirst().orElseThrow();
        assertThat(specAId, startsWith("openapi:shared_title_"));

        // when - import a DIFFERENT spec B that shares the title "Shared Title"
        httpState.add(openAPIExpectation(SPEC_B));

        // then - spec A's expectation must STILL be present (no cross-spec deletion)
        Set<String> afterB = activeIds();
        assertThat("spec A must survive importing spec B", afterB, hasItem(specAId));
        // and spec B's expectation must also be present, in a DISTINCT namespace
        String specBId = afterB.stream().filter(id -> id.endsWith(":getBeta")).findFirst().orElseThrow();
        assertThat(specBId, startsWith("openapi:shared_title_"));
        assertThat("distinct specs get distinct namespaces", namespaceOf(specAId), is(not(namespaceOf(specBId))));
        // both expectations coexist
        assertThat(afterB, hasItems(specAId, specBId));
    }

    @Test
    public void invariant2_manualExpectationMustSurviveOpenApiImportAndReimport() {
        // given - a manual (normal client) expectation with a non-openapi id
        httpState.add(new Expectation(request().withPath("/manual"))
            .withId("manual-expectation-id")
            .thenRespond(response().withStatusCode(200)));
        assertThat(activeIds(), hasItem("manual-expectation-id"));

        // when - import an OpenAPI spec, then re-import the same spec
        httpState.add(openAPIExpectation(SPEC_A));
        assertThat("manual expectation survives first import", activeIds(), hasItem("manual-expectation-id"));

        httpState.add(openAPIExpectation(SPEC_A));

        // then - the manual expectation must NEVER be pruned
        assertThat("manual expectation survives re-import", activeIds(), hasItem("manual-expectation-id"));
    }

    @Test
    public void invariant3_reimportingSameSpecUrlStillPrunesRemovedOperations() throws Exception {
        // A spec URL/file reference is a STABLE identity: the same path yields the same namespace
        // even when the file content evolves, so incremental sync keeps pruning removed operations.
        // (Inline payloads instead key off content — editing them changes the namespace; that
        // deliberate trade-off is documented on OpenApiSyncPlanner#deriveSpecKey and covered by
        // reimportingSameSpecIsIdempotent below.)
        Path specFile = Files.createTempFile("openapi-evolving-", ".yaml");
        try {
            String specUrl = specFile.toAbsolutePath().toString();

            // given - v1 (opOne + opTwo)
            Files.write(specFile, SPEC_C_V1.getBytes(StandardCharsets.UTF_8));
            OpenAPIParser.clearCache(specUrl);
            httpState.add(openAPIExpectation(specUrl));
            Set<String> afterV1 = activeIds();
            assertThat(afterV1.stream().anyMatch(id -> id.endsWith(":opOne")), is(true));
            assertThat(afterV1.stream().anyMatch(id -> id.endsWith(":opTwo")), is(true));

            // when - the SAME url now resolves to the evolved spec with opTwo removed
            Files.write(specFile, SPEC_C_V2.getBytes(StandardCharsets.UTF_8));
            OpenAPIParser.clearCache(specUrl); // evict the LRU parse cache so the new content is re-read
            httpState.add(openAPIExpectation(specUrl));

            // then - opOne retained, opTwo pruned (same namespace -> incremental sync prunes)
            Set<String> afterV2 = activeIds();
            assertThat("opOne retained", afterV2.stream().anyMatch(id -> id.endsWith(":opOne")), is(true));
            assertThat("opTwo pruned on same-source incremental sync", afterV2.stream().anyMatch(id -> id.endsWith(":opTwo")), is(false));
        } finally {
            Files.deleteIfExists(specFile);
            OpenAPIParser.clearCache(specFile.toAbsolutePath().toString());
        }
    }

    @Test
    public void reimportingAChangedUrlSpecAutomaticallyEvictsTheParseCache() throws Exception {
        // Re-importing a URL/file spec must pick up the CURRENT content without a manual cache eviction:
        // HttpState.add evicts the LRU parse cache for a URL/file spec before re-importing (an inline
        // payload needs no eviction — it is keyed by content). This test performs NO manual clearCache
        // between the two imports, so it fails if the import does not auto-evict.
        Path specFile = Files.createTempFile("openapi-autoevict-", ".yaml");
        try {
            String specUrl = specFile.toAbsolutePath().toString();

            // given - v1 (opOne + opTwo) imported and parsed-into-cache by the import itself
            Files.write(specFile, SPEC_C_V1.getBytes(StandardCharsets.UTF_8));
            httpState.add(openAPIExpectation(specUrl));
            assertThat(activeIds().stream().anyMatch(id -> id.endsWith(":opTwo")), is(true));

            // when - the same URL now resolves to v2 (opTwo removed) and is re-imported WITHOUT a manual clearCache
            Files.write(specFile, SPEC_C_V2.getBytes(StandardCharsets.UTF_8));
            httpState.add(openAPIExpectation(specUrl));

            // then - the import auto-evicted the stale parse, so opTwo is pruned
            assertThat("opOne retained", activeIds().stream().anyMatch(id -> id.endsWith(":opOne")), is(true));
            assertThat("opTwo pruned because the changed URL spec was re-read (cache auto-evicted)",
                activeIds().stream().anyMatch(id -> id.endsWith(":opTwo")), is(false));
        } finally {
            Files.deleteIfExists(specFile);
            OpenAPIParser.clearCache(specFile.toAbsolutePath().toString());
        }
    }

    @Test
    public void reimportingSameSpecIsIdempotent() {
        httpState.add(openAPIExpectation(SPEC_A));
        int afterFirst = activeIds().size();
        httpState.add(openAPIExpectation(SPEC_A));
        assertThat("re-importing the same spec must not duplicate expectations", activeIds().size(), is(afterFirst));
    }

    private static String namespaceOf(String id) {
        return id.substring(0, id.lastIndexOf(':') + 1);
    }
}
