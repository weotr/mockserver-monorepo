<?php

declare(strict_types=1);

// Demonstrates MockServer's stateful-scenario features from the PHP client by
// running the 5 canonical scenarios in sequence and asserting each:
//
//   1. state_machine      — scenarioState / newScenarioState login flow
//   2. sequential_cycling — one expectation, multiple cycling httpResponses
//   3. timed_transition   — scenario REST helper, timed auto-transition
//   4. external_trigger   — scenario REST helper, external trigger()
//   5. cross_protocol     — crossProtocolScenarios advanced by an HTTP event
//
// Discovery (per the harness): MOCKSERVER_HOST (default localhost) /
// MOCKSERVER_PORT (default 1080).
//
// Prints "PASS: <scenario>" for each and exits 0 only if all pass.
//
// Prerequisites: MockServer running, e.g.
//   docker run -d -p 1080:1080 mockserver/mockserver

require_once __DIR__ . '/../../../mockserver-client-php/vendor/autoload.php';

use MockServer\CrossProtocolScenario;
use MockServer\CrossProtocolTrigger;
use MockServer\Expectation;
use MockServer\HttpRequest;
use MockServer\HttpResponse;
use MockServer\MockServerClient;
use MockServer\ResponseMode;
use MockServer\Times;

$host = getenv('MOCKSERVER_HOST') ?: 'localhost';
$port = (int) (getenv('MOCKSERVER_PORT') ?: '1080');
$baseUrl = "http://{$host}:{$port}";

$client = new MockServerClient($host, $port);

/**
 * Perform an HTTP GET (or other method) through MockServer's data plane and
 * return [statusCode, bodyString].
 *
 * @return array{0:int,1:string}
 */
function httpCall(string $baseUrl, string $path, string $method = 'GET'): array
{
    $ch = curl_init($baseUrl . $path);
    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_CUSTOMREQUEST, $method);
    $body = curl_exec($ch);
    if ($body === false) {
        $error = curl_error($ch);
        curl_close($ch);
        throw new RuntimeException("HTTP {$method} {$path} failed: {$error}");
    }
    $status = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);

    return [$status, (string) $body];
}

/**
 * Assert a condition; throw with a clear message on failure.
 */
function assertTrue(bool $condition, string $message): void
{
    if (!$condition) {
        throw new RuntimeException("ASSERTION FAILED: {$message}");
    }
}

function assertStatus(int $expected, int $actual, string $context): void
{
    assertTrue($expected === $actual, "{$context}: expected HTTP {$expected}, got {$actual}");
}

/**
 * Decode a JSON body and assert a field equals an expected value.
 *
 * @param array{0:int,1:string} $response
 */
function assertJsonField(array $response, string $field, mixed $expected, string $context): void
{
    $decoded = json_decode($response[1], true);
    assertTrue(is_array($decoded), "{$context}: body is not valid JSON: {$response[1]}");
    assertTrue(
        array_key_exists($field, $decoded) && $decoded[$field] === $expected,
        "{$context}: expected \"{$field}\"=" . json_encode($expected)
            . ', got ' . json_encode($decoded[$field] ?? null),
    );
}

// =====================================================================
// 1. state_machine — login flow (scenarioState / newScenarioState)
// =====================================================================
function scenarioStateMachine(MockServerClient $client, string $baseUrl): void
{
    $client->reset();

    // POST /login (Started -> LoggedIn), times=1 -> 200 {"token":"abc123"}
    $client->upsertExpectation(
        (new Expectation())
            ->httpRequest(HttpRequest::request()->method('POST')->path('/login'))
            ->scenarioName('LoginFlow')
            ->scenarioState('Started')
            ->newScenarioState('LoggedIn')
            ->times(Times::once())
            ->httpResponse(
                HttpResponse::response()
                    ->statusCode(200)
                    ->body(json_encode(['token' => 'abc123'], JSON_THROW_ON_ERROR)),
            ),
    );

    // GET /profile while LoggedIn -> 200 {"name":"Alice"}
    $client->upsertExpectation(
        (new Expectation())
            ->httpRequest(HttpRequest::request()->method('GET')->path('/profile'))
            ->scenarioName('LoginFlow')
            ->scenarioState('LoggedIn')
            ->httpResponse(
                HttpResponse::response()
                    ->statusCode(200)
                    ->body(json_encode(['name' => 'Alice'], JSON_THROW_ON_ERROR)),
            ),
    );

    // GET /profile while Started -> 401 {"error":"Not authenticated"}
    $client->upsertExpectation(
        (new Expectation())
            ->httpRequest(HttpRequest::request()->method('GET')->path('/profile'))
            ->scenarioName('LoginFlow')
            ->scenarioState('Started')
            ->httpResponse(
                HttpResponse::response()
                    ->statusCode(401)
                    ->body(json_encode(['error' => 'Not authenticated'], JSON_THROW_ON_ERROR)),
            ),
    );

    // Before login: GET /profile -> 401
    $before = httpCall($baseUrl, '/profile');
    assertStatus(401, $before[0], 'state_machine GET /profile before login');
    assertJsonField($before, 'error', 'Not authenticated', 'state_machine GET /profile before login');

    // Login: POST /login -> 200 with token, advances Started -> LoggedIn
    $login = httpCall($baseUrl, '/login', 'POST');
    assertStatus(200, $login[0], 'state_machine POST /login');
    assertJsonField($login, 'token', 'abc123', 'state_machine POST /login');

    // After login: GET /profile -> 200 name=Alice
    $after = httpCall($baseUrl, '/profile');
    assertStatus(200, $after[0], 'state_machine GET /profile after login');
    assertJsonField($after, 'name', 'Alice', 'state_machine GET /profile after login');

    echo "PASS: state_machine\n";
}

