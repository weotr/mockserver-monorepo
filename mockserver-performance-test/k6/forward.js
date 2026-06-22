// Forward-path load test — a dedicated REGRESSION GUARD for the upstream
// connection-pool default (`mockserver.forwardConnectionPoolEnabled`, default
// true). Where load.js exercises the CONTROL PLANE / match hot path, this
// scenario hammers the OUTBOUND/FORWARD path, because that is where a pooling
// regression shows up: without pooling, every forwarded request opens a fresh
// upstream TCP connection, and under sustained high rate the host exhausts
// ephemeral ports → BindException → request failures. With pooling on (the
// shipped default) idle keep-alive upstream connections are reused, so the
// error rate stays ~0 at the same rate.
//
// THE ERROR-RATE THRESHOLD IS THE GUARD. If pooling ever regresses to
// per-request connections, this run fails on BindException-driven errors at
// peak rate. The latency gates are secondary (forward adds an upstream hop).
//
// ---------------------------------------------------------------------------
// Topology — SUT forwards to a SEPARATE loopback upstream MockServer:
//
//   k6  ──HTTP──▶  SUT MockServer (:1080)  ──forward──▶  upstream MockServer (:1090)
//                  matches /forward                       answers /simple → 200
//
//   - SUT (:1080): the instance under measurement; built from THIS worktree so
//     the pool default under test is the one being exercised. Seeded with one
//     /forward expectation (httpOverrideForwardedRequest → upstream /simple).
//   - upstream (:1090): a plain second MockServer answering /simple with 200.
//     A separate instance keeps the upstream's own matching load from
//     contaminating the SUT's forward latency, and gives the SUT real outbound
//     sockets to pool/exhaust.
//
// Single-container alternative (quick local smoke, NOT the regression run):
// set K6_FORWARD_SELF=true to loop /forward back to the SUT's own /simple on
// 127.0.0.1:1080. This still opens outbound sockets, but mixes inbound+outbound
// load on one instance — use the two-instance topology for the real guard.
//
// ---------------------------------------------------------------------------
// Run (two-instance topology):
//
//   # 1. upstream MockServer on :1090 answering /simple → 200
//   java -jar mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar \
//        -serverPort 1090 >/tmp/upstream.log 2>&1 &
//   curl -s -XPUT 'http://localhost:1090/mockserver/expectation' -d \
//     '[{"httpRequest":{"path":"/simple"},"httpResponse":{"statusCode":200,"body":"some simple response"},"times":{"unlimited":true}}]'
//
//   # 2. SUT MockServer on :1080 (pool default = ON; this is the guarded path)
//   java -jar mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar \
//        -serverPort 1080 >/tmp/sut.log 2>&1 &
//
//   # 3. run the guard — SUT forwards to the upstream on :1090
//   k6 run -e FORWARD_UPSTREAM_HOST=127.0.0.1:1090 \
//          mockserver-performance-test/k6/forward.js
//
// Demonstrate the guard CATCHES a regression (force pooling OFF on the SUT and
// re-run — the error-rate gate should trip at peak):
//
//   java -Dmockserver.forwardConnectionPoolEnabled=false -jar \
//        mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar \
//        -serverPort 1080 ...
//
import { CONFIG, FORWARD_LOAD, LIMITS } from './lib/config.js';
import { seedForward, resetMockServer, getForward } from './lib/expectations.js';

export const options = {
  insecureSkipTLSVerify: CONFIG.insecureSkipTLSVerify,
  scenarios: {
    // Closed-loop arrival-rate so the offered RPS is controlled regardless of
    // server latency — ramp to peak, hold, ramp down. Peak (default 1500 rps)
    // is the level at which the OLD per-request default failed.
    forward: {
      executor: 'ramping-arrival-rate',
      exec: 'forward',
      startRate: FORWARD_LOAD.startRate,
      timeUnit: '1s',
      preAllocatedVUs: FORWARD_LOAD.preAllocatedVUs,
      maxVUs: FORWARD_LOAD.maxVUs,
      stages: [
        { target: FORWARD_LOAD.peakRate, duration: FORWARD_LOAD.rampUp },
        { target: FORWARD_LOAD.peakRate, duration: FORWARD_LOAD.hold },
        { target: 0, duration: FORWARD_LOAD.rampDown },
      ],
    },
  },
  thresholds: {
    // THE GUARD: error rate must stay ~0. A pooling regression exhausts
    // ephemeral ports → BindException → forward failures, which trips this.
    // Reuses K6_MAX_ERROR_RATE (default 0.01) so a slow agent can relax it,
    // but the signal is binary: pooled ≈ 0, unpooled ≫ 0 at peak.
    'http_req_failed{op:forward}': [`rate<${LIMITS.errorRate}`],
    http_req_failed: [`rate<${LIMITS.errorRate}`],
    // Secondary: forward-path latency bounds (looser than the match path
    // because of the upstream hop). Override via K6_FWD_P95_MS / K6_FWD_P99_MS.
    'http_req_duration{op:forward}': [`p(95)<${FORWARD_LOAD.p95}`, `p(99)<${FORWARD_LOAD.p99}`],
    // The forward response must actually be a 200 from the upstream.
    checks: [`rate>${LIMITS.checkRate}`],
  },
};

export function setup() {
  seedForward();
}

export function forward() {
  getForward();
}

export function teardown() {
  resetMockServer();
}
