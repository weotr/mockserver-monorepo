<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Connection options for response actions.
 */
class ConnectionOptions implements \JsonSerializable
{
    private ?bool $suppressContentLengthHeader = null;
    private ?int $contentLengthHeaderOverride = null;
    private ?bool $suppressConnectionHeader = null;
    private ?int $chunkSize = null;
    private ?bool $keepAliveOverride = null;
    private ?bool $closeSocket = null;
    private ?Delay $closeSocketDelay = null;

    public function suppressContentLengthHeader(bool $suppress): self
    {
        $this->suppressContentLengthHeader = $suppress;
        return $this;
    }

    public function contentLengthHeaderOverride(int $override): self
    {
        $this->contentLengthHeaderOverride = $override;
        return $this;
    }

    public function suppressConnectionHeader(bool $suppress): self
    {
        $this->suppressConnectionHeader = $suppress;
        return $this;
    }

    public function chunkSize(int $chunkSize): self
    {
        $this->chunkSize = $chunkSize;
        return $this;
    }

    public function keepAliveOverride(bool $override): self
    {
        $this->keepAliveOverride = $override;
        return $this;
    }

    public function closeSocket(bool $close): self
    {
        $this->closeSocket = $close;
        return $this;
    }

    public function closeSocketDelay(Delay $delay): self
    {
        $this->closeSocketDelay = $delay;
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

        if ($this->suppressContentLengthHeader !== null) {
            $data['suppressContentLengthHeader'] = $this->suppressContentLengthHeader;
        }
        if ($this->contentLengthHeaderOverride !== null) {
            $data['contentLengthHeaderOverride'] = $this->contentLengthHeaderOverride;
        }
        if ($this->suppressConnectionHeader !== null) {
            $data['suppressConnectionHeader'] = $this->suppressConnectionHeader;
        }
        if ($this->chunkSize !== null) {
            $data['chunkSize'] = $this->chunkSize;
        }
        if ($this->keepAliveOverride !== null) {
            $data['keepAliveOverride'] = $this->keepAliveOverride;
        }
        if ($this->closeSocket !== null) {
            $data['closeSocket'] = $this->closeSocket;
        }
        if ($this->closeSocketDelay !== null) {
            $data['closeSocketDelay'] = $this->closeSocketDelay->toArray();
        }

        return $data;
    }
}
