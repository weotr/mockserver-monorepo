<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use MockServer\ConnectionOptions;
use MockServer\Delay;
use MockServer\HttpResponse;
use PHPUnit\Framework\TestCase;

class HttpResponseTest extends TestCase
{
    public function testEmptyResponse(): void
    {
        $response = HttpResponse::response();
        $this->assertSame([], $response->toArray());
    }

    public function testStatusCode(): void
    {
        $response = HttpResponse::response()->statusCode(201);

        $this->assertSame(['statusCode' => 201], $response->toArray());
    }

    public function testReasonPhrase(): void
    {
        $response = HttpResponse::response()
            ->statusCode(404)
            ->reasonPhrase('Not Found');

        $this->assertSame([
            'statusCode' => 404,
            'reasonPhrase' => 'Not Found',
        ], $response->toArray());
    }

    public function testHeaders(): void
    {
        $response = HttpResponse::response()
            ->statusCode(200)
            ->header('Content-Type', 'application/json')
            ->header('X-Custom', 'a', 'b');

        $expected = [
            'statusCode' => 200,
            'headers' => [
                'Content-Type' => ['application/json'],
                'X-Custom' => ['a', 'b'],
            ],
        ];

        $this->assertSame($expected, $response->toArray());
    }

    public function testCookies(): void
    {
        $response = HttpResponse::response()
            ->cookie('session', 'xyz');

        $expected = [
            'cookies' => [
                'session' => ['xyz'],
            ],
        ];

        $this->assertSame($expected, $response->toArray());
    }

    public function testStringBody(): void
    {
        $response = HttpResponse::response()
            ->statusCode(200)
            ->body('hello world');

        $expected = [
            'statusCode' => 200,
            'body' => 'hello world',
        ];

        $this->assertSame($expected, $response->toArray());
    }

    public function testJsonBody(): void
    {
        $response = HttpResponse::response()
            ->statusCode(200)
            ->jsonBody(['message' => 'ok']);

        $array = $response->toArray();

        $this->assertSame(200, $array['statusCode']);
        $this->assertSame('JSON', $array['body']['type']);
        $this->assertSame('{"message":"ok"}', $array['body']['json']);
    }

    public function testDelay(): void
    {
        $response = HttpResponse::response()
            ->statusCode(200)
            ->delay(Delay::milliseconds(500));

        $expected = [
            'statusCode' => 200,
            'delay' => [
                'timeUnit' => 'MILLISECONDS',
                'value' => 500,
            ],
        ];

        $this->assertSame($expected, $response->toArray());
    }

    public function testConnectionOptions(): void
    {
        $opts = (new ConnectionOptions())
            ->closeSocket(true)
            ->suppressContentLengthHeader(true);

        $response = HttpResponse::response()
            ->statusCode(200)
            ->connectionOptions($opts);

        $array = $response->toArray();

        $this->assertSame(200, $array['statusCode']);
        $this->assertTrue($array['connectionOptions']['closeSocket']);
        $this->assertTrue($array['connectionOptions']['suppressContentLengthHeader']);
    }

    public function testJsonSerialize(): void
    {
        $response = HttpResponse::response()
            ->statusCode(204);

        $json = json_encode($response, JSON_THROW_ON_ERROR);
        $decoded = json_decode($json, true);

        $this->assertSame(204, $decoded['statusCode']);
    }

    public function testGetters(): void
    {
        $response = HttpResponse::response()
            ->statusCode(200)
            ->body('test');

        $this->assertSame(200, $response->getStatusCode());
        $this->assertSame('test', $response->getBody());
        $this->assertSame([], $response->getHeaders());
        $this->assertNull($response->getDelay());
    }
}
