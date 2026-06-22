<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Reference to a server-side callback class for a response or forward action.
 *
 * A class callback is the declarative, REST-only callback form: the
 * expectation names a class (already loaded on the MockServer's classpath)
 * that implements one of MockServer's callback interfaces
 * ({@code ExpectationResponseCallback} / {@code ExpectationForwardCallback}),
 * and MockServer invokes it to produce the response (or the request to
 * forward). The class name is the only required field — the class need not be
 * resolvable on the client side; the server resolves and stores it.
 *
 * Serialises to the {@code httpResponseClassCallback} /
 * {@code httpForwardClassCallback} wire shape:
 * {@code {"callbackClass": "...", "delay": {...}?, "primary": false?}}.
 *
 * Note: object/closure callbacks (where the callback runs in *your* process)
 * require a callback WebSocket that the REST-only PHP client does not
 * implement, so they are unavailable in PHP — use class callbacks instead.
 *
 * @example
 *   $callback = HttpClassCallback::callback('com.example.MyResponseCallback')
 *       ->delay(Delay::milliseconds(250));
 */
class HttpClassCallback implements \JsonSerializable
{
    private string $callbackClass;
    private ?Delay $delay = null;
    private ?bool $primary = null;

    public function __construct(string $callbackClass)
    {
        $this->callbackClass = $callbackClass;
    }

    /**
     * Static factory for fluent construction.
     */
    public static function callback(string $callbackClass): self
    {
        return new self($callbackClass);
    }

    /**
     * Set the fully-qualified name of the server-side callback class.
     */
    public function callbackClass(string $callbackClass): self
    {
        $this->callbackClass = $callbackClass;
        return $this;
    }

    /**
     * Apply a delay before the callback's result is returned.
     */
    public function delay(Delay $delay): self
    {
        $this->delay = $delay;
        return $this;
    }

    /**
     * Mark this callback's connection as primary (kept open in preference to
     * other connections when the server prunes idle ones).
     */
    public function primary(bool $primary): self
    {
        $this->primary = $primary;
        return $this;
    }

    public function getCallbackClass(): string
    {
        return $this->callbackClass;
    }

    public function getDelay(): ?Delay
    {
        return $this->delay;
    }

    public function getPrimary(): ?bool
    {
        return $this->primary;
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
        $data = ['callbackClass' => $this->callbackClass];

        if ($this->delay !== null) {
            $data['delay'] = $this->delay->toArray();
        }
        if ($this->primary !== null) {
            $data['primary'] = $this->primary;
        }

        return $data;
    }
}
