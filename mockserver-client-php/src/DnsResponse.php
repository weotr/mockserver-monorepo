<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Fluent builder for a DNS response action.
 *
 * Produces the {@code dnsResponse} action JSON. Wire keys:
 * responseCode, answerRecords, authorityRecords, additionalRecords, delay, primary.
 *
 * @example
 *   DnsResponse::response()
 *       ->responseCode('NOERROR')
 *       ->answer(DnsRecord::aRecord('example.com', '1.2.3.4'));
 */
class DnsResponse implements \JsonSerializable
{
    private ?string $responseCode = null;
    /** @var list<DnsRecord> */
    private array $answerRecords = [];
    /** @var list<DnsRecord> */
    private array $authorityRecords = [];
    /** @var list<DnsRecord> */
    private array $additionalRecords = [];
    private ?Delay $delay = null;
    private ?bool $primary = null;

    public static function response(): self
    {
        return new self();
    }

    public function responseCode(string $responseCode): self
    {
        $this->responseCode = $responseCode;
        return $this;
    }

    /**
     * Append an answer-section record.
     */
    public function answer(DnsRecord $record): self
    {
        $this->answerRecords[] = $record;
        return $this;
    }

    /**
     * Append an authority-section record.
     */
    public function authority(DnsRecord $record): self
    {
        $this->authorityRecords[] = $record;
        return $this;
    }

    /**
     * Append an additional-section record.
     */
    public function additional(DnsRecord $record): self
    {
        $this->additionalRecords[] = $record;
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
        if ($this->responseCode !== null) {
            $data['responseCode'] = $this->responseCode;
        }
        if (!empty($this->answerRecords)) {
            $data['answerRecords'] = array_map(fn(DnsRecord $r) => $r->toArray(), $this->answerRecords);
        }
        if (!empty($this->authorityRecords)) {
            $data['authorityRecords'] = array_map(fn(DnsRecord $r) => $r->toArray(), $this->authorityRecords);
        }
        if (!empty($this->additionalRecords)) {
            $data['additionalRecords'] = array_map(fn(DnsRecord $r) => $r->toArray(), $this->additionalRecords);
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
