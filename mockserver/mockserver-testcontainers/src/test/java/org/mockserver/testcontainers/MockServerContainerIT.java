package org.mockserver.testcontainers;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockserver.client.MockServerClient;
import org.testcontainers.DockerClientFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Integration tests for {@link MockServerContainer} that require a running Docker daemon.
 * <p>
 * Named {@code *IT} so Maven runs it in the integration-test/verify phase only.
 * This test is validated in CI (Buildkite), not locally; it is skipped when Docker is unavailable.
 */
class MockServerContainerIT {

    @Test
    void containerStartsAndAcceptsMockExpectation() throws Exception {
        Assumptions.assumeTrue(
            DockerClientFactory.instance().isDockerAvailable(),
            "Docker is not available — skipping integration test"
        );

        try (MockServerContainer container = new MockServerContainer(
            // Use a known good image for integration testing
            org.testcontainers.utility.DockerImageName.parse("mockserver/mockserver:latest")
        )) {
            container.start();

            // Create an expectation via the client
            MockServerClient client = container.getClient();
            client.when(
                request().withMethod("GET").withPath("/hello")
            ).respond(
                response().withStatusCode(200).withBody("world")
            );

            // Issue a matching HTTP request
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(container.getEndpoint() + "/hello"))
                .GET()
                .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            assertThat(httpResponse.statusCode(), is(200));
            assertThat(httpResponse.body(), is("world"));

            // Verify endpoint format
            assertThat(container.getEndpoint(), startsWith("http://"));
            assertThat(container.getSecureEndpoint(), startsWith("https://"));
        }
    }
}
