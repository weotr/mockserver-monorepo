# Load-Injection Measurement Harness

Drive **N MockServer instances as HTTP load _generators_** at a single **Envoy
`direct_response` sink** and measure four things:

1. **Ceiling (simple)** — how much load *one* MockServer instance can inject
   before it saturates (offered-vs-achieved rps as the offered rate climbs a
   stair), using a trivial GET. This runs on a **fat injector** (pinned to ~8
   cores) with a **fine stair** so it finds the real CPU-bound ceiling with a
   clean plateau, not an open-model overshoot/collapse.
1b. **Ceiling (complex)** — the **same** fat injector and stair, but a
   deliberately heavier request: a POST with a 5-row feeder and a ~1 KB
   Velocity-templated JSON body (32 interpolations) so the template engine does
   real work per request. Kept to **one request per iteration** so achieved
   requests/sec still equals the arrival rate and the two ceilings are directly
   comparable. With `ceiling_rps` + `injector_cpu_pct` from both JSONs you get an
   **empirical rps/core data point**: `simple = R/core`, `complex = R'/core`, and
   the per-request cost factor ≈ `R/R'` (complex is lower — each request is
   pricier).
1c. **Per-core sweep** — pin **one** injector to exactly C cores for each C ∈
   {1, 2, 4, 8}, run the SIMPLE-GET stair, and record the highest clean ceiling,
   the injector CPU% there, and `rps_per_core = ceiling_rps / C`. This **measures**
   the efficiency curve directly instead of inferring it from a single core count:
   a single injector's dispatch path plateaus well before 8 cores saturate, so
   **`rps_per_core` falls as C grows** (the diminishing-returns story). `injector_cpu_pct`
   (docker-stats percent-of-ONE-core, so 195% ≈ 1.95 cores) shows whether each C
   actually CPU-saturates: ≈ C×100% at small C, well under at large C.
2. **Scaling** — how aggregate injected throughput grows as instances are added
   (1 → 2 → 4 → 6), and the per-instance breakdown. This runs on **lean
   injectors** (2 cores each), each driven at **~80% of the measured lean 2-core
   ceiling** — a rate **derived at runtime** by a quick lean probe, not
   hardcoded — so each instance is comfortably CPU-bound but never collapsing.

The phases run **sequentially** (never concurrently), so the fat-ceiling /
per-core injector and the lean-scaling injectors reuse the same cores without
contention.

The Envoy sink answers every request with `200 OK` from its own event loop — it
has **no upstream**, so it absorbs far more than the injectors can produce. That
makes the **injector the provable bottleneck**, which is the whole point: any
plateau is MockServer's injection ceiling, not the target's serving limit.

A small **Lua filter drains any request body before `direct_response`**. Envoy's
`direct_response` path otherwise leaves a POST body unconsumed and sends
`connection: close` to avoid HTTP desync — which, under keep-alive load, churns
the injector's connection pool and floods `kind="connection"` errors, making the
complex (POST-with-body) ceiling collapse to a meaningless near-zero. Draining
the body keeps the connection alive; bodyless requests (the simple GET ceiling)
skip the buffering, so the sink stays cheap for them.

### Connection reuse — why the ceiling phases give Envoy headroom and assert reuse

The measured ceiling is only meaningful if it reflects **request dispatch**, not
**connection setup**. MockServer's forward HTTP client pools only *idle*
keep-alive connections, so it can only reuse a connection that is momentarily idle
between responses. For **trivial high-rate requests** (a bodyless GET to this
sink completes in tens of microseconds) the dispatch concurrency needed by
Little's Law climbs until connections are essentially never idle — so `acquire`
finds the pool empty and the client opens a **fresh socket per request**
(connection *churn*). That churn spikes injector CPU on connection setup and, under
the open arrival model, tips into an overshoot/error collapse. A heavier request
(the ~1 KB templated POST) paces dispatch enough that connections stay reusable,
so it does **not** churn. (Root cause was the forward client's idle-only pool;
MockServer core now ships an opt-in **keep-warm** forward pool that retains a
standing warm set sized to demand — see the image requirement below.)

Three mitigations keep the ceiling honest:

1. **Keep-warm forward pool on the injectors.** The injectors set
   `MOCKSERVER_FORWARD_CONNECTION_POOL_KEEP_ALIVE=true` (bounded by
   `MOCKSERVER_FORWARD_CONNECTION_POOL_MAX_TOTAL_PER_KEY`), so the forward client
   keeps a warm set of connections instead of only pooling momentarily-idle ones —
   the trivial high-rate GET reuses connections instead of churning.
