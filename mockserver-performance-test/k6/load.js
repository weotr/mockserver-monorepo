// Load test — the primary gated scenario. Closed-loop (arrival-rate) load so
// the offered RPS is controlled regardless of server latency, which makes
// results comparable across runs and releases.
//
// Two scenarios run concurrently, mirroring the legacy Locust mix:
//   - match  : data-plane hot path (GET /simple), ramping arrival rate
//   - create : control-plane churn (PUT expectation, remainingTimes:5)
//
// Thresholds are CI pass/fail gates (tune via K6_P95_MS / K6_P99_MS /
// K6_MAX_ERROR_RATE, or shape via K6_PEAK_RATE etc — see lib/config.js).
//
//   k6 run mockserver-performance-test/k6/load.js
//   k6 run -e K6_PEAK_RATE=1000 -e BASE_URL=https://localhost:1080 .../load.js
//
import { CONFIG, LOAD, LIMITS, baseThresholds } from './lib/config.js';
import {
  seedExpectations,
  resetMockServer,
  getSimple,
  createSimpleExpectation,
} from './lib/expectations.js';

// Sum a k6 duration string to seconds, supporting compound forms (e.g.
// "1m30s", "2h30m", "500ms"). Throws loudly on an unparseable value rather
// than silently under-counting (which would cut the create scenario short).
function toSeconds(d) {
  const str = String(d).trim();
  const tokenRe = /(\d+)(ms|s|m|h)/g;
  let total = 0;
  let matched = false;
  let token;
  while ((token = tokenRe.exec(str)) !== null) {
    matched = true;
    const value = Number(token[1]);
    switch (token[2]) {
      case 'ms':
        total += value / 1000;
        break;
      case 's':
        total += value;
        break;
      case 'm':
        total += value * 60;
        break;
      case 'h':
        total += value * 3600;
        break;
    }
  }
  if (!matched) {
    throw new Error(`toSeconds: cannot parse duration "${d}" (expected forms like 30s, 1m, 1m30s, 500ms)`);
  }
  return total;
}

// constant-arrival-rate needs a single duration; run create for the whole test
// (the match scenario's ramp-up + hold + ramp-down wall-clock).
const TOTAL_SECONDS = [LOAD.rampUp, LOAD.hold, LOAD.rampDown].reduce((sum, p) => sum + toSeconds(p), 0);

export const options = {
  insecureSkipTLSVerify: CONFIG.insecureSkipTLSVerify,
  scenarios: {
    match: {
      executor: 'ramping-arrival-rate',
      exec: 'match',
      startRate: LOAD.startRate,
      timeUnit: '1s',
      preAllocatedVUs: LOAD.preAllocatedVUs,
      maxVUs: LOAD.maxVUs,
      stages: [
        { target: LOAD.peakRate, duration: LOAD.rampUp },
        { target: LOAD.peakRate, duration: LOAD.hold },
        { target: 0, duration: LOAD.rampDown },
      ],
    },
    create: {
      executor: 'constant-arrival-rate',
      exec: 'create',
      rate: LOAD.createRate,
      timeUnit: '1s',
      duration: `${TOTAL_SECONDS}s`,
      preAllocatedVUs: 5,
      maxVUs: 50,
    },
  },
  thresholds: {
    ...baseThresholds(),
    // Data-plane match path is the headline number — gate it explicitly.
    'http_req_duration{op:match}': [`p(95)<${LIMITS.p95}`, `p(99)<${LIMITS.p99}`],
    'http_req_failed{op:match}': [`rate<${LIMITS.errorRate}`],
  },
};

export function setup() {
  seedExpectations();
}

export function match() {
  getSimple();
}

export function create() {
  createSimpleExpectation();
}

export function teardown() {
  resetMockServer();
}
