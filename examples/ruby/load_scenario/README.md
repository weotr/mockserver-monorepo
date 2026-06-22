# Load Scenario Registry (Ruby)

Demonstrates MockServer's **Load Scenario registry** with the Ruby client. A
load scenario is a named, server-side traffic generator: register it once, then
start/stop it by name while the server generates synthetic traffic and reports
live status.

`load_scenario.rb` builds a realistic multi-stage scenario (`checkout-load`: a
RATE ramp → VU hold → PAUSE, with two Velocity-templated request steps and a
`start_delay_millis`) from the typed model, registers it, starts it, lists it
(asserting it is `RUNNING`), reads its live status, then stops and clears it. It
prints `PASS` and exits 0 on success.

## Client methods used

| Method | Endpoint |
|--------|----------|
| `load_scenario(scenario)` | register/upsert (`PUT /mockserver/loadScenario`) |
| `start_load_scenarios(names)` | start one/many — `String` or `Array<String>` (`PUT .../start`) |
| `load_scenarios` | list all (`GET /mockserver/loadScenario`) |
| `get_load_scenario(name)` | one scenario + live status (`GET .../{name}`) |
| `stop_load_scenarios(names = nil)` | stop one/many; `nil` = stop all (`PUT .../stop`) |
| `run_load_scenario(scenario)` | register + start in one call |
| `delete_load_scenario(name)` / `clear_load_scenarios` | delete one / clear all |

The typed model — `LoadScenario`, `LoadProfile`, `LoadStage` (`.rate(...)` /
`.vu(...)` / `.pause(...)`), `LoadStep` and `Delay` — is optional;
`load_scenario` also accepts a plain `Hash` in the same camelCase shape.

## Prerequisites

- **Ruby 3.0+** and the `mockserver-client` gem (or run with the source tree on
  the load path: `ruby -I../../mockserver-client-ruby/lib load_scenario.rb`).
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
MOCKSERVER_HOST=localhost MOCKSERVER_PORT=1080 ruby load_scenario.rb
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
  `request`, not `http_request`).
- Stage VUs/rate are validated against the server's safety caps (default VUs ≤
  50, rate ≤ 500 req/s); raise the `mockserver.loadGeneration*` properties to go
  higher.
