/*
 * MockServer Node client -- Load Scenario registry examples.
 *
 * A "load scenario" is a named, server-side traffic generator: you register it
 * once (its profile of ramp/hold/pause stages and the request steps it drives),
 * then start/stop it by name. While running it generates synthetic traffic
 * against the data plane and reports live throughput/latency status. This is the
 * registry workflow exercised with the Node client:
 *
 *   client.loadScenario(scenario)        register/upsert (PUT /mockserver/loadScenario)
 *   client.startLoadScenarios(names)     start one/many (PUT .../start)
 *   client.loadScenarios()               list all (GET /mockserver/loadScenario)
 *   client.getLoadScenario(name)         one scenario + live status (GET .../{name})
 *   client.stopLoadScenarios(names?)     stop one/many; no arg = stop all (PUT .../stop)
 *   client.runLoadScenario(scenario)     register + start in one call
 *   client.deleteLoadScenario(name)      delete one (DELETE .../{name})
 *   client.clearLoadScenarios()          clear the registry (DELETE /mockserver/loadScenario)
 *
 * IMPORTANT: the server must be started with load generation enabled, otherwise
 * starting returns HTTP 403:
 *   java -Dmockserver.loadGenerationEnabled=true -jar mockserver-netty-...-jar-with-dependencies.jar -serverPort 1080
 *   (or env MOCKSERVER_LOAD_GENERATION_ENABLED=true). Registering is always allowed.
 *
 * Prints "PASS" on success and exits 0; exits non-zero on the first failure.
 *
 * MockServer location is read from the environment (defaults localhost:1080):
 *   MOCKSERVER_HOST (default "localhost")
 *   MOCKSERVER_PORT (default 1080)
 */

var mockServerClient = require('mockserver-client').mockServerClient;

var HOST = process.env.MOCKSERVER_HOST || 'localhost';
var PORT = parseInt(process.env.MOCKSERVER_PORT || '1080', 10);

var client = mockServerClient(HOST, PORT);

// A realistic multi-stage scenario: a linear RATE ramp, then a VU hold, then a
// PAUSE. Two Velocity-templated steps, each embedding a full HttpRequest. Note
// the per-step field is `request` (not `httpRequest`). Stage VUs are kept within
// the default safety cap of 50 (mockserver.loadGenerationMaxVirtualUsers).
var scenario = {
    name: 'checkout-load',
    templateType: 'VELOCITY',
    maxRequests: 100000,
    labels: { team: 'payments', env: 'staging' },
    profile: {
        stages: [
            { type: 'RATE', startRate: 5, endRate: 50, durationMillis: 30000, curve: 'LINEAR', maxVus: 50 },
            { type: 'VU', vus: 25, durationMillis: 60000 },
            { type: 'PAUSE', durationMillis: 10000 }
        ]
    },
    steps: [
        {
            name: 'browse',
            request: { method: 'GET', path: '/products/$!iteration.index' },
            thinkTime: { timeUnit: 'MILLISECONDS', value: 500 }
        },
        {
            name: 'checkout',
            request: {
                method: 'POST',
                path: '/cart/checkout',
                headers: { 'Content-Type': ['application/json'] },
                body: '{"item":"$!iteration.index","qty":1}'
            },
            labels: { critical: 'true' }
        }
    ]
};

function sleep(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
}

function fail(message) {
    console.error('FAIL: ' + message);
    process.exit(1);
}

(async function () {
    try {
        // A target expectation so generated traffic gets a 200 to measure.
        await client.mockAnyResponse({
            httpRequest: {},
            httpResponse: { statusCode: 200, body: 'ok' }
        });

        // 1. Register (does NOT run it yet).
        await client.loadScenario(scenario);
        console.log('registered "checkout-load"');

        // 2. Start it (accepts a single name string or an array of names).
        await client.startLoadScenarios('checkout-load');
        console.log('started "checkout-load"');
        await sleep(1500);

        // 3. List all registered scenarios -> { scenarios: [ <status node>, ... ] }.
        var list = await client.loadScenarios();
        var running = (list.scenarios || []).filter(function (s) { return s.state === 'RUNNING'; });
        if (!running.some(function (s) { return s.name === 'checkout-load'; })) {
            fail('checkout-load is not RUNNING in the list (is loadGenerationEnabled=true?)');
        }
        console.log('listed: ' + (list.scenarios || []).map(function (s) { return s.name + '=' + s.state; }).join(', '));

        // One scenario's live status (throughput/latency, current stage, ...).
        var status = await client.getLoadScenario('checkout-load');
        console.log('status: state=' + status.state + ' stageType=' + status.stageType +
            ' currentTarget=' + status.currentTarget + ' requestsSent=' + status.requestsSent);

        // 4. Stop it (no argument stops ALL running scenarios).
        await client.stopLoadScenarios('checkout-load');
        console.log('stopped "checkout-load"');

        // Tidy up the registry.
        await client.clearLoadScenarios();

        console.log('PASS');
        process.exit(0);
    } catch (error) {
        fail(JSON.stringify(error));
    }
})();