2. **Envoy headroom in the ceiling phases.** Both ceiling phases give Envoy **6
   cores** (`ENVOY_CPUS=2-7`, `--concurrency 6`) and the fat injector **8 cores**
   (`I1_CPUS=8-15`), so Envoy returns responses fast (shorter in-flight time → less
   pressure on the pool) and is never itself the bottleneck. (CI #71 measured the
   complex ceiling Envoy-bound at `envoy_cpu 113%` on a 2-core Envoy — this split
   fixes that.) The **scaling** phase is unchanged (Envoy on `0-1`).
3. **A connection-reuse assertion.** Each ceiling point records
   `reqs_per_connection` = Envoy `downstream_rq_total` rate ÷ `downstream_cx_total`
   rate. A healthy pool reuses each connection for tens-to-hundreds of requests; a
   value below `REUSE_MIN` (default 4) means the injector is churning, so the point
   is flagged with a loud WARNING and **excluded from `ceiling_rps`** — exactly like
   the throttle/error assertions. The reported ceiling is therefore the highest rate
   the injector sustained while *reusing* connections, never a churn-corrupted
   number.

> **Image requirement — keep-warm needs a fresh snapshot.** The keep-warm flag
> (`MOCKSERVER_FORWARD_CONNECTION_POOL_KEEP_ALIVE`) only works on a
> `mockserver/mockserver:mockserver-snapshot` image **built at or after core commit
> `142cbc778`** ("opt-in keep-warm forward connection pool"). An **older snapshot
> silently ignores** the flag: the simple-GET injector churns connections, the
> reuse assertion (correctly) excludes every mid-ladder point, and `ceiling_rps`
> collapses to a near-floor value — wasting a paid ~60-min CI run. **Before a CI
> run, pull a fresh snapshot** (`docker pull mockserver/mockserver:mockserver-snapshot`)
> or rebuild it from a master checkout that includes `142cbc778`. `run-inject.sh`
> self-diagnoses this: if the reuse assertion is active and **zero** ceiling points
> pass it, the ceiling phase prints a loud WARNING naming a stale image as the
> likely cause.

```mermaid
flowchart LR
  subgraph injectors["MockServer injectors (load generators)"]
    I1["injector-1"]
    I2["injector-2"]
    IN["injector-N ..."]
  end
  I1 --> E["Envoy\ndirect_response 200 OK\n(near-infinite sink)"]
  I2 --> E
  IN --> E
  P["Prometheus\nscrapes both sides"] -.-> injectors
  P -.-> E
  R["run-inject.sh\noffered vs achieved,\nper-instance by run_id,\nEnvoy received"] -.-> P
```

## Quick start (local)

Requires Docker, `jq`, and `curl` on the host.

```bash
cd mockserver-performance-test/stack/inject

# Bring up the sink + 2 injectors + Prometheus (profile selects how many injectors)
MOCKSERVER_IMAGE=mockserver/mockserver:mockserver-snapshot \
  docker compose -f docker-compose.inject.yml --profile n2 up -d

# ...or just let run-inject.sh manage compose up/down for you:
./run-inject.sh ceiling          # 1 injector, simple GET stair  -> inject-ceiling.json
./run-inject.sh ceiling-complex  # 1 injector, templated POST    -> inject-ceiling-complex.json
./run-inject.sh percore          # 1 injector at C=1,2,4,8 cores -> inject-percore.json
./run-inject.sh scale 1 2        # aggregate rps at N=1 and N=2   -> inject-scale.json

# short laptop smoke of the per-core sweep (fewer cores, short stair):
PERCORE_CORES="1 2" PERCORE_INJ_CPU0=2 PERCORE_ENVOY_CPUS=8-13 \
  PERCORE_OFFERED="2000 4000 6000 8000" PERCORE_HOLD_S=14 PERCORE_SETTLE_S=10 \
  RATE_WINDOW=8s ./run-inject.sh percore

# Tear everything down
docker compose -f docker-compose.inject.yml \
  --profile n1 --profile n2 --profile n4 --profile n6 down -v
```

`run-inject.sh` brings the stack up/down itself, so you normally only run the
two sub-commands. For a short laptop-safe smoke, shorten the holds/ladder (keep
`CEILING_OFFERED` in step with the stage rates in the ceiling scenario file):

```bash
# ceiling: fat-ish injector, finer SHORT stair
ENVOY_CPUS=0-1 I1_CPUS=2-7 CEILING_OFFERED="2000 4000 6000 8000" \
  CEILING_HOLD_S=14 CEILING_SETTLE_S=10 RATE_WINDOW=8s ./run-inject.sh ceiling

# scale: lean injectors + short lean probe (PROBE_*); or force the rate and skip
# the probe entirely with SCALE_PER_INSTANCE_RPS
ENVOY_CPUS=0-1 I1_CPUS=2-3 I2_CPUS=4-5 \
  PROBE_OFFERED="2000 3000 4000" PROBE_HOLD_S=12 PROBE_SETTLE_S=8 \
  SCALE_HOLD_S=20 SCALE_SETTLE_S=12 RATE_WINDOW=8s ./run-inject.sh scale 1 2
```

