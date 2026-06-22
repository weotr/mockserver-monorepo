<?php

declare(strict_types=1);

// Demonstrates creating a basic MockServer expectation, exercising it with a
// real HTTP request, and verifying that the request was received.
//
// Prerequisites: MockServer running on localhost:1080
//   docker run -d -p 1080:1080 mockserver/mockserver

require_once __DIR__ . '/../../../mockserver-client-php/vendor/autoload.php';

use MockServer\MockServerClient;
use MockServer\HttpRequest;
use MockServer\HttpResponse;
use MockServer\VerificationTimes;

$client = new MockServerClient('localhost', 1080);

// -------------------------------------------------------------------
// 1. Create an expectation: GET /hello -> 200 "Hello from PHP!"
// -------------------------------------------------------------------
$client->when(
    HttpRequest::request()->method('GET')->path('/hello')
)->respond(
    HttpResponse::response()
        ->statusCode(200)
        ->header('Content-Type', 'text/plain')
        ->body('Hello from PHP!')
);
echo "1. Created expectation: GET /hello -> 200 \"Hello from PHP!\"\n";

// -------------------------------------------------------------------
// 2. Send a test request through MockServer
// -------------------------------------------------------------------
$ch = curl_init('http://localhost:1080/hello');
curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
$body = curl_exec($ch);
$status = curl_getinfo($ch, CURLINFO_HTTP_CODE);
curl_close($ch);

echo "\n--- Test request: GET /hello ---\n";
echo "Status: {$status}\n";
echo "Body:   {$body}\n";

// -------------------------------------------------------------------
// 3. Verify the request was received at least once
// -------------------------------------------------------------------
$client->verify(
    HttpRequest::request()->method('GET')->path('/hello'),
    VerificationTimes::atLeast(1)
);
echo "\n2. Verified: GET /hello received at least once\n";

// -------------------------------------------------------------------
// Clean up
// -------------------------------------------------------------------
$client->reset();
echo "\nAll expectations cleared.\n";
