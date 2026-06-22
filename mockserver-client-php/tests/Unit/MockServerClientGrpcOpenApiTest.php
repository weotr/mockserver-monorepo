<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use GuzzleHttp\Client as GuzzleClient;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use GuzzleHttp\Middleware;
use GuzzleHttp\Psr7\Response;
use MockServer\Exception\InvalidRequestException;
use MockServer\Exception\MockServerException;
use MockServer\Exception\VerificationException;
use MockServer\HttpResponse;
use MockServer\HttpRequest;
use MockServer\HttpSseResponse;
use MockServer\HttpWebSocketResponse;
use MockServer\GrpcStreamResponse;
use MockServer\GrpcStreamMessage;
use MockServer\BinaryResponse;
use MockServer\DnsResponse;
use MockServer\DnsRecord;
use MockServer\MockServerClient;
use MockServer\OpenAPIExpectation;
use MockServer\SseEvent;
use MockServer\WebSocketMessage;
use PHPUnit\Framework\TestCase;

/**
 * Wire-format tests for the WS1.x parity additions: gRPC descriptor
 * management, OpenAPI import, verifyZeroInteractions, and the advanced
 * response builders (SSE / WebSocket / gRPC-stream / binary / DNS).
 */
class MockServerClientGrpcOpenApiTest extends TestCase
{
    /**
     * @param array<Response> $responses
     * @param array<array> $history
     */
    private function createClientWithMock(array $responses, array &$history = []): MockServerClient
    {
        $mock = new MockHandler($responses);
        $handlerStack = HandlerStack::create($mock);
        $handlerStack->push(Middleware::history($history));

        $client = new MockServerClient('localhost', 1080);

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

    // -----------------------------------------------------------------
    // gRPC descriptor management
    // -----------------------------------------------------------------

    public function testUploadGrpcDescriptorSendsRawBytesAsOctetStream(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(201, [], '{"status":"loaded"}'),
        ], $history);

