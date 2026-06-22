<?php

declare(strict_types=1);

namespace MockServer\Llm;

/**
 * Minimal RFC 4122 v4 UUID generator (no external dependency), used for
 * conversation scenario names.
 *
 * @internal
 */
final class Uuid
{
    private function __construct()
    {
    }

    public static function v4(): string
    {
        $bytes = random_bytes(16);
        // Set version to 0100 (v4) and variant to 10xx (RFC 4122).
        $bytes[6] = chr((ord($bytes[6]) & 0x0f) | 0x40);
        $bytes[8] = chr((ord($bytes[8]) & 0x3f) | 0x80);

        $hex = bin2hex($bytes);

        return sprintf(
            '%s-%s-%s-%s-%s',
            substr($hex, 0, 8),
            substr($hex, 8, 4),
            substr($hex, 12, 4),
            substr($hex, 16, 4),
            substr($hex, 20, 12)
        );
    }
}
