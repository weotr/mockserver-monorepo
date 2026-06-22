/*
 * MockServer Node client -- stateful-scenario examples.
 *
 * Runs the five canonical stateful-scenario demonstrations in sequence against a
 * running MockServer, resetting the server before each one so they are
 * self-contained and order-independent. Each scenario exercises the data plane
 * with Node's built-in http module (or the client where applicable) and ASSERTS
 * the observed behaviour. A `PASS: <scenario>` line is printed per scenario; the
 * process exits 0 only if all five pass and non-zero on the first failure.
 *
 * Scenarios:
 *   1. state_machine      -- login flow advances the scenario state machine
 *   2. sequential_cycling -- one expectation, multiple responses, cycling
 *   3. timed_transition   -- scenario REST helper with a timed auto-transition
 *   4. external_trigger   -- scenario REST helper triggered externally
 *   5. cross_protocol     -- a request on one path advances another path's scenario
 *
 * MockServer location is read from the environment (defaults localhost:1080):
 *   MOCKSERVER_HOST (default "localhost")
 *   MOCKSERVER_PORT (default 1080)
 */

var http = require('http');
var mockServerClient = require('mockserver-client').mockServerClient;

var HOST = process.env.MOCKSERVER_HOST || 'localhost';
var PORT = parseInt(process.env.MOCKSERVER_PORT || '1080', 10);

var client = mockServerClient(HOST, PORT);

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

// Issue an HTTP request to the MockServer data plane and resolve with
// {statusCode, body}. Used instead of the client so the example shows real
// end-to-end behaviour the way a system-under-test would observe it.
function call(method, path) {
    return new Promise(function (resolve, reject) {
        var req = http.request(
            {hostname: HOST, port: PORT, path: path, method: method},
            function (res) {
                var body = '';
                res.on('data', function (chunk) { body += chunk; });
                res.on('end', function () {
                    resolve({statusCode: res.statusCode, body: body});
                });
            }
        );
        req.on('error', reject);
        req.end();
    });
}

function get(path) { return call('GET', path); }
function post(path) { return call('POST', path); }

function delay(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
}

// Throw (rejecting the scenario) with a clear message when an assertion fails.
function assert(condition, message) {
    if (!condition) {
        throw new Error(message);
    }
}

function assertStatus(response, expected, context) {
    assert(
        response.statusCode === expected,
        context + ': expected status ' + expected + ' but got ' + response.statusCode +
        ' (body: ' + response.body + ')'
    );
}

function assertBodyContains(response, needle, context) {
    assert(
        response.body.indexOf(needle) !== -1,
        context + ': expected body to contain "' + needle + '" but got ' + response.body
    );
}

// ---------------------------------------------------------------------------
// 1. state_machine -- login flow
// ---------------------------------------------------------------------------
//
// A LoginFlow scenario starts in state "Started". GET /profile is unauthorised
// until POST /login advances the scenario to "LoggedIn".
async function stateMachine() {
    await client.mockAnyResponse([
        {
            httpRequest: {method: 'POST', path: '/login'},
            httpResponse: {statusCode: 200, body: JSON.stringify({token: 'abc123'})},
            scenarioName: 'LoginFlow',
            scenarioState: 'Started',
            newScenarioState: 'LoggedIn',
            times: {remainingTimes: 1, unlimited: false}
        },
        {
            httpRequest: {method: 'GET', path: '/profile'},
            httpResponse: {statusCode: 200, body: JSON.stringify({name: 'Alice'})},
            scenarioName: 'LoginFlow',
            scenarioState: 'LoggedIn'
        },
        {
            httpRequest: {method: 'GET', path: '/profile'},
            httpResponse: {statusCode: 401, body: JSON.stringify({error: 'Not authenticated'})},
            scenarioName: 'LoginFlow',
            scenarioState: 'Started'
        }
    ]);

    var before = await get('/profile');
    assertStatus(before, 401, 'state_machine GET /profile before login');

    var login = await post('/login');
    assertStatus(login, 200, 'state_machine POST /login');
    assertBodyContains(login, 'abc123', 'state_machine POST /login token');

    var after = await get('/profile');
    assertStatus(after, 200, 'state_machine GET /profile after login');
    assertBodyContains(after, 'Alice', 'state_machine GET /profile name');
}

// ---------------------------------------------------------------------------
// 2. sequential_cycling -- multiple responses, one expectation (no scenario)
// ---------------------------------------------------------------------------
//
// A single expectation carries three responses returned in order, then cycles
// back to the first: 200, 503, 200, 200, ...
async function sequentialCycling() {
    await client.mockAnyResponse({
        httpRequest: {method: 'GET', path: '/api/status'},
        httpResponses: [
            {statusCode: 200, body: JSON.stringify({status: 'ok'})},
            {statusCode: 503, body: JSON.stringify({status: 'degraded'})},
            {statusCode: 200, body: JSON.stringify({status: 'ok'})}
        ],
        responseMode: 'SEQUENTIAL'
    });

    var first = await get('/api/status');
    assertStatus(first, 200, 'sequential_cycling call 1');

    var second = await get('/api/status');
    assertStatus(second, 503, 'sequential_cycling call 2');

    var third = await get('/api/status');
    assertStatus(third, 200, 'sequential_cycling call 3');

    // The fourth call cycles back to the first response.
    var fourth = await get('/api/status');
    assertStatus(fourth, 200, 'sequential_cycling call 4 (cycled back to first)');
}

