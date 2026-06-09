<?php

declare(strict_types=1);

namespace MockServer;

use GuzzleHttp\Client as GuzzleClient;
use GuzzleHttp\Exception\ConnectException;
use GuzzleHttp\Exception\GuzzleException;
use GuzzleHttp\RequestOptions;
use MockServer\Exception\ConnectionException;
use MockServer\Exception\InvalidRequestException;
use MockServer\Exception\MockServerException;
use MockServer\Exception\VerificationException;

/**
 * PHP client for MockServer.
 *
 * Provides the full MockServer control-plane REST API with a fluent builder DSL.
 *
 * @example Basic usage
 *   $client = new MockServerClient('localhost', 1080);
 *   $client->when(
 *       HttpRequest::request()->method('GET')->path('/hello')
 *   )->respond(
 *       HttpResponse::response()->statusCode(200)->body('world')
 *   );
 *   $client->verify(
 *       HttpRequest::request()->path('/hello'),
 *       VerificationTimes::atLeast(1)
 *   );
 *   $client->reset();
 */
class MockServerClient
{
    private GuzzleClient $httpClient;
    private string $baseUri;

    /**
     * @param string $host MockServer hostname
     * @param int $port MockServer port (default 1080)
     * @param string $contextPath Optional context path prefix
     * @param bool $secure Use HTTPS
     * @param array<string, mixed> $guzzleOptions Additional Guzzle client options
     */
    public function __construct(
        string $host = 'localhost',
        int $port = 1080,
        string $contextPath = '',
        bool $secure = false,
        array $guzzleOptions = [],
    ) {
        $scheme = $secure ? 'https' : 'http';
        $ctxPath = '';
        if ($contextPath !== '') {
            $ctxPath = str_starts_with($contextPath, '/') ? $contextPath : '/' . $contextPath;
        }
        $this->baseUri = "{$scheme}://{$host}:{$port}{$ctxPath}";

        $defaultOptions = [
            'base_uri' => $this->baseUri,
            'http_errors' => false,
            RequestOptions::TIMEOUT => 60,
            RequestOptions::CONNECT_TIMEOUT => 10,
            RequestOptions::HEADERS => [
                'Content-Type' => 'application/json; charset=utf-8',
            ],
        ];

        $this->httpClient = new GuzzleClient(array_merge($defaultOptions, $guzzleOptions));
    }

    // -----------------------------------------------------------------
    // Fluent API
    // -----------------------------------------------------------------

    /**
     * Begin building an expectation via the fluent when/respond API.
     *
     * @param HttpRequest $request The request matcher
     * @param Times|null $times How many times to match (null = unlimited)
     * @param TimeToLive|null $timeToLive How long the expectation lives (null = forever)
     * @param int|null $priority Expectation priority
     * @return ForwardChainExpectation
     */
    public function when(
        HttpRequest $request,
        ?Times $times = null,
        ?TimeToLive $timeToLive = null,
        ?int $priority = null,
    ): ForwardChainExpectation {
        $expectation = new Expectation();
        $expectation->httpRequest($request);

        if ($times !== null) {
            $expectation->times($times);
        }
        if ($timeToLive !== null) {
            $expectation->timeToLive($timeToLive);
        }
        if ($priority !== null) {
            $expectation->priority($priority);
        }

        return new ForwardChainExpectation($this, $expectation);
    }

    // -----------------------------------------------------------------
    // Core API methods
    // -----------------------------------------------------------------

    /**
     * Create or update an expectation.
     *
     * @internal Called by ForwardChainExpectation; prefer when()->respond() for external use.
     * @param Expectation $expectation
     * @return array The server response (created expectations).
     * @throws InvalidRequestException
     * @throws MockServerException
     */
    public function upsertExpectation(Expectation $expectation): array
    {
        $body = json_encode([$expectation->toArray()], JSON_THROW_ON_ERROR);
        $response = $this->put('/mockserver/expectation', $body);

        $status = $response->getStatusCode();
        $responseBody = (string) $response->getBody();

        if ($status === 400 || $status === 406) {
            throw new InvalidRequestException("Invalid expectation: {$responseBody}");
        }
        if ($status >= 400) {
            throw new MockServerException("Failed to create expectation (HTTP {$status}): {$responseBody}");
        }

        if ($responseBody !== '') {
            $parsed = json_decode($responseBody, true);
            if (is_array($parsed)) {
                return $parsed;
            }
        }

        return [$expectation->toArray()];
    }

