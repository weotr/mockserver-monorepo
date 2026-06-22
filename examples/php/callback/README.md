# Class Callbacks

## What it demonstrates

How to attach a **class callback** to an expectation from the MockServer PHP
client. A class callback names a server-side class (already on MockServer's
classpath) that implements a callback interface; MockServer invokes it to
produce the response or the request to forward. It is pure JSON — no WebSocket.

The script runs three cases, registers each through the control plane, and
asserts MockServer **accepts and stores** the expectation (the referenced class
need not exist on the server for the wire shape to validate):

1. **response_class_callback** — `httpResponseClassCallback` from a class-name
   string (`com.example.MyResponseCallback`).
2. **response_class_callback_with_delay** — the same, built from an
   `HttpClassCallback` with a `delay` and the `primary` flag.
3. **forward_class_callback** — `httpForwardClassCallback`
   (`com.example.MyForwardCallback`).

## Object / closure callbacks are NOT available in PHP

MockServer also supports **object (closure) callbacks**, where the callback runs
in *your* process: the client opens a callback **WebSocket**, the server hands
it a `clientId`, and on each match it streams the request to your code, which
returns the response.

**The PHP client is REST-only and does not implement that callback WebSocket,
so object/closure callbacks are not available in PHP.** Use a **class callback**
(a class on the MockServer classpath) instead — that is what this example shows.
This is a transport limitation of the PHP client, not of MockServer.

## API

```php
use MockServer\Expectation;
use MockServer\HttpClassCallback;
use MockServer\HttpRequest;
use MockServer\Delay;

// Response class callback (string form)
$client->upsertExpectation(
    (new Expectation())
        ->httpRequest(HttpRequest::request()->method('GET')->path('/dynamic'))
        ->httpResponseClassCallback('com.example.MyResponseCallback')
);

// With a delay and the primary flag (object form)
$client->upsertExpectation(
    (new Expectation())
        ->httpRequest(HttpRequest::request()->path('/delayed'))
        ->httpResponseClassCallback(
            HttpClassCallback::callback('com.example.MyResponseCallback')
                ->delay(Delay::milliseconds(250))
                ->primary(true)
        )
);

// Forward class callback
$client->upsertExpectation(
    (new Expectation())
        ->httpRequest(HttpRequest::request()->path('/proxy'))
        ->httpForwardClassCallback('com.example.MyForwardCallback')
);
```

## Prerequisites

- PHP 8.1+
- Composer dependencies installed (`cd ../../../mockserver-client-php && composer install`)
- MockServer running on `localhost:1080`
  (`docker run -d -p 1080:1080 mockserver/mockserver`)

The server location is read from the `MOCKSERVER_HOST` (default `localhost`) and
`MOCKSERVER_PORT` (default `1080`) environment variables.

## Run

```bash
php callback.php
```

The script resets MockServer before each case, so it is self-contained and
order-independent. It exits `0` only if all three cases pass.

## Expected output

```
Running class-callback examples against http://localhost:1080

PASS: response_class_callback
PASS: response_class_callback_with_delay
PASS: forward_class_callback

All 3 class-callback cases passed.
```
