# MockServer PHP Client

PHP client for [MockServer](https://www.mock-server.com) - enables easy mocking of any system you integrate with via HTTP or HTTPS.

## Requirements

- PHP 8.1+
- Composer

## Installation

```bash
composer require mock-server/mockserver-client
```

## Quick Start

```php
<?php

use MockServer\MockServerClient;
use MockServer\HttpRequest;
use MockServer\HttpResponse;
use MockServer\VerificationTimes;

// Connect to MockServer
$client = new MockServerClient('localhost', 1080);

// Create an expectation
$client->when(
    HttpRequest::request()->method('GET')->path('/hello')
)->respond(
    HttpResponse::response()
        ->statusCode(200)
        ->header('Content-Type', 'application/json')
        ->body('{"message":"world"}')
);

// Verify the request was received
$client->verify(
    HttpRequest::request()->path('/hello'),
    VerificationTimes::atLeast(1)
);

// Reset all expectations
$client->reset();
```

## API

### Creating Expectations

```php
use MockServer\Times;
use MockServer\TimeToLive;
use MockServer\Delay;
use MockServer\HttpForward;

// Respond with a delay
$client->when(
    HttpRequest::request()->method('POST')->path('/api/data')
        ->header('Content-Type', 'application/json')
        ->jsonBody(['key' => 'value'])
)->respond(
    HttpResponse::response()
        ->statusCode(201)
        ->body('{"id": 1}')
        ->delay(Delay::milliseconds(500))
);

// Match only 3 times, with priority
$client->when(
    HttpRequest::request()->path('/limited'),
    Times::exactly(3),
    TimeToLive::exactly('SECONDS', 60),
    priority: 10
)->respond(
    HttpResponse::response()->statusCode(200)
);

// Forward to another server
$client->when(
    HttpRequest::request()->path('/proxy')
)->forward(
    HttpForward::forward()->host('backend.local')->port(8080)->scheme('HTTP')
);
```

### Verification

```php
use MockServer\VerificationTimes;

// Verify at least once
$client->verify(
    HttpRequest::request()->path('/hello'),
    VerificationTimes::atLeast(1)
);

// Verify exactly 3 times
$client->verify(
    HttpRequest::request()->method('POST')->path('/api'),
    VerificationTimes::exactly(3)
);

// Verify sequence
$client->verifySequence(
    HttpRequest::request()->path('/first'),
    HttpRequest::request()->path('/second')
);
```

### Retrieving Recorded Data

```php
// Retrieve recorded requests
$requests = $client->retrieveRecordedRequests(
    HttpRequest::request()->path('/api')
);

// Retrieve active expectations
$expectations = $client->retrieveActiveExpectations();

// Retrieve log messages
$logs = $client->retrieveLogMessages();
```

### Control Operations

```php
// Clear specific expectations/logs
$client->clear(HttpRequest::request()->path('/old'));
$client->clear(null, 'EXPECTATIONS');  // type: EXPECTATIONS, LOG, or ALL
$client->clearById('my-expectation-id');

// Reset everything
$client->reset();

// Check server status
$status = $client->status();  // ['ports' => [1080]]

// Bind additional ports
$client->bind(1081, 1082);

// Check if server is running
if ($client->hasStarted()) {
    echo "MockServer is ready";
}
```

## Building

```bash
composer install
```

## Running Tests

Unit tests (no server required):

```bash
vendor/bin/phpunit --testsuite Unit
```

Integration tests (requires a running MockServer):

```bash
MOCKSERVER_URL=http://localhost:1080 vendor/bin/phpunit --testsuite Integration
```

## License

Apache 2.0 - see [LICENSE](../LICENSE.md)
