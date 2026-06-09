<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Fluent builder for an HTTP error action.
 *
 * @example
 *   $error = HttpError::error()
 *       ->dropConnection(true)
 *       ->responseBytes(base64_encode("garbage"));
 */
class HttpError implements \JsonSerializable
{
    private ?bool $dropConnection = null;
    private ?string $responseBytes = null;

    /**
     * Static factory for fluent construction.
     */
    public static function error(): self
    {
        return new self();
    }

    public function dropConnection(bool $dropConnection): self
    {
        $this->dropConnection = $dropConnection;
        return $this;
    }

    /**
     * @param string $responseBytes Base64-encoded bytes to return before dropping
     */
    public function responseBytes(string $responseBytes): self
    {
        $this->responseBytes = $responseBytes;
        return $this;
    }

    /**
     * @return array<string, mixed>
     */
    public function jsonSerialize(): array
    {
        return $this->toArray();
    }

    /**
     * @return array<string, mixed>
     */
    public function toArray(): array
    {
        $data = [];

        if ($this->dropConnection !== null) {
            $data['dropConnection'] = $this->dropConnection;
        }
        if ($this->responseBytes !== null) {
            $data['responseBytes'] = $this->responseBytes;
        }

        return $data;
    }
}