// =====================================================================
// 2. sequential_cycling — multiple responses, one expectation (no scenario)
// =====================================================================
function scenarioSequentialCycling(MockServerClient $client, string $baseUrl): void
{
    $client->reset();

    // GET /api/status -> [200 ok, 503 degraded, 200 ok], SEQUENTIAL (default).
    $client->upsertExpectation(
        (new Expectation())
            ->httpRequest(HttpRequest::request()->method('GET')->path('/api/status'))
            ->responseMode(ResponseMode::SEQUENTIAL)
            ->httpResponses(
                HttpResponse::response()
                    ->statusCode(200)
                    ->body(json_encode(['status' => 'ok'], JSON_THROW_ON_ERROR)),
                HttpResponse::response()
                    ->statusCode(503)
                    ->body(json_encode(['status' => 'degraded'], JSON_THROW_ON_ERROR)),
                HttpResponse::response()
                    ->statusCode(200)
                    ->body(json_encode(['status' => 'ok'], JSON_THROW_ON_ERROR)),
            ),
    );

    // 4 calls cycle: 200, 503, 200, then back to first -> 200.
    $expected = [200, 503, 200, 200];
    foreach ($expected as $i => $code) {
        $n = $i + 1;
        $resp = httpCall($baseUrl, '/api/status');
        assertStatus($code, $resp[0], "sequential_cycling call #{$n}");
    }

    echo "PASS: sequential_cycling\n";
}

// =====================================================================
// 3. timed_transition — scenario REST helper, timed auto-transition
// =====================================================================
function scenarioTimedTransition(MockServerClient $client, string $baseUrl): void
{
    $client->reset();

    // GET /status while Deploying -> 200 {"status":"deploying"}
    $client->upsertExpectation(
        (new Expectation())
            ->httpRequest(HttpRequest::request()->method('GET')->path('/status'))
            ->scenarioName('DeployFlow')
            ->scenarioState('Deploying')
            ->httpResponse(
                HttpResponse::response()
                    ->statusCode(200)
                    ->body(json_encode(['status' => 'deploying'], JSON_THROW_ON_ERROR)),
            ),
    );

    // GET /status while Deployed -> 200 {"status":"complete"}
    $client->upsertExpectation(
        (new Expectation())
            ->httpRequest(HttpRequest::request()->method('GET')->path('/status'))
            ->scenarioName('DeployFlow')
            ->scenarioState('Deployed')
            ->httpResponse(
                HttpResponse::response()
                    ->statusCode(200)
                    ->body(json_encode(['status' => 'complete'], JSON_THROW_ON_ERROR)),
            ),
    );

    // Start Deploying, auto-transition to Deployed after 1000ms.
    $client->scenario('DeployFlow')->set('Deploying', 1000, 'Deployed');

    // Before the transition: deploying.
    $during = httpCall($baseUrl, '/status');
    assertStatus(200, $during[0], 'timed_transition GET /status during deploy');
    assertJsonField($during, 'status', 'deploying', 'timed_transition GET /status during deploy');

    // Wait for the timed transition to fire.
    usleep(1_300_000);

    // After the transition: complete.
    $after = httpCall($baseUrl, '/status');
    assertStatus(200, $after[0], 'timed_transition GET /status after deploy');
    assertJsonField($after, 'status', 'complete', 'timed_transition GET /status after deploy');

    echo "PASS: timed_transition\n";
}

