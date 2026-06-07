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

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Integration test for the Pact export endpoint (PUT /mockserver/pact).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Creating response expectations via the client and then calling PUT /mockserver/pact
 *       returns a valid Pact v3 consumer contract JSON.</li>
 *   <li>The returned JSON contains consumer.name, provider.name, interactions with the
 *       correct request/response details, and metadata.pactSpecification.version=3.0.0.</li>
 * </ul>
 */
public class PactExportIntegrationTest {

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

    @Test
    public void shouldExportExpectationsAsPactContract() throws Exception {
        // Create two response expectations via the client
        mockServerClient
            .when(request().withMethod("GET").withPath("/api/users"))
            .respond(
                response()
                    .withStatusCode(200)
                    .withHeader("content-type", "application/json")
                    .withBody("[{\"id\":1,\"name\":\"Alice\"}]")
            );

        mockServerClient
            .when(request().withMethod("POST").withPath("/api/users").withBody("{\"name\":\"Bob\"}"))
            .respond(
                response()
                    .withStatusCode(201)
                    .withHeader("content-type", "application/json")
                    .withBody("{\"id\":2,\"name\":\"Bob\"}")
            );

        // Export as Pact contract
        String response = sendPutRequest("/mockserver/pact?consumer=MyConsumer&provider=MyProvider", "");
        assertThat("PUT /mockserver/pact should return 200", response, containsString("200"));

        String body = extractJsonBody(response);
        JsonNode pact = OBJECT_MAPPER.readTree(body);

        // Verify consumer and provider names
        assertThat(pact.path("consumer").path("name").asText(), is("MyConsumer"));
        assertThat(pact.path("provider").path("name").asText(), is("MyProvider"));

        // Verify interactions array
        JsonNode interactions = pact.path("interactions");
        assertThat("should have 2 interactions", interactions.size(), is(2));

        // Verify first interaction (GET /api/users)
        boolean hasGetUsers = false;
        boolean hasPostUsers = false;
        for (JsonNode interaction : interactions) {
            String method = interaction.path("request").path("method").asText();
            String path = interaction.path("request").path("path").asText();

            if ("GET".equals(method) && "/api/users".equals(path)) {
                hasGetUsers = true;
                assertThat(interaction.path("response").path("status").asInt(), is(200));
                // Body should be structured JSON, not an escaped string
                JsonNode responseBody = interaction.path("response").path("body");
                assertThat(responseBody.isArray(), is(true));
                assertThat(responseBody.get(0).path("name").asText(), is("Alice"));
            }
            if ("POST".equals(method) && "/api/users".equals(path)) {
                hasPostUsers = true;
                assertThat(interaction.path("response").path("status").asInt(), is(201));
                JsonNode responseBody = interaction.path("response").path("body");
                assertThat(responseBody.path("name").asText(), is("Bob"));
            }
        }
        assertThat("should have GET /api/users interaction", hasGetUsers, is(true));
        assertThat("should have POST /api/users interaction", hasPostUsers, is(true));

        // Verify Pact metadata
        assertThat(pact.path("metadata").path("pactSpecification").path("version").asText(), is("3.0.0"));
    }

    @Test
    public void shouldUseDefaultConsumerAndProviderWhenNotSpecified() throws Exception {
        // Create one simple expectation
        mockServerClient
            .when(request().withMethod("GET").withPath("/health"))
            .respond(response().withStatusCode(200).withBody("{\"status\":\"ok\"}"));

        // Export without specifying consumer/provider
        String response = sendPutRequest("/mockserver/pact", "");
        assertThat(response, containsString("200"));

        String body = extractJsonBody(response);
        JsonNode pact = OBJECT_MAPPER.readTree(body);

        // Should default to "consumer" and "provider"
        assertThat(pact.path("consumer").path("name").asText(), is("consumer"));
        assertThat(pact.path("provider").path("name").asText(), is("provider"));

        // Should still have the interaction
        assertThat(pact.path("interactions").size(), is(1));
    }

    @Test
    public void shouldReturnEmptyInteractionsWhenNoExpectations() throws Exception {
        // No expectations created — export should succeed with empty interactions
        String response = sendPutRequest("/mockserver/pact?consumer=C&provider=P", "");
        assertThat(response, containsString("200"));

        String body = extractJsonBody(response);
        JsonNode pact = OBJECT_MAPPER.readTree(body);

        assertThat(pact.path("consumer").path("name").asText(), is("C"));
        assertThat(pact.path("provider").path("name").asText(), is("P"));
        assertThat(pact.path("interactions").size(), is(0));
        assertThat(pact.path("metadata").path("pactSpecification").path("version").asText(), is("3.0.0"));
    }

    // ---- HTTP helpers ----

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
