<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use GuzzleHttp\Client as GuzzleClient;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use GuzzleHttp\Middleware;
use GuzzleHttp\Psr7\Response;
use MockServer\Exception\ConnectionException;
use MockServer\Exception\InvalidRequestException;
use MockServer\Exception\VerificationException;
use MockServer\HttpForward;
use MockServer\HttpRequest;
use MockServer\HttpResponse;
use MockServer\MockServerClient;
use MockServer\Times;
use MockServer\VerificationTimes;
use PHPUnit\Framework\TestCase;

class MockServerClientTest extends TestCase
{
    /**
     * Create a client with a mocked HTTP handler.
     *
     * @param array<Response> $responses Queued responses
     * @param array<array> &$history Reference to capture request history
     * @return MockServerClient
     */
    private function createClientWithMock(array $responses, array &$history = []): MockServerClient
    {
        $mock = new MockHandler($responses);
        $handlerStack = HandlerStack::create($mock);
        $handlerStack->push(Middleware::history($history));

        // We need to inject the Guzzle client via the guzzleOptions
        // Since the constructor creates its own client, we use a reflective approach
        $client = new MockServerClient('localhost', 1080);

        // Replace the internal httpClient via reflection
        $reflection = new \ReflectionClass($client);
        $prop = $reflection->getProperty('httpClient');
        $prop->setAccessible(true);
        $prop->setValue($client, new GuzzleClient([
            'handler' => $handlerStack,
            'http_errors' => false,
            'headers' => ['Content-Type' => 'application/json; charset=utf-8'],
        ]));

        return $client;
    }

