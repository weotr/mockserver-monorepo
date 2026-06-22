package org.mockserver.client;

import org.mockserver.serialization.model.PausedStreamFrameDTO;
import org.mockserver.serialization.model.StreamFrameDecisionDTO;

/**
 * Handler invoked when a RESPONSE_STREAM or INBOUND_STREAM phase breakpoint is hit.
 * The paused frame DTO is passed to the handler; the return value is the decision
 * DTO that determines how the frame is resolved.
 *
 * <p>The handler receives a {@link PausedStreamFrameDTO} and must return a
 * {@link StreamFrameDecisionDTO} with the {@code correlationId} echoed from the
 * input and an {@code action} of CONTINUE, MODIFY, DROP, INJECT, or CLOSE.
 */
@FunctionalInterface
public interface BreakpointStreamFrameHandler {

    /**
     * Handle a paused stream frame at the RESPONSE_STREAM or INBOUND_STREAM
     * breakpoint phase.
     *
     * @param pausedFrame the paused stream frame DTO from the server
     * @return the decision DTO to send back to the server
     */
    StreamFrameDecisionDTO handle(PausedStreamFrameDTO pausedFrame);
}
