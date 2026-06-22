<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use MockServer\Delay;
use MockServer\Expectation;
use MockServer\HttpClassCallback;
use MockServer\HttpRequest;
use PHPUnit\Framework\TestCase;

class HttpClassCallbackTest extends TestCase
{
    public function testCallbackClassOnly(): void
    {
        $callback = HttpClassCallback::callback('com.example.MyCallback');

        $this->assertSame(
            ['callbackClass' => 'com.example.MyCallback'],
            $callback->toArray(),
        );
    }

    public function testCallbackWithDelayAndPrimary(): void
    {
        $callback = HttpClassCallback::callback('com.example.MyCallback')
            ->delay(Delay::milliseconds(250))
            ->primary(true);

        $expected = [
            'callbackClass' => 'com.example.MyCallback',
            'delay' => ['timeUnit' => 'MILLISECONDS', 'value' => 250],
            'primary' => true,
        ];

        $this->assertSame($expected, $callback->toArray());
    }

    public function testGetters(): void
    {
        $delay = Delay::seconds(2);
        $callback = (new HttpClassCallback('com.example.Other'))
            ->callbackClass('com.example.Final')
            ->delay($delay)
            ->primary(false);

        $this->assertSame('com.example.Final', $callback->getCallbackClass());
        $this->assertSame($delay, $callback->getDelay());
        $this->assertFalse($callback->getPrimary());
    }

    public function testJsonSerialize(): void
    {
        $callback = HttpClassCallback::callback('com.example.MyCallback');

        $decoded = json_decode(json_encode($callback, JSON_THROW_ON_ERROR), true);

        $this->assertSame('com.example.MyCallback', $decoded['callbackClass']);
    }

    public function testExpectationResponseClassCallbackFromString(): void
    {
        $expectation = (new Expectation())
            ->httpRequest(HttpRequest::request()->method('GET')->path('/cb'))
            ->httpResponseClassCallback('com.example.ResponseCallback');

        $expected = [
            'httpRequest' => [
                'method' => 'GET',
                'path' => '/cb',
            ],
            'httpResponseClassCallback' => [
                'callbackClass' => 'com.example.ResponseCallback',
            ],
        ];

        $this->assertSame($expected, $expectation->toArray());
        $this->assertSame(
            'com.example.ResponseCallback',
            $expectation->toArray()['httpResponseClassCallback']['callbackClass'],
        );
    }

    public function testExpectationResponseClassCallbackFromObject(): void
    {
        $expectation = (new Expectation())
            ->httpRequest(HttpRequest::request()->path('/cb'))
            ->httpResponseClassCallback(
                HttpClassCallback::callback('com.example.ResponseCallback')
                    ->delay(Delay::milliseconds(100)),
            );

        $data = $expectation->toArray();

        $this->assertSame(
            'com.example.ResponseCallback',
            $data['httpResponseClassCallback']['callbackClass'],
        );
        $this->assertSame(
            ['timeUnit' => 'MILLISECONDS', 'value' => 100],
            $data['httpResponseClassCallback']['delay'],
        );
    }

    public function testExpectationForwardClassCallbackFromString(): void
    {
        $expectation = (new Expectation())
            ->httpRequest(HttpRequest::request()->path('/proxy'))
            ->httpForwardClassCallback('com.example.ForwardCallback');

        $data = $expectation->toArray();

        $this->assertArrayHasKey('httpForwardClassCallback', $data);
        $this->assertSame(
            'com.example.ForwardCallback',
            $data['httpForwardClassCallback']['callbackClass'],
        );
    }

    public function testExpectationGetters(): void
    {
        $expectation = (new Expectation())
            ->httpResponseClassCallback('com.example.ResponseCallback')
            ->httpForwardClassCallback('com.example.ForwardCallback');

        $this->assertSame(
            'com.example.ResponseCallback',
            $expectation->getHttpResponseClassCallback()?->getCallbackClass(),
        );
        $this->assertSame(
            'com.example.ForwardCallback',
            $expectation->getHttpForwardClassCallback()?->getCallbackClass(),
        );
    }
}
