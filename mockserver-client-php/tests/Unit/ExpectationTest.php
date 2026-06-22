<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use MockServer\CrossProtocolScenario;
use MockServer\CrossProtocolTrigger;
use MockServer\Delay;
use MockServer\Expectation;
use MockServer\HttpForward;
use MockServer\HttpRequest;
use MockServer\HttpResponse;
use MockServer\ResponseMode;
use MockServer\TimeToLive;
use MockServer\Times;
use PHPUnit\Framework\TestCase;

class ExpectationTest extends TestCase
{
    public function testMinimalExpectation(): void
    {
        $expectation = (new Expectation())
            ->httpRequest(
                HttpRequest::request()->method('GET')->path('/hello')
            )
            ->httpResponse(
                HttpResponse::response()->statusCode(200)->body('world')
            );

        $expected = [
            'httpRequest' => [
                'method' => 'GET',
                'path' => '/hello',
            ],
            'httpResponse' => [
                'statusCode' => 200,
                'body' => 'world',
            ],
        ];

        $this->assertSame($expected, $expectation->toArray());
    }

    public function testFullExpectation(): void
    {
        $expectation = (new Expectation())
            ->id('my-expectation')
            ->priority(10)
            ->httpRequest(
                HttpRequest::request()
                    ->method('POST')
                    ->path('/api/users')
                    ->header('Content-Type', 'application/json')
                    ->queryStringParameter('version', '2')
                    ->body('{"name":"test"}')
            )
            ->httpResponse(
                HttpResponse::response()
                    ->statusCode(201)
                    ->header('Location', '/api/users/1')
                    ->body('{"id":1}')
                    ->delay(Delay::milliseconds(100))
            )
            ->times(Times::exactly(3))
            ->timeToLive(TimeToLive::exactly('SECONDS', 60));

        $array = $expectation->toArray();

        $this->assertSame('my-expectation', $array['id']);
        $this->assertSame(10, $array['priority']);
        $this->assertSame('POST', $array['httpRequest']['method']);
        $this->assertSame('/api/users', $array['httpRequest']['path']);
        $this->assertSame(201, $array['httpResponse']['statusCode']);
        $this->assertSame(100, $array['httpResponse']['delay']['value']);
        $this->assertSame(3, $array['times']['remainingTimes']);
        $this->assertFalse($array['times']['unlimited']);
        $this->assertSame('SECONDS', $array['timeToLive']['timeUnit']);
        $this->assertSame(60, $array['timeToLive']['timeToLive']);
        $this->assertFalse($array['timeToLive']['unlimited']);
    }

    public function testForwardExpectation(): void
    {
        $expectation = (new Expectation())
            ->httpRequest(
                HttpRequest::request()->path('/proxy')
            )
            ->httpForward(
                HttpForward::forward()
                    ->host('backend.local')
                    ->port(8080)
                    ->scheme('HTTP')
            );

        $array = $expectation->toArray();

        $this->assertSame('/proxy', $array['httpRequest']['path']);
        $this->assertSame('backend.local', $array['httpForward']['host']);
        $this->assertSame(8080, $array['httpForward']['port']);
        $this->assertSame('HTTP', $array['httpForward']['scheme']);
        $this->assertArrayNotHasKey('httpResponse', $array);
    }

    public function testTimesUnlimited(): void
    {
        $expectation = (new Expectation())
            ->httpRequest(HttpRequest::request()->path('/any'))
            ->httpResponse(HttpResponse::response()->statusCode(200))
            ->times(Times::unlimited());

        $array = $expectation->toArray();

        $this->assertTrue($array['times']['unlimited']);
        $this->assertArrayNotHasKey('remainingTimes', $array['times']);
    }

    public function testTimesOnce(): void
    {
        $times = Times::once();
        $array = $times->toArray();

        $this->assertSame(1, $array['remainingTimes']);
        $this->assertFalse($array['unlimited']);
    }

    public function testTimeToLiveUnlimited(): void
    {
        $ttl = TimeToLive::unlimited();
        $array = $ttl->toArray();

        $this->assertTrue($array['unlimited']);
        $this->assertArrayNotHasKey('timeUnit', $array);
        $this->assertArrayNotHasKey('timeToLive', $array);
    }

    public function testJsonSerialize(): void
    {
        $expectation = (new Expectation())
            ->httpRequest(HttpRequest::request()->method('GET')->path('/test'))
            ->httpResponse(HttpResponse::response()->statusCode(200));

        $json = json_encode($expectation, JSON_THROW_ON_ERROR);
        $decoded = json_decode($json, true);

        $this->assertSame('GET', $decoded['httpRequest']['method']);
        $this->assertSame(200, $decoded['httpResponse']['statusCode']);
    }

