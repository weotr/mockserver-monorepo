<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Triggering protocol events for a {@see CrossProtocolScenario}.
 *
 * These are the string values understood by the MockServer control plane.
 */
final class CrossProtocolTrigger
{
    /** A DNS query was received. */
    public const DNS_QUERY = 'DNS_QUERY';

    /** A WebSocket connection was established. */
    public const WEBSOCKET_CONNECT = 'WEBSOCKET_CONNECT';

    /** A gRPC request was received. */
    public const GRPC_REQUEST = 'GRPC_REQUEST';

    /** An HTTP request was received. */
    public const HTTP_REQUEST = 'HTTP_REQUEST';

    private function __construct()
    {
    }
}
