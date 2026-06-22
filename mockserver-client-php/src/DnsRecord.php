<?php

declare(strict_types=1);

namespace MockServer;

/**
 * A single DNS resource record within a {@see DnsResponse}.
 *
 * Wire keys: name, type, dnsClass, ttl, value, priority, weight, port.
 */
class DnsRecord implements \JsonSerializable
{
    private ?string $name = null;
    private ?string $type = null;
    private ?string $dnsClass = null;
    private ?int $ttl = null;
    private ?string $value = null;
    private ?int $priority = null;
    private ?int $weight = null;
    private ?int $port = null;

    public static function record(): self
    {
        return new self();
    }

    public static function aRecord(string $name, string $ip): self
    {
        return (new self())->name($name)->type('A')->value($ip);
    }

    public static function aaaaRecord(string $name, string $ip): self
    {
        return (new self())->name($name)->type('AAAA')->value($ip);
    }

    public static function cnameRecord(string $name, string $cname): self
    {
        return (new self())->name($name)->type('CNAME')->value($cname);
    }

    public static function mxRecord(string $name, int $priority, string $exchange): self
    {
        return (new self())->name($name)->type('MX')->priority($priority)->value($exchange);
    }

    public static function srvRecord(string $name, int $priority, int $weight, int $port, string $target): self
    {
        return (new self())
            ->name($name)
            ->type('SRV')
            ->priority($priority)
            ->weight($weight)
            ->port($port)
            ->value($target);
    }

    public static function txtRecord(string $name, string $text): self
    {
        return (new self())->name($name)->type('TXT')->value($text);
    }

    public static function ptrRecord(string $name, string $pointer): self
    {
        return (new self())->name($name)->type('PTR')->value($pointer);
    }

    public function name(string $name): self
    {
        $this->name = $name;
        return $this;
    }

    public function type(string $type): self
    {
        $this->type = $type;
        return $this;
    }

    public function dnsClass(string $dnsClass): self
    {
        $this->dnsClass = $dnsClass;
        return $this;
    }

    public function ttl(int $ttl): self
    {
        $this->ttl = $ttl;
        return $this;
    }

    public function value(string $value): self
    {
        $this->value = $value;
        return $this;
    }

    public function priority(int $priority): self
    {
        $this->priority = $priority;
        return $this;
    }

    public function weight(int $weight): self
    {
        $this->weight = $weight;
        return $this;
    }

    public function port(int $port): self
    {
        $this->port = $port;
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
        if ($this->name !== null) {
            $data['name'] = $this->name;
        }
        if ($this->type !== null) {
            $data['type'] = $this->type;
        }
        if ($this->dnsClass !== null) {
            $data['dnsClass'] = $this->dnsClass;
        }
        if ($this->ttl !== null) {
            $data['ttl'] = $this->ttl;
        }
        if ($this->value !== null) {
            $data['value'] = $this->value;
        }
        if ($this->priority !== null) {
            $data['priority'] = $this->priority;
        }
        if ($this->weight !== null) {
            $data['weight'] = $this->weight;
        }
        if ($this->port !== null) {
            $data['port'] = $this->port;
        }
        return $data;
    }
}
