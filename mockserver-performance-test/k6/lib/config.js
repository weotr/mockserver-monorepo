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

export { env, num, bool };
