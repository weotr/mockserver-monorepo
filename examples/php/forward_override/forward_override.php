<?php

declare(strict_types=1);

// Demonstrates using httpOverrideForwardedRequest to statically modify a proxied
// request. This is the PHP alternative to interactive breakpoints (the PHP client
// does not include WebSocket support).
//
// How it works:
//   1. Create an "upstream" mock on /upstream that returns a canned JSON response.
//   2. Create an httpOverrideForwardedRequest expectation via the raw REST API
//      that rewrites requests from /proxy to /upstream on the same MockServer,
//      and overrides the response with an extra header.
//   3. Send a request to /proxy and show that the response was modified.
//
// Prerequisites: MockServer running on localhost:1080
//   docker run -d -p 1080:1080 mockserver/mockserver

require_once __DIR__ . '/../../../mockserver-client-php/vendor/autoload.php';

use MockServer\MockServerClient;
use MockServer\HttpRequest;
use MockServer\HttpResponse;

$client = new MockServerClient('localhost', 1080);

// -------------------------------------------------------------------
// 1. Upstream mock: GET /upstream -> 200 JSON
// -------------------------------------------------------------------
$client->when(
    HttpRequest::request()->method('GET')->path('/upstream')
)->respond(
    HttpResponse::response()
        ->statusCode(200)
        ->header('Content-Type', 'application/json')
        ->body('{"source":"upstream","modified":false}')
);
echo "1. Created upstream expectation: GET /upstream -> 200\n";

// -------------------------------------------------------------------
// 2. Create httpOverrideForwardedRequest via raw REST API
//    The PHP client's forward() only supports HttpForward (simple forward).
//    httpOverrideForwardedRequest lets us rewrite the forwarded request AND
//    override the response -- this is the static way to modify proxied traffic.
// -------------------------------------------------------------------
$payload = json_encode([
    [
        'httpRequest' => [
            'method' => 'GET',
            'path' => '/proxy',
        ],
        'httpOverrideForwardedRequest' => [
            'httpRequest' => [
                'path' => '/upstream',
                'socketAddress' => [
                    'host' => 'localhost',
                    'port' => 1080,
                    'scheme' => 'HTTP',
                ],
            ],
            'httpResponse' => [
                'headers' => [
                    'X-Modified-By' => ['php-forward-override'],
                ],
                'body' => '{"source":"upstream","modified":true,"override":"php-client"}',
            ],
        ],
    ],
], JSON_THROW_ON_ERROR);

$ch = curl_init('http://localhost:1080/mockserver/expectation');
curl_setopt_array($ch, [
    CURLOPT_CUSTOMREQUEST => 'PUT',
    CURLOPT_POSTFIELDS => $payload,
    CURLOPT_HTTPHEADER => ['Content-Type: application/json; charset=utf-8'],
    CURLOPT_RETURNTRANSFER => true,
]);
$result = curl_exec($ch);
$status = curl_getinfo($ch, CURLINFO_HTTP_CODE);
curl_close($ch);

if ($status >= 400) {
    echo "ERROR: Failed to create forward override expectation (HTTP {$status}): {$result}\n";
    exit(1);
}
echo "2. Created forward override: GET /proxy -> forward to /upstream (localhost:1080)\n";
echo "   Response will be overridden with modified body and X-Modified-By header\n";

// -------------------------------------------------------------------
// 3. Send request to /proxy -- the response will be the overridden version
// -------------------------------------------------------------------
echo "\n3. Sending GET /proxy ...\n";

$ch = curl_init('http://localhost:1080/proxy');
curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
curl_setopt($ch, CURLOPT_HEADER, true);
$response = curl_exec($ch);
$headerSize = curl_getinfo($ch, CURLINFO_HEADER_SIZE);
$httpStatus = curl_getinfo($ch, CURLINFO_HTTP_CODE);
curl_close($ch);

$headers = substr($response, 0, $headerSize);
$body = substr($response, $headerSize);

// Extract X-Modified-By header
$modifiedBy = '(not set)';
foreach (explode("\r\n", $headers) as $line) {
    if (stripos($line, 'X-Modified-By:') === 0) {
        $modifiedBy = trim(substr($line, strlen('X-Modified-By:')));
    }
}

echo "\n--- Response from GET /proxy ---\n";
echo "Status:          {$httpStatus}\n";
echo "Body:            {$body}\n";
echo "X-Modified-By:   {$modifiedBy}\n";

// -------------------------------------------------------------------
// Clean up
// -------------------------------------------------------------------
$client->reset();
echo "\nAll expectations cleared.\n";
