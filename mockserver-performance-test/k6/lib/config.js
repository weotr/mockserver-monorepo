// Shared k6 configuration for the MockServer performance suite.
//
// All tunables are environment-driven (k6 `-e KEY=value` or real env vars via
// `__ENV`) so the same scripts run locally, in the docker-compose stack, and in
// CI without edits. Defaults reproduce the historical Locust target
// (MockServer on localhost:1080, 4 seeded expectations, the request matching
// the last expectation).
//
// Connection target resolution (first match wins):
//   1. BASE_URL                      e.g. https://mockserver:1080
//   2. MOCKSERVER_PROTOCOL + MOCKSERVER_HOST   e.g. http + localhost:1080
//
// MOCKSERVER_HOST mirrors the Locust harness variable (host[:port]); a bare
// host gets :1080 appended.

function env(name, fallback) {
  const value = __ENV[name];
  return value === undefined || value === '' ? fallback : value;
}

function num(name, fallback) {
  const value = env(name, undefined);
  if (value === undefined) {
    return fallback;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function bool(name, fallback) {
  const value = env(name, undefined);
  if (value === undefined) {
    return fallback;
  }
  return ['1', 'true', 'yes', 'on'].includes(value.toLowerCase());
}

function resolveBaseUrl() {
  const explicit = env('BASE_URL', undefined);
  if (explicit) {
    return explicit.replace(/\/+$/, '');
  }
  const protocol = env('MOCKSERVER_PROTOCOL', 'http');
  let host = env('MOCKSERVER_HOST', 'localhost:1080');
  if (!host.includes(':')) {
    host = `${host}:1080`;
  }
  return `${protocol}://${host}`;
}

const baseUrl = resolveBaseUrl();

// A MockServer under test uses a self-signed CA, so HTTPS perf runs against a
// LOCAL/private instance legitimately need TLS verification skipped. Public
// targets should NOT silently skip verification — so the default is insecure
// only for loopback/private hosts, and explicit (INSECURE_SKIP_TLS_VERIFY) for
// anything else. This removes the "accidentally hit prod with verify off"
// footgun while keeping local HTTPS runs working out of the box.
function isLocalOrPrivateTarget(url) {
  const rawHost = url.replace(/^[a-z]+:\/\//i, '').split('/')[0];
  // Strip IPv6 bracket notation ([::1]:1080) before taking the host; otherwise
  // split(':')[0] would yield "[" and miss the loopback check.
  const host = (rawHost.startsWith('[') ? rawHost.slice(1, rawHost.indexOf(']')) : rawHost.split(':')[0]).toLowerCase();
  return (
    host === 'localhost' ||
    host === '127.0.0.1' ||
    host === '::1' ||
    host === 'host.docker.internal' ||
    host === 'mockserver' ||
    host.endsWith('.local') ||
    host.endsWith('.internal') ||
    /^10\./.test(host) ||
    /^192\.168\./.test(host) ||
    /^172\.(1[6-9]|2\d|3[01])\./.test(host)
  );
}

const insecureSkipTLSVerify = bool('INSECURE_SKIP_TLS_VERIFY', isLocalOrPrivateTarget(baseUrl));

// Never let insecure verification be silently active against a non-local host.
if (insecureSkipTLSVerify && baseUrl.startsWith('https') && !isLocalOrPrivateTarget(baseUrl)) {
  console.warn(`[k6] WARNING: TLS verification is DISABLED for non-local target ${baseUrl} (INSECURE_SKIP_TLS_VERIFY).`);
}

export const CONFIG = {
  baseUrl,
  // MockServer control plane is reachable with or without the /mockserver
  // prefix; use the canonical prefixed form (matches the dashboard + metrics).
  controlPlane: `${baseUrl}/mockserver`,
  // Keep-Alive headers preserved for parity with the Locust harness. k6 reuses
  // connections per VU by default, so these are belt-and-braces.
  keepAliveHeaders: {
    Connection: 'Keep-Alive',
    'Keep-Alive': 'timeout=120, max=1000',
  },
  insecureSkipTLSVerify,
};

// Load-shape tunables (used by the scenario files). Defaults are deliberately
// conservative so an accidental local run does not saturate a workstation.
export const LOAD = {
  // ramping-arrival-rate (load.js): requests/sec per stage.
  startRate: num('K6_START_RATE', 50),
  peakRate: num('K6_PEAK_RATE', 500),
  rampUp: env('K6_RAMP_UP', '30s'),
  hold: env('K6_HOLD', '1m'),
  rampDown: env('K6_RAMP_DOWN', '15s'),
  preAllocatedVUs: num('K6_PRE_VUS', 50),
  maxVUs: num('K6_MAX_VUS', 600),
  // create-expectation churn rate run alongside matching (control-plane load).
  createRate: num('K6_CREATE_RATE', 10),
  // stress.js peak target.
  stressPeakRate: num('K6_STRESS_PEAK_RATE', 5000),
  // soak.js sustained rate + duration.
  soakRate: num('K6_SOAK_RATE', 200),
  soakDuration: env('K6_SOAK_DURATION', '30m'),
};

// Threshold values become CI pass/fail gates. Tunable so a slow CI agent can
// relax them via env without editing scripts. p95/p99 are in milliseconds.
export const LIMITS = {
  p95: num('K6_P95_MS', 25),
  p99: num('K6_P99_MS', 100),
  errorRate: num('K6_MAX_ERROR_RATE', 0.01),
  checkRate: num('K6_MIN_CHECK_RATE', 0.99),
};

// Standard thresholds shared by the load/stress/soak scenarios. Global
// http_req_duration/http_req_failed gates plus a check-pass-rate gate.
export function baseThresholds() {
  return {
    http_req_failed: [`rate<${LIMITS.errorRate}`],
    http_req_duration: [`p(95)<${LIMITS.p95}`, `p(99)<${LIMITS.p99}`],
    checks: [`rate>${LIMITS.checkRate}`],
  };
}

// Regression scenario tunables (regression.js). Unlike load.js, the regression
// run offers a FIXED rate per behaviour (constant-arrival-rate) so the recorded
// number is "latency under fixed load" — the only thing comparable across runs
// when the goal is detecting a change, not finding peak throughput. A warmup
// window runs first (tagged op:warmup) so JIT/GC reach steady state before the
// measured window; only the measured window feeds the result JSON.
export const REGRESSION = {
  rate: num('K6_REG_RATE', 200), // offered req/s PER behaviour
  duration: env('K6_REG_DURATION', '2m'), // measured window
  warmup: env('K6_REG_WARMUP', '30s'), // pre-measurement warmup window
  preAllocatedVUs: num('K6_REG_PRE_VUS', 20),
  maxVUs: num('K6_REG_MAX_VUS', 200),
  // Transport label recorded in the result key (<op>_<proto>). Defaults from the
  // BASE_URL scheme; HTTPS auto-negotiates HTTP/2 with MockServer via ALPN, so
  // the https run is labelled https_h2 unless K6_HTTP2=false.
  proto: env('PROTO', baseUrl.startsWith('https') ? (bool('K6_HTTP2', true) ? 'https_h2' : 'https') : 'http'),
  // handleSummary() writes the machine-readable result here (mapped to a file by
  // k6); the perf-test-run.sh step merges the http + https_h2 runs into one JSON.
  resultPath: env('K6_RESULT_PATH', 'regression-result.json'),
};

// Resource-growth scenario tunables (growth.js). A sustained constant-load run
// whose purpose is to surface "X increases over time" regressions (e.g. issue
// #2329: O(n) log eviction once the request log fills to maxLogEntries). The
// rate is high enough to fill the DEFAULT 100k log within the run; low-rate
// latency probes at the start and end measure the latency slope, paired with the
// CPU/heap trajectory sampled by perf-test-run.sh. Do NOT shrink maxLogEntries
// for this run — a smaller log would never fill and would hide the bug.
export const GROWTH = {
  rate: num('K6_GROWTH_RATE', 800), // fill load (req/s) — fills 100k log in ~50s
  // Keep K6_GROWTH_DURATION >= 3 × K6_GROWTH_PROBE so the first/last probe
  // windows have a clear gap between them and the ratio measures a real slope.
  duration: env('K6_GROWTH_DURATION', '6m'),
  probeWindow: env('K6_GROWTH_PROBE', '30s'), // first/last latency-probe window
  probeRate: num('K6_GROWTH_PROBE_RATE', 20),
  preAllocatedVUs: num('K6_GROWTH_PRE_VUS', 50),
  maxVUs: num('K6_GROWTH_MAX_VUS', 400),
  resultPath: env('K6_GROWTH_RESULT_PATH', 'growth-result.json'),
};

// Forward/proxy behaviour target. The forward action routes /forward to a
// DEDICATED upstream (a second MockServer) so the forward latency is not
// contaminated by the matching load on the instance under measurement. Set
// K6_FORWARD_SELF=true to keep the legacy self-forward (127.0.0.1:1080) for a
// quick single-container local smoke.
export const FORWARD = {
  upstreamHost: env('FORWARD_UPSTREAM_HOST', 'mockserver-upstream:1080'),
  forwardSelf: bool('K6_FORWARD_SELF', false),
};

export { env, num, bool };
