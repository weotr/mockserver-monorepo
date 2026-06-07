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
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Integration test for the operating mode endpoint (GET/PUT /mockserver/mode).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>GET /mockserver/mode returns SIMULATE with proxyUnmatchedRequests=false by default.</li>
 *   <li>PUT /mockserver/mode?mode=SPY switches the mode and GET reflects SPY/true.</li>
 *   <li>PUT /mockserver/mode?mode=SIMULATE switches back and an unmatched request returns 404.</li>
 *   <li>PUT /mockserver/mode?mode=CAPTURE is accepted and reported correctly.</li>
 *   <li>Invalid mode names return 400.</li>
 * </ul>
 */
public class OperatingModeIntegrationTest {

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
    public void resetServer() throws Exception {
        mockServerClient.reset();
        // reset() does not reset the operating mode, so explicitly set SIMULATE
        // to ensure tests are independent of execution order
        sendPutRequest("/mockserver/mode?mode=SIMULATE", "");
    }

    @Test
    public void shouldReturnSimulateModeAfterReset() throws Exception {
        // After resetting to SIMULATE in @Before, GET should report SIMULATE
        String response = sendGetRequest("/mockserver/mode");
        assertThat("GET /mockserver/mode should return 200", response, containsString("200"));

        String body = extractJsonBody(response);
        JsonNode mode = OBJECT_MAPPER.readTree(body);
        assertThat(mode.path("mode").asText(), is("SIMULATE"));
        assertThat(mode.path("proxyUnmatchedRequests").asBoolean(), is(false));
    }

    @Test
    public void shouldSwitchToSpyMode() throws Exception {
        // Switch to SPY
        String putResponse = sendPutRequest("/mockserver/mode?mode=SPY", "");
        assertThat("PUT should return 200", putResponse, containsString("200"));

        String putBody = extractJsonBody(putResponse);
        JsonNode putMode = OBJECT_MAPPER.readTree(putBody);
        assertThat(putMode.path("mode").asText(), is("SPY"));
        assertThat(putMode.path("proxyUnmatchedRequests").asBoolean(), is(true));

        // Verify GET reflects SPY
        String getResponse = sendGetRequest("/mockserver/mode");
        String getBody = extractJsonBody(getResponse);
        JsonNode getMode = OBJECT_MAPPER.readTree(getBody);
        assertThat(getMode.path("mode").asText(), is("SPY"));
        assertThat(getMode.path("proxyUnmatchedRequests").asBoolean(), is(true));
    }

    @Test
    public void shouldSwitchToCaptureMode() throws Exception {
        String putResponse = sendPutRequest("/mockserver/mode?mode=CAPTURE", "");
        assertThat(putResponse, containsString("200"));

        String putBody = extractJsonBody(putResponse);
        JsonNode putMode = OBJECT_MAPPER.readTree(putBody);
        assertThat(putMode.path("mode").asText(), is("CAPTURE"));
        assertThat(putMode.path("proxyUnmatchedRequests").asBoolean(), is(true));

        // Verify GET reflects CAPTURE
        String getResponse = sendGetRequest("/mockserver/mode");
        String getBody = extractJsonBody(getResponse);
        JsonNode getMode = OBJECT_MAPPER.readTree(getBody);
        assertThat(getMode.path("mode").asText(), is("CAPTURE"));
        assertThat(getMode.path("proxyUnmatchedRequests").asBoolean(), is(true));
    }

    @Test
    public void shouldSwitchBackToSimulateMode() throws Exception {
        // First switch to SPY
        sendPutRequest("/mockserver/mode?mode=SPY", "");

        // Then back to SIMULATE
        String putResponse = sendPutRequest("/mockserver/mode?mode=SIMULATE", "");
        assertThat(putResponse, containsString("200"));

        String putBody = extractJsonBody(putResponse);
        JsonNode putMode = OBJECT_MAPPER.readTree(putBody);
        assertThat(putMode.path("mode").asText(), is("SIMULATE"));
        assertThat(putMode.path("proxyUnmatchedRequests").asBoolean(), is(false));

        // An unmatched request should return 404 in SIMULATE mode
        String unmatchedResponse = sendGetRequest("/nonexistent/path");
        assertThat("unmatched request in SIMULATE mode should return 404",
            unmatchedResponse, containsString("404"));
    }

    @Test
    public void shouldRejectInvalidMode() throws Exception {
        String response = sendPutRequest("/mockserver/mode?mode=INVALID", "");
        assertThat("invalid mode should return 400", response, containsString("400"));
    }

    @Test
    public void shouldRejectEmptyMode() throws Exception {
        String response = sendPutRequest("/mockserver/mode", "");
        assertThat("missing mode should return 400", response, containsString("400"));
    }

    @Test
    public void shouldAcceptCaseInsensitiveMode() throws Exception {
        String response = sendPutRequest("/mockserver/mode?mode=spy", "");
        assertThat(response, containsString("200"));

        String body = extractJsonBody(response);
        JsonNode mode = OBJECT_MAPPER.readTree(body);
        assertThat(mode.path("mode").asText(), is("SPY"));
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

    private String sendGetRequest(String path) throws Exception {
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            String headers = "GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + mockServerPort + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";
            output.write(headers.getBytes(StandardCharsets.UTF_8));
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
