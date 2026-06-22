<?php

declare(strict_types=1);

// Demonstrates MockServer CLASS callbacks from the PHP client.
//
// A class callback references a server-side class (on MockServer's classpath)
// that implements one of MockServer's callback interfaces:
//   - ExpectationResponseCallback -> httpResponseClassCallback
//   - ExpectationForwardCallback  -> httpForwardClassCallback
//
// The PHP client is REST-only — it has no callback WebSocket — so OBJECT /
// CLOSURE callbacks (where the callback runs in *your* PHP process) are NOT
// possible from PHP. Class callbacks are pure JSON and work fine. See README.
//
// This example asserts the *wire shape*: that MockServer ACCEPTS an
// expectation carrying httpResponseClassCallback / httpForwardClassCallback
// (200 on upsert). The referenced class need not exist on the server for the
// upsert to validate and store the expectation.
//
// Discovery (per the harness): MOCKSERVER_HOST (default localhost) /
// MOCKSERVER_PORT (default 1080).
//
// Prints "PASS: <case>" for each and exits 0 only if all pass.
//
// Prerequisites: MockServer running, e.g.
//   docker run -d -p 1080:1080 mockserver/mockserver

require_once __DIR__ . '/../../../mockserver-client-php/vendor/autoload.php';

use MockServer\Delay;
use MockServer\Expectation;
use MockServer\HttpClassCallback;
use MockServer\HttpRequest;
use MockServer\MockServerClient;

$host = getenv('MOCKSERVER_HOST') ?: 'localhost';
$port = (int) (getenv('MOCKSERVER_PORT') ?: '1080');

$client = new MockServerClient($host, $port);

/**
 * Assert a condition; throw with a clear message on failure.
 */
function assertTrue(bool $condition, string $message): void
{
    if (!$condition) {
        throw new RuntimeException("ASSERTION FAILED: {$message}");
    }
}

// =====================================================================
// 1. response_class_callback — httpResponseClassCallback (string form)
// =====================================================================
function responseClassCallback(MockServerClient $client): void
{
    $client->reset();

    // The server accepts and stores the expectation even though
    // "com.example.MyResponseCallback" is not on its classpath here.
    $client->upsertExpectation(
        (new Expectation())
            ->httpRequest(HttpRequest::request()->method('GET')->path('/dynamic'))
            ->httpResponseClassCallback('com.example.MyResponseCallback'),
    );

    // Confirm it round-trips back from the server as a response class callback.
    $active = $client->retrieveActiveExpectations(
        HttpRequest::request()->method('GET')->path('/dynamic'),
    );
    assertTrue($active !== [], 'response_class_callback: expectation not stored');
    $callbackClass = $active[0]['httpResponseClassCallback']['callbackClass'] ?? null;
    assertTrue(
        $callbackClass === 'com.example.MyResponseCallback',
        'response_class_callback: expected callbackClass com.example.MyResponseCallback, got '
            . json_encode($callbackClass),
    );

    echo "PASS: response_class_callback\n";
}

// =====================================================================
// 2. response_class_callback_with_delay — object form + delay + primary
// =====================================================================
function responseClassCallbackWithDelay(MockServerClient $client): void
{
    $client->reset();

    $client->upsertExpectation(
        (new Expectation())
            ->httpRequest(HttpRequest::request()->method('GET')->path('/delayed-dynamic'))
            ->httpResponseClassCallback(
                HttpClassCallback::callback('com.example.MyResponseCallback')
                    ->delay(Delay::milliseconds(250))
                    ->primary(true),
            ),
    );

    $active = $client->retrieveActiveExpectations(
        HttpRequest::request()->method('GET')->path('/delayed-dynamic'),
    );
    assertTrue($active !== [], 'response_class_callback_with_delay: expectation not stored');
    $callback = $active[0]['httpResponseClassCallback'] ?? null;
    assertTrue(
        is_array($callback) && ($callback['callbackClass'] ?? null) === 'com.example.MyResponseCallback',
        'response_class_callback_with_delay: missing callbackClass',
    );

    echo "PASS: response_class_callback_with_delay\n";
}

// =====================================================================
// 3. forward_class_callback — httpForwardClassCallback
// =====================================================================
function forwardClassCallback(MockServerClient $client): void
{
    $client->reset();

    $client->upsertExpectation(
        (new Expectation())
            ->httpRequest(HttpRequest::request()->method('GET')->path('/proxy'))
            ->httpForwardClassCallback('com.example.MyForwardCallback'),
    );

    $active = $client->retrieveActiveExpectations(
        HttpRequest::request()->method('GET')->path('/proxy'),
    );
    assertTrue($active !== [], 'forward_class_callback: expectation not stored');
    $callbackClass = $active[0]['httpForwardClassCallback']['callbackClass'] ?? null;
    assertTrue(
        $callbackClass === 'com.example.MyForwardCallback',
        'forward_class_callback: expected callbackClass com.example.MyForwardCallback, got '
            . json_encode($callbackClass),
    );

    echo "PASS: forward_class_callback\n";
}

// =====================================================================
// Run all cases in sequence; exit 0 only if all pass.
// =====================================================================
echo "Running class-callback examples against http://{$host}:{$port}\n\n";

$cases = [
    'response_class_callback' => 'responseClassCallback',
    'response_class_callback_with_delay' => 'responseClassCallbackWithDelay',
    'forward_class_callback' => 'forwardClassCallback',
];

$failed = 0;
foreach ($cases as $name => $fn) {
    try {
        $fn($client);
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
    echo "All 3 class-callback cases passed.\n";
    exit(0);
}

fwrite(STDERR, "{$failed} case(s) failed.\n");
exit(1);
