/*
 * MockServer Node client -- callback examples.
 *
 * Runs the callback demonstrations in sequence against a running MockServer,
 * resetting the server before each so they are self-contained and
 * order-independent. Each example ASSERTS its outcome; a `PASS: <example>` line
 * is printed per example. The process exits 0 only if all pass and non-zero on
 * the first failure.
 *
 * Examples:
 *   1. class_callback_raw    -- a raw expectation carrying httpResponseClassCallback
 *                               is accepted by the server (200 on upsert). Class
 *                               callbacks are REST-only (pure JSON, no WebSocket);
 *                               the named class is resolved inside the MockServer
 *                               JVM, so this only asserts the wire shape is stored.
 *   2. class_callback_helper -- the respondWithClassCallback(...) convenience
 *                               method produces the same accepted expectation.
 *   3. forward_class_callback-- forwardWithClassCallback(...) registers an
 *                               httpForwardClassCallback expectation (also 200).
 *   4. object_callback       -- mockWithCallback(...) opens the callback
 *                               WebSocket and runs a LOCAL closure that derives
 *                               the response FROM the request. A real data-plane
 *                               request is sent and the dynamic response asserted,
 *                               exercising Node's object-callback path end-to-end.
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
// {statusCode, body}.
function call(method, path, body) {
    return new Promise(function (resolve, reject) {
        var req = http.request(
            {hostname: HOST, port: PORT, path: path, method: method},
            function (res) {
                var responseBody = '';
                res.on('data', function (chunk) { responseBody += chunk; });
                res.on('end', function () {
                    resolve({statusCode: res.statusCode, body: responseBody});
                });
            }
        );
        req.on('error', reject);
        if (body !== undefined && body !== null) {
            req.write(body);
        }
        req.end();
    });
}

function get(path) { return call('GET', path); }
function post(path, body) { return call('POST', path, body); }

// Throw (rejecting the example) with a clear message when an assertion fails.
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
// 1. class_callback_raw -- raw expectation with httpResponseClassCallback
// ---------------------------------------------------------------------------
//
// A class callback names a server-side class implementing the MockServer
// ExpectationResponseCallback interface. It is pure JSON / REST-only -- no
// callback WebSocket is opened. The class is resolved inside the MockServer JVM,
// so here we only assert the server ACCEPTS and stores the expectation; the
// named class need not exist on this client.
async function classCallbackRaw() {
    var result = await client.mockAnyResponse({
        httpRequest: {path: '/class/raw'},
        httpResponseClassCallback: {
            callbackClass: 'org.mockserver.examples.MyResponseCallback'
        },
        times: {remainingTimes: 1, unlimited: false}
    });
    assert(
        result && result.statusCode === 201,
        'class_callback_raw: expected upsert to return 201 but got ' +
        (result && result.statusCode)
    );
}

// ---------------------------------------------------------------------------
// 2. class_callback_helper -- respondWithClassCallback(...) convenience method
// ---------------------------------------------------------------------------
async function classCallbackHelper() {
    var result = await client.respondWithClassCallback(
        '/class/helper',
        'org.mockserver.examples.MyResponseCallback'
    );
    assert(
        result && result.statusCode === 201,
        'class_callback_helper: expected upsert to return 201 but got ' +
        (result && result.statusCode)
    );
}

// ---------------------------------------------------------------------------
// 3. forward_class_callback -- forwardWithClassCallback(...) convenience method
// ---------------------------------------------------------------------------
async function forwardClassCallback() {
    var result = await client.forwardWithClassCallback(
        '/forward/helper',
        'org.mockserver.examples.MyForwardCallback'
    );
    assert(
        result && result.statusCode === 201,
        'forward_class_callback: expected upsert to return 201 but got ' +
        (result && result.statusCode)
    );
}

// ---------------------------------------------------------------------------
// 4. object_callback -- mockWithCallback(...) runs a LOCAL closure
// ---------------------------------------------------------------------------
//
// Unlike a class callback, an object/closure callback runs in THIS process: the
// client opens the callback WebSocket, the server hands it a clientId, and on a
// match the request is sent over the WebSocket to the closure, which derives the
// response. Here the closure echoes the request method and a header value, so we
// can prove the dynamic response really came from the closure.
async function objectCallback() {
    await client.mockWithCallback(
        {path: '/object/callback'},
        function (request) {
            var who = (request.headers && (request.headers['X-Who'] || request.headers['x-who']));
            var whoValue = Array.isArray(who) ? who[0] : (who || 'anonymous');
            return {
                statusCode: 200,
                headers: {'Content-Type': ['application/json']},
                body: JSON.stringify({
                    method: request.method,
                    greeting: 'hello ' + whoValue
                })
            };
        }
    );

    // Send a real data-plane request; the closure derives the response from it.
    var response = await new Promise(function (resolve, reject) {
        var req = http.request(
            {
                hostname: HOST,
                port: PORT,
                path: '/object/callback',
                method: 'POST',
                headers: {'X-Who': 'world'}
            },
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

    assertStatus(response, 200, 'object_callback POST /object/callback');
    assertBodyContains(response, '"method":"POST"', 'object_callback derived method');
    assertBodyContains(response, 'hello world', 'object_callback derived greeting');
}

// ---------------------------------------------------------------------------
// Runner -- reset before each example, print PASS lines, exit non-zero on fail
// ---------------------------------------------------------------------------

var EXAMPLES = [
    {name: 'class_callback_raw', run: classCallbackRaw},
    {name: 'class_callback_helper', run: classCallbackHelper},
    {name: 'forward_class_callback', run: forwardClassCallback},
    {name: 'object_callback', run: objectCallback}
];

async function main() {
    console.log('Running callback examples against http://' + HOST + ':' + PORT + '\n');

    for (var i = 0; i < EXAMPLES.length; i++) {
        var example = EXAMPLES[i];
        // Reset before each example so they are independent and order-free.
        await client.reset();
        await example.run();
        console.log('PASS: ' + example.name);
    }

    console.log('\nAll callback examples passed.');
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
