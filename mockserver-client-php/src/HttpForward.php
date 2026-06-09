<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Fluent builder for an HTTP forward action.
 *
 * @example
 *   $forward = HttpForward::forward()
 *       ->host('example.com')
 *       ->port(443)
 *       ->scheme('HTTPS');
 */
class HttpForward implements \JsonSerializable
{
    private ?string $host = null;
    private ?int $port = null;
    private ?string $scheme = null;

    /**
     * Static factory for fluent construction.
     */
    public static function forward(): self
    {
        return new self();
    }

    public function host(string $host): self
    {
        $this->host = $host;
        return $this;
    }

    public function port(int $port): self
    {
        $this->port = $port;
        return $this;
    }

    /**
     * @param string $scheme "HTTP" or "HTTPS"
     */
    public function scheme(string $scheme): self
    {
        $this->scheme = strtoupper($scheme);
        return $this;
    }

    public function getHost(): ?string
    {
        return $this->host;
    }

    public function getPort(): ?int
    {
        return $this->port;
    }

    public function getScheme(): ?string
    {
        return $this->scheme;
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

        if ($this->host !== null) {
            $data['host'] = $this->host;
        }
        if ($this->port !== null) {
            $data['port'] = $this->port;
        }
        if ($this->scheme !== null) {
            $data['scheme'] = $this->scheme;
        }

        return $data;
    }
}
