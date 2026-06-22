package org.mockserver.client;

import org.mockserver.model.HttpMessage;
import org.mockserver.model.HttpRequest;

/**
 * Handler invoked when a REQUEST-phase breakpoint is hit. The paused request
 * is passed to the handler; the return value determines the resolution:
 *
 * <ul>
 *   <li>Return an {@link HttpRequest} to CONTINUE (forward the original or a
 *       modified request upstream).</li>
 *   <li>Return an {@link org.mockserver.model.HttpResponse} to ABORT (write
 *       that response to the downstream client without forwarding).</li>
 * </ul>
 *
 * <p>The return type is {@link HttpMessage} so the handler can return either
 * an {@code HttpRequest} (continue/modify) or an {@code HttpResponse} (abort).
 */
@FunctionalInterface
public interface BreakpointRequestHandler {

    /**
     * Handle a paused request at the REQUEST breakpoint phase.
     *
     * @param httpRequest the request that was paused before being forwarded upstream
     * @return an {@link HttpRequest} to continue/modify, or an
     *         {@link org.mockserver.model.HttpResponse} to abort
     */
    HttpMessage handle(HttpRequest httpRequest);
}
