// Expectation seeding + the per-request actions exercised by the scenarios.
//
// Parity with the historical Locust harness:
//   - the same 4 expectations are seeded (the request matches the LAST one, so
//     the matcher does a near-full scan — the realistic worst case)
//   - the `match` action GETs /simple (data-plane hot path)
//   - the `create` action PUTs a /simple expectation with remainingTimes:5
//     (control-plane churn, exactly as the Locust `expectation` task did)
//   - the `forward` action GETs /forward (proxy/override path)
//
// New scenarios add a large-body match and a regex-matcher workload to exercise
// the body-decode and regex paths called out in the performance plan.

import http from 'k6/http';
import { check, fail } from 'k6';
import { CONFIG } from './config.js';

// The 4 seeded expectations — byte-for-byte the same shapes as the legacy
// expectations.json so recorded baselines remain comparable.
export const SEED_EXPECTATIONS = [
  {
    httpRequest: { path: '/not_simple' },
    httpResponse: { statusCode: 200, body: 'some not simple response' },
    times: { unlimited: true },
  },
  {
    httpRequest: { method: 'POST', path: '/simple' },
    httpResponse: { statusCode: 200, body: 'some simple POST response' },
    times: { unlimited: true },
  },
  {
    httpRequest: { path: '/forward' },
    httpOverrideForwardedRequest: {
      httpRequest: { headers: { host: ['127.0.0.1:1080'] }, path: '/simple' },
    },
    times: { unlimited: true },
  },
  {
    httpRequest: { path: '/simple' },
    httpResponse: { statusCode: 200, body: 'some simple response' },
    times: { unlimited: true },
  },
];

// A ~4 KB JSON expectation + matching request body to exercise the body-decode
// and JSON-match path (BodyDecoderEncoder / JsonStringMatcher in the plan).
const LARGE_BODY = JSON.stringify({
  items: Array.from({ length: 50 }, (_, i) => ({
    id: i,
    name: `item-${i}`,
    tags: ['alpha', 'beta', 'gamma'],
    nested: { value: i * 7, label: `label-${i}` },
  })),
});

export const LARGE_BODY_EXPECTATION = {
  httpRequest: {
    method: 'POST',
    path: '/large',
    body: { type: 'JSON', json: LARGE_BODY, matchType: 'ONLY_MATCHING_FIELDS' },
  },
  httpResponse: { statusCode: 200, body: 'matched large body' },
  times: { unlimited: true },
};

// A regex path matcher to exercise the RegexStringMatcher / timeout-executor
// path (plan A1.3).
export const REGEX_EXPECTATION = {
  httpRequest: { path: '/regex/[a-z0-9]+/resource' },
  httpResponse: { statusCode: 200, body: 'matched regex' },
  times: { unlimited: true },
};

function jsonParams(extraTags) {
  return {
    headers: { 'Content-Type': 'application/json', ...CONFIG.keepAliveHeaders },
    tags: extraTags || {},
  };
}

// --- lifecycle -------------------------------------------------------------

// Seed the base expectations. Called from each scenario's setup(). Fails the
// run loudly if MockServer is unreachable or rejects the expectations.
export function seedExpectations(extra = []) {
  const payload = JSON.stringify([...SEED_EXPECTATIONS, ...extra]);
  const res = http.put(`${CONFIG.controlPlane}/expectation`, payload, jsonParams());
  if (res.status !== 201 && res.status !== 200) {
    fail(`failed to seed expectations: HTTP ${res.status} ${res.body}`);
  }
  return res;
}

export function resetMockServer() {
  return http.put(`${CONFIG.controlPlane}/reset`, null, jsonParams());
}

// --- actions (tagged so k6 reports per-operation) --------------------------

const SIMPLE_EXPECTATION = JSON.stringify([
  {
    httpRequest: { path: '/simple' },
    httpResponse: { statusCode: 200, body: 'some simple response' },
    times: { remainingTimes: 5 },
  },
]);

export function createSimpleExpectation() {
  const res = http.put(
    `${CONFIG.controlPlane}/expectation`,
    SIMPLE_EXPECTATION,
    jsonParams({ op: 'create', name: 'PUT /mockserver/expectation' }),
  );
  check(res, { 'create: 201': (r) => r.status === 201 });
  return res;
}

export function getSimple() {
  const res = http.get(`${CONFIG.baseUrl}/simple`, {
    headers: CONFIG.keepAliveHeaders,
    tags: { op: 'match', name: 'GET /simple' },
  });
  check(res, { 'match: 200': (r) => r.status === 200 });
  return res;
}

export function getForward() {
  const res = http.get(`${CONFIG.baseUrl}/forward`, {
    headers: CONFIG.keepAliveHeaders,
    tags: { op: 'forward', name: 'GET /forward' },
  });
  check(res, { 'forward: 200': (r) => r.status === 200 });
  return res;
}

export function postLargeBody() {
  const res = http.post(`${CONFIG.baseUrl}/large`, LARGE_BODY, jsonParams({ op: 'large', name: 'POST /large' }));
  check(res, { 'large: 200': (r) => r.status === 200 });
  return res;
}

export function getRegex() {
  const res = http.get(`${CONFIG.baseUrl}/regex/abc123/resource`, {
    headers: CONFIG.keepAliveHeaders,
    tags: { op: 'regex', name: 'GET /regex/:id/resource' },
  });
  check(res, { 'regex: 200': (r) => r.status === 200 });
  return res;
}