    /**
     * Verify that a request was received the expected number of times.
     *
     * @param HttpRequest $request The request to verify
     * @param VerificationTimes|null $times Expected call count (null = at least once)
     * @throws VerificationException If verification fails
     * @throws MockServerException On communication errors
     */
    public function verify(HttpRequest $request, ?VerificationTimes $times = null): void
    {
        $payload = ['httpRequest' => $request->toArray()];
        if ($times !== null) {
            $payload['times'] = $times->toArray();
        }

        $body = json_encode($payload, JSON_THROW_ON_ERROR);
        $response = $this->put('/mockserver/verify', $body);

        $status = $response->getStatusCode();
        $responseBody = (string) $response->getBody();

        if ($status === 406) {
            throw new VerificationException($responseBody ?: 'Verification failed');
        }
        if ($status >= 400) {
            throw new MockServerException("Verification request failed (HTTP {$status}): {$responseBody}");
        }
    }

    /**
     * Verify that requests were received in the specified sequence.
     *
     * @param HttpRequest ...$requests The requests in expected order
     * @throws VerificationException If sequence verification fails
     * @throws MockServerException On communication errors
     */
    public function verifySequence(HttpRequest ...$requests): void
    {
        $payload = [
            'httpRequests' => array_map(fn(HttpRequest $r) => $r->toArray(), $requests),
        ];

        $body = json_encode($payload, JSON_THROW_ON_ERROR);
        $response = $this->put('/mockserver/verifySequence', $body);

        $status = $response->getStatusCode();
        $responseBody = (string) $response->getBody();

        if ($status === 406) {
            throw new VerificationException($responseBody ?: 'Sequence verification failed');
        }
        if ($status >= 400) {
            throw new MockServerException("Verify sequence request failed (HTTP {$status}): {$responseBody}");
        }
    }

    /**
     * Clear expectations and/or logs matching the request.
     *
     * @param HttpRequest|null $request Matcher to select what to clear (null = clear all)
     * @param string|null $type "EXPECTATIONS", "LOG", or "ALL" (null = ALL)
     */
    public function clear(?HttpRequest $request = null, ?string $type = null): void
    {
        $path = '/mockserver/clear';
        if ($type !== null) {
            $path .= '?type=' . urlencode($type);
        }

        $body = $request !== null ? json_encode($request->toArray(), JSON_THROW_ON_ERROR) : '';
        $response = $this->put($path, $body);

        $status = $response->getStatusCode();
        if ($status >= 400) {
            $responseBody = (string) $response->getBody();
            throw new MockServerException("Clear failed (HTTP {$status}): {$responseBody}");
        }
    }

    /**
     * Clear by expectation ID.
     *
     * @param string $expectationId The ID of the expectation to clear
     * @param string|null $type "EXPECTATIONS", "LOG", or "ALL"
     */
    public function clearById(string $expectationId, ?string $type = null): void
    {
        $path = '/mockserver/clear';
        if ($type !== null) {
            $path .= '?type=' . urlencode($type);
        }

        $body = json_encode(['id' => $expectationId], JSON_THROW_ON_ERROR);
        $response = $this->put($path, $body);

        $status = $response->getStatusCode();
        if ($status >= 400) {
            $responseBody = (string) $response->getBody();
            throw new MockServerException("Clear by ID failed (HTTP {$status}): {$responseBody}");
        }
    }

    /**
     * Reset MockServer: remove all expectations and clear all recorded requests.
     */
    public function reset(): void
    {
        $response = $this->put('/mockserver/reset', '');

        $status = $response->getStatusCode();
        if ($status >= 400) {
            $responseBody = (string) $response->getBody();
            throw new MockServerException("Reset failed (HTTP {$status}): {$responseBody}");
        }
    }

    /**
     * Retrieve recorded requests matching the given filter.
     *
     * @param HttpRequest|null $request Filter (null = all)
     * @return array List of recorded request arrays
     */
    public function retrieveRecordedRequests(?HttpRequest $request = null): array
    {
        return $this->retrieve($request, 'REQUESTS', 'JSON');
    }

    /**
     * Retrieve active expectations matching the given filter.
     *
     * @param HttpRequest|null $request Filter (null = all)
     * @return array List of expectation arrays
     */
    public function retrieveActiveExpectations(?HttpRequest $request = null): array
    {
        return $this->retrieve($request, 'ACTIVE_EXPECTATIONS', 'JSON');
    }

    /**
     * Retrieve recorded expectations matching the given filter.
     *
     * @param HttpRequest|null $request Filter (null = all)
     * @return array List of expectation arrays
     */
    public function retrieveRecordedExpectations(?HttpRequest $request = null): array
    {
        return $this->retrieve($request, 'RECORDED_EXPECTATIONS', 'JSON');
    }

