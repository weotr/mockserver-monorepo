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
 *   <li>PUT /mockserver/openapi with an inline OpenAPI spec creates expectations with stable
 *       {@code openapi:<specKey>:<operationId>} ids.</li>
 *   <li>Re-importing a modified spec (same title, different operations) prunes removed operations
 *       and adds new ones without creating duplicates — the incremental/idempotent contract.</li>
 * </ul>
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

        // Verify stable ids: openapi:foo:listItems and openapi:foo:getItem
        boolean hasListItems = false;
        boolean hasGetItem = false;
        for (JsonNode exp : expectations) {
            String id = exp.get("id").asText();
            if ("openapi:foo:listItems".equals(id)) {
                hasListItems = true;
            }
            if ("openapi:foo:getItem".equals(id)) {
                hasGetItem = true;
            }
        }
        assertThat("should have openapi:foo:listItems", hasListItems, is(true));
        assertThat("should have openapi:foo:getItem", hasGetItem, is(true));
    }

    @Test
    public void shouldIncrementallySyncOpenApiSpec() throws Exception {
        // Import v1 spec (listItems + getItem)
        String responseV1 = sendPutRequest("/mockserver/openapi", SPEC_V1);
        assertThat("first import should return 201", responseV1, containsString("201"));

        // Verify 2 expectations after v1
        String activeV1 = sendPutRequest("/mockserver/retrieve?type=ACTIVE_EXPECTATIONS", "");
        JsonNode expectationsV1 = OBJECT_MAPPER.readTree(extractJsonBody(activeV1));
        assertThat("v1 should have 2 expectations", expectationsV1.size(), is(2));

        // Import v2 spec (listItems kept, getItem removed, createItem added)
        String responseV2 = sendPutRequest("/mockserver/openapi", SPEC_V2);
        assertThat("second import should return 201", responseV2, containsString("201"));

        // Retrieve active expectations after v2
        String activeV2 = sendPutRequest("/mockserver/retrieve?type=ACTIVE_EXPECTATIONS", "");
        JsonNode expectationsV2 = OBJECT_MAPPER.readTree(extractJsonBody(activeV2));

        assertThat("v2 should have exactly 2 expectations (listItems + createItem)", expectationsV2.size(), is(2));

        boolean hasListItems = false;
        boolean hasCreateItem = false;
        boolean hasGetItem = false;
        for (JsonNode exp : expectationsV2) {
            String id = exp.get("id").asText();
            if ("openapi:foo:listItems".equals(id)) {
                hasListItems = true;
            }
            if ("openapi:foo:createItem".equals(id)) {
                hasCreateItem = true;
            }
            if ("openapi:foo:getItem".equals(id)) {
                hasGetItem = true;
            }
        }
        assertThat("listItems should be retained", hasListItems, is(true));
        assertThat("createItem should be added", hasCreateItem, is(true));
        assertThat("getItem should be pruned", hasGetItem, is(false));
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
