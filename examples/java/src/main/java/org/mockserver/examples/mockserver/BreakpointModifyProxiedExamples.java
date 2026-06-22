package org.mockserver.examples.mockserver;

import org.mockserver.client.MockServerClient;
import org.mockserver.mock.breakpoint.BreakpointPhase;

import java.util.EnumSet;

import static org.mockserver.model.HttpOverrideForwardedRequest.forwardOverriddenRequest;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.MediaType.APPLICATION_JSON_UTF_8;
import static org.mockserver.model.SocketAddress.socketAddress;

/**
 * Demonstrates using interactive breakpoints to modify proxied (forwarded) traffic.
 *
 * <p>These examples use a self-forwarding (loopback) setup so they are entirely
 * self-contained: no external upstream server is required. The MockServer forwards
 * to itself via {@code socketAddress}, and a breakpoint intercepts the exchange.
 *
 * <p>The RESPONSE phase fires reliably on matched forward expectations, making it
 * the most dependable phase for modifying proxied responses.
 */
public class BreakpointModifyProxiedExamples {

    /**
     * Register a RESPONSE-phase breakpoint that modifies the upstream response
     * before it reaches the caller.
     *
     * <p>Flow:
     * <ol>
     *   <li>Create a mock "upstream" endpoint: GET /upstream/greeting -> 200 JSON</li>
     *   <li>Create a forward expectation: GET /service/greeting -> loopback to /upstream/greeting</li>
     *   <li>Register a RESPONSE-phase breakpoint whose handler modifies the response body</li>
     * </ol>
     *
     * <p>When a caller sends GET /service/greeting, the response body is modified by
     * the breakpoint handler before being delivered.
     */
    public void modifyProxiedResponseWithBreakpoint() {
        MockServerClient client = new MockServerClient("localhost", 1080);

        // Step 1: Create mock upstream endpoint
        client
            .when(
                request()
                    .withMethod("GET")
                    .withPath("/upstream/greeting")
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withContentType(APPLICATION_JSON_UTF_8)
                    .withBody("{\"message\":\"Hello from upstream\",\"source\":\"original\"}")
            );

        // Step 2: Create loopback forward expectation
        client
            .when(
                request()
                    .withMethod("GET")
                    .withPath("/service/greeting")
            )
            .forward(
                forwardOverriddenRequest(
                    request()
                        .withPath("/upstream/greeting")
                        .withSocketAddress(
                            socketAddress()
                                .withHost("localhost")
                                .withPort(1080)
                                .withScheme(org.mockserver.model.SocketAddress.Scheme.HTTP)
                        )
                )
            );

        // Step 3: Register a RESPONSE-phase breakpoint
        String breakpointId = client.addBreakpoint(
            request()
                .withMethod("GET")
                .withPath("/service/greeting"),
            EnumSet.of(BreakpointPhase.RESPONSE),
            // No request handler (RESPONSE phase only)
            null,
            // RESPONSE handler: modify the upstream response body
            (httpRequest, httpResponse) -> {
                String originalBody = httpResponse.getBodyAsString();
                String modifiedBody = originalBody
                    .replace("\"source\":\"original\"", "\"source\":\"modified-by-breakpoint\"")
                    .replace("}", ",\"injectedField\":\"added by breakpoint handler\"}");
                return httpResponse.withBody(modifiedBody);
            },
            // No stream frame handler
            null
        );
    }

    /**
     * Register a breakpoint using the full {@code addBreakpoint} overload
     * with explicit phase set and both request and response handlers.
     *
     * <p>The request handler passes the request through unchanged (auto-continue).
     * The response handler injects a custom header into the upstream response.
     */
    public void modifyProxiedResponseWithExplicitPhases() {
        MockServerClient client = new MockServerClient("localhost", 1080);

        // Upstream mock
        client
            .when(
                request()
                    .withMethod("GET")
                    .withPath("/upstream/data")
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withBody("{\"data\":\"from upstream\"}")
            );

        // Loopback forward
        client
            .when(
                request()
                    .withMethod("GET")
                    .withPath("/service/data")
            )
            .forward(
                forwardOverriddenRequest(
                    request()
                        .withPath("/upstream/data")
                        .withSocketAddress(
                            socketAddress()
                                .withHost("localhost")
                                .withPort(1080)
                                .withScheme(org.mockserver.model.SocketAddress.Scheme.HTTP)
                        )
                )
            );

        // Register breakpoint with explicit phases and both handlers
        String breakpointId = client.addBreakpoint(
            request()
                .withMethod("GET")
                .withPath("/service/data"),
            EnumSet.of(BreakpointPhase.REQUEST, BreakpointPhase.RESPONSE),
            // REQUEST handler: pass through unchanged
            httpRequest -> httpRequest,
            // RESPONSE handler: inject a custom header
            (httpRequest, httpResponse) ->
                httpResponse.withHeader("X-Breakpoint-Modified", "true"),
            // No stream frame handler
            null
        );
    }
}
