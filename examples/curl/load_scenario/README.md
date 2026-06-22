# Load Scenario Registry (curl)

Two self-contained shell scripts that exercise MockServer's **Load Scenario
registry** REST API end-to-end. A load scenario is a named, server-side traffic
generator: you register it once, then start/stop it by name. While running it
generates synthetic traffic against the data plane and reports live
throughput/latency status. Each script resets/cleans up after itself and asserts
the observed behaviour, exiting non-zero on failure.

| Script | Demonstrates |
|--------|--------------|
| `register_start_list_stop.sh` | The core lifecycle: `PUT /mockserver/loadScenario` (register) → `PUT /mockserver/loadScenario/start` → `GET /mockserver/loadScenario` (list, expect `RUNNING`) → `PUT /mockserver/loadScenario/stop`. Uses a single-step scenario with a RATE ramp → VU hold → PAUSE profile. |
| `multi_stage_profile.sh` | A realistic multi-stage, multi-step scenario (`checkout-load`): linear arrival-rate ramp 5→50 req/s, then a 25-VU hold, then a PAUSE; two Velocity-templated request steps (`browse`, `checkout`). Polls live status, stops all, then clears the registry. Posts the body from [`../../json/load_scenario/checkout_load.json`](../../json/load_scenario/checkout_load.json). |

## Prerequisites

- **MockServer started with load generation enabled** — this is required to
  *start* scenarios (registering is always allowed):

  ```bash
  java -Dmockserver.loadGenerationEnabled=true \
    -jar mockserver-netty-jar-with-dependencies.jar -serverPort 1080
  # or, for the Docker image:
  docker run -d -p 1080:1080 \
    -e MOCKSERVER_LOAD_GENERATION_ENABLED=true mockserver/mockserver
  ```

  Starting a scenario without this returns **HTTP 403**
  `{"error":"load generation not enabled (set loadGenerationEnabled=true)"}`.
- `curl` installed.
- Set `MOCKSERVER_URL` if not at the default (`http://localhost:1080`); the
  scripts also honour `MOCKSERVER_HOST` / `MOCKSERVER_PORT`.

## Run

```bash
export MOCKSERVER_URL=http://localhost:1080
./register_start_list_stop.sh
./multi_stage_profile.sh
```

## The REST API at a glance

| Method | Path | Body | Purpose |
|--------|------|------|---------|
| `PUT` | `/mockserver/loadScenario` | a `LoadScenario` | register / upsert (allowed even when disabled) → `{"status":"loaded","name":...,"state":"LOADED"}` |
| `GET` | `/mockserver/loadScenario` | — | list all → `{"scenarios":[ <status node>, ... ]}` |
| `GET` | `/mockserver/loadScenario/{name}` | — | one scenario's definition + live status; `404` if absent |
| `PUT` | `/mockserver/loadScenario/start` | `{"names":["a","b"]}` or `{"name":"a"}` | start one/many → `{"status":"started","started":[{"name":...,"state":"RUNNING"}]}` (`403` if disabled) |
| `PUT` | `/mockserver/loadScenario/stop` | `{"names":[...]}`, `{"all":true}`, or `{}` | stop one/many/all → `{"status":"stopped","stopped":[...]}` |
| `DELETE` | `/mockserver/loadScenario/{name}` | — | delete one → `{"status":"deleted"|"absent","name":...}` |
| `DELETE` | `/mockserver/loadScenario` | — | clear all → `{"status":"cleared"}` |

### LoadScenario body shape

```jsonc
{
  "name": "checkout-load",          // registry key (required, unique)
  "templateType": "VELOCITY",       // VELOCITY (default) or MUSTACHE — renders each step's request
  "maxRequests": 100000,            // optional hard cap on total requests
  "startDelayMillis": 0,            // optional delay before the run begins
  "labels": { "team": "payments" }, // optional metric labels
  "profile": {
    "stages": [                     // run in sequence
      { "type": "RATE", "startRate": 5, "endRate": 50, "durationMillis": 30000, "curve": "LINEAR", "maxVus": 50 },
      { "type": "VU",   "vus": 25, "durationMillis": 60000 },
      { "type": "PAUSE", "durationMillis": 10000 }
    ]
  },
  "steps": [                        // each iteration runs the steps in order
    { "name": "browse",   "request": { "method": "GET",  "path": "/products/$!iteration.index" },
      "thinkTime": { "timeUnit": "MILLISECONDS", "value": 500 } },
    { "name": "checkout", "request": { "method": "POST", "path": "/cart/checkout", "body": "{\"qty\":1}" } }
  ]
}
```

**Stage types:** `RATE` (open model — drives an arrival rate; hold with `rate`,
ramp with `startRate`/`endRate`, optionally cap concurrency with `maxVus`), `VU`
(closed model — drives concurrent virtual users; hold with `vus`, ramp with
`startVus`/`endVus`), and `PAUSE` (no load for `durationMillis`). `curve` is
`LINEAR` (default), `QUADRATIC`, or `EXPONENTIAL`.

> **Safety caps.** Stages are validated against the server's configurable
> ceilings — by default `maxVus`/`vus` ≤ **50**
> (`mockserver.loadGenerationMaxVirtualUsers`) and rate ≤ **500** req/s
> (`mockserver.loadGenerationMaxRequestsPerSecond`). Exceeding a cap returns
> `400` (e.g. `"stage[0].maxVus 100 exceeds the maximum of 50"`). Raise the
> relevant `mockserver.loadGeneration*` property if you need more.