// ---------------------------------------------------------------------------
// 3. timed_transition -- scenario REST helper, timed auto-transition
// ---------------------------------------------------------------------------
//
// /status returns "deploying" while the DeployFlow scenario is "Deploying" and
// "complete" once it auto-transitions to "Deployed" after ~1 second.
async function timedTransition() {
    await client.mockAnyResponse([
        {
            httpRequest: {method: 'GET', path: '/status'},
            httpResponse: {statusCode: 200, body: JSON.stringify({status: 'deploying'})},
            scenarioName: 'DeployFlow',
            scenarioState: 'Deploying'
        },
        {
            httpRequest: {method: 'GET', path: '/status'},
            httpResponse: {statusCode: 200, body: JSON.stringify({status: 'complete'})},
            scenarioName: 'DeployFlow',
            scenarioState: 'Deployed'
        }
    ]);

    // Start in "Deploying", auto-transition to "Deployed" after 1000ms.
    await client.scenario('DeployFlow').set('Deploying', {transitionAfterMs: 1000, nextState: 'Deployed'});

    var deploying = await get('/status');
    assertStatus(deploying, 200, 'timed_transition GET /status before transition');
    assertBodyContains(deploying, 'deploying', 'timed_transition deploying body');

    // Wait past the transition window, then confirm the new state is active.
    await delay(1300);

    var complete = await get('/status');
    assertStatus(complete, 200, 'timed_transition GET /status after transition');
    assertBodyContains(complete, 'complete', 'timed_transition complete body');
}

// ---------------------------------------------------------------------------
// 4. external_trigger -- scenario REST helper, external trigger
// ---------------------------------------------------------------------------
//
// /health is healthy until an external caller trips the HealthFlow scenario to
// "Down" via the scenario trigger helper.
async function externalTrigger() {
    await client.mockAnyResponse([
        {
            httpRequest: {method: 'GET', path: '/health'},
            httpResponse: {statusCode: 200, body: JSON.stringify({status: 'healthy'})},
            scenarioName: 'HealthFlow',
            scenarioState: 'Started'
        },
        {
            httpRequest: {method: 'GET', path: '/health'},
            httpResponse: {statusCode: 503, body: JSON.stringify({status: 'down'})},
            scenarioName: 'HealthFlow',
            scenarioState: 'Down'
        }
    ]);

    var healthy = await get('/health');
    assertStatus(healthy, 200, 'external_trigger GET /health before trigger');
    assertBodyContains(healthy, 'healthy', 'external_trigger healthy body');

    // External event flips the scenario to "Down".
    await client.scenario('HealthFlow').trigger('Down');

    var down = await get('/health');
    assertStatus(down, 503, 'external_trigger GET /health after trigger');
    assertBodyContains(down, 'down', 'external_trigger down body');
}

// ---------------------------------------------------------------------------
// 5. cross_protocol -- crossProtocolScenarios (HTTP_REQUEST trigger)
// ---------------------------------------------------------------------------
//
// GET /api/status is unmatched until a GET /events request fires a cross-protocol
// trigger that advances the ConnFlow scenario to "Connected". The same mechanism
// advances scenarios from DNS_QUERY / WEBSOCKET_CONNECT / GRPC_REQUEST events.
async function crossProtocol() {
    await client.mockAnyResponse([
        {
            httpRequest: {method: 'GET', path: '/events'},
            httpResponse: {statusCode: 200, body: JSON.stringify({stream: 'open'})},
            crossProtocolScenarios: [
                {
                    trigger: 'HTTP_REQUEST',
                    matchPattern: '/events',
                    scenarioName: 'ConnFlow',
                    targetState: 'Connected'
                }
            ]
        },
        {
            httpRequest: {method: 'GET', path: '/api/status'},
            httpResponse: {statusCode: 200, body: JSON.stringify({status: 'connected'})},
            scenarioName: 'ConnFlow',
            scenarioState: 'Connected'
        }
    ]);

    // Before the trigger the scenario is not yet "Connected", so the
    // /api/status expectation does not match and MockServer returns 404.
    var before = await get('/api/status');
    assertStatus(before, 404, 'cross_protocol GET /api/status before trigger (unmatched)');

    // The /events request fires the cross-protocol trigger.
    var events = await get('/events');
    assertStatus(events, 200, 'cross_protocol GET /events');

    var after = await get('/api/status');
    assertStatus(after, 200, 'cross_protocol GET /api/status after trigger');
    assertBodyContains(after, 'connected', 'cross_protocol connected body');
}

// ---------------------------------------------------------------------------
// Runner -- reset before each scenario, print PASS lines, exit non-zero on fail
// ---------------------------------------------------------------------------

var SCENARIOS = [
    {name: 'state_machine', run: stateMachine},
    {name: 'sequential_cycling', run: sequentialCycling},
    {name: 'timed_transition', run: timedTransition},
    {name: 'external_trigger', run: externalTrigger},
    {name: 'cross_protocol', run: crossProtocol}
];

async function main() {
    console.log('Running scenario examples against http://' + HOST + ':' + PORT + '\n');

    for (var i = 0; i < SCENARIOS.length; i++) {
        var scenario = SCENARIOS[i];
        // Reset before each scenario so they are independent and order-free.
        await client.reset();
        await scenario.run();
        console.log('PASS: ' + scenario.name);
    }

    console.log('\nAll scenarios passed.');
}

main().then(
    function () {
        process.exit(0);
    },
    function (error) {
        console.error('\nFAIL: ' + (error && error.message ? error.message : error));
        process.exit(1);
    }
);