| Profile | Injectors up |
|---------|--------------|
| `n1`    | injector-1 |
| `n2`    | injector-1, 2 |
| `n4`    | injector-1, 2, 3, 4 |
| `n6`    | injector-1 … 6 |

| Service | Host URL | Notes |
|---------|----------|-------|
| Envoy sink | `http://localhost:8000` | every request → `200 OK` |
| Envoy admin / stats | `http://localhost:9901` | `/ready`, `/stats/prometheus` |
| injector-K | `http://localhost:108K` | each injector's `1080` published as `1080+K` |
| Prometheus | `http://localhost:9090` | scrapes injectors + Envoy every 2s |

## What each measurement means

`inject-ceiling.json` (one injector, RATE stair):

- `offered_rps` — the arrival rate the RATE stage asked for.
- `achieved_rps` — `sum(rate(mock_server_load_requests_total[15s]))` — what the
  injector actually dispatched. While `achieved ≈ offered`, you are below the
  ceiling.
- `throttled_rps` — `mock_server_load_throttled_total` rate. **Must be ~0.** If
  it climbs, a cap is binding (the run prints a loud WARNING) and the point is
  not trustworthy.
- `error_rate` — connection/timeout errors as a fraction. Must be ~0.
- `injector_cpu_pct` / `envoy_cpu_pct` — `docker stats` snapshots. At the
  ceiling the injector should be CPU-bound (~100% of its pin) while Envoy has
  headroom.
- `target_received_rps` — Envoy's `envoy_http_downstream_rq_completed` rate;
  should track `achieved_rps` (proof the injected requests really landed).
