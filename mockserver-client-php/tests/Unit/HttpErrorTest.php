<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use MockServer\HttpError;
use PHPUnit\Framework\TestCase;

class HttpErrorTest extends TestCase
{
    public function testEmptyError(): void
    {
        $error = HttpError::error();
        $this->assertSame([], $error->toArray());
    }

    public function testDropConnection(): void
    {
        $error = HttpError::error()->dropConnection(true);

        $this->assertSame(['dropConnection' => true], $error->toArray());
    }

    public function testResponseBytes(): void
    {
        $bytes = base64_encode('garbage');
        $error = HttpError::error()
            ->dropConnection(true)
            ->responseBytes($bytes);

        $expected = [
            'dropConnection' => true,
            'responseBytes' => $bytes,
        ];

        $this->assertSame($expected, $error->toArray());
    }

    public function testJsonSerialize(): void
    {
        $error = HttpError::error()->dropConnection(false);

        $json = json_encode($error, JSON_THROW_ON_ERROR);
        $decoded = json_decode($json, true);

        $this->assertFalse($decoded['dropConnection']);
    }
}
