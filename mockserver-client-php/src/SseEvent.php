<?php

declare(strict_types=1);

namespace MockServer;

/**
 * A single Server-Sent Event within an {@see HttpSseResponse}.
 *
 * Wire keys: event, data, id, retry, delay.
 */
class SseEvent implements \JsonSerializable
{
    private ?string $event = null;
    private ?string $data = null;
    private ?string $id = null;
    private ?int $retry = null;
    private ?Delay $delay = null;

    public static function event(): self
    {
        return new self();
    }

    public function withEvent(string $event): self
    {
        $this->event = $event;
        return $this;
    }

    public function withData(string $data): self
    {
        $this->data = $data;
        return $this;
    }

    public function withId(string $id): self
    {
        $this->id = $id;
        return $this;
    }

    /**
     * Set the SSE "retry" reconnection time in milliseconds.
     */
    public function withRetry(int $retryMillis): self
    {
        $this->retry = $retryMillis;
        return $this;
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
        if ($this->event !== null) {
            $data['event'] = $this->event;
        }
        if ($this->data !== null) {
            $data['data'] = $this->data;
        }
        if ($this->id !== null) {
            $data['id'] = $this->id;
        }
        if ($this->retry !== null) {
            $data['retry'] = $this->retry;
        }
        if ($this->delay !== null) {
            $data['delay'] = $this->delay->toArray();
        }
        return $data;
    }
}