    public function testFileBodyExpectation(): void
    {
        $expectation = (new Expectation())
            ->httpRequest(
                HttpRequest::request()->method('GET')->path('/template')
            )
            ->httpResponse(
                HttpResponse::response()
                    ->statusCode(200)
                    ->fileBody('/templates/page.html', 'text/html', 'VELOCITY')
            );

        $array = $expectation->toArray();

        $this->assertSame('/template', $array['httpRequest']['path']);
        $this->assertSame(200, $array['httpResponse']['statusCode']);
        $this->assertSame('FILE', $array['httpResponse']['body']['type']);
        $this->assertSame('/templates/page.html', $array['httpResponse']['body']['filePath']);
        $this->assertSame('text/html', $array['httpResponse']['body']['contentType']);
        $this->assertSame('VELOCITY', $array['httpResponse']['body']['templateType']);
    }

    public function testFromArrayRoundTripsBuilderOutput(): void
    {
        $built = (new Expectation())
            ->id('rt')
            ->priority(7)
            ->httpRequest(
                HttpRequest::request()
                    ->method('POST')
                    ->path('/api/"weird"\\path' . "\n" . 'x')
                    ->body('{"name":"a\\b","q":"\"quoted\""}')
            )
            ->httpResponse(
                HttpResponse::response()
                    ->statusCode(201)
                    ->body("body with \"quotes\", \\backslash and \n newline")
                    ->delay(Delay::milliseconds(50))
            )
            ->times(Times::exactly(3));

        $array = $built->toArray();
        // simulate the generated code: json_encode then json_decode then fromArray
        $decoded = json_decode(json_encode($array, JSON_THROW_ON_ERROR), true);
        $reconstructed = Expectation::fromArray($decoded);

        $this->assertSame($array, $reconstructed->toArray());
        $this->assertSame(
            json_encode($array, JSON_THROW_ON_ERROR),
            json_encode($reconstructed, JSON_THROW_ON_ERROR)
        );
    }

    public function testFromArrayPreservesFieldsNotModelledByBuilder(): void
    {
        // httpForwardTemplate has no typed builder, but fromArray must keep it
        $raw = [
            'httpRequest' => ['path' => '/x'],
            'httpForwardTemplate' => [
                'templateType' => 'JAVASCRIPT',
                'template' => 'return { path: request.path };',
            ],
            'times' => ['remainingTimes' => 2, 'unlimited' => false],
        ];

        $reconstructed = Expectation::fromArray($raw);

        $this->assertSame($raw, $reconstructed->toArray());
        $this->assertSame($raw, $reconstructed->jsonSerialize());
    }

    public function testScenarioFieldsOmittedWhenUnset(): void
    {
        $array = (new Expectation())
            ->httpRequest(HttpRequest::request()->path('/x'))
            ->httpResponse(HttpResponse::response()->statusCode(200))
            ->toArray();

        $this->assertArrayNotHasKey('scenarioName', $array);
        $this->assertArrayNotHasKey('scenarioState', $array);
        $this->assertArrayNotHasKey('newScenarioState', $array);
        $this->assertArrayNotHasKey('httpResponses', $array);
        $this->assertArrayNotHasKey('responseMode', $array);
        $this->assertArrayNotHasKey('responseWeights', $array);
        $this->assertArrayNotHasKey('switchAfter', $array);
        $this->assertArrayNotHasKey('crossProtocolScenarios', $array);
    }

    public function testScenarioStateFieldsSerialized(): void
    {
        $array = (new Expectation())
            ->httpRequest(HttpRequest::request()->path('/deploy'))
            ->httpResponse(HttpResponse::response()->statusCode(200))
            ->scenarioName('Deploy')
            ->scenarioState('Deploying')
            ->newScenarioState('Deployed')
            ->toArray();

        $this->assertSame('Deploy', $array['scenarioName']);
        $this->assertSame('Deploying', $array['scenarioState']);
        $this->assertSame('Deployed', $array['newScenarioState']);
    }

    public function testMultipleResponsesWeightedSerialization(): void
    {
        $array = (new Expectation())
            ->httpRequest(HttpRequest::request()->path('/multi'))
            ->httpResponses(
                HttpResponse::response()->statusCode(200)->body('a'),
                HttpResponse::response()->statusCode(500)->body('b'),
            )
            ->responseMode(ResponseMode::WEIGHTED)
            ->responseWeights(3, 1)
            ->toArray();

        $this->assertSame('WEIGHTED', $array['responseMode']);
        $this->assertSame([3, 1], $array['responseWeights']);
        $this->assertCount(2, $array['httpResponses']);
        $this->assertSame(200, $array['httpResponses'][0]['statusCode']);
        $this->assertSame('a', $array['httpResponses'][0]['body']);
        $this->assertSame(500, $array['httpResponses'][1]['statusCode']);
        $this->assertSame('b', $array['httpResponses'][1]['body']);
        // httpResponses must be a JSON array (sequential keys), not an object
        $this->assertSame([0, 1], array_keys($array['httpResponses']));
    }

