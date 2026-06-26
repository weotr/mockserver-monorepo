package org.mockserver.netty.integration.mock;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Integration test for the {@code GET /mockserver/llm/optimisationReport}
 * control-plane endpoint. Verifies routing, both output formats with their
 * content types, format validation, and the empty-capture behaviour (200 with
 * an empty report / "no LLM traffic" brief). The report-construction logic over
 * recorded traffic is covered by the core service and MCP tool tests.
 */
public class LlmOptimisationReportEndpointIntegrationTest {

    private static ClientAndServer server;
    private static int port;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeClass
    public static void startServer() {
        server = startClientAndServer();
        port = server.getPort();
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(server);
    }

    private static HttpResponse<String> get(String query) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/mockserver/llm/optimisationReport" + query))
            .GET()
            .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void jsonFormatReturnsEmptyReportWithJsonContentType() throws Exception {
        HttpResponse<String> response = get("?format=json");
        assertThat(response.statusCode(), is(200));
        assertThat(response.headers().firstValue("content-type").orElse(""), containsString("application/json"));
        assertThat(response.body(), containsString("\"schemaVersion\""));
        assertThat(response.body(), containsString("\"callCount\" : 0"));
        assertThat(response.body(), containsString("\"generatedBy\" : \"mockserver\""));
    }

    @Test
    public void defaultFormatIsJson() throws Exception {
        HttpResponse<String> response = get("");
        assertThat(response.statusCode(), is(200));
        assertThat(response.headers().firstValue("content-type").orElse(""), containsString("application/json"));
        assertThat(response.body(), containsString("\"schemaVersion\""));
    }

    @Test
    public void markdownFormatReturnsBriefWithMarkdownContentType() throws Exception {
        HttpResponse<String> response = get("?format=markdown");
        assertThat(response.statusCode(), is(200));
        assertThat(response.headers().firstValue("content-type").orElse(""), containsString("text/markdown"));
        assertThat(response.body(), containsString("You are an LLM cost-optimisation expert"));
        assertThat(response.body(), containsString("No LLM traffic captured"));
    }

    @Test
    public void csvFormatReturnsCsvWithCsvContentType() throws Exception {
        HttpResponse<String> response = get("?format=csv");
        assertThat(response.statusCode(), is(200));
        assertThat(response.headers().firstValue("content-type").orElse(""), containsString("text/csv"));
        // Per-call header row is always present (header-only on an empty capture).
        assertThat(response.body(), containsString("index,provider,model,input_tokens"));
        // Totals/summary section is always present.
        assertThat(response.body(), containsString("section,metric,value"));
        assertThat(response.body(), containsString("totals,call_count,0"));
        assertThat(response.body(), containsString("verdict,grade,A"));
    }

    @Test
    public void invalidFormatReturnsBadRequestWithWidenedMessage() throws Exception {
        HttpResponse<String> response = get("?format=xml");
        assertThat(response.statusCode(), is(400));
        assertThat(response.body(), containsString("format must be one of: json, markdown, csv"));
    }

    @Test
    public void responseCarriesCorsHeadersForCrossOriginDashboard() throws Exception {
        // The dashboard UI may be served from a different host/port than the API,
        // so the control-plane response must carry CORS headers (this endpoint
        // routes through the standard apiResponse=true path that always adds them).
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/mockserver/llm/optimisationReport?format=json"))
            .header("Origin", "http://localhost:1234")
            .GET()
            .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode(), is(200));
        // MockServer echoes the requesting origin back (more secure than a bare "*"),
        // which is what a cross-origin dashboard needs to read the response.
        assertThat(response.headers().firstValue("access-control-allow-origin").orElse(""), is("http://localhost:1234"));
    }

    @Test
    public void preflightOptionsRequestIsAllowedForControlPlane() throws Exception {
        // A browser preflights the cross-origin request with OPTIONS; control-plane
        // paths (under /mockserver) answer the preflight even without enableCORSForAPI.
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/mockserver/llm/optimisationReport"))
            .header("Origin", "http://localhost:1234")
            .header("Access-Control-Request-Method", "GET")
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode(), is(200));
        assertThat(response.headers().firstValue("access-control-allow-methods").orElse(""), containsString("GET"));
    }
}
