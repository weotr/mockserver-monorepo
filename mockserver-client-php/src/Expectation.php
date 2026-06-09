<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Represents a MockServer expectation (request matcher + action).
 */
class Expectation implements \JsonSerializable
{
    private ?string $id = null;
    private ?int $priority = null;
    private ?HttpRequest $httpRequest = null;
    private ?HttpResponse $httpResponse = null;
    private ?HttpForward $httpForward = null;
    private ?HttpError $httpError = null;
    private ?Times $times = null;
    private ?TimeToLive $timeToLive = null;

    public function id(string $id): self
    {
        $this->id = $id;
        return $this;
    }

    public function priority(int $priority): self
    {
        $this->priority = $priority;
        return $this;
    }

    public function httpRequest(HttpRequest $request): self
    {
        $this->httpRequest = $request;
        return $this;
    }

    public function httpResponse(HttpResponse $response): self
    {
        $this->httpResponse = $response;
        return $this;
    }

    public function httpForward(HttpForward $forward): self
    {
        $this->httpForward = $forward;
        return $this;
    }

    public function httpError(HttpError $error): self
    {
        $this->httpError = $error;
        return $this;
    }

    public function times(Times $times): self
    {
        $this->times = $times;
        return $this;
    }

    public function timeToLive(TimeToLive $timeToLive): self
    {
        $this->timeToLive = $timeToLive;
        return $this;
    }

    public function getId(): ?string
    {
        return $this->id;
    }

    public function getPriority(): ?int
    {
        return $this->priority;
    }

    public function getHttpRequest(): ?HttpRequest
    {
        return $this->httpRequest;
    }

    public function getHttpResponse(): ?HttpResponse
    {
        return $this->httpResponse;
    }

    public function getHttpForward(): ?HttpForward
    {
        return $this->httpForward;
    }

    public function getHttpError(): ?HttpError
    {
        return $this->httpError;
    }

    public function getTimes(): ?Times
    {
        return $this->times;
    }

    public function getTimeToLive(): ?TimeToLive
    {
        return $this->timeToLive;
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

        if ($this->id !== null) {
            $data['id'] = $this->id;
        }
        if ($this->priority !== null) {
            $data['priority'] = $this->priority;
        }
        if ($this->httpRequest !== null) {
            $data['httpRequest'] = $this->httpRequest->toArray();
        }
        if ($this->httpResponse !== null) {
            $data['httpResponse'] = $this->httpResponse->toArray();
        }
        if ($this->httpForward !== null) {
            $data['httpForward'] = $this->httpForward->toArray();
        }
        if ($this->httpError !== null) {
            $data['httpError'] = $this->httpError->toArray();
        }
        if ($this->times !== null) {
            $data['times'] = $this->times->toArray();
        }
        if ($this->timeToLive !== null) {
            $data['timeToLive'] = $this->timeToLive->toArray();
        }

        return $data;
    }
}