        $descriptorBytes = "\x00\x01\x02\xFF\xFEbinary-descriptor-set";
        $client->uploadGrpcDescriptor($descriptorBytes);

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/grpc/descriptors', $request->getUri()->getPath());
        // Raw bytes, NOT base64 — body must equal the original byte string verbatim.
        $this->assertSame($descriptorBytes, (string) $request->getBody());
        $this->assertSame('application/octet-stream', $request->getHeaderLine('Content-Type'));
    }

    public function testUploadGrpcDescriptorRejectsEmptyBytes(): void
    {
        $client = $this->createClientWithMock([]);

        $this->expectException(\InvalidArgumentException::class);
        $client->uploadGrpcDescriptor('');
    }

    public function testUploadGrpcDescriptorThrowsOnBadRequest(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], 'failed to load gRPC descriptor: corrupt'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->uploadGrpcDescriptor("\x01\x02");
    }

    public function testRetrieveGrpcServicesParsesResponse(): void
    {
        $history = [];
        $servicesJson = [
            [
                'name' => 'example.Greeter',
                'methods' => [
                    [
                        'name' => 'SayHello',
                        'inputType' => 'example.HelloRequest',
                        'outputType' => 'example.HelloReply',
                        'clientStreaming' => false,
                        'serverStreaming' => false,
                    ],
                ],
            ],
        ];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode($servicesJson)),
        ], $history);

        $services = $client->retrieveGrpcServices();

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/grpc/services', $request->getUri()->getPath());
        $this->assertCount(1, $services);
        $this->assertSame('example.Greeter', $services[0]['name']);
        $this->assertSame('SayHello', $services[0]['methods'][0]['name']);
    }

    public function testRetrieveGrpcServicesEmptyBody(): void
    {
        $client = $this->createClientWithMock([
            new Response(200, [], ''),
        ]);

        $this->assertSame([], $client->retrieveGrpcServices());
    }

    public function testClearGrpcDescriptorsSendsPut(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, []),
        ], $history);

        $client->clearGrpcDescriptors();

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/grpc/clear', $request->getUri()->getPath());
    }

    public function testClearGrpcDescriptorsThrowsOnError(): void
    {
        $client = $this->createClientWithMock([
            new Response(500, [], 'boom'),
        ]);

        $this->expectException(MockServerException::class);
        $client->clearGrpcDescriptors();
    }

    // -----------------------------------------------------------------
    // OpenAPI import
    // -----------------------------------------------------------------

    public function testOpenApiExpectationSendsSpecUrl(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(201, []),
        ], $history);

        $client->openApiExpectation(
            OpenAPIExpectation::openAPI('https://example.com/openapi.json')
        );

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/openapi', $request->getUri()->getPath());
        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('https://example.com/openapi.json', $body['specUrlOrPayload']);
        $this->assertArrayNotHasKey('operationsAndResponses', $body);
    }

    public function testOpenApiExpectationSendsOperationsAndResponses(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(201, []),
        ], $history);

        $client->openApiExpectation(
            OpenAPIExpectation::openAPI('petstore.json', [
                'listPets' => '200',
                'createPets' => '201',
            ])
        );

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame('petstore.json', $body['specUrlOrPayload']);
        $this->assertSame('200', $body['operationsAndResponses']['listPets']);
        $this->assertSame('201', $body['operationsAndResponses']['createPets']);
    }

    public function testOpenApiExpectationThrowsOnInvalid(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], 'Unable to parse OpenAPI specification'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->openApiExpectation(OpenAPIExpectation::openAPI('not-a-spec'));
    }

    // -----------------------------------------------------------------
    // verifyZeroInteractions
    // -----------------------------------------------------------------

    public function testVerifyZeroInteractionsSendsEmptyRequestAtMostZero(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(202, []),
        ], $history);

        $client->verifyZeroInteractions();

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/verify', $request->getUri()->getPath());
        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame([], (array) $body['httpRequest']);
        $this->assertSame(0, $body['times']['atMost']);
        $this->assertArrayNotHasKey('httpResponse', $body);
    }

    public function testVerifyZeroInteractionsThrowsWhenRequestsReceived(): void
    {
        $client = $this->createClientWithMock([
            new Response(406, [], 'Request found at least once'),
        ]);

        $this->expectException(VerificationException::class);
        $client->verifyZeroInteractions();
    }

    // -----------------------------------------------------------------
    // Advanced response builders via fluent when()->respondWith*()
    // -----------------------------------------------------------------

    public function testRespondWithSseSendsHttpSseResponse(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(201, [], '[]'),
        ], $history);

        $client->when(HttpRequest::request()->path('/events'))
            ->respondWithSse(
                HttpSseResponse::response()
                    ->statusCode(200)
                    ->header('Content-Type', 'text/event-stream')
                    ->event(SseEvent::event()->withData('hello'))
                    ->event(SseEvent::event()->withEvent('update')->withId('1')->withData('{"x":1}'))
            );

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $sse = $body[0]['httpSseResponse'];
        $this->assertSame(200, $sse['statusCode']);
        $this->assertSame(['text/event-stream'], $sse['headers']['Content-Type']);
        $this->assertCount(2, $sse['events']);
        $this->assertSame('hello', $sse['events'][0]['data']);
        $this->assertSame('update', $sse['events'][1]['event']);
        $this->assertSame('1', $sse['events'][1]['id']);
    }

    public function testRespondWithWebSocketSendsHttpWebSocketResponse(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(201, [], '[]'),
        ], $history);

        $client->when(HttpRequest::request()->path('/ws'))
            ->respondWithWebSocket(
                HttpWebSocketResponse::response()
                    ->subprotocol('chat')
                    ->message(WebSocketMessage::text('hi'))
                    ->message(WebSocketMessage::binary("\x00\x01"))
            );

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $ws = $body[0]['httpWebSocketResponse'];
        $this->assertSame('chat', $ws['subprotocol']);
        $this->assertSame('hi', $ws['messages'][0]['text']);
        // Binary message is base64-encoded on the wire.
        $this->assertSame(base64_encode("\x00\x01"), $ws['messages'][1]['binary']);
    }

    public function testRespondWithGrpcStreamSendsGrpcStreamResponse(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(201, [], '[]'),
        ], $history);

        $client->when(HttpRequest::request()->path('/grpc'))
            ->respondWithGrpcStream(
                GrpcStreamResponse::response()
                    ->statusName('OK')
                    ->header('grpc-encoding', 'identity')
                    ->message(GrpcStreamMessage::message(['id' => 1]))
                    ->message(GrpcStreamMessage::message(['id' => 2]))
            );

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $grpc = $body[0]['grpcStreamResponse'];
        $this->assertSame('OK', $grpc['statusName']);
        $this->assertSame(['identity'], $grpc['headers']['grpc-encoding']);
        $this->assertCount(2, $grpc['messages']);
        $this->assertSame(1, $grpc['messages'][0]['json']['id']);
        $this->assertSame(2, $grpc['messages'][1]['json']['id']);
    }

    public function testRespondWithBinarySendsBinaryResponse(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(201, [], '[]'),
        ], $history);

        $client->when(HttpRequest::request()->path('/raw'))
            ->respondWithBinary(
                BinaryResponse::response()->fromBytes("\xDE\xAD\xBE\xEF")
            );

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $binary = $body[0]['binaryResponse'];
        $this->assertSame(base64_encode("\xDE\xAD\xBE\xEF"), $binary['binaryData']);
    }

    public function testRespondWithDnsSendsDnsResponse(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(201, [], '[]'),
        ], $history);

        $client->when(HttpRequest::request()->path('/dns'))
            ->respondWithDns(
                DnsResponse::response()
                    ->responseCode('NOERROR')
                    ->answer(DnsRecord::aRecord('example.com', '1.2.3.4'))
                    ->answer(DnsRecord::mxRecord('example.com', 10, 'mail.example.com'))
            );

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $dns = $body[0]['dnsResponse'];
        $this->assertSame('NOERROR', $dns['responseCode']);
        $this->assertCount(2, $dns['answerRecords']);
        $this->assertSame('A', $dns['answerRecords'][0]['type']);
        $this->assertSame('1.2.3.4', $dns['answerRecords'][0]['value']);
        $this->assertSame('MX', $dns['answerRecords'][1]['type']);
        $this->assertSame(10, $dns['answerRecords'][1]['priority']);
    }
}
