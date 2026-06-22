<?php

declare(strict_types=1);

namespace MockServer;

/**
 * A single WebSocket message within an {@see HttpWebSocketResponse}.
 *
 * Wire keys: text, binary (base64-encoded), delay. A message is either
 * textual or binary; binary payloads are base64-encoded on the wire.
 */
class WebSocketMessage implements \JsonSerializable
{
    private ?string $text = null;
    private ?string $binary = null;
    private ?Delay $delay = null;

    /**
     * Create a textual WebSocket message.
     */
    public static function text(string $text): self
    {
        $message = new self();
        $message->text = $text;
        return $message;
    }

    /**
     * Create a binary WebSocket message from raw bytes (base64-encoded on the wire).
     *
     * @param string $bytes Raw binary data
     */
    public static function binary(string $bytes): self
    {
        $message = new self();
        $message->binary = base64_encode($bytes);
        return $message;
    }

    /**
     * Create a binary WebSocket message from already base64-encoded data.
     */
    public static function binaryBase64(string $base64): self
    {
        $message = new self();
        $message->binary = $base64;
        return $message;
    }

    public function withDelay(Delay $delay): self
    {
        $this->delay = $delay;
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
        if ($this->text !== null) {
            $data['text'] = $this->text;
        }
        if ($this->binary !== null) {
            $data['binary'] = $this->binary;
        }
        if ($this->delay !== null) {
            $data['delay'] = $this->delay->toArray();
        }
        return $data;
    }
}
