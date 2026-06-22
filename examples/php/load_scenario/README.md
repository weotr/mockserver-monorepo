# Load Scenario Registry (PHP)

Demonstrates MockServer's **Load Scenario registry** with the PHP client. A load
scenario is a named, server-side traffic generator: register it once, then
start/stop it by name while the server generates synthetic traffic and reports
live status.

`load_scenario.php` builds a realistic multi-stage scenario (`checkout-load`: a
RATE ramp → VU hold → PAUSE, with two Velocity-templated request steps and a
`startDelayMillis`) with the fluent builder, registers it, starts it, lists it
(asserting it is `RUNNING`), reads its live status, then stops and clears it. It
prints `PASS` and exits 0 on success.

## Client methods used

| Method | Endpoint |
|--------|----------|
| `loadScenario($scenario)` | register/upsert (`PUT /mockserver/loadScenario`) |
| `startLoadScenarios($names)` | start one/many — `string` or `string[]` (`PUT .../start`) |
| `loadScenarios()` | list all (`GET /mockserver/loadScenario`) |
| `getLoadScenario($name)` | one scenario + live status (`GET .../{name}`) |
| `stopLoadScenarios($names = null)` | stop one/many; `null` = stop all (`PUT .../stop`) |
| `runLoadScenario($scenario)` | register + start in one call |
| `deleteLoadScenario($name)` / `clearLoadScenarios()` | delete one / clear all |

The fluent builder — `LoadScenario::scenario(...)`, `LoadProfile::of(...)`,
`LoadStage` (`::rateRamp(...)->maxVus(...)` / `::vuHold(...)` / `::pause(...)`)
and `Delay` — is optional; `loadScenario()` also accepts a plain associative
array in the same camelCase shape.

## Prerequisites

- **PHP 8.1+** with the client installed (`composer install` in
  `../../mockserver-client-php`).
- **MockServer started with load generation enabled** (required to *start*
  scenarios — registering is always allowed):

  ```bash
  java -Dmockserver.loadGenerationEnabled=true \
    -jar mockserver-netty-jar-with-dependencies.jar -serverPort 1080
  # or: docker run -d -p 1080:1080 -e MOCKSERVER_LOAD_GENERATION_ENABLED=true mockserver/mockserver
  ```

  Starting without it returns HTTP 403.

## Run

```bash
MOCKSERVER_HOST=localhost MOCKSERVER_PORT=1080 php load_scenario.php
```

## Expected output

```
registered "checkout-load"
started "checkout-load"
listed: checkout-load=RUNNING
status: state=RUNNING stageType=RATE currentTarget=... requestsSent=...
stopped "checkout-load"
PASS
```

## Notes

- The per-step `request` is a standard MockServer `HttpRequest` (the field is
  `request`, not `httpRequest`).
- Stage VUs/rate are validated against the server's safety caps (default VUs ≤
  50, rate ≤ 500 req/s); raise the `mockserver.loadGeneration*` properties to go
  higher.
