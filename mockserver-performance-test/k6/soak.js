// Soak test — sustained moderate load over a long duration to surface slow
// degradation: memory growth from the event log / expectation churn, GC
// pressure, file-descriptor or connection leaks. Pair with the docker-compose
// stack (Part D) so Grafana shows JVM heap/GC trends across the soak.
//
//   k6 run mockserver-performance-test/k6/soak.js
//   k6 run -e K6_SOAK_DURATION=2h -e K6_SOAK_RATE=400 .../soak.js
//
import { CONFIG, LOAD, LIMITS, baseThresholds } from './lib/config.js';
import {
  seedExpectations,
  resetMockServer,
  getSimple,
  createSimpleExpectation,
} from './lib/expectations.js';

export const options = {
  insecureSkipTLSVerify: CONFIG.insecureSkipTLSVerify,
  scenarios: {
    // Steady data-plane matching for the whole soak.
    match: {
      executor: 'constant-arrival-rate',
      exec: 'match',
      rate: LOAD.soakRate,
      timeUnit: '1s',
      duration: LOAD.soakDuration,
      preAllocatedVUs: LOAD.preAllocatedVUs,
      maxVUs: LOAD.maxVUs,
    },
    // Continuous control-plane churn — this is what grows the event log over
    // time, so it is the interesting signal for a memory soak.
    create: {
      executor: 'constant-arrival-rate',
      exec: 'create',
      rate: LOAD.createRate,
      timeUnit: '1s',
      duration: LOAD.soakDuration,
      preAllocatedVUs: 5,
      maxVUs: 50,
    },
  },
  thresholds: {
    ...baseThresholds(),
    // On a soak, the p99 drifting up over time is the failure signal.
    'http_req_duration{op:match}': [`p(99)<${LIMITS.p99}`],
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
