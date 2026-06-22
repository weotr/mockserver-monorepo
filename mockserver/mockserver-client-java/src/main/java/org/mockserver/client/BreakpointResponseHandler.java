package org.mockserver.client;

import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

/**
 * Handler invoked when a RESPONSE-phase breakpoint is hit. The paused request
 * and response are passed to the handler; the return value is the response
 * that will be written to the downstream client (continue or modify).
 */
@FunctionalInterface
public interface BreakpointResponseHandler {

    /**
     * Handle a paused response at the RESPONSE breakpoint phase.
     *
     * @param httpRequest  the original request
     * @param httpResponse the upstream response that was paused
     * @return the response to write to the downstream client
     */
    HttpResponse handle(HttpRequest httpRequest, HttpResponse httpResponse);
}
