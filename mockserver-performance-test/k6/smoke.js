// Smoke test — minimal load, exercises every action once per iteration to
// prove the harness + MockServer wiring works. Fast enough for a pre-load
// sanity check (and a real, if tiny, end-to-end run in CI when a MockServer is
// available). Not a performance gate.
//
//   k6 run mockserver-performance-test/k6/smoke.js
//
import { sleep } from 'k6';
import { CONFIG, LIMITS } from './lib/config.js';
import {
  seedExpectations,
  resetMockServer,
  LARGE_BODY_EXPECTATION,
  REGEX_EXPECTATION,
  getSimple,
  createSimpleExpectation,
  getForward,
  postLargeBody,
  getRegex,
} from './lib/expectations.js';

export const options = {
  insecureSkipTLSVerify: CONFIG.insecureSkipTLSVerify,
  vus: 1,
  iterations: 5,
  // Smoke is a wiring check, not a latency gate: assert correctness only
  // (every action returns the right status, no transport errors). Latency
  // percentiles are intentionally NOT gated here — a cold-start JVM produces
  // outliers on the first few requests, which belong to load.js's gates.
  thresholds: {
    http_req_failed: [`rate<${LIMITS.errorRate}`],
    checks: [`rate>${LIMITS.checkRate}`],
  },
};

export function setup() {
  seedExpectations([LARGE_BODY_EXPECTATION, REGEX_EXPECTATION]);
}

export default function () {
  getSimple();
  createSimpleExpectation();
  getForward();
  postLargeBody();
  getRegex();
  sleep(0.1);
}

export function teardown() {
  resetMockServer();
}
