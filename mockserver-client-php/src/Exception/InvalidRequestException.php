<?php

declare(strict_types=1);

namespace MockServer\Exception;

/**
 * Thrown when the server rejects a request as invalid (HTTP 400).
 */
class InvalidRequestException extends MockServerException
{
}
