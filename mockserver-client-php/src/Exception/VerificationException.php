<?php

declare(strict_types=1);

namespace MockServer\Exception;

/**
 * Thrown when a verification call fails (HTTP 406 from MockServer).
 */
class VerificationException extends MockServerException
{
}
