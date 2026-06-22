# Load Scenario Registry (Go)

Demonstrates MockServer's **Load Scenario registry** with the Go client. A load
scenario is a named, server-side traffic generator: register it once, then
start/stop it by name while the server generates synthetic traffic and reports
live status.

`main.go` builds a realistic multi-stage scenario (`checkout-load`: a RATE ramp →
VU hold → PAUSE, with two Velocity-templated request steps and a
`StartDelayMillis`) from the typed model, registers it, starts it, lists it
(asserting it is `RUNNING`), reads its live status, then stops and clears it. It
prints `PASS` and exits 0 on success.

## Client methods used

| Method | Endpoint |
|--------|----------|
| `LoadScenario(scenario)` | register/upsert (`PUT /mockserver/loadScenario`) |
| `StartLoadScenarios(names...)` | start one/many — variadic names (`PUT .../start`) |
| `LoadScenarios()` | list all (`GET /mockserver/loadScenario`) |
| `GetLoadScenario(name)` | one scenario + live status (`GET .../{name}`) |
| `StopLoadScenarios(names...)` | stop one/many; no args = stop all (`PUT .../stop`) |
| `RunLoadScenario(scenario)` | register + start in one call |
| `DeleteLoadScenario(name)` / `ClearLoadScenarios()` | delete one / clear all |

The typed model — `LoadScenario`, `LoadProfile`, `LoadStage`
(`RampRateStage(...)` / `ConstantVusStage(...)` / `PauseStage(...)`), `LoadStep`
and `Delay` — maps directly to the `LoadScenario` JSON contract. The optional
numeric setpoints (e.g. `MaxVus`) are `*int` fields, set on the returned stage.

## Prerequisites

- **Go 1.21+**.
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
MOCKSERVER_HOST=localhost MOCKSERVER_PORT=1080 go run .
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

- The per-step `Request` is a standard MockServer `HttpRequest`.
- Stage VUs/rate are validated against the server's safety caps (default VUs ≤
  50, rate ≤ 500 req/s); raise the `mockserver.loadGeneration*` properties to go
  higher.
