package org.mockserver.async;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Process-wide holder for the optional {@link AsyncApiControlPlane} implementation.
 * <p>
 * Lives in mockserver-core so that {@link org.mockserver.mock.HttpState} can call it
 * without a compile-time dependency on mockserver-async. When no implementation is
 * registered (the module is not on the classpath), the {@code load()} and {@code status()}
 * methods return a "not available" JSON response — the endpoint is still routable but
 * responds with 501/503 semantics, same as other optional features.
 * <p>
 * Thread safety: {@link #register(AsyncApiControlPlane)} is called once at startup;
 * reads are safe after that. The volatile field ensures visibility across threads.
 */
public class AsyncApiControlPlaneRegistry {

    private static final AsyncApiControlPlaneRegistry INSTANCE = new AsyncApiControlPlaneRegistry();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private volatile AsyncApiControlPlane delegate;

    AsyncApiControlPlaneRegistry() {
    }

    public static AsyncApiControlPlaneRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Register the control-plane implementation. Called once at server startup by
     * the mockserver-async module's bootstrap code.
     */
    public void register(AsyncApiControlPlane controlPlane) {
        this.delegate = controlPlane;
    }

    /**
     * @return true if an implementation is registered (mockserver-async is on the classpath)
     */
    public boolean isAvailable() {
        return delegate != null;
    }

    /**
     * Delegate to the registered implementation, or return a "not available" response.
     */
    public JsonNode load(String requestBody) {
        if (delegate == null) {
            return notAvailableResponse();
        }
        return delegate.load(requestBody);
    }

    /**
     * Delegate to the registered implementation, or return a "not available" response.
     */
    public JsonNode status() {
        if (delegate == null) {
            return notAvailableResponse();
        }
        return delegate.status();
    }

    /**
     * Reset async mocking state. Safe to call even when no implementation is registered.
     */
    public void reset() {
        if (delegate != null) {
            delegate.reset();
        }
    }

    /**
     * Delegate verify to the registered implementation, or return a not-available message.
     *
     * @return {@code null} if verification passes; a failure description if it fails;
     *         a not-available message if no implementation is registered
     */
    public String verify(String verificationJson) {
        if (delegate == null) {
            return "AsyncAPI messaging module is not available — mockserver-async is not on the classpath";
        }
        return delegate.verify(verificationJson);
    }

    private JsonNode notAvailableResponse() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", "AsyncAPI messaging module is not available — mockserver-async is not on the classpath");
        return node;
    }
}
