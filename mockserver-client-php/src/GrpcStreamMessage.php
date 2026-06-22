<?php

declare(strict_types=1);

namespace MockServer;

/**
 * A single message within a {@see GrpcStreamResponse}.
 *
 * Wire keys: json, delay. The {@code json} value is the protobuf message
 * rendered as JSON (decoded against the uploaded descriptor set).
 */
class GrpcStreamMessage implements \JsonSerializable
{
    private string|array|null $json = null;
    private ?Delay $delay = null;

    /**
     * Create a message from a JSON-encodable payload.
     *
     * @param array|string $json An associative array (encoded to JSON) or a JSON string.
     */
    public static function message(array|string $json): self
    {
        $message = new self();
        $message->json = $json;
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
        if ($this->json !== null) {
            $data['json'] = $this->json;
        }
        if ($this->delay !== null) {
            $data['delay'] = $this->delay->toArray();
        }
        return $data;
    }
}