// =====================================================================
// 4. external_trigger — scenario REST helper, external trigger
// =====================================================================
function scenarioExternalTrigger(MockServerClient $client, string $baseUrl): void
{
    $client->reset();

    // GET /health while Started -> 200 {"status":"healthy"}
    $client->upsertExpectation(
        (new Expectation())
            ->httpRequest(HttpRequest::request()->method('GET')->path('/health'))
            ->scenarioName('HealthFlow')
            ->scenarioState('Started')
            ->httpResponse(
                HttpResponse::response()
                    ->statusCode(200)
                    ->body(json_encode(['status' => 'healthy'], JSON_THROW_ON_ERROR)),
            ),
    );

    // GET /health while Down -> 503 {"status":"down"}
    $client->upsertExpectation(
        (new Expectation())
            ->httpRequest(HttpRequest::request()->method('GET')->path('/health'))
            ->scenarioName('HealthFlow')
            ->scenarioState('Down')
            ->httpResponse(
                HttpResponse::response()
                    ->statusCode(503)
                    ->body(json_encode(['status' => 'down'], JSON_THROW_ON_ERROR)),
            ),
    );

    // Healthy by default (start state is "Started").
    $healthy = httpCall($baseUrl, '/health');
    assertStatus(200, $healthy[0], 'external_trigger GET /health before trigger');
    assertJsonField($healthy, 'status', 'healthy', 'external_trigger GET /health before trigger');

    // Externally trigger the transition to Down.
    $client->scenario('HealthFlow')->trigger('Down');

    // Now down.
    $down = httpCall($baseUrl, '/health');
    assertStatus(503, $down[0], 'external_trigger GET /health after trigger');
    assertJsonField($down, 'status', 'down', 'external_trigger GET /health after trigger');

    echo "PASS: external_trigger\n";
}

// =====================================================================
// 5. cross_protocol — crossProtocolScenarios (HTTP_REQUEST trigger)
// =====================================================================
function scenarioCrossProtocol(MockServerClient $client, string $baseUrl): void
{
    $client->reset();

    // GET /events -> 200, and on this HTTP event advance ConnFlow -> Connected.
    $client->upsertExpectation(
        (new Expectation())
            ->httpRequest(HttpRequest::request()->method('GET')->path('/events'))
            ->httpResponse(HttpResponse::response()->statusCode(200))
            ->crossProtocolScenarios(
                CrossProtocolScenario::trigger(CrossProtocolTrigger::HTTP_REQUEST)
                    ->matchPattern('/events')
                    ->scenarioName('ConnFlow')
                    ->targetState('Connected'),
            ),
    );

    // GET /api/conn-status while ConnFlow=Connected -> 200 {"status":"connected"}
    $client->upsertExpectation(
        (new Expectation())
            ->httpRequest(HttpRequest::request()->method('GET')->path('/api/conn-status'))
            ->scenarioName('ConnFlow')
            ->scenarioState('Connected')
            ->httpResponse(
                HttpResponse::response()
                    ->statusCode(200)
                    ->body(json_encode(['status' => 'connected'], JSON_THROW_ON_ERROR)),
            ),
    );

    // Before any /events: ConnFlow not yet Connected, so /api/conn-status is
    // unmatched -> 404.
    $before = httpCall($baseUrl, '/api/conn-status');
    assertStatus(404, $before[0], 'cross_protocol GET /api/conn-status before /events');

    // Fire the HTTP event that advances ConnFlow -> Connected.
    $events = httpCall($baseUrl, '/events');
    assertStatus(200, $events[0], 'cross_protocol GET /events');

    // Now Connected: /api/conn-status -> 200 connected.
    $after = httpCall($baseUrl, '/api/conn-status');
    assertStatus(200, $after[0], 'cross_protocol GET /api/conn-status after /events');
    assertJsonField($after, 'status', 'connected', 'cross_protocol GET /api/conn-status after /events');

    echo "PASS: cross_protocol\n";
}

// =====================================================================
// Run all scenarios in sequence; exit 0 only if all pass.
// =====================================================================
echo "Running stateful-scenario examples against {$baseUrl}\n\n";

$scenarios = [
    'state_machine' => 'scenarioStateMachine',
    'sequential_cycling' => 'scenarioSequentialCycling',
    'timed_transition' => 'scenarioTimedTransition',
    'external_trigger' => 'scenarioExternalTrigger',
    'cross_protocol' => 'scenarioCrossProtocol',
];

$failed = 0;
foreach ($scenarios as $name => $fn) {
    try {
        $fn($client, $baseUrl);
    } catch (Throwable $e) {
        $failed++;
        fwrite(STDERR, "FAIL: {$name} — {$e->getMessage()}\n");
    }
}

// Clean up.
try {
    $client->reset();
} catch (Throwable $e) {
    fwrite(STDERR, "WARN: final reset failed — {$e->getMessage()}\n");
}

echo "\n";
if ($failed === 0) {
    echo "All 5 scenarios passed.\n";
    exit(0);
}

fwrite(STDERR, "{$failed} scenario(s) failed.\n");
exit(1);
