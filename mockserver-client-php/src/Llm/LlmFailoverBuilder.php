<?php

declare(strict_types=1);

namespace MockServer\Llm;

use InvalidArgumentException;
use MockServer\Expectation;
use MockServer\MockServerClient;

/**
 * Builder for provider failover/retry scenarios
 * (mirrors org.mockserver.client.LlmFailoverBuilder).
 *
 * Produces an ordered list of expectations: failure expectations (limited
 * {@code times}) first — consumed before — a single success expectation with
 * unlimited {@code times}. Consecutive identical failures are coalesced into
 * one expectation with {@code times.remainingTimes = count}.
 *
 * @example
 *   LlmFailoverBuilder::llmFailover()
 *       ->withPath('/v1/chat/completions')
 *       ->withProvider(Provider::OPENAI)
 *       ->failWith(429)
 *       ->failWith(503, 2)
 *       ->thenRespondWith(Completion::completion()->withText('OK'))
 *       ->applyTo($client);
 */
class LlmFailoverBuilder
{
    private ?string $path = null;
    private ?string $provider = null;
    private ?string $model = null;
    /** @var list<array{statusCode: int, errorBody: string|null}> */
    private array $failures = [];
    private ?Completion $successCompletion = null;

    /**
     * Entry point mirroring {@code LlmFailoverBuilder.llmFailover()}.
     */
    public static function llmFailover(): self
    {
        return new self();
    }

    public function withPath(string $path): self
    {
        $this->path = $path;
        return $this;
    }

    public function withProvider(string $provider): self
    {
        $this->provider = $provider;
        return $this;
    }

    public function withModel(string $model): self
    {
        $this->model = $model;
        return $this;
    }

    /**
     * Add one (or {@code count}) failure attempt(s) with the given status.
     *
     * Mirrors the three Java overloads:
     *   failWith(status)
     *   failWith(status, errorBody)   — custom JSON error body
     *   failWith(status, count)       — repeat the default body N times
     *
     * @param string|int|null $errorBodyOrCount A non-empty string is a custom
     *                                           error body; an int is a repeat count.
     */
    public function failWith(int $statusCode, string|int|null $errorBodyOrCount = null): self
    {
        if ($statusCode < 100 || $statusCode > 599) {
            throw new InvalidArgumentException("statusCode must be between 100 and 599, got {$statusCode}");
        }

        if (is_int($errorBodyOrCount)) {
            if ($errorBodyOrCount < 1) {
                throw new InvalidArgumentException("count must be >= 1, got {$errorBodyOrCount}");
            }
            for ($i = 0; $i < $errorBodyOrCount; $i++) {
                $this->failures[] = ['statusCode' => $statusCode, 'errorBody' => null];
            }
        } else {
            $this->failures[] = ['statusCode' => $statusCode, 'errorBody' => $errorBodyOrCount];
        }

        return $this;
    }

    public function thenRespondWith(Completion $completion): self
    {
        $this->successCompletion = $completion;
        return $this;
    }

    public function getFailureCount(): int
    {
        return count($this->failures);
    }

    /**
     * Default JSON error body for a status code, mirroring the Java helper.
     */
    public static function defaultErrorBody(int $statusCode): string
    {
        switch ($statusCode) {
            case 429:
                $type = 'rate_limit_error';
                $message = 'Rate limit exceeded. Please retry after a brief wait.';
                break;
            case 500:
                $type = 'internal_server_error';
                $message = 'An internal error occurred. Please retry your request.';
                break;
            case 502:
                $type = 'bad_gateway';
                $message = 'Bad gateway. The upstream server returned an invalid response.';
                break;
            case 503:
                $type = 'service_unavailable';
                $message = 'The service is temporarily overloaded. Please retry later.';
                break;
            default:
                $type = 'error';
                $message = 'Request failed with status ' . $statusCode;
                break;
        }

        return json_encode(
            ['error' => ['type' => $type, 'message' => $message]],
            JSON_THROW_ON_ERROR
        );
    }

    /**
     * Coalesce consecutive identical failures.
     *
     * @return list<array{statusCode: int, errorBody: string|null, count: int}>
     */
    private function coalesce(): array
    {
        $result = [];
        foreach ($this->failures as $spec) {
            $last = $result[count($result) - 1] ?? null;
            if ($last !== null
                && $last['statusCode'] === $spec['statusCode']
                && $last['errorBody'] === $spec['errorBody']) {
                $result[count($result) - 1]['count']++;
                continue;
            }
            $result[] = ['statusCode' => $spec['statusCode'], 'errorBody' => $spec['errorBody'], 'count' => 1];
        }
        return $result;
    }

    /**
     * @return list<Expectation>
     */
    public function build(): array
    {
        if ($this->path === null) {
            throw new InvalidArgumentException('Path must be set');
        }
        if ($this->provider === null) {
            throw new InvalidArgumentException('Provider must be set');
        }
        if (count($this->failures) === 0) {
            throw new InvalidArgumentException('At least one failure must be defined');
        }
        if ($this->successCompletion === null) {
            throw new InvalidArgumentException('Success completion must be set via thenRespondWith()');
        }

        $expectations = [];
        foreach ($this->coalesce() as $cf) {
            $body = $cf['errorBody'] ?? self::defaultErrorBody($cf['statusCode']);
            $expectations[] = Expectation::fromArray([
                'httpRequest' => ['method' => 'POST', 'path' => $this->path],
                'times' => ['remainingTimes' => $cf['count'], 'unlimited' => false],
                'timeToLive' => ['unlimited' => true],
                'httpResponse' => [
                    'statusCode' => $cf['statusCode'],
                    'headers' => [['name' => 'Content-Type', 'values' => ['application/json']]],
                    'body' => $body,
                ],
            ]);
        }

        $successAction = HttpLlmResponse::llmResponse()
            ->withProvider($this->provider)
            ->withCompletion($this->successCompletion);
        if ($this->model !== null) {
            $successAction->withModel($this->model);
        }

        $expectations[] = Expectation::fromArray([
            'httpRequest' => ['method' => 'POST', 'path' => $this->path],
            'times' => ['remainingTimes' => 0, 'unlimited' => true],
            'timeToLive' => ['unlimited' => true],
            'httpLlmResponse' => $successAction->toArray(),
        ]);

        return $expectations;
    }

    /**
     * @return array<mixed>
     */
    public function applyTo(MockServerClient $client): array
    {
        $results = [];
        foreach ($this->build() as $expectation) {
            $results[] = $client->upsertExpectation($expectation);
        }
        return $results;
    }
}