    /**
     * Retrieve log messages matching the given filter.
     *
     * @param HttpRequest|null $request Filter (null = all)
     * @return array List of log entry strings/arrays
     */
    public function retrieveLogMessages(?HttpRequest $request = null): array
    {
        return $this->retrieve($request, 'LOGS', 'JSON');
    }

    /**
     * Check the server status (bound ports).
     *
     * @return array{ports: list<int>} Status response
     */
    public function status(): array
    {
        $response = $this->put('/mockserver/status', '');

        $status = $response->getStatusCode();
        $responseBody = (string) $response->getBody();

        if ($status >= 400) {
            throw new MockServerException("Status request failed (HTTP {$status}): {$responseBody}");
        }

        if ($responseBody !== '') {
            $parsed = json_decode($responseBody, true);
            if (is_array($parsed)) {
                return $parsed;
            }
        }

        return ['ports' => []];
    }

    /**
     * Bind additional ports.
     *
     * @param int ...$ports Ports to bind
     * @return array{ports: list<int>} Bound ports response
     */
    public function bind(int ...$ports): array
    {
        $body = json_encode(['ports' => array_values($ports)], JSON_THROW_ON_ERROR);
        $response = $this->put('/mockserver/bind', $body);

        $status = $response->getStatusCode();
        $responseBody = (string) $response->getBody();

        if ($status >= 400) {
            throw new MockServerException("Bind failed (HTTP {$status}): {$responseBody}");
        }

        if ($responseBody !== '') {
            $parsed = json_decode($responseBody, true);
            if (is_array($parsed)) {
                return $parsed;
            }
        }

        return ['ports' => []];
    }

    /**
     * Check if MockServer has started (polls with retries).
     *
     * @param int $attempts Number of attempts
     * @param float $timeout Seconds to wait between attempts
     * @return bool True if server is reachable
     */
    public function hasStarted(int $attempts = 10, float $timeout = 0.5): bool
    {
        for ($i = 0; $i < $attempts; $i++) {
            try {
                $response = $this->put('/mockserver/status', '');
                if ($response->getStatusCode() === 200) {
                    return true;
                }
            } catch (ConnectionException) {
                // Not yet started, retry
            }

            if ($i < $attempts - 1) {
                usleep((int) ($timeout * 1_000_000));
            }
        }

        return false;
    }

    /**
     * Get the base URI of this client.
     */
    public function getBaseUri(): string
    {
        return $this->baseUri;
    }

    // -----------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------

    /**
     * Generic retrieve method.
     *
     * @param HttpRequest|null $request Filter
     * @param string $type REQUESTS, ACTIVE_EXPECTATIONS, RECORDED_EXPECTATIONS, LOGS
     * @param string $format JSON or LOG_ENTRIES
     * @return array
     */
    private function retrieve(?HttpRequest $request, string $type, string $format): array
    {
        $path = '/mockserver/retrieve?type=' . urlencode($type) . '&format=' . urlencode($format);
        $body = $request !== null ? json_encode($request->toArray(), JSON_THROW_ON_ERROR) : '';

        $response = $this->put($path, $body);

        $status = $response->getStatusCode();
        $responseBody = (string) $response->getBody();

        if ($status >= 400) {
            throw new MockServerException("Retrieve ({$type}) failed (HTTP {$status}): {$responseBody}");
        }

        if ($responseBody !== '') {
            $parsed = json_decode($responseBody, true);
            if (is_array($parsed)) {
                return $parsed;
            }
        }

        return [];
    }

    /**
     * Send a PUT request to MockServer.
     *
     * @param string $path Request path (including query string if any)
     * @param string $body JSON body
     * @return \Psr\Http\Message\ResponseInterface
     * @throws ConnectionException
     */
    private function put(string $path, string $body): \Psr\Http\Message\ResponseInterface
    {
        try {
            return $this->httpClient->request('PUT', $path, [
                RequestOptions::BODY => $body,
            ]);
        } catch (ConnectException $e) {
            throw new ConnectionException(
                "Failed to connect to MockServer at {$this->baseUri}: {$e->getMessage()}",
                (int) $e->getCode(),
                $e
            );
        } catch (GuzzleException $e) {
            throw new ConnectionException(
                "Request to MockServer at {$this->baseUri} failed: {$e->getMessage()}",
                (int) $e->getCode(),
                $e
            );
        }
    }
}
