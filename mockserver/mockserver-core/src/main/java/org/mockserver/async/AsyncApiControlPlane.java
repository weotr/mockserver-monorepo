package org.mockserver.async;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * SPI interface for the AsyncAPI control-plane, defined in mockserver-core so that
 * {@link org.mockserver.mock.HttpState} can route {@code PUT/GET /mockserver/asyncapi}
 * without depending on the mockserver-async module.
 * <p>
 * The implementation lives in {@code org.mockserver.async.controlplane.AsyncApiControlPlaneImpl}
 * (mockserver-async) and is registered at server startup via
 * {@link AsyncApiControlPlaneRegistry#register(AsyncApiControlPlane)}.
 * <p>
 * This follows the same singleton-registry pattern used by {@link org.mockserver.grpc.GrpcHealthRegistry},
 * {@link org.mockserver.wasm.WasmStore}, and {@link org.mockserver.mock.drift.DriftStore}:
 * a lightweight interface/holder in core, implementation in an optional module, HttpState
 * routes to the holder, and the holder gracefully returns "not available" when no
 * implementation is registered.
 */
public interface AsyncApiControlPlane {

    /**
     * Load an AsyncAPI spec and start mocking (publishing and/or subscribing).
     *
     * @param requestBody the raw request body — either a plain AsyncAPI spec (JSON/YAML)
     *                    or a JSON wrapper {@code {"spec": "...", "brokerConfig": {...}}}
     * @return a JSON status node describing what was loaded and started
     */
    JsonNode load(String requestBody);

    /**
     * Return the current status: loaded spec info, active channels, publishers, subscribers,
     * and recorded messages.
     *
     * @return a JSON status node
     */
    JsonNode status();

    /**
     * Reset all async mocking state — stop publishers/subscribers, clear recorded messages.
     * Called from {@link org.mockserver.mock.HttpState#reset()}.
     */
    void reset();

    /**
     * Verify that recorded messages match the given criteria.
     * <p>
     * The verification request is a JSON string with fields:
     * <ul>
     *   <li>{@code channel} (required) — the channel/topic to check</li>
     *   <li>{@code payloadSubstring} (optional) — payload must contain this substring</li>
     *   <li>{@code payloadJsonPath} (optional) — a dot-notation JSON path to extract</li>
     *   <li>{@code expectedValue} (optional) — the expected value at the JSON path</li>
     *   <li>{@code count} (optional) — object with {@code atLeast}, {@code atMost}, or
     *       {@code exactly} fields; defaults to {@code atLeast: 1}</li>
     * </ul>
     *
     * @param verificationJson the JSON verification request
     * @return {@code null} or empty string if the verification passes;
     *         a human-readable failure description if it does not
     * @throws IllegalArgumentException if the request is malformed
     */
    String verify(String verificationJson);

    /**
     * Generate HTTP mock expectations from an AsyncAPI spec so that example messages can be
     * served over plain HTTP (one {@code GET} expectation per channel returning a schema-aware
     * example payload), without standing up a live message broker.
     * <p>
     * This is the import analogue of {@link #load(String)}: instead of publishing example
     * messages to a broker, it returns expectations the caller can register on the HTTP mock.
     * Implemented in mockserver-async (which has access to {@code mockserver-core} types), the
     * result is returned as a JSON array string in the standard MockServer expectation format,
     * so that {@link org.mockserver.mock.HttpState} can deserialize and add it without a
     * compile-time dependency on the async module's parser.
     *
     * @param requestBody the raw request body — a plain AsyncAPI spec (JSON/YAML) or a JSON
     *                    wrapper {@code {"spec": "...", "channelPathPrefix": "..."}}
     * @return a JSON array string of MockServer expectations (one per channel)
     * @throws IllegalArgumentException if the spec is missing or cannot be parsed
     */
    String generateHttpExpectations(String requestBody);
}
