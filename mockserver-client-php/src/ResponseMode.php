<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Response-selection modes for an expectation with multiple {@code httpResponses}.
 *
 * These are the string values understood by the MockServer control plane; use
 * them with {@see Expectation::responseMode()}.
 */
final class ResponseMode
{
    /** Cycle through responses in order (default). */
    public const SEQUENTIAL = 'SEQUENTIAL';

    /** Pick a response at random (uniform). */
    public const RANDOM = 'RANDOM';

    /** Pick a response at random, weighted by {@code responseWeights}. */
    public const WEIGHTED = 'WEIGHTED';

    /** Serve each response for {@code switchAfter} requests before advancing. */
    public const SWITCH = 'SWITCH';

    private function __construct()
    {
    }
}
