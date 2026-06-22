<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Fluent builder for a gRPC server-stream response action.
 *
 * Produces the {@code grpcStreamResponse} action JSON. Wire keys:
 * statusName, statusMessage, headers, messages, closeConnection, delay, primary.
 *
 * @example
 *   GrpcStreamResponse::response()
 *       ->message(GrpcStreamMessage::message(['id' => 1]))
 *       ->message(GrpcStreamMessage::message(['id' => 2]))
 *       ->statusName('OK');
 */
class GrpcStreamResponse implements \JsonSerializable
{
    private ?string $statusName = null;
    private ?string $statusMessage = null;
    /** @var array<string, list<string>> */
    private array $headers = [];
    /** @var list<GrpcStreamMessage> */
    private array $messages = [];
    private ?bool $closeConnection = null;
    private ?Delay $delay = null;
    private ?bool $primary = null;

    public static function response(): self
    {
        return new self();
    }

    /**
     * Set the trailing gRPC status name (e.g. "OK", "NOT_FOUND").
     */
    public function statusName(string $statusName): self
    {
        $this->statusName = $statusName;
        return $this;
    }

    public function statusMessage(string $statusMessage): self
    {
        $this->statusMessage = $statusMessage;
        return $this;
    }

    /**
     * Add a response header / metadata entry (multi-value supported).
     */
    public function header(string $name, string ...$values): self
    {
        if (!isset($this->headers[$name])) {
            $this->headers[$name] = [];
        }
        foreach ($values as $value) {
            $this->headers[$name][] = $value;
        }
        return $this;
    }

    /**
     * Append a single stream message.
     */
    public function message(GrpcStreamMessage $message): self
    {
        $this->messages[] = $message;
        return $this;
    }

    /**
     * Replace the full message list.
     *
     * @param list<GrpcStreamMessage> $messages
     */
    public function messages(array $messages): self
    {
        $this->messages = array_values($messages);
        return $this;
    }

    public function closeConnection(bool $closeConnection): self
    {
        $this->closeConnection = $closeConnection;
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
        if ($this->statusName !== null) {
            $data['statusName'] = $this->statusName;
        }
        if ($this->statusMessage !== null) {
            $data['statusMessage'] = $this->statusMessage;
        }
        if (!empty($this->headers)) {
            $data['headers'] = $this->headers;
        }
        if (!empty($this->messages)) {
            $data['messages'] = array_map(fn(GrpcStreamMessage $m) => $m->toArray(), $this->messages);
        }
        if ($this->closeConnection !== null) {
            $data['closeConnection'] = $this->closeConnection;
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