    public function testWhenRespondSendsCorrectJson(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(201, [], json_encode([
                ['httpRequest' => ['method' => 'GET', 'path' => '/hello'], 'httpResponse' => ['statusCode' => 200]],
            ])),
        ], $history);

        $client->when(
            HttpRequest::request()->method('GET')->path('/hello')
        )->respond(
            HttpResponse::response()->statusCode(200)->body('world')
        );

        $this->assertCount(1, $history);
        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/expectation', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertIsArray($body);
        $this->assertCount(1, $body);
        $this->assertSame('GET', $body[0]['httpRequest']['method']);
        $this->assertSame('/hello', $body[0]['httpRequest']['path']);
        $this->assertSame(200, $body[0]['httpResponse']['statusCode']);
        $this->assertSame('world', $body[0]['httpResponse']['body']);
    }

    public function testWhenForwardSendsCorrectJson(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(201, [], '[]'),
        ], $history);

        $client->when(
            HttpRequest::request()->path('/proxy')
        )->forward(
            HttpForward::forward()->host('backend.local')->port(8080)->scheme('HTTP')
        );

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame('backend.local', $body[0]['httpForward']['host']);
        $this->assertSame(8080, $body[0]['httpForward']['port']);
        $this->assertSame('HTTP', $body[0]['httpForward']['scheme']);
    }

    public function testWhenWithTimesAndPriority(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(201, [], '[]'),
        ], $history);

        $client->when(
            HttpRequest::request()->path('/limited'),
            Times::exactly(5),
            priority: 10
        )->respond(
            HttpResponse::response()->statusCode(200)
        );

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame(5, $body[0]['times']['remainingTimes']);
        $this->assertFalse($body[0]['times']['unlimited']);
        $this->assertSame(10, $body[0]['priority']);
    }

    public function testVerifySendsCorrectPayload(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(202, []),
        ], $history);

        $client->verify(
            HttpRequest::request()->method('GET')->path('/hello'),
            VerificationTimes::atLeast(1)
        );

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/verify', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('GET', $body['httpRequest']['method']);
        $this->assertSame('/hello', $body['httpRequest']['path']);
        $this->assertSame(1, $body['times']['atLeast']);
    }

    public function testVerifyThrowsOnFailure(): void
    {
        $client = $this->createClientWithMock([
            new Response(406, [], 'Request not found at least 1 times'),
        ]);

        $this->expectException(VerificationException::class);
        $this->expectExceptionMessage('Request not found at least 1 times');

        $client->verify(
            HttpRequest::request()->path('/missing'),
            VerificationTimes::atLeast(1)
        );
    }

    public function testVerifySequenceSendsCorrectPayload(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(202, []),
        ], $history);

        $client->verifySequence(
            HttpRequest::request()->path('/first'),
            HttpRequest::request()->path('/second')
        );

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame('/mockserver/verifySequence', $history[0]['request']->getUri()->getPath());
        $this->assertCount(2, $body['httpRequests']);
        $this->assertSame('/first', $body['httpRequests'][0]['path']);
        $this->assertSame('/second', $body['httpRequests'][1]['path']);
    }

    public function testVerifySequenceThrowsOnFailure(): void
    {
        $client = $this->createClientWithMock([
            new Response(406, [], 'Sequence not found'),
        ]);

        $this->expectException(VerificationException::class);

        $client->verifySequence(
            HttpRequest::request()->path('/a'),
            HttpRequest::request()->path('/b')
        );
    }

    public function testClearSendsRequest(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, []),
        ], $history);

        $client->clear(HttpRequest::request()->path('/old'));

        $request = $history[0]['request'];
        $this->assertSame('/mockserver/clear', $request->getUri()->getPath());
        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('/old', $body['path']);
    }

    public function testClearWithType(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, []),
        ], $history);

        $client->clear(null, 'EXPECTATIONS');

        $request = $history[0]['request'];
        $this->assertStringContainsString('type=EXPECTATIONS', (string) $request->getUri());
    }

    public function testClearById(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, []),
        ], $history);

        $client->clearById('my-exp-id');

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame('my-exp-id', $body['id']);
    }

    public function testResetSendsEmptyPut(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, []),
        ], $history);

        $client->reset();

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/reset', $request->getUri()->getPath());
    }

    public function testRetrieveRecordedRequests(): void
    {
        $responseData = [
            ['method' => 'GET', 'path' => '/foo'],
            ['method' => 'POST', 'path' => '/bar'],
        ];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode($responseData)),
        ]);

        $result = $client->retrieveRecordedRequests();

        $this->assertCount(2, $result);
        $this->assertSame('GET', $result[0]['method']);
        $this->assertSame('/bar', $result[1]['path']);
    }

    public function testRetrieveActiveExpectations(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '[]'),
        ], $history);

        $result = $client->retrieveActiveExpectations();

        $this->assertSame([], $result);
        $this->assertStringContainsString('type=ACTIVE_EXPECTATIONS', (string) $history[0]['request']->getUri());
    }

    public function testRetrieveRecordedExpectations(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '[]'),
        ], $history);

        $result = $client->retrieveRecordedExpectations();

        $this->assertSame([], $result);
        $this->assertStringContainsString('type=RECORDED_EXPECTATIONS', (string) $history[0]['request']->getUri());
    }

    public function testRetrieveLogMessages(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['log entry 1', 'log entry 2'])),
        ], $history);

        $result = $client->retrieveLogMessages();

        $this->assertCount(2, $result);
        $this->assertStringContainsString('type=LOGS', (string) $history[0]['request']->getUri());
    }

    public function testRetrieveWithFilter(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '[]'),
        ], $history);

        $client->retrieveRecordedRequests(HttpRequest::request()->path('/filtered'));

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame('/filtered', $body['path']);
    }

    public function testStatus(): void
    {
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['ports' => [1080]])),
        ]);

        $result = $client->status();

        $this->assertSame([1080], $result['ports']);
    }

    public function testBind(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['ports' => [1080, 1081]])),
        ], $history);

        $result = $client->bind(1081);

        $this->assertSame([1080, 1081], $result['ports']);
        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame([1081], $body['ports']);
    }

    public function testHasStartedReturnsTrue(): void
    {
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['ports' => [1080]])),
        ]);

        $this->assertTrue($client->hasStarted(1, 0.0));
    }

    public function testHasStartedReturnsFalseAfterRetries(): void
    {
        $client = $this->createClientWithMock([
            new Response(503, []),
            new Response(503, []),
        ]);

        $this->assertFalse($client->hasStarted(2, 0.0));
    }

    public function testGetBaseUri(): void
    {
        $client = new MockServerClient('myhost', 9090, '/ctx', true);
        $this->assertSame('https://myhost:9090/ctx', $client->getBaseUri());
    }

    public function testGetBaseUriDefaultHttp(): void
    {
        $client = new MockServerClient('localhost', 1080);
        $this->assertSame('http://localhost:1080', $client->getBaseUri());
    }

    public function testContextPathSlashHandling(): void
    {
        $client = new MockServerClient('localhost', 1080, 'myapp');
        $this->assertSame('http://localhost:1080/myapp', $client->getBaseUri());

        $client2 = new MockServerClient('localhost', 1080, '/myapp');
        $this->assertSame('http://localhost:1080/myapp', $client2->getBaseUri());
    }

    public function testInvalidExpectationThrows(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], 'Invalid JSON'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $this->expectExceptionMessage('Invalid JSON');

        $client->when(
            HttpRequest::request()->path('/bad')
        )->respond(
            HttpResponse::response()
        );
    }
}
