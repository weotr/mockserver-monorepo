// Stress test — push the data-plane match path well past the load-test peak to
// find the breaking point (where latency/error-rate degrade sharply).
//
// Exploratory by design: no hard latency gate (we WANT to find where it breaks),
// but the run aborts if the error rate climbs past the configured ceiling so a
// genuinely broken build fails fast rather than hammering for the full ramp.
//
//   k6 run mockserver-performance-test/k6/stress.js
//   k6 run -e K6_STRESS_PEAK_RATE=10000 .../stress.js
//
import { CONFIG, LOAD, LIMITS } from './lib/config.js';
import { seedExpectations, resetMockServer, getSimple } from './lib/expectations.js';

export const options = {
  insecureSkipTLSVerify: CONFIG.insecureSkipTLSVerify,
  scenarios: {
    stress: {
      executor: 'ramping-arrival-rate',
      exec: 'match',
      startRate: LOAD.startRate,
      timeUnit: '1s',
      preAllocatedVUs: LOAD.preAllocatedVUs,
      maxVUs: LOAD.maxVUs,
      stages: [
        { target: LOAD.peakRate, duration: '30s' },
        { target: LOAD.stressPeakRate, duration: '2m' },
        { target: LOAD.stressPeakRate, duration: '1m' },
        { target: 0, duration: '30s' },
      ],
    },
  },
  thresholds: {
    // Abort the whole run if errors exceed the ceiling — a broken build, not a
    // capacity limit. Latency is intentionally ungated here.
    http_req_failed: [{ threshold: `rate<${LIMITS.errorRate}`, abortOnFail: true, delayAbortEval: '30s' }],
  },
};

export function setup() {
  seedExpectations();
}

export function match() {
  getSimple();
}

export function teardown() {
  resetMockServer();
}
