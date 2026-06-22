<?php

declare(strict_types=1);

namespace MockServer\Exception;

/**
 * Thrown when the on-demand binary launcher fails to download, verify, or
 * extract the MockServer binary bundle.
 */
class BinaryInstallException extends MockServerException
{
}
