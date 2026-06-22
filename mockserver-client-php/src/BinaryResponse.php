<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Fluent builder for a raw binary (TCP/binary-proxy) response action.
 *
 * Produces the {@code binaryResponse} action JSON. Wire keys:
 * binaryData (base64-encoded), delay, primary.
 *
 * @example
 *   BinaryResponse::response()->fromBytes("\x00\x01\x02");
 */
class BinaryResponse implements \JsonSerializable
{
    private ?string $binaryData = null;
    private ?Delay $delay = null;
    private ?bool $primary = null;

    public static function response(): self
    {
        return new self();
    }

    /**
     * Set the response payload from raw bytes (base64-encoded on the wire).
     *
     * @param string $bytes Raw binary data
     */
    public function fromBytes(string $bytes): self
    {
        $this->binaryData = base64_encode($bytes);
        return $this;
    }

    /**
     * Set the response payload from already base64-encoded data.
     */
    public function binaryData(string $base64): self
    {
        $this->binaryData = $base64;
        return $this;
    }

    public function delay(Delay $delay): self
    {
        $this->delay = $delay;
        return $this;
    }

    public function primary(bool $primary): self
    {
        $this->primary = $primary;
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
        if ($this->binaryData !== null) {
            $data['binaryData'] = $this->binaryData;
        }
        if ($this->delay !== null) {
            $data['delay'] = $this->delay->toArray();
        }
        if ($this->primary !== null) {
            $data['primary'] = $this->primary;
        }
        return $data;
    }
}