    public function testSwitchModeSerialization(): void
    {
        $array = (new Expectation())
            ->httpRequest(HttpRequest::request()->path('/switch'))
            ->addHttpResponse(HttpResponse::response()->statusCode(200))
            ->addHttpResponse(HttpResponse::response()->statusCode(503))
            ->responseMode(ResponseMode::SWITCH)
            ->switchAfter(2)
            ->toArray();

        $this->assertSame('SWITCH', $array['responseMode']);
        $this->assertSame(2, $array['switchAfter']);
        $this->assertCount(2, $array['httpResponses']);
    }

    public function testCrossProtocolScenariosSerialization(): void
    {
        $array = (new Expectation())
            ->httpRequest(HttpRequest::request()->path('/x'))
            ->httpResponse(HttpResponse::response()->statusCode(200))
            ->crossProtocolScenarios(
                CrossProtocolScenario::trigger(CrossProtocolTrigger::DNS_QUERY)
                    ->matchPattern('api.example.com')
                    ->scenarioName('Deploy')
                    ->targetState('Deploying'),
                CrossProtocolScenario::trigger(CrossProtocolTrigger::HTTP_REQUEST)
                    ->scenarioName('Deploy')
                    ->targetState('Deployed'),
            )
            ->toArray();

        $this->assertCount(2, $array['crossProtocolScenarios']);

        $first = $array['crossProtocolScenarios'][0];
        $this->assertSame('DNS_QUERY', $first['trigger']);
        $this->assertSame('api.example.com', $first['matchPattern']);
        $this->assertSame('Deploy', $first['scenarioName']);
        $this->assertSame('Deploying', $first['targetState']);

        // matchPattern omitted when unset
        $second = $array['crossProtocolScenarios'][1];
        $this->assertSame('HTTP_REQUEST', $second['trigger']);
        $this->assertArrayNotHasKey('matchPattern', $second);
        $this->assertSame('Deployed', $second['targetState']);
    }

    public function testResponseModeConstants(): void
    {
        $this->assertSame('SEQUENTIAL', ResponseMode::SEQUENTIAL);
        $this->assertSame('RANDOM', ResponseMode::RANDOM);
        $this->assertSame('WEIGHTED', ResponseMode::WEIGHTED);
        $this->assertSame('SWITCH', ResponseMode::SWITCH);
    }

    public function testCrossProtocolTriggerConstants(): void
    {
        $this->assertSame('DNS_QUERY', CrossProtocolTrigger::DNS_QUERY);
        $this->assertSame('WEBSOCKET_CONNECT', CrossProtocolTrigger::WEBSOCKET_CONNECT);
        $this->assertSame('GRPC_REQUEST', CrossProtocolTrigger::GRPC_REQUEST);
        $this->assertSame('HTTP_REQUEST', CrossProtocolTrigger::HTTP_REQUEST);
    }

    public function testScenarioFieldsRoundTripThroughFromArray(): void
    {
        $built = (new Expectation())
            ->httpRequest(HttpRequest::request()->path('/x'))
            ->httpResponses(
                HttpResponse::response()->statusCode(200),
                HttpResponse::response()->statusCode(500),
            )
            ->responseMode(ResponseMode::WEIGHTED)
            ->responseWeights(2, 1)
            ->switchAfter(3)
            ->scenarioName('Deploy')
            ->scenarioState('Deploying')
            ->newScenarioState('Deployed')
            ->crossProtocolScenarios(
                CrossProtocolScenario::trigger(CrossProtocolTrigger::DNS_QUERY)
                    ->scenarioName('Deploy')
                    ->targetState('Deploying'),
            );

        $array = $built->toArray();
        $decoded = json_decode(json_encode($array, JSON_THROW_ON_ERROR), true);
        $reconstructed = Expectation::fromArray($decoded);

        $this->assertSame($array, $reconstructed->toArray());
    }

    public function testGetters(): void
    {
        $request = HttpRequest::request()->path('/x');
        $response = HttpResponse::response()->statusCode(200);

        $expectation = (new Expectation())
            ->id('test-id')
            ->priority(5)
            ->httpRequest($request)
            ->httpResponse($response);

        $this->assertSame('test-id', $expectation->getId());
        $this->assertSame(5, $expectation->getPriority());
        $this->assertSame($request, $expectation->getHttpRequest());
        $this->assertSame($response, $expectation->getHttpResponse());
        $this->assertNull($expectation->getHttpForward());
        $this->assertNull($expectation->getHttpError());
        $this->assertNull($expectation->getTimes());
        $this->assertNull($expectation->getTimeToLive());
    }
}
