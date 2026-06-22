package org.mockserver.netty.integration.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Integration test for the OpenAPI incremental sync feature (OpenApiSyncPlanner + HttpState prune path).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>PUT /mockserver/openapi with an inline OpenAPI spec creates expectations with stable,
 *       collision-resistant {@code openapi:<specKey>:<operationId>} ids (the spec key embeds a short
 *       hash of the spec source).</li>
 *   <li>Importing a DIFFERENT inline spec that shares the same {@code info.title} does NOT delete the
 *       first spec's expectations — the cross-spec data-loss guard.</li>
 *   <li>Re-importing the byte-identical inline spec is idempotent (no duplicates).</li>
 * </ul>
 *
 * <p>Incremental sync of an evolving spec (pruning operations removed from it) is guaranteed for
 * URL/file-referenced specs and is proven deterministically at the HttpState/planner level in
 * mockserver-core ({@code OpenApiCrossSpecSyncTest}, {@code OpenApiSyncPlannerTest}).
 */
public class OpenApiIncrementalSyncIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static MockServerClient mockServerClient;
    private static int mockServerPort;

    @BeforeClass
    public static void startServer() {
        mockServerPort = new MockServer().getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
    }

    // ---- OpenAPI spec v1: title "Foo", operations listItems + getItem ----
    private static final String SPEC_V1 = "{\n" +
        "  \"specUrlOrPayload\": \"{\\\"openapi\\\":\\\"3.0.0\\\",\\\"info\\\":{\\\"title\\\":\\\"Foo\\\",\\\"version\\\":\\\"1.0.0\\\"},\\\"paths\\\":{\\\"/items\\\":{\\\"get\\\":{\\\"operationId\\\":\\\"listItems\\\",\\\"responses\\\":{\\\"200\\\":{\\\"description\\\":\\\"ok\\\",\\\"content\\\":{\\\"application/json\\\":{\\\"schema\\\":{\\\"type\\\":\\\"array\\\",\\\"items\\\":{\\\"type\\\":\\\"string\\\"}}}}}}}},\\\"/items/{id}\\\":{\\\"get\\\":{\\\"operationId\\\":\\\"getItem\\\",\\\"parameters\\\":[{\\\"name\\\":\\\"id\\\",\\\"in\\\":\\\"path\\\",\\\"required\\\":true,\\\"schema\\\":{\\\"type\\\":\\\"string\\\"}}],\\\"responses\\\":{\\\"200\\\":{\\\"description\\\":\\\"ok\\\",\\\"content\\\":{\\\"application/json\\\":{\\\"schema\\\":{\\\"type\\\":\\\"string\\\"}}}}}}}}}\"\n" +
        "}";

    // ---- OpenAPI spec v2: title "Foo", operations listItems (kept) + createItem (added), getItem removed ----
    private static final String SPEC_V2 = "{\n" +
        "  \"specUrlOrPayload\": \"{\\\"openapi\\\":\\\"3.0.0\\\",\\\"info\\\":{\\\"title\\\":\\\"Foo\\\",\\\"version\\\":\\\"2.0.0\\\"},\\\"paths\\\":{\\\"/items\\\":{\\\"get\\\":{\\\"operationId\\\":\\\"listItems\\\",\\\"responses\\\":{\\\"200\\\":{\\\"description\\\":\\\"ok\\\",\\\"content\\\":{\\\"application/json\\\":{\\\"schema\\\":{\\\"type\\\":\\\"array\\\",\\\"items\\\":{\\\"type\\\":\\\"string\\\"}}}}}}},\\\"post\\\":{\\\"operationId\\\":\\\"createItem\\\",\\\"requestBody\\\":{\\\"content\\\":{\\\"application/json\\\":{\\\"schema\\\":{\\\"type\\\":\\\"string\\\"}}}},\\\"responses\\\":{\\\"201\\\":{\\\"description\\\":\\\"created\\\",\\\"content\\\":{\\\"application/json\\\":{\\\"schema\\\":{\\\"type\\\":\\\"string\\\"}}}}}}}}}\"\n" +
        "}";

    @Test
    public void shouldCreateExpectationsFromOpenApiSpec() throws Exception {
        // Import v1 spec
        String response = sendPutRequest("/mockserver/openapi", SPEC_V1);
        assertThat("PUT /mockserver/openapi should return 201", response, containsString("201"));

        // Retrieve active expectations
        String activeExpectations = sendPutRequest("/mockserver/retrieve?type=ACTIVE_EXPECTATIONS", "");
        JsonNode expectations = OBJECT_MAPPER.readTree(extractJsonBody(activeExpectations));

        assertThat("should have exactly 2 expectations", expectations.size(), is(2));

        // Verify stable ids: openapi:foo_<hash>:listItems and openapi:foo_<hash>:getItem
        // (the spec key embeds a short hash of the spec source to avoid cross-spec collisions)
        boolean hasListItems = false;
        boolean hasGetItem = false;
        for (JsonNode exp : expectations) {
            String id = exp.get("id").asText();
            if (id.startsWith("openapi:foo_") && id.endsWith(":listItems")) {
                hasListItems = true;
            }
            if (id.startsWith("openapi:foo_") && id.endsWith(":getItem")) {
                hasGetItem = true;
            }
        }
        assertThat("should have openapi:foo_<hash>:listItems", hasListItems, is(true));
        assertThat("should have openapi:foo_<hash>:getItem", hasGetItem, is(true));
    }

    // NOTE on incremental sync of an EVOLVING spec (prune removed ops): that guarantee holds when
    // the spec is referenced by a stable URL/file path (the path is the namespace identity, so changed
    // content under the same path re-targets the same namespace and removed ops are pruned). It is
    // proven deterministically at the HttpState level in
    // mockserver-core's org.mockserver.mock.OpenApiCrossSpecSyncTest#invariant3_... and via the pure
    // planner in OpenApiSyncPlannerTest, rather than here, because driving file-content evolution through
    // the live HTTP path also exercises the third-party swagger file reader/cache and is not the unit
    // under test. Inline payloads instead key off content (editing them spawns a new namespace by
    // design) — see shouldNotDeleteOtherSpecWhenImportingDifferentInlineSpecWithSameTitle below.

    @Test
    public void shouldNotDeleteOtherSpecWhenImportingDifferentInlineSpecWithSameTitle() throws Exception {
        // Cross-spec data-loss guard (the confirmed CRITICAL bug): two DIFFERENT inline specs that
        // share the same info.title "Foo" must land in DISTINCT namespaces, so importing one never
        // deletes the other's expectations.
        sendPutRequest("/mockserver/openapi", SPEC_V1); // title Foo: listItems + getItem
        sendPutRequest("/mockserver/openapi", SPEC_V2); // title Foo (different payload): listItems + createItem

        String active = sendPutRequest("/mockserver/retrieve?type=ACTIVE_EXPECTATIONS", "");
        JsonNode expectations = OBJECT_MAPPER.readTree(extractJsonBody(active));

        boolean hasGetItem = false;     // unique to spec V1
        boolean hasCreateItem = false;  // unique to spec V2
        for (JsonNode exp : expectations) {
            String id = exp.get("id").asText();
            if (id.endsWith(":getItem")) {
                hasGetItem = true;
            }
            if (id.endsWith(":createItem")) {
                hasCreateItem = true;
            }
        }
        assertThat("spec V1's getItem must survive importing spec V2 (no cross-spec deletion)", hasGetItem, is(true));
        assertThat("spec V2's createItem must be present", hasCreateItem, is(true));
    }

    @Test
    public void shouldBeIdempotentWhenReimportingSameSpec() throws Exception {
        // Import v1 twice
        sendPutRequest("/mockserver/openapi", SPEC_V1);
        sendPutRequest("/mockserver/openapi", SPEC_V1);

        // Should still have exactly 2 expectations, no duplicates
        String active = sendPutRequest("/mockserver/retrieve?type=ACTIVE_EXPECTATIONS", "");
        JsonNode expectations = OBJECT_MAPPER.readTree(extractJsonBody(active));
        assertThat("reimporting same spec should not create duplicates", expectations.size(), is(2));
    }

    // ---- HTTP helpers (matching GrpcIntegrationTest / LlmAgentLoopE2eTest pattern) ----

    private String sendPutRequest(String path, String body) throws Exception {
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            String headers = "PUT " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + mockServerPort + "\r\n" +
                "Connection: close\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "\r\n";
            output.write(headers.getBytes(StandardCharsets.UTF_8));
            output.write(bodyBytes);
            output.flush();
            return IOUtils.toString(socket.getInputStream(), StandardCharsets.UTF_8);
        }
    }

    private String extractJsonBody(String httpResponse) {
        int bodyStart = httpResponse.indexOf("\r\n\r\n");
        if (bodyStart < 0) {
            bodyStart = httpResponse.indexOf("\n\n");
            if (bodyStart < 0) {
                return httpResponse;
            }
            return httpResponse.substring(bodyStart + 2);
        }
        return httpResponse.substring(bodyStart + 4);
    }
}
