package org.mockserver.model;

/**
 * Identifies the protocol event that triggers a cross-protocol scenario
 * state transition. When a matching event fires, the associated scenario
 * is advanced to the configured target state.
 */
public enum CrossProtocolTrigger {
    DNS_QUERY,
    WEBSOCKET_CONNECT,
    GRPC_REQUEST,
    HTTP_REQUEST
}
