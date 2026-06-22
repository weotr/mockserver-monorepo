<?php

declare(strict_types=1);

// Demonstrates MockServer's Load Scenario registry from the PHP client.
//
// A "load scenario" is a named, server-side traffic generator: you register it
// once (its profile of ramp/hold/pause stages and the request steps it drives),
// then start/stop it by name. While running it generates synthetic traffic
// against the data plane and reports live throughput/latency status. This is the
// registry workflow exercised with the fluent PHP builder:
//
//   $client->loadScenario($scenario)        register/upsert (PUT /mockserver/loadScenario)
//   $client->startLoadScenarios($names)     start one/many (PUT .../start)
//   $client->loadScenarios()                list all (GET /mockserver/loadScenario)
//   $client->getLoadScenario($name)         one scenario + live status (GET .../{name})
//   $client->stopLoadScenarios($names)      stop one/many; null = stop all (PUT .../stop)
//   $client->runLoadScenario($scenario)     register + start in one call
//   $client->deleteLoadScenario($name)      delete one (DELETE .../{name})
//   $client->clearLoadScenarios()           clear the registry (DELETE /mockserver/loadScenario)
//
// IMPORTANT: the server must be started with load generation enabled, otherwise
// starting returns HTTP 403:
//   java -Dmockserver.loadGenerationEnabled=true -jar mockserver-netty-...-jar-with-dependencies.jar -serverPort 1080
//   (or env MOCKSERVER_LOAD_GENERATION_ENABLED=true). Registering is always allowed.
//
// Prints "PASS" and exits 0 on success; exits non-zero on the first failure.
//
// Discovery: MOCKSERVER_HOST (default localhost) / MOCKSERVER_PORT (default 1080).

require_once __DIR__ . '/../../../mockserver-client-php/vendor/autoload.php';

use MockServer\Delay;
use MockServer\Expectation;
use MockServer\HttpRequest;
use MockServer\HttpResponse;
use MockServer\LoadProfile;
use MockServer\LoadScenario;
use MockServer\LoadStage;
use MockServer\MockServerClient;

$host = getenv('MOCKSERVER_HOST') ?: 'localhost';
$port = (int) (getenv('MOCKSERVER_PORT') ?: '1080');

$client = new MockServerClient($host, $port);

/**
 * A realistic multi-stage scenario built with the fluent builder: a linear RATE
 * ramp (5 -> 50 req/s, capped at 50 VUs), then a 25-VU hold, then a PAUSE. Two
 * Velocity-templated steps drive each iteration ($iteration.index varies the
 * request). startDelayMillis defers load for half a second after start. Stage
 * VUs are kept within the default safety cap of 50 (loadGenerationMaxVirtualUsers).
 */
function buildScenario(): LoadScenario
{
    return LoadScenario::scenario('checkout-load')
        ->templateType('VELOCITY')
        ->maxRequests(100000)
        ->startDelayMillis(500)
        ->labels(['team' => 'payments', 'env' => 'staging'])
        ->profile(LoadProfile::of(
            LoadStage::rateRamp(5, 50, 30000, 'LINEAR')->maxVus(50),
            LoadStage::vuHold(25, 60000),
            LoadStage::pause(10000),
        ))
        ->addStep(
            HttpRequest::request()->method('GET')->path('/products/$iteration.index'),
            Delay::milliseconds(500),
            'browse',
        )
        ->addStep(
            HttpRequest::request()->method('POST')->path('/cart/checkout')
                ->body('{"item":"$iteration.index","qty":1}'),
            null,
            'checkout',
            ['critical' => 'true'],
        );
}

function assertTrue(bool $condition, string $message): void
{
    if (!$condition) {
        throw new RuntimeException("ASSERTION FAILED: {$message}");
    }
}

try {
    // A catch-all target expectation so generated traffic gets a 200 to measure.
    $client->upsertExpectation(
        (new Expectation())
            ->httpRequest(HttpRequest::request()->path('/.*'))
            ->httpResponse(HttpResponse::response()->statusCode(200)->body('ok')),
    );

    $scenario = buildScenario();

    // 1. Register (does NOT start it yet).
    $client->loadScenario($scenario);
    echo "registered \"checkout-load\"\n";

    // 2. Start it (accepts a single name or an array of names).
    $client->startLoadScenarios('checkout-load');
    echo "started \"checkout-load\"\n";
    usleep(1_500_000);

    // 3. List all registered scenarios -> { "scenarios": [ <status node>, ... ] }.
    $scenarios = $client->loadScenarios()['scenarios'] ?? [];
    $running = array_filter($scenarios, static fn ($s) => ($s['state'] ?? null) === 'RUNNING');
    assertTrue(
        array_filter($running, static fn ($s) => ($s['name'] ?? null) === 'checkout-load') !== [],
        'checkout-load is not RUNNING in the list (is loadGenerationEnabled=true?)',
    );
    echo 'listed: ' . implode(', ', array_map(
        static fn ($s) => "{$s['name']}={$s['state']}",
        $scenarios,
    )) . "\n";

    // One scenario's live status (throughput/latency, current stage, ...).
    $status = $client->getLoadScenario('checkout-load');
    printf(
        "status: state=%s stageType=%s currentTarget=%s requestsSent=%s\n",
        $status['state'] ?? '',
        $status['stageType'] ?? '',
        $status['currentTarget'] ?? '',
        $status['requestsSent'] ?? '',
    );

    // 4. Stop it (pass null to stop ALL running scenarios).
    $client->stopLoadScenarios('checkout-load');
    echo "stopped \"checkout-load\"\n";

    // Tidy up the registry.
    $client->clearLoadScenarios();

    echo "PASS\n";
    exit(0);
} catch (Throwable $e) {
    fwrite(STDERR, "FAIL: {$e->getMessage()}\n");
    exit(1);
}
