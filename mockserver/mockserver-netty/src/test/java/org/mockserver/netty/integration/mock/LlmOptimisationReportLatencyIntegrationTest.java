package org.mockserver.netty.integration.mock;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.Delay;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end check that per-call upstream latency is populated in the LLM
 * optimisation report for proxied/forwarded traffic. A forward action sends an
 * LLM-shaped request to a deliberately-delayed upstream; the report served by
 * the forwarding server must show a non-zero round-trip latency, and the
 * forwarded response written to the real client must NOT leak the internal
 * {@code x-mockserver-response-time-ms} header.
 * <p>
 * The forward target is {@code localhost} (Host is adjusted to the target on the
 * forward path), so the report's provider sniffer can't recognise a well-known
 * host; the path-gated configured-provider fallback ({@code llmProvider=OPENAI}
 * on a {@code /v1/...} path) classifies the recorded traffic as LLM. The
 * {@code llmProvider} property is set/reset around the class.
 */
public class LlmOptimisationReportLatencyIntegrationTest {

    private static ClientAndServer upstream;
    private static ClientAndServer proxy;
    private static int proxyPort;
    private static String previousLlmProvider;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final String OPENAI_BODY =
        "{\"model\":\"gpt-4o-2024-08-06\",\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":10}}";

    @BeforeClass
    public static void startServers() {
        previousLlmProvider = ConfigurationProperties.llmProvider();
        ConfigurationProperties.llmProvider("OPENAI");

        upstream = startClientAndServer();
        upstream
            .when(request().withMethod("POST").withPath("/v1/chat/completions"))
            .respond(response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody(OPENAI_BODY)
                // deliberate upstream delay so the round-trip latency is clearly > 0
                .withDelay(Delay.milliseconds(250)));

        proxy = startClientAndServer();
        proxyPort = proxy.getPort();
        proxy
            .when(request().withMethod("POST").withPath("/v1/chat/completions"))
            .forward(forward().withHost("localhost").withPort(upstream.getPort()));
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(proxy);
        stopQuietly(upstream);
        ConfigurationProperties.llmProvider(previousLlmProvider == null ? "" : previousLlmProvider);
    }

    @Test
    public void perCallLatencyIsPopulatedForForwardedTrafficWithoutLeakingHeader() throws Exception {
        // send an LLM-shaped request through the forwarding proxy
        HttpRequest forwarded = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + proxyPort + "/v1/chat/completions"))
            .header("content-type", "application/json")
            .header("Authorization", "Bearer sk-secret-abc")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"model\":\"gpt-4o-2024-08-06\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
            .build();
        HttpResponse<String> forwardedResponse = HTTP.send(forwarded, HttpResponse.BodyHandlers.ofString());

        assertThat(forwardedResponse.statusCode(), is(200));
        // the internal timing header must NEVER be written to the real client
        assertThat("client response must not leak the internal response-time header",
            forwardedResponse.headers().firstValue("x-mockserver-response-time-ms").isPresent(), is(false));

        // query the optimisation report on the forwarding server
        HttpRequest reportRequest = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + proxyPort + "/mockserver/llm/optimisationReport?format=json"))
            .GET()
            .build();
        HttpResponse<String> report = HTTP.send(reportRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(report.statusCode(), is(200));
        String body = report.body();
        assertThat("report should contain exactly one captured call, was:\n" + body,
            body.contains("\"callCount\" : 1"), is(true));

        long totalLatency = extractLong(body, "\"totalLatencyMs\"\\s*:\\s*(\\d+)");
        assertTrue("expected per-call upstream latency > 0 but report totalLatencyMs was " + totalLatency
            + " in report:\n" + body, totalLatency > 0);
    }

    private static long extractLong(String json, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(json);
        assertTrue("could not find pattern " + regex + " in:\n" + json, matcher.find());
        return Long.parseLong(matcher.group(1));
    }
}