- `reqs_per_connection` — Envoy `downstream_rq_total` rate ÷ `downstream_cx_total`
  rate. High (tens–hundreds) means the injector is **reusing** its keep-alive pool;
  near 1 means **connection churn** (a fresh socket per request — see "Connection
  reuse"). Below `REUSE_MIN` (default 4) the point is flagged and excluded from
  `ceiling_rps`.
- `ceiling_rps` — the highest offered rate the injector achieved **cleanly**
  (achieved within 10% of offered, no throttle, no errors, **and reusing
  connections**). With the fine stair this lands on a clean plateau just below where
  CPU saturates, rather than the last point before an overshoot/churn collapse.
- `scenario` — which scenario produced this file (`inject-ceiling` for the simple
  GET, `inject-ceiling-complex` for the templated POST).

`inject-ceiling-complex.json` has the **same contract** as `inject-ceiling.json`
(same `points[]` fields, `ceiling_rps`, `ceiling_evidence`) but is produced from
the heavier templated-POST scenario. To get the per-request cost factor, compare
**rps per core** at each ceiling:
`rps_per_core = ceiling_rps / (injector_cpu_pct / 100)` (the evidence block's
`injector_cpu_pct` is the CPU% of the injector's pin at the ceiling point). The
complex scenario's rps/core is lower; `factor ≈ rps_per_core_simple /
rps_per_core_complex` is roughly how much more CPU each templated request costs.

`inject-percore.json` (per-core sweep, simple GET) — one `points[]` entry per core
count C:

- `cores` — how many cores the single injector was pinned to for this point.
- `ceiling_rps` — the highest CLEAN sustained ceiling at C cores (same clean-gate:
  achieved ≥ 0.9 × offered, throttle ≈ 0, errors ≈ 0, reuse ≥ `REUSE_MIN`).
- `injector_cpu_pct` — docker-stats **percent-of-ONE-core** at that point (195% ≈
  1.95 cores). At small C it should be ≈ C×100% (CPU-saturated); at large C it
  won't reach C×100% — the dispatch path, not CPU, is the limit (the whole point).
- `reqs_per_connection` — connection reuse at the ceiling (high = healthy).
- `rps_per_core` — `ceiling_rps / cores`. **Falls as C grows** because a single
  injector plateaus before more cores help — publish this as the efficiency curve
  instead of inferring rps/core from a single 2-core-vs-8-core comparison.

`inject-scale.json` (sweep over N):

- `per_instance_offered_rps` — the rate each lean injector was driven at. This is
  **derived at runtime**: before the N-sweep, `run-inject.sh` runs a quick lean
  2-core ceiling probe to find the lean ceiling C, then sets this to
  `floor(0.8 × C)` (clamped to a sane minimum). It is **not** hardcoded — driving
  a lean 2-core injector far past its ~4–5k ceiling (the old hardcoded 30k) makes
  every instance collapse into throttle + high error rate, which is exactly the
  invalid run this harness now avoids.
- `aggregate_rps` — total injected rps across all instances.
- `per_instance_rps` — `sum by (run_id)(rate(...))`; one entry per instance
  (`run_id` is a fresh UUID per trigger, so co-scraped instances stay distinct).
- `target_received_rps` — Envoy received; cross-checked against `aggregate_rps`.
- `scaling_efficiency.efficiency_pct` — `actual_at_max / (per_instance_offered ×
  instances)`. 100% means clean linear scaling (each added instance adds a full
  instance's worth of throughput).

## Caps — why they are raised

MockServer's load generator self-throttles to protect the server. The defaults
(`maxRequestsPerSecond=500`, `maxRate=500`, 50 VUs) would silently cap every
injector at ~500 rps and inflate `mock_server_load_throttled{reason="rate_limit"}`.
The compose file raises every cap **well above** any scenario peak so the cap
never binds; `run-inject.sh` then **asserts** `throttled ≈ 0` before trusting a
number. The raised caps (per injector) are:

```
MOCKSERVER_LOAD_GENERATION_MAX_REQUESTS_PER_SECOND=200000
MOCKSERVER_LOAD_GENERATION_MAX_RATE=200000
MOCKSERVER_LOAD_GENERATION_MAX_IN_FLIGHT_REQUESTS=20000
MOCKSERVER_LOAD_GENERATION_MAX_VIRTUAL_USERS=4000
MOCKSERVER_LOAD_GENERATION_MAX_DURATION_MILLIS=1800000
```

(The peak rate is validated **up front** at register/trigger time — if a
scenario's peak exceeds a cap the trigger is rejected — so the caps must be
raised *before* the scenario is registered, which the compose env does.)

## Measurement method

Rates are computed with the **Prometheus HTTP API** (`/api/v1/query`):

- aggregate: `sum(rate(mock_server_load_requests_total{scenario="X"}[15s]))`
- per-instance: `sum by (run_id)(rate(mock_server_load_requests_total{scenario="X"}[15s]))`
- Envoy received: `sum(rate(envoy_http_downstream_rq_completed{envoy_http_conn_manager_prefix="ingress_http"}[15s]))`

Each point is sampled **mid-way through a held stage** so the 15s `rate()` window
sits entirely inside the steady portion of the hold. CPU% comes from `docker
stats`.

## Honest note — there is no built-in cross-node coordinator

MockServer does **not** ship a distributed load-generation coordinator. Each
injector runs its own independent Load Scenario; this harness orchestrates the
**launch** (docker compose profiles) and the **aggregation** (Prometheus scrape +
`sum`/`sum by (run_id)`) **externally**. The per-instance `run_id` label is what
makes co-scraped instances separable. "Aggregate injected rps" is therefore a
measured external sum, not a single coordinated run.

## CI

`.buildkite/scripts/steps/perf-test-inject.sh` runs this on the pinned perf box
and uploads `inject-ceiling.json` + `inject-ceiling-complex.json` +
`inject-percore.json` + `inject-scale.json` as Buildkite artifacts. The phases run
**sequentially** and pin **differently**:

- **Ceiling (simple)** — **wide Envoy on `2-7`** (6 cores, `--concurrency 6`) + a
  **fat** injector on `8-15` (8 cores), fine stair, simple GET — the per-instance
  CPU-bound ceiling on a clean plateau. The wide Envoy + the reuse assertion keep
  the ceiling injector-bound and free of connection-churn artifacts.
- **Ceiling (complex)** — same wide-Envoy + fat-injector split + stair, templated
  POST — the comparable rps/core data point.
- **Per-core** (`C ∈ {1,2,4,8}`) — one injector pinned to **C cores** (its first C
  of cores `2-9`) with **Envoy on a disjoint `10-15`** (6 cores), simple GET — the
  measured rps/core efficiency curve.
- **Scale** (`N ∈ {1,2,4,6}`) — Envoy on `0-1`, **lean** injectors 2 cores each
  (`2-3 … 12-13`), each driven at the runtime-derived ~80%-of-lean-ceiling rate
  (left unchanged — the scaling phase is already clean).

**Timeout headroom:** the per-core sweep adds ~4 short stairs (~10–12 min). The
full step — two ceilings (~10 min) + per-core (~10–12 min) + scale (~8–12 min) —
totals ~30–35 min, comfortably inside the **60-min** step timeout. Bump the
pipeline step timeout only if a slower agent pushes a run past ~50 min. The C-list
is overridable via `PERF_INJECT_PERCORE` (e.g. `"1 2 4"`) to trim time.

It is **opt-in** — dispatched by `perf-test-guard.sh` only when `PERF_INJECT=true`
(build env) or the build message contains `[perf-inject]` — so it stays off the
lean daily regression build.
```
