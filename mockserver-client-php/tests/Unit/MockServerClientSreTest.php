<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use GuzzleHttp\Client as GuzzleClient;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use GuzzleHttp\Middleware;
use GuzzleHttp\Psr7\Response;
use MockServer\Delay;
use MockServer\Exception\FeatureNotEnabledException;
use MockServer\Exception\InvalidRequestException;
use MockServer\Exception\VerificationException;
use MockServer\HttpRequest;
use MockServer\LoadProfile;
use MockServer\LoadScenario;
use MockServer\LoadStage;
use MockServer\MockServerClient;
use PHPUnit\Framework\TestCase;

/**
 * Tests for the SRE control-plane methods: load scenarios, service chaos,
 * SLO verdicts, preemption and chaos experiments.
 */
class MockServerClientSreTest extends TestCase
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

    // -----------------------------------------------------------------
    // Load scenario
    // -----------------------------------------------------------------

    public function testLoadScenarioSendsCorrectJsonFromValueObject(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['name' => 'checkout-load', 'state' => 'LOADED'])),
        ], $history);

        $scenario = LoadScenario::scenario('checkout-load')
            ->templateType('velocity')
            ->maxRequests(5000)
            ->labels(['team' => 'checkout'])
            ->profile(LoadProfile::of(
                LoadStage::vuRamp(1, 10, 10000),
                LoadStage::vuHold(10, 30000),
                LoadStage::pause(5000),
            ))
            ->addStep(
                HttpRequest::request()->method('GET')->path('/api/item/$iteration.index'),
                Delay::milliseconds(20),
                'fetch-item',
                ['route' => 'item'],
            );

        $result = $client->loadScenario($scenario);

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('checkout-load', $body['name']);
        $this->assertSame('VELOCITY', $body['templateType']);
        $this->assertSame(5000, $body['maxRequests']);
        $this->assertSame(['team' => 'checkout'], $body['labels']);

        // profile — staged (Load Profile v2)
        $stages = $body['profile']['stages'];
        $this->assertCount(3, $stages);
        $this->assertSame('VU', $stages[0]['type']);
        $this->assertSame(1, $stages[0]['startVus']);
        $this->assertSame(10, $stages[0]['endVus']);
        $this->assertSame(10000, $stages[0]['durationMillis']);
        $this->assertSame('LINEAR', $stages[0]['curve']);
        $this->assertSame('VU', $stages[1]['type']);
        $this->assertSame(10, $stages[1]['vus']);
        $this->assertSame(30000, $stages[1]['durationMillis']);
        $this->assertArrayNotHasKey('curve', $stages[1]);
        $this->assertArrayNotHasKey('startVus', $stages[1]);
        $this->assertSame('PAUSE', $stages[2]['type']);
        $this->assertSame(5000, $stages[2]['durationMillis']);
        $this->assertArrayNotHasKey('vus', $stages[2]);

        // steps
        $this->assertCount(1, $body['steps']);
        $step = $body['steps'][0];
        $this->assertSame('GET', $step['request']['method']);
        $this->assertSame('/api/item/$iteration.index', $step['request']['path']);
        $this->assertSame('MILLISECONDS', $step['thinkTime']['timeUnit']);
        $this->assertSame(20, $step['thinkTime']['value']);
        $this->assertSame('fetch-item', $step['name']);
        $this->assertSame(['route' => 'item'], $step['labels']);

        $this->assertSame('checkout-load', $result['name']);
        $this->assertSame('LOADED', $result['state']);
    }

    public function testLinearLoadProfileShape(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->loadScenario(
            LoadScenario::scenario('ramp')
                ->profile(LoadProfile::linear(1, 20, 60000))
                ->addStep(HttpRequest::request()->path('/x'))
        );

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $stage = $body['profile']['stages'][0];
        $this->assertSame('VU', $stage['type']);
        $this->assertSame('LINEAR', $stage['curve']);
        $this->assertSame(1, $stage['startVus']);
        $this->assertSame(20, $stage['endVus']);
        $this->assertSame(60000, $stage['durationMillis']);
        $this->assertArrayNotHasKey('vus', $stage);
        $this->assertArrayNotHasKey('rate', $stage);
    }

    public function testRateLoadStageShape(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->loadScenario(
            LoadScenario::scenario('rate')
                ->profile(LoadProfile::of(
                    LoadStage::rateHold(100.0, 30000)->maxVus(20),
                    LoadStage::rateRamp(10.0, 200.0, 60000, 'EXPONENTIAL'),
                ))
                ->addStep(HttpRequest::request()->path('/x'))
        );

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $hold = $body['profile']['stages'][0];
        $this->assertSame('RATE', $hold['type']);
        $this->assertEquals(100.0, $hold['rate']);
        $this->assertSame(30000, $hold['durationMillis']);
        $this->assertSame(20, $hold['maxVus']);
        $this->assertArrayNotHasKey('curve', $hold);
        $this->assertArrayNotHasKey('vus', $hold);

        $ramp = $body['profile']['stages'][1];
        $this->assertSame('RATE', $ramp['type']);
        $this->assertSame('EXPONENTIAL', $ramp['curve']);
        $this->assertEquals(10.0, $ramp['startRate']);
        $this->assertEquals(200.0, $ramp['endRate']);
        $this->assertArrayNotHasKey('rate', $ramp);
        $this->assertArrayNotHasKey('maxVus', $ramp);
    }

    public function testLoadScenarioAcceptsPlainArray(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->loadScenario([
            'name' => 'raw',
            'profile' => ['type' => 'CONSTANT', 'vus' => 2, 'durationMillis' => 1000],
            'steps' => [['request' => ['method' => 'GET', 'path' => '/raw']]],
        ]);

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame('raw', $body['name']);
        $this->assertSame('/raw', $body['steps'][0]['request']['path']);
    }

    public function testLoadScenarioRegistersAndReturnsRef(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['name' => 'x', 'state' => 'LOADED'])),
        ], $history);

        $result = $client->loadScenario(LoadScenario::scenario('x')
            ->profile(LoadProfile::constant(1, 1000))
            ->addStep(HttpRequest::request()->path('/x')));

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario', $request->getUri()->getPath());
        $this->assertSame('x', $result['name']);
        $this->assertSame('LOADED', $result['state']);
    }

    public function testLoadScenarioSerialisesStartDelayMillis(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->loadScenario(
            LoadScenario::scenario('delayed')
                ->startDelayMillis(250)
                ->profile(LoadProfile::constant(1, 1000))
                ->addStep(HttpRequest::request()->path('/x'))
        );

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        // PHP json_encode renders whole-number floats as ints; assertEquals (not assertSame).
        $this->assertEquals(250, $body['startDelayMillis']);
    }

    public function testLoadScenarioRegistrationAllowedWhenGenerationDisabled(): void
    {
        // Registration is NOT gated — a 403 is surfaced generically, not as
        // FeatureNotEnabledException, because the registration endpoint never
        // gates on loadGenerationEnabled.
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['name' => 'x', 'state' => 'LOADED'])),
        ], $history);

        $result = $client->loadScenario(LoadScenario::scenario('x')
            ->profile(LoadProfile::constant(1, 1000))
            ->addStep(HttpRequest::request()->path('/x')));

        $this->assertSame('LOADED', $result['state']);
    }

    public function testLoadScenariosListsAllViaGet(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode([
                'scenarios' => [
                    ['name' => 'a', 'state' => 'RUNNING'],
                    ['name' => 'b', 'state' => 'LOADED'],
                ],
            ])),
        ], $history);

        $result = $client->loadScenarios();

        $request = $history[0]['request'];
        $this->assertSame('GET', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario', $request->getUri()->getPath());
        $this->assertCount(2, $result['scenarios']);
        $this->assertSame('a', $result['scenarios'][0]['name']);
        $this->assertSame('RUNNING', $result['scenarios'][0]['state']);
    }

    public function testGetLoadScenarioUsesNamedGet(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['name' => 'check out', 'state' => 'COMPLETED'])),
        ], $history);

        $result = $client->getLoadScenario('check out');

        $request = $history[0]['request'];
        $this->assertSame('GET', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario/check%20out', $request->getUri()->getPath());
        $this->assertSame('COMPLETED', $result['state']);
    }

    public function testGetLoadScenarioThrowsOn404(): void
    {
        $client = $this->createClientWithMock([
            new Response(404, [], 'no such scenario'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->getLoadScenario('missing');
    }

    public function testDeleteLoadScenarioUsesNamedDelete(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->deleteLoadScenario('checkout-load');

        $request = $history[0]['request'];
        $this->assertSame('DELETE', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario/checkout-load', $request->getUri()->getPath());
    }

    public function testClearLoadScenariosUsesUnscopedDelete(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->clearLoadScenarios();

        $request = $history[0]['request'];
        $this->assertSame('DELETE', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario', $request->getUri()->getPath());
    }

    public function testStartLoadScenariosWithSingleNameSendsNamesArray(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode([
                'started' => [['name' => 'a', 'state' => 'RUNNING']],
                'status' => 'started',
            ])),
        ], $history);

        $result = $client->startLoadScenarios('a');

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario/start', $request->getUri()->getPath());
        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame(['names' => ['a']], $body);
        $this->assertSame('started', $result['status']);
        $this->assertSame('a', $result['started'][0]['name']);
    }

    public function testStartLoadScenariosWithArraySendsNamesArray(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->startLoadScenarios(['a', 'b']);

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame(['names' => ['a', 'b']], $body);
    }

    public function testStartLoadScenariosThrowsFeatureNotEnabledOn403(): void
    {
        $client = $this->createClientWithMock([
            new Response(403, [], 'load generation disabled'),
        ]);

        $this->expectException(FeatureNotEnabledException::class);
        $this->expectExceptionMessage('loadGenerationEnabled=true');

        $client->startLoadScenarios('a');
    }

    public function testStartLoadScenariosThrowsOn404UnknownName(): void
    {
        $client = $this->createClientWithMock([
            new Response(404, [], 'unknown scenario'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->startLoadScenarios('ghost');
    }

    public function testStopLoadScenariosWithNoArgsStopsAllWithEmptyBody(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['stopped' => [], 'status' => 'stopped'])),
        ], $history);

        $result = $client->stopLoadScenarios();

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/loadScenario/stop', $request->getUri()->getPath());
        $this->assertSame('', (string) $request->getBody());
        $this->assertSame('stopped', $result['status']);
    }

    public function testStopLoadScenariosWithNamesSendsNamesArray(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->stopLoadScenarios(['a', 'b']);

        $request = $history[0]['request'];
        $this->assertSame('/mockserver/loadScenario/stop', $request->getUri()->getPath());
        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame(['names' => ['a', 'b']], $body);
    }

    public function testStopLoadScenariosWithSingleNameSendsNamesArray(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->stopLoadScenarios('a');

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame(['names' => ['a']], $body);
    }

    public function testRunLoadScenarioRegistersThenStarts(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['name' => 'run-me', 'state' => 'LOADED'])),
            new Response(200, [], json_encode([
                'started' => [['name' => 'run-me', 'state' => 'RUNNING']],
                'status' => 'started',
            ])),
        ], $history);

        $result = $client->runLoadScenario(
            LoadScenario::scenario('run-me')
                ->profile(LoadProfile::constant(1, 1000))
                ->addStep(HttpRequest::request()->path('/x'))
        );

        $this->assertCount(2, $history);
        $register = $history[0]['request'];
        $this->assertSame('PUT', $register->getMethod());
        $this->assertSame('/mockserver/loadScenario', $register->getUri()->getPath());

        $start = $history[1]['request'];
        $this->assertSame('PUT', $start->getMethod());
        $this->assertSame('/mockserver/loadScenario/start', $start->getUri()->getPath());
        $startBody = json_decode((string) $start->getBody(), true);
        $this->assertSame(['names' => ['run-me']], $startBody);

        $this->assertSame('started', $result['status']);
    }

    public function testRunLoadScenarioAcceptsPlainArrayAndStartsByName(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
            new Response(200, [], '{}'),
        ], $history);

        $client->runLoadScenario([
            'name' => 'raw-run',
            'profile' => ['stages' => [['type' => 'VU', 'vus' => 1, 'durationMillis' => 1000]]],
            'steps' => [['request' => ['method' => 'GET', 'path' => '/raw']]],
        ]);

        $startBody = json_decode((string) $history[1]['request']->getBody(), true);
        $this->assertSame(['names' => ['raw-run']], $startBody);
    }

    // -----------------------------------------------------------------
    // Service chaos
    // -----------------------------------------------------------------

    public function testSetServiceChaosSendsCorrectPayload(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->setServiceChaos(
            'payments.internal:8443',
            [
                'errorStatus' => 503,
                'errorProbability' => 0.3,
                'latency' => ['timeUnit' => 'MILLISECONDS', 'value' => 200],
            ],
            60000,
        );

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/serviceChaos', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('payments.internal:8443', $body['host']);
        $this->assertSame(503, $body['chaos']['errorStatus']);
        $this->assertSame(0.3, $body['chaos']['errorProbability']);
        $this->assertSame('MILLISECONDS', $body['chaos']['latency']['timeUnit']);
        $this->assertSame(200, $body['chaos']['latency']['value']);
        $this->assertSame(60000, $body['ttlMillis']);
    }

    public function testSetServiceChaosOmitsTtlWhenNull(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], '{}'),
        ], $history);

        $client->setServiceChaos('api.local', ['errorStatus' => 500]);

        $body = json_decode((string) $history[0]['request']->getBody(), true);
        $this->assertSame('api.local', $body['host']);
        $this->assertArrayNotHasKey('ttlMillis', $body);
    }

    public function testSetServiceChaosThrowsInvalidRequestOn400(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], 'invalid chaos profile'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->setServiceChaos('api.local', ['errorStatus' => 9999]);
    }

    // -----------------------------------------------------------------
    // SLO verdicts
    // -----------------------------------------------------------------

    public function testVerifySloSendsCorrectPayloadAndReturnsVerdict(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['name' => 'checkout-slo', 'result' => 'PASS'])),
        ], $history);

        $verdict = $client->verifySlo([
            'name' => 'checkout-slo',
            'window' => ['type' => 'LOOKBACK', 'lookbackMillis' => 60000],
            'minimumSampleCount' => 20,
            'upstreamHosts' => ['payments.svc'],
            'objectives' => [
                ['sli' => 'LATENCY_P95', 'comparator' => 'LESS_THAN', 'threshold' => 250.0, 'scope' => 'FORWARD'],
                ['sli' => 'ERROR_RATE', 'comparator' => 'LESS_THAN_OR_EQUAL', 'threshold' => 0.01],
            ],
        ]);

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/verifySLO', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('checkout-slo', $body['name']);
        $this->assertSame('LATENCY_P95', $body['objectives'][0]['sli']);
        // PHP's json_encode renders 250.0 as "250", which json_decodes back to int 250; the server
        // accepts either, so assert numeric equality rather than the exact int/float type.
        $this->assertEquals(250.0, $body['objectives'][0]['threshold']);

        $this->assertSame('PASS', $verdict['result']);
    }

    public function testVerifySloThrowsVerificationExceptionOn406Fail(): void
    {
        $client = $this->createClientWithMock([
            new Response(406, [], json_encode(['result' => 'FAIL'])),
        ]);

        $this->expectException(VerificationException::class);
        $client->verifySlo(['objectives' => []]);
    }

    public function testVerifySloThrowsInvalidRequestOn400Disabled(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], 'SLO tracking disabled'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $this->expectExceptionMessage('sloTrackingEnabled=true');

        $client->verifySlo(['objectives' => []]);
    }

    // -----------------------------------------------------------------
    // Preemption
    // -----------------------------------------------------------------

    public function testSetPreemptionSendsCorrectPayload(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['state' => 'draining', 'inFlight' => 2])),
        ], $history);

        $result = $client->setPreemption([
            'mode' => 'both',
            'drainMillis' => 10000,
            'ttlMillis' => 60000,
            'lastStreamId' => 3,
        ]);

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/preemption', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('both', $body['mode']);
        $this->assertSame(10000, $body['drainMillis']);
        $this->assertSame(60000, $body['ttlMillis']);
        $this->assertSame(3, $body['lastStreamId']);

        $this->assertSame('draining', $result['state']);
    }

    public function testSetPreemptionEmptyBodySendsJsonObject(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['state' => 'draining'])),
        ], $history);

        $client->setPreemption();

        // An empty body must serialise as a JSON object "{}", not an array "[]".
        $this->assertSame('{}', (string) $history[0]['request']->getBody());
    }

    public function testPreemptionStatusUsesGet(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['state' => 'inactive', 'inFlight' => 0])),
        ], $history);

        $result = $client->preemptionStatus();

        $request = $history[0]['request'];
        $this->assertSame('GET', $request->getMethod());
        $this->assertSame('/mockserver/preemption', $request->getUri()->getPath());
        $this->assertSame('inactive', $result['state']);
    }

    public function testClearPreemptionUsesDelete(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['state' => 'inactive'])),
        ], $history);

        $result = $client->clearPreemption();

        $request = $history[0]['request'];
        $this->assertSame('DELETE', $request->getMethod());
        $this->assertSame('/mockserver/preemption', $request->getUri()->getPath());
        $this->assertSame('inactive', $result['state']);
    }

    // -----------------------------------------------------------------
    // Chaos experiment
    // -----------------------------------------------------------------

    public function testStartChaosExperimentSendsCorrectPayload(): void
    {
        $history = [];
        $client = $this->createClientWithMock([
            new Response(200, [], json_encode(['status' => 'started', 'name' => 'gradual-degradation'])),
        ], $history);

        $result = $client->startChaosExperiment([
            'name' => 'gradual-degradation',
            'stages' => [
                [
                    'durationMillis' => 10000,
                    'profiles' => ['api.example.com' => ['errorStatus' => 500, 'errorProbability' => 0.1]],
                ],
                [
                    'durationMillis' => 10000,
                    'profiles' => ['api.example.com' => ['errorStatus' => 500, 'errorProbability' => 0.5]],
                ],
            ],
        ]);

        $request = $history[0]['request'];
        $this->assertSame('PUT', $request->getMethod());
        $this->assertSame('/mockserver/chaosExperiment', $request->getUri()->getPath());

        $body = json_decode((string) $request->getBody(), true);
        $this->assertSame('gradual-degradation', $body['name']);
        $this->assertCount(2, $body['stages']);
        $this->assertSame(10000, $body['stages'][0]['durationMillis']);
        $this->assertSame(0.1, $body['stages'][0]['profiles']['api.example.com']['errorProbability']);

        $this->assertSame('started', $result['status']);
    }

    public function testStartChaosExperimentThrowsInvalidRequestOn400(): void
    {
        $client = $this->createClientWithMock([
            new Response(400, [], 'empty stages'),
        ]);

        $this->expectException(InvalidRequestException::class);
        $client->startChaosExperiment(['name' => 'bad', 'stages' => []]);
    }
}
