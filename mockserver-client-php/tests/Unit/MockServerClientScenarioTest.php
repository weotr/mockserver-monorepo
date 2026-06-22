<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use GuzzleHttp\Client as GuzzleClient;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use GuzzleHttp\Middleware;
use GuzzleHttp\Psr7\Response;
use MockServer\Exception\InvalidRequestException;
use MockServer\MockServerClient;
use MockServer\ScenarioHandle;
use PHPUnit\Framework\TestCase;

/**
 * Tests for the stateful-scenario control-plane helper: scenario(), scenarios(),
 * and the ScenarioHandle state()/set()/trigger() operations.
 */
class MockServerClientScenarioTest extends TestCase
{
    /**
     * @param array<Response> $responses
     * @param array<array> &$history
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

    public function testScenarioReturnsHandle(): void
    {
        $client = $this->createClientWithMock([]);
        $this->assertInstanceOf(ScenarioHandle::class, $client->scenario('Deploy'));
    }

    public function testStateIssuesGet(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['scenarioName' => 'Deploy', 'currentState' => 'Deploying'])),
        ], $history);

        $result = $client->scenario('Deploy')->state();

        $request = $history[0]['request'];
        $this->assertSame('GET', $request->getMethod());
        $this->assertSame('/mockserver/scenario/Deploy', $request->getUri()->getPath());
        $this->assertSame('', (string) $request->getBody());

        $this->assertSame('Deploy', $result['scenarioName']);
        $this->assertSame('Deploying', $result['currentState']);
    }

    public function testScenarioNameIsUrlEncoded(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['scenarioName' => 'My Deploy/v2', 'currentState' => 'A'])),
        ], $history);

        $client->scenario('My Deploy/v2')->state();

        $request = $history[0]['request'];
        // rawurlencode: space -> %20, slash -> %2F
        $this->assertSame('/mockserver/scenario/My%20Deploy%2Fv2', $request->getUri()->getPath());
    }

    public function testSetStateOnlyIssuesPutWithStateBody(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['scenarioName' => 'Deploy', 'currentState' => 'Deploying'])),
        ], $history);

        $result = $client->scenario('Deploy')->set('Deploying');

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/scenario/Deploy', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame(['state' => 'Deploying'], $body);
        $this->assertArrayNotHasKey('transitionAfterMs', $body);
        $this->assertArrayNotHasKey('nextState', $body);

        $this->assertSame('Deploying', $result['currentState']);
    }

    public function testSetTimedIssuesPutWithTransitionFields(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode([
                'scenarioName' => 'Deploy',
                'currentState' => 'Deploying',
                'nextState' => 'Deployed',
                'transitionAfterMs' => 5000,
            ])),
        ], $history);

        $result = $client->scenario('Deploy')->set('Deploying', 5000, 'Deployed');

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/scenario/Deploy', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('Deploying', $body['state']);
        $this->assertSame(5000, $body['transitionAfterMs']);
        $this->assertSame('Deployed', $body['nextState']);

        $this->assertSame('Deployed', $result['nextState']);
    }

    public function testTriggerIssuesPutToTriggerPath(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['scenarioName' => 'Deploy', 'currentState' => 'Failed'])),
        ], $history);

        $result = $client->scenario('Deploy')->trigger('Failed');

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/scenario/Deploy/trigger', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame(['newState' => 'Failed'], $body);

        $this->assertSame('Failed', $result['currentState']);
    }

    public function testScenariosIssuesGetToListPath(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode([
                'scenarios' => [
                    ['scenarioName' => 'Deploy', 'currentState' => 'Deploying'],
                    ['scenarioName' => 'Rollout', 'currentState' => 'Idle'],
                ],
            ])),
        ], $history);

        $result = $client->scenarios();

        $request = $history[0]['request'];
        $this->assertSame('GET', $request->getMethod());
        $this->assertSame('/mockserver/scenario', $request->getUri()->getPath());

        $this->assertCount(2, $result['scenarios']);
        $this->assertSame('Deploy', $result['scenarios'][0]['scenarioName']);
    }

    public function testInvalidRequestRaisesException(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], 'unknown scenario'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->scenario('Missing')->trigger('Nope');
    }
}
