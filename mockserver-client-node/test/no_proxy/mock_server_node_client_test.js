'use strict';

var crypto = require('crypto');
var { describe, it, before, beforeEach } = require('node:test');
var assert = require('node:assert/strict');
var mockServer = require('../../');
var mockServerClient = mockServer.mockServerClient;
var http = require('http');

// Narrow suppressor for unhandled Q-promise rejections caused by WebSocket
// reconnect attempts during reset()/teardown.  The MockServer Node client
// uses Q promises internally; when a WebSocket connection is closed (e.g.
// by reset()), the reconnect logic in webSocketClient.js fires Q-deferred
// rejections (ECONNREFUSED, "Max reconnect attempts reached", etc.) that
// are unhandled because no consumer is listening at that point.  node:test
// counts every unhandled rejection as a test failure, so we must suppress
// these benign teardown events.  We only suppress rejections whose string
// representation matches known WebSocket/reconnect noise — anything else
// is logged to stderr so real bugs stay visible.
process.on('unhandledRejection', function (reason) {
    var msg = String(reason || '');
    // Known benign patterns from webSocketClient.js teardown:
    //  - ECONNREFUSED: connection refused during reconnect attempt
    //  - "Can't connect to MockServer": wrapper message from webSocketClient.js
    //  - "Max reconnect attempts reached": logged when retries are exhausted
    //  - WebSocket close/error objects serialised by JSON.stringify
    if (/ECONNREFUSED/.test(msg) ||
        /Can't connect to MockServer/.test(msg) ||
        /Max reconnect attempts/.test(msg) ||
        /WebSocket/.test(msg)) {
        // Benign teardown noise — swallow silently.
        return;
    }
    // Unexpected rejection — log it so real bugs are visible.
    console.error('[unhandledRejection — NOT suppressed]', reason);
});

var mockServerHost = process.env.MOCKSERVER_HOST || "localhost";
var mockServerPort = parseInt(process.env.MOCKSERVER_PORT, 10) || 1080;
// External mode: the server runs in a separate Docker container, so tests
// that depend on the server being able to reach the test client (forward
// callbacks) or on binding additional ports that the client can reach
// (port-bind test) cannot work and are skipped.
var isExternalMode = !!process.env.MOCKSERVER_HOST;

function guid() {
    return crypto.randomUUID();
}

function sendRequest(method, host, port, path, jsonBody, headers) {
    return new Promise(function (resolve, reject) {
        var body = (typeof jsonBody === "string" ? jsonBody : JSON.stringify(jsonBody || ""));
        var options = {
            method: method,
            host: host,
            port: port,
            headers: headers || {},
            path: path
        };
        options.headers.Connection = "keep-alive";

        var callback = function (response) {
            var data = '';

            response.on('data', function (chunk) {
                data += chunk;
            });

            response.on('end', function () {
                if (response.statusCode >= 400 && response.statusCode < 600) {
                    if (response.statusCode === 404) {
                        reject("404 Not Found");
                    } else {
                        reject(data);
                    }
                } else {
                    resolve({
                        statusCode: response.statusCode,
                        headers: response.headers,
                        body: data
                    });
                }
            });
        };

        var req = http.request(options, callback);
        if (options.method === "POST") {
            req.write(body);
        }
        req.end();
    });
}

describe('mock server node client (no proxy)', { concurrency: 1 }, function () {
    var client;
    var uuid = guid();

    beforeEach(async function () {
        client = mockServerClient(mockServerHost, mockServerPort);
        // The narrowed unhandledRejection handler (above) suppresses benign
        // Q-promise rejections from WebSocket teardown during reset, so
        // client.reset() can be used directly.
        await client.reset();
    });

    it('should create full expectation with string body', async function () {
        await client.mockAnyResponse({
            'httpRequest': {
                'method': 'POST',
                'path': '/somePath',
                'queryStringParameters': [
                    { 'name': 'test', 'values': ['true'] }
                ],
                'body': { 'type': "STRING", 'string': 'someBody' }
            },
            'httpResponse': {
                'statusCode': 200,
                'body': JSON.stringify({name: 'value'}),
                'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 }
            },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        // non matching request
        await assert.rejects(
            sendRequest("GET", mockServerHost, mockServerPort, "/otherPath"),
            function (err) { return err === "404 Not Found"; }
        );

        // matching request
        var response = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody");
        assert.equal(response.statusCode, 200);
        assert.equal(response.body, '{"name":"value"}');

        // matching request, but no times remaining
        await assert.rejects(
            sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody"),
            function (err) { return err === "404 Not Found"; }
        );
    });

    it('should create full expectation with string body over tls', async function () {
        var clientOverTls = mockServerClient(mockServerHost, mockServerPort, undefined, true);
        await clientOverTls.mockAnyResponse({
            'httpRequest': {
                'method': 'POST',
                'path': '/somePath',
                'queryStringParameters': [
                    { 'name': 'test', 'values': ['true'] }
                ],
                'body': { 'type': "STRING", 'string': 'someBody' }
            },
            'httpResponse': {
                'statusCode': 200,
                'body': JSON.stringify({name: 'value'}),
                'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 }
            },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        var response = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody");
        assert.equal(response.statusCode, 200);
        assert.equal(response.body, '{"name":"value"}');
    });

    it('should create expectations from array', async function () {
        await client.mockAnyResponse([
            { 'httpRequest': { 'path': '/somePathOne' }, 'httpResponse': { 'body': JSON.stringify({name: 'one'}) } },
            { 'httpRequest': { 'path': '/somePathTwo' }, 'httpResponse': { 'body': JSON.stringify({name: 'two'}) } },
            { 'httpRequest': { 'path': '/somePathThree' }, 'httpResponse': { 'body': JSON.stringify({name: 'three'}) } }
        ]);

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"one"}');

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo", "someBody");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"two"}');

        var r3 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathThree", "someBody");
        assert.equal(r3.statusCode, 200);
        assert.equal(r3.body, '{"name":"three"}');
    });

    it('should set default headers on expectation array', async function () {
        client.setDefaultHeaders([
            {"name": "x-test-default", "values": ["default-value"]}
        ]);
        await client.mockAnyResponse([
            { 'httpRequest': { 'path': '/somePathOne' }, 'httpResponse': { 'body': JSON.stringify({name: 'one'}) } },
            { 'httpRequest': { 'path': '/somePathTwo' }, 'httpResponse': { 'body': JSON.stringify({name: 'two'}), 'headers': [ {"name": "x-test", "values": ["test-value"]} ] } },
            { 'httpRequest': { 'path': '/somePathThree' }, 'httpResponse': { 'body': JSON.stringify({name: 'three'}) } }
        ]);

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"one"}');
        assert.equal(r1.headers["x-test-default"], "default-value");

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo", "someBody");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"two"}');
        assert.equal(r2.headers["x-test-default"], "default-value");
        assert.equal(r2.headers["x-test"], "test-value");

        var r3 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathThree", "someBody");
        assert.equal(r3.statusCode, 200);
        assert.equal(r3.body, '{"name":"three"}');
        assert.equal(r3.headers["x-test-default"], "default-value");
    });

    it('should clear default headers', async function () {
        await client.mockAnyResponse({
            'httpRequest': { 'path': '/somePathOne' },
            'httpResponse': { 'body': JSON.stringify({name: 'one'}) }
        });

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"one"}');

        client.setDefaultHeaders([], []);
        await client.mockAnyResponse({
            'httpRequest': { 'path': '/somePathTwo' },
            'httpResponse': { 'body': JSON.stringify({name: 'one'}) }
        });

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"one"}');
        assert.ok(!(r2.headers["Content-Type"] || r2.headers["content-type"]));
        assert.ok(!(r2.headers["Cache-Control"] || r2.headers["cache-control"]));
    });

    it('should expose server validation failure', async function () {
        try {
            await client.mockAnyResponse({
                'httpRequest': {
                    'paths': '/somePath',
                    'body': { 'type': "STRING", 'vaue': 'someBody' }
                },
                'httpResponse': {}
            });
            assert.fail("should have thrown");
        } catch (error) {
            // Use substring matching — the server's schema validation message
            // varies across versions.
            assert.ok(error.indexOf("incorrect expectation json format for:") !== -1,
                "should contain preamble, got: " + error);
            assert.ok(error.indexOf("\"paths\" : \"/somePath\"") !== -1,
                "should echo the invalid field, got: " + error);
            assert.ok(error.indexOf("$.httpRequest.paths: is not defined in the schema") !== -1,
                "should flag the unknown property, got: " + error);
            assert.ok(error.indexOf("Documentation: https://mock-server.com/mock_server/creating_expectations.html") !== -1,
                "should link to documentation, got: " + error);
        }
    });

    it('should match on method only', async function () {
        await client.mockAnyResponse({
            'httpRequest': { 'method': 'GET' },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'first_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });
        await client.mockAnyResponse({
            'httpRequest': { 'method': 'POST' },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'second_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        await assert.rejects(sendRequest("PUT", mockServerHost, mockServerPort, "/somePath"), function (err) { return err === "404 Not Found"; });

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"first_body"}');

        var r2 = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"second_body"}');
    });

    it('should match on path only', async function () {
        await client.mockAnyResponse({
            'httpRequest': { 'path': '/firstPath' },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'first_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });
        await client.mockAnyResponse({
            'httpRequest': { 'path': '/secondPath' },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'second_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/otherPath"), function (err) { return err === "404 Not Found"; });

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/firstPath");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"first_body"}');

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/secondPath");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"second_body"}');
    });

    it('should match on query string parameters only', async function () {
        await client.mockAnyResponse({
            'httpRequest': { 'queryStringParameters': [ { 'name': 'param', 'values': ['first'] } ] },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'first_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });
        await client.mockAnyResponse({
            'httpRequest': { 'queryStringParameters': [ { 'name': 'param', 'values': ['second'] } ] },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'second_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePath?param=other"), function (err) { return err === "404 Not Found"; });

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath?param=first");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"first_body"}');

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath?param=second");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"second_body"}');
    });

    it('should match on body only', async function () {
        await client.mockAnyResponse({
            'httpRequest': { 'body': { 'type': "STRING", 'string': 'someBody' } },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'first_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });
        await client.mockAnyResponse({
            'httpRequest': { 'body': { 'type': "REGEX", 'regex': 'someOtherBody' } },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'second_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        await assert.rejects(sendRequest("POST", mockServerHost, mockServerPort, "/otherPath", "someIncorrectBody"), function (err) { return err === "404 Not Found"; });

        var r1 = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath", "someBody");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"first_body"}');

        var r2 = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath", "someOtherBody");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"second_body"}');
    });

    it('should match on headers only', async function () {
        await client.mockAnyResponse({
            'httpRequest': { 'headers': [ { 'name': 'Allow', 'values': ['first'] } ] },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'first_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });
        await client.mockAnyResponse({
            'httpRequest': { 'headers': [ { 'name': 'Allow', 'values': ['second'] } ] },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'second_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Allow': 'other'}), function (err) { return err === "404 Not Found"; });

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Allow': 'first'});
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"first_body"}');

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Allow': 'second'});
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"second_body"}');
    });

    it('should support headers object format in matcher', async function () {
        client.setDefaultHeaders([
            {"name": "x-test-default", "values": ["default-value"]}
        ]);
        await client.mockAnyResponse({
            'httpRequest': { 'path': '/somePath' },
            'httpResponse': {
                'statusCode': 200,
                'body': JSON.stringify({name: 'first_body'}),
                'headers': { 'x-test': ['test-value'] },
                'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 }
            },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        var r = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath");
        assert.equal(r.statusCode, 200);
        assert.equal(r.body, '{"name":"first_body"}');
        assert.equal(r.headers["x-test-default"], "default-value");
        assert.equal(r.headers["x-test"], "test-value");
    });

    it('should support headers object format in default headers', async function () {
        client.setDefaultHeaders({ "x-test-default": ["default-value"] });
        await client.mockAnyResponse({
            'httpRequest': { 'path': '/somePath' },
            'httpResponse': {
                'statusCode': 200,
                'body': JSON.stringify({name: 'first_body'}),
                'headers': [ { 'name': 'x-test', 'values': ['test-value'] } ],
                'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 }
            },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        var r = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Allow': 'first'});
        assert.equal(r.statusCode, 200);
        assert.equal(r.body, '{"name":"first_body"}');
        assert.equal(r.headers["x-test-default"], "default-value");
        assert.equal(r.headers["x-test"], "test-value");
    });

    it('should support headers object format in default headers and matcher', async function () {
        client.setDefaultHeaders([
            {"name": "x-test-default", "values": ["default-value"]}
        ]);
        await client.mockAnyResponse({
            'httpRequest': { 'path': '/somePath' },
            'httpResponse': {
                'statusCode': 200,
                'body': JSON.stringify({name: 'first_body'}),
                'headers': { 'x-test': ['test-value'] },
                'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 }
            },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        var r = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Allow': 'first'});
        assert.equal(r.statusCode, 200);
        assert.equal(r.body, '{"name":"first_body"}');
        assert.equal(r.headers["x-test-default"], "default-value");
        assert.equal(r.headers["x-test"], "test-value");
    });

    it('should match on cookies only', async function () {
        await client.mockAnyResponse({
            'httpRequest': { 'cookies': [ { 'name': 'cookie', 'value': 'first' } ] },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'first_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });
        await client.mockAnyResponse({
            'httpRequest': { 'cookies': [ { 'name': 'cookie', 'value': 'second' } ] },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'second_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Cookie': 'cookie=other'}), function (err) { return err === "404 Not Found"; });

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Cookie': 'cookie=first'});
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"first_body"}');

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Cookie': 'cookie=second'});
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"second_body"}');
    });

    it('should create simple response expectation', async function () {
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 203);

        await assert.rejects(sendRequest("POST", mockServerHost, mockServerPort, "/otherPath"), function (err) { return err === "404 Not Found"; });

        var r = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody");
        assert.equal(r.statusCode, 203);
        assert.equal(r.body, '{"name":"value"}');

        await assert.rejects(sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody"), function (err) { return err === "404 Not Found"; });
    });

    it('should create expectation with open api request', async function () {
        await client.mockAnyResponse({
            'httpRequest': {
                'specUrlOrPayload': 'https://raw.githubusercontent.com/mock-server/mockserver/master/mockserver-integration-testing/src/main/resources/org/mockserver/openapi/openapi_petstore_example.json',
                'operationId': 'listPets'
            },
            'httpResponse': { 'statusCode': 200, 'body': "open_api_response" }
        });

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/otherPath"), function (err) { return err === "404 Not Found"; });

        var r = await sendRequest("GET", mockServerHost, mockServerPort, "/v1/pets?limit=10");
        assert.equal(r.statusCode, 200);
        assert.equal(r.body, 'open_api_response');
    });

    it('should create open api expectation', async function () {
        await client.openAPIExpectation({
            'specUrlOrPayload': 'https://raw.githubusercontent.com/mock-server/mockserver/master/mockserver-integration-testing/src/main/resources/org/mockserver/openapi/openapi_petstore_example.json',
            'operationsAndResponses': { 'showPetById': '200', 'listPets': '200' }
        });

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/otherPath"), function (err) { return err === "404 Not Found"; });

        var r = await sendRequest("GET", mockServerHost, mockServerPort, "/v1/pets?limit=10");
        assert.equal(r.statusCode, 200);
        assert.equal(r.body, '[ {\n  "id" : 0,\n  "name" : "some_string_value",\n  "tag" : "some_string_value"\n} ]');
    });

    it('should create expectation with method callback', async function () {
        await client.mockWithCallback({
            'method': 'POST',
            'path': '/somePath',
            'queryStringParameters': [ { 'name': 'test', 'values': ['true'] } ],
            'body': { 'type': "STRING", 'string': 'someBody' }
        }, function (request) {
            if (request.method === 'POST' && request.path === '/somePath') {
                return { 'statusCode': 200, 'body': JSON.stringify({name: 'value'}) };
            } else {
                return { 'statusCode': 406 };
            }
        }, 1, 10, { timeUnit: "HOURS", timeToLive: 1 }, "some_id");

        await assert.rejects(
            sendRequest("POST", mockServerHost, mockServerPort, "/someOtherPath?test=true", "", {'Vary': uuid}),
            function (err) { return err === "404 Not Found"; }
        );

        var r = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody", {'Vary': uuid});
        assert.equal(r.statusCode, 200);
        assert.equal(r.body, '{"name":"value"}');

        await assert.rejects(
            sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody", {'Vary': uuid}),
            function (err) { return err === "404 Not Found"; }
        );
    });

    it('should create expectation with method callback over tls', async function () {
        var clientOverTls = mockServerClient(mockServerHost, mockServerPort, undefined, true);
        await clientOverTls.mockWithCallback({
            'method': 'POST',
            'path': '/somePath',
            'queryStringParameters': [ { 'name': 'test', 'values': ['true'] } ],
            'body': { 'type': "STRING", 'string': 'someBody' }
        }, function (request) {
            if (request.method === 'POST' && request.path === '/somePath') {
                return { 'statusCode': 200, 'body': JSON.stringify({name: 'value'}) };
            } else {
                return { 'statusCode': 406 };
            }
        });

        await assert.rejects(
            sendRequest("POST", mockServerHost, mockServerPort, "/someOtherPath?test=true", "", {'Vary': uuid}),
            function (err) { return err === "404 Not Found"; }
        );

        var r = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody", {'Vary': uuid});
        assert.equal(r.statusCode, 200);
        assert.equal(r.body, '{"name":"value"}');

        await assert.rejects(
            sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody", {'Vary': uuid}),
            function (err) { return err === "404 Not Found"; }
        );
    });

    it('should create multiple parallel expectations with method callback', async function () {
        await client.mockWithCallback({
            'method': 'GET', 'path': '/one'
        }, function (request) {
            if (request.method === 'GET' && request.path === '/one') {
                return { 'statusCode': 201, 'body': 'one' };
            } else {
                return { 'statusCode': 406 };
            }
        }, { remainingTimes: 2, unlimited: false });

        await client.mockWithCallback({
            'method': 'GET', 'path': '/two'
        }, function (request) {
            if (request.method === 'GET' && request.path === '/two') {
                return { 'statusCode': 202, 'body': 'two' };
            } else {
                return { 'statusCode': 406 };
            }
        });

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/one", "", {'Vary': uuid});
        assert.equal(r1.statusCode, 201);
        assert.equal(r1.body, 'one');

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/two", "someBody", {'Vary': uuid});
        assert.equal(r2.statusCode, 202);
        assert.equal(r2.body, 'two');

        var r3 = await sendRequest("GET", mockServerHost, mockServerPort, "/one", "", {'Vary': uuid});
        assert.equal(r3.statusCode, 201);
        assert.equal(r3.body, 'one');
    });

    it('should create expectation with method callback with numeric times', async function () {
        await client.mockWithCallback({
            'method': 'GET', 'path': '/one'
        }, function (request) {
            if (request.method === 'GET' && request.path === '/one') {
                return { 'statusCode': 201, 'body': 'one' };
            } else {
                return { 'statusCode': 406 };
            }
        }, 2);

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/one", "", {'Vary': uuid});
        assert.equal(r1.statusCode, 201);
        assert.equal(r1.body, 'one');

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/one", "", {'Vary': uuid});
        assert.equal(r2.statusCode, 201);
        assert.equal(r2.body, 'one');

        await assert.rejects(
            sendRequest("GET", mockServerHost, mockServerPort, "/one", "", {'Vary': uuid}),
            function (err) { return err === "404 Not Found"; }
        );
    });

    it('should create expectation with forward method callback', { skip: isExternalMode ? 'forward callbacks cannot reach the host from a Docker container' : false }, async function () {
        await client.mockWithForwardCallback({
            'method': 'GET', 'path': '/somePath'
        }, function (request) {
            return { 'method': request.method, 'path': request.path, 'headers': request.headers };
        }, 1, 10, { timeUnit: "HOURS", timeToLive: 1 }, "some_id");

        var r = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Vary': uuid});
        assert.ok(r.statusCode);

        await assert.rejects(
            sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Vary': uuid}),
            function (err) { return err === "404 Not Found"; }
        );
    });

    it('should create expectation with forward and response method callback', { skip: isExternalMode ? 'forward-and-response callbacks cannot reach the host from a Docker container' : false }, async function () {
        await client.mockWithForwardAndResponseCallback({
            'method': 'GET', 'path': '/somePath'
        }, function (request) {
            return { 'method': request.method, 'path': request.path, 'headers': request.headers };
        }, function (request, response) {
            return { 'statusCode': 200, 'body': 'overridden' };
        }, 1, 10, { timeUnit: "HOURS", timeToLive: 1 }, "some_id");

        var r = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Vary': uuid});
        assert.equal(r.statusCode, 200);
        assert.equal(r.body, 'overridden');
    });

    it('should update default headers for simple response expectation', async function () {
        client.setDefaultHeaders([
            {"name": "content-type", "values": ["application/json; charset=utf-8"]},
            {"name": "x-test", "values": ["test-value"]}
        ]);
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 203);

        var r = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody");
        assert.equal(r.statusCode, 203);
        assert.equal(r.body, '{"name":"value"}');
        assert.equal(r.headers["content-type"], "application/json; charset=utf-8");
        assert.equal(r.headers["x-test"], "test-value");
    });

    it('should verify exact number of requests have been sent', async function () {
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 203);
        var r = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath", "someBody");
        assert.equal(r.statusCode, 203);

        await client.verify({ 'method': 'POST', 'path': '/somePath', 'body': 'someBody' }, 1, 1);
    });

    it('should verify at least a number of requests have been sent', async function () {
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 203);
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 203);

        var r1 = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath", "someBody");
        assert.equal(r1.statusCode, 203);
        var r2 = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath", "someBody");
        assert.equal(r2.statusCode, 203);

        await client.verify({ 'method': 'POST', 'path': '/somePath', 'body': 'someBody' }, 1);
    });

    it('should fail when no requests have been sent', async function () {
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 203);
        var r = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath", "someBody");
        assert.equal(r.statusCode, 203);

        try {
            await client.verify({ 'path': '/someOtherPath' }, 1);
            assert.fail("should have thrown");
        } catch (message) {
            assert.equal(message, "Request not found at least once");
        }
    });

    it('should fail when not enough exact requests have been sent', async function () {
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 203);
        var r = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath", "someBody");
        assert.equal(r.statusCode, 203);

        try {
            await client.verify({ 'method': 'POST', 'path': '/somePath', 'body': 'someBody' }, 2, 3);
            assert.fail("should have thrown");
        } catch (message) {
            assert.equal(message, "Request not found between 2 and 3 times");
        }
    });

    it('should fail when not enough at least requests have been sent', async function () {
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 203);
        var r = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath", "someBody");
        assert.equal(r.statusCode, 203);

        try {
            await client.verify({ 'method': 'POST', 'path': '/somePath', 'body': 'someBody' }, 2);
            assert.fail("should have thrown");
        } catch (message) {
            assert.equal(message, "Request not found at least 2 times");
        }
    });

    it('should pass when correct sequence of requests have been sent', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        var r1 = await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        assert.equal(r1.statusCode, 201);

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");
        assert.equal(r2.statusCode, 202);

        await client.verifySequence(
            { 'method': 'POST', 'path': '/somePathOne', 'body': 'someBody' },
            { 'method': 'GET', 'path': '/notFound' },
            { 'method': 'GET', 'path': '/somePathTwo' }
        );
    });

    it('should fail when incorrect sequence (wrong order) of requests have been sent', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");

        try {
            await client.verifySequence(
                { 'body': 'someBody', 'method': 'POST', 'path': '/somePathOne' },
                { 'method': 'GET', 'path': '/somePathTwo' },
                { 'method': 'GET', 'path': '/notFound' }
            );
            assert.fail("should have thrown");
        } catch (message) {
            assert.equal(message, "Request sequence not found");
        }
    });

    it('should fail when incorrect sequence (first request incorrect body) of requests have been sent', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");

        try {
            await client.verifySequence(
                { 'body': 'some_incorrect_body', 'method': 'POST', 'path': '/somePathOne' },
                { 'method': 'GET', 'path': '/notFound' },
                { 'method': 'GET', 'path': '/somePathTwo' }
            );
            assert.fail("should have thrown");
        } catch (message) {
            assert.equal(message, "Request sequence not found");
        }
    });

    it('should clear expectations and logs by path', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathTwo', {name: 'value'}, 200);

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"value"}');

        await client.clear('/somePathOne');

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne"), function (err) { return err === "404 Not Found"; });

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"value"}');

        await client.clear('/somePathOne');
        var requests = await client.retrieveRecordedRequests({ "path": "/somePathOne" });
        assert.equal(requests.length, 0);

        var requests2 = await client.retrieveRecordedRequests({ "httpRequest": { "path": "/somePathTwo" } });
        assert.equal(requests2.length, 1);
        assert.equal(requests2[0].path, '/somePathTwo');
    });

    it('should clear expectations by request matcher', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathTwo', {name: 'value'}, 200);

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"value"}');

        await client.clear({ "path": "/somePathOne" });

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne"), function (err) { return err === "404 Not Found"; });

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"value"}');
    });

    it('should clear expectations by expectation matcher', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathTwo', {name: 'value'}, 200);

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"value"}');

        await client.clear({ "httpRequest": { "path": "/somePathOne" } });

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne"), function (err) { return err === "404 Not Found"; });

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"value"}');
    });

    it('should clear only logs by path', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathTwo', {name: 'value'}, 200);

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"value"}');

        await client.clear('/somePathOne', 'EXPECTATIONS');

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne"), function (err) { return err === "404 Not Found"; });

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"value"}');

        await client.clear('/somePathOne', 'LOG');
        var requests = await client.retrieveRecordedRequests({ "path": "/somePathOne" });
        assert.equal(requests.length, 0);
    });

    it('should clear only expectation by path', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathTwo', {name: 'value'}, 200);

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"value"}');

        await client.clear('/somePathOne', 'EXPECTATIONS');

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne"), function (err) { return err === "404 Not Found"; });

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"value"}');

        await client.clear('/somePathOne', 'LOG');
        var requests = await client.retrieveRecordedRequests({ "path": "/somePathOne" });
        assert.equal(requests.length, 0);

        var requests2 = await client.retrieveRecordedRequests({ "httpRequest": { "path": "/somePathTwo" } });
        assert.equal(requests2.length, 1);
        assert.equal(requests2[0].path, '/somePathTwo');
    });

    it('should reset expectations', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathTwo', {name: 'value'}, 200);

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"value"}');

        await client.reset();

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne"), function (err) { return err === "404 Not Found"; });
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo"), function (err) { return err === "404 Not Found"; });
    });

    it('should retrieve some expectations using object matcher', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        var expectations = await client.retrieveActiveExpectations({ "httpRequest": { "path": "/somePathOne" } });
        assert.equal(expectations.length, 2);
        assert.equal(expectations[0].httpRequest.path, '/somePathOne');
        assert.equal(expectations[0].httpResponse.body, '{"name":"one"}');
        assert.equal(expectations[0].httpResponse.statusCode, 201);
        assert.equal(expectations[1].httpRequest.path, '/somePathOne');
        assert.equal(expectations[1].httpResponse.body, '{"name":"one"}');
        assert.equal(expectations[1].httpResponse.statusCode, 201);
    });

    it('should retrieve some expectations using path', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        var expectations = await client.retrieveActiveExpectations("/somePathOne");
        assert.equal(expectations.length, 2);
        assert.equal(expectations[0].httpRequest.path, '/somePathOne');
        assert.equal(expectations[0].httpResponse.body, '{"name":"one"}');
        assert.equal(expectations[0].httpResponse.statusCode, 201);
        assert.equal(expectations[1].httpRequest.path, '/somePathOne');
        assert.equal(expectations[1].httpResponse.body, '{"name":"one"}');
        assert.equal(expectations[1].httpResponse.statusCode, 201);
    });

    it('should retrieve all expectations using object matcher', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        var expectations = await client.retrieveActiveExpectations({ "httpRequest": { "path": "/somePath.*" } });
        assert.equal(expectations.length, 3);
        assert.equal(expectations[0].httpRequest.path, '/somePathOne');
        assert.equal(expectations[1].httpRequest.path, '/somePathOne');
        assert.equal(expectations[2].httpRequest.path, '/somePathTwo');
    });

    it('should retrieve all expectations using null matcher', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        var expectations = await client.retrieveActiveExpectations();
        assert.equal(expectations.length, 3);
    });

    it('should retrieve some requests using object matcher', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");

        var requests = await client.retrieveRecordedRequests({ "httpRequest": { "path": "/somePathOne" } });
        assert.equal(requests.length, 2);
        assert.equal(requests[0].path, '/somePathOne');
        assert.equal(requests[0].method, 'POST');
        assert.equal(requests[0].body, 'someBody');
        assert.equal(requests[1].path, '/somePathOne');
        assert.equal(requests[1].method, 'GET');
    });

    it('should retrieve some requests using path', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");

        var requests = await client.retrieveRecordedRequests("/somePathOne");
        assert.equal(requests.length, 2);
        assert.equal(requests[0].path, '/somePathOne');
        assert.equal(requests[0].method, 'POST');
        assert.equal(requests[0].body, 'someBody');
        assert.equal(requests[1].path, '/somePathOne');
        assert.equal(requests[1].method, 'GET');
    });

    it('should retrieve all requests using object matcher', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");

        var requests = await client.retrieveRecordedRequests({ "httpRequest": { "path": "/.*" } });
        assert.equal(requests.length, 4);
        assert.equal(requests[0].path, '/somePathOne');
        assert.equal(requests[0].method, 'POST');
        assert.equal(requests[0].body, 'someBody');
        assert.equal(requests[1].path, '/somePathOne');
        assert.equal(requests[1].method, 'GET');
        assert.equal(requests[2].path, '/notFound');
        assert.equal(requests[2].method, 'GET');
        assert.equal(requests[3].path, '/somePathTwo');
        assert.equal(requests[3].method, 'GET');
    });

    it('should retrieve all requests using path', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");

        var requests = await client.retrieveRecordedRequests("/.*");
        assert.equal(requests.length, 4);
        assert.equal(requests[0].path, '/somePathOne');
        assert.equal(requests[0].method, 'POST');
        assert.equal(requests[0].body, 'someBody');
        assert.equal(requests[1].path, '/somePathOne');
        assert.equal(requests[1].method, 'GET');
        assert.equal(requests[2].path, '/notFound');
        assert.equal(requests[2].method, 'GET');
        assert.equal(requests[3].path, '/somePathTwo');
        assert.equal(requests[3].method, 'GET');
    });

    it('should retrieve some requests and responses using object matcher', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");

        var responses = await client.retrieveRecordedRequestsAndResponses({ "httpRequest": { "path": "/somePathOne" } });
        assert.equal(responses.length, 2);
        assert.equal(responses[0].httpRequest.path, '/somePathOne');
        assert.equal(responses[0].httpRequest.method, 'POST');
        assert.equal(responses[0].httpRequest.body, 'someBody');
        assert.equal(responses[0].httpResponse.statusCode, 201);
        assert.equal(responses[0].httpResponse.body, '{"name":"one"}');
        assert.equal(responses[1].httpRequest.path, '/somePathOne');
        assert.equal(responses[1].httpRequest.method, 'GET');
        assert.equal(responses[1].httpResponse.statusCode, 201);
        assert.equal(responses[1].httpResponse.body, '{"name":"one"}');
    });

    it('should retrieve some requests and responses using path', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");

        var responses = await client.retrieveRecordedRequestsAndResponses("/somePathOne");
        assert.equal(responses.length, 2);
        assert.equal(responses[0].httpRequest.path, '/somePathOne');
        assert.equal(responses[0].httpRequest.method, 'POST');
        assert.equal(responses[0].httpRequest.body, 'someBody');
        assert.equal(responses[0].httpResponse.statusCode, 201);
        assert.equal(responses[0].httpResponse.body, '{"name":"one"}');
        assert.equal(responses[1].httpRequest.path, '/somePathOne');
        assert.equal(responses[1].httpRequest.method, 'GET');
        assert.equal(responses[1].httpResponse.statusCode, 201);
        assert.equal(responses[1].httpResponse.body, '{"name":"one"}');
    });

    it('should retrieve some logs using object matcher', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });

        var logMessages = await client.retrieveLogMessages({ "httpRequest": { "path": "/somePathOne" } });
        assert.equal(logMessages.length, 6);
        assert.ok(logMessages[0].indexOf('resetting all expectations and request logs') !== -1, logMessages[0]);
        assert.ok(logMessages[1].indexOf("creating expectation:\n") !== -1, logMessages[1]);
        assert.ok(logMessages[2].indexOf("received request:\n\n  {\n    \"method\" : \"POST\",\n    \"path\" : \"/somePathOne\",\n") !== -1, logMessages[2]);
        assert.ok(logMessages[3].indexOf("request:\n\n  {\n    \"method\" : \"POST\",\n    \"path\" : \"/somePathOne\",\n") !== -1, logMessages[3]);
    });

    it('should retrieve some logs using path', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });

        var logMessages = await client.retrieveLogMessages("/somePathOne");
        assert.equal(logMessages.length, 6);
        assert.ok(logMessages[0].indexOf('resetting all expectations and request logs') !== -1, logMessages[0]);
        assert.ok(logMessages[1].indexOf("creating expectation:\n") !== -1, logMessages[1]);
        assert.ok(logMessages[2].indexOf("received request:\n\n  {\n    \"method\" : \"POST\",\n    \"path\" : \"/somePathOne\",\n") !== -1, logMessages[2]);
        assert.ok(logMessages[3].indexOf("request:\n\n  {\n    \"method\" : \"POST\",\n    \"path\" : \"/somePathOne\",\n") !== -1, logMessages[3]);
    });

    it('should bind to additional port', { skip: isExternalMode ? 'newly bound port is not published from a Docker container' : false }, async function () {
        var response = await client.bind([mockServerPort + 1]);
        assert.equal(response.statusCode, 200);
        assert.ok(response.body.indexOf("\"artifactId\" : \"mockserver-core\"") !== -1,
            "bind response should contain artifactId, got: " + response.body);
        assert.ok(response.body.indexOf("\"ports\" : [ " + (mockServerPort + 1) + " ]") !== -1,
            "bind response should contain new port, got: " + response.body);

        var status = await sendRequest("PUT", mockServerHost, mockServerPort + 1, "/status");
        assert.equal(status.statusCode, 200);
        assert.ok(status.body.indexOf("\"artifactId\" : \"mockserver-core\"") !== -1,
            "status response should contain artifactId, got: " + status.body);
        assert.ok(status.body.indexOf("\"ports\" : [ " + mockServerPort + ", " + (mockServerPort + 1) + " ]") !== -1,
            "status response should contain both ports, got: " + status.body);
    });

    // ========================================================================
    // GAP-CLOSING TESTS: clock, service-chaos, by-id, verifyZeroInteractions,
    // retrieveLogMessages (already above), retrieveRecordedExpectations
    // ========================================================================

    it('should freeze and query clock', async function () {
        await client.freezeClock("2025-01-15T09:30:00Z");
        var status = await client.clockStatus();
        assert.equal(status.frozen, true);
        assert.ok(status.currentInstant, "clockStatus should include currentInstant");
        assert.ok(typeof status.currentEpochMillis === 'number', "clockStatus should include currentEpochMillis");
    });

    it('should advance frozen clock', async function () {
        await client.freezeClock("2025-01-15T09:30:00Z");
        var before = await client.clockStatus();
        await client.advanceClock(60000);
        var after = await client.clockStatus();
        assert.equal(after.frozen, true);
        assert.ok(after.currentEpochMillis >= before.currentEpochMillis + 60000,
            "clock should have advanced by at least 60000ms");
    });

    it('should reset clock to real time', async function () {
        await client.freezeClock("2025-01-15T09:30:00Z");
        await client.resetClock();
        var status = await client.clockStatus();
        assert.equal(status.frozen, false);
    });

    it('should freeze clock without explicit instant', async function () {
        await client.freezeClock();
        var status = await client.clockStatus();
        assert.equal(status.frozen, true);
    });

    it('should set and query service chaos', async function () {
        var chaosProfile = {
            errorStatus: 503,
            errorProbability: 0.5,
            dropConnectionProbability: 0.0
        };
        await client.setServiceChaos("payments.svc", chaosProfile);
        var status = await client.serviceChaosStatus();
        assert.ok(status.services, "serviceChaosStatus should include services");
        // The host key may be normalized by the server (lowercased, etc.)
        var hosts = Object.keys(status.services);
        assert.ok(hosts.length >= 1, "should have at least one chaos registration");
    });

    it('should remove service chaos for a specific host', async function () {
        await client.setServiceChaos("cache.svc", { errorStatus: 500, errorProbability: 1.0 });
        await client.removeServiceChaos("cache.svc");
        var status = await client.serviceChaosStatus();
        var hosts = Object.keys(status.services || {});
        var hasCacheSvc = hosts.some(function (h) { return h.toLowerCase().indexOf("cache.svc") !== -1; });
        assert.equal(hasCacheSvc, false, "cache.svc chaos should have been removed");
    });

    it('should clear all service chaos', async function () {
        await client.setServiceChaos("a.svc", { errorStatus: 500, errorProbability: 1.0 });
        await client.setServiceChaos("b.svc", { errorStatus: 503, errorProbability: 0.5 });
        await client.clearServiceChaos();
        var status = await client.serviceChaosStatus();
        var hosts = Object.keys(status.services || {});
        assert.equal(hosts.length, 0, "all service chaos should have been cleared");
    });

    it('should verify by expectation id', async function () {
        // Create expectation with known id
        await client.mockAnyResponse({
            'id': 'test-verify-by-id',
            'httpRequest': { 'path': '/byIdPath' },
            'httpResponse': { 'statusCode': 200, 'body': 'byId' },
            'times': { 'unlimited': true }
        });

        await sendRequest("GET", mockServerHost, mockServerPort, "/byIdPath");

        await client.verifyById({ 'id': 'test-verify-by-id' }, 1, 1);
    });

    it('should fail verify by expectation id when not matched', async function () {
        await client.mockAnyResponse({
            'id': 'test-verify-by-id-fail',
            'httpRequest': { 'path': '/byIdPathFail' },
            'httpResponse': { 'statusCode': 200, 'body': 'byId' },
            'times': { 'unlimited': true }
        });

        // do NOT send request
        try {
            await client.verifyById({ 'id': 'test-verify-by-id-fail' }, 1);
            assert.fail("should have thrown");
        } catch (message) {
            assert.ok(typeof message === 'string', "error should be a string");
        }
    });

    it('should verify sequence by expectation ids', async function () {
        await client.mockAnyResponse({
            'id': 'seq-a',
            'httpRequest': { 'path': '/seqA' },
            'httpResponse': { 'statusCode': 200, 'body': 'a' },
            'times': { 'unlimited': true }
        });
        await client.mockAnyResponse({
            'id': 'seq-b',
            'httpRequest': { 'path': '/seqB' },
            'httpResponse': { 'statusCode': 200, 'body': 'b' },
            'times': { 'unlimited': true }
        });

        await sendRequest("GET", mockServerHost, mockServerPort, "/seqA");
        await sendRequest("GET", mockServerHost, mockServerPort, "/seqB");

        await client.verifySequenceById({ 'id': 'seq-a' }, { 'id': 'seq-b' });
    });

    it('should clear by expectation id', async function () {
        await client.mockAnyResponse({
            'id': 'clear-by-id-test',
            'httpRequest': { 'path': '/clearById' },
            'httpResponse': { 'statusCode': 200, 'body': 'cleared' },
            'times': { 'unlimited': true }
        });

        var r = await sendRequest("GET", mockServerHost, mockServerPort, "/clearById");
        assert.equal(r.statusCode, 200);

        await client.clearById('clear-by-id-test');

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/clearById"), function (err) { return err === "404 Not Found"; });
    });

    it('should verify zero interactions', async function () {
        // After reset (from beforeEach), no requests should have been recorded
        await client.verifyZeroInteractions();
    });

    it('should fail verify zero interactions when requests exist', async function () {
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 200);
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePath");

        try {
            await client.verifyZeroInteractions();
            assert.fail("should have thrown");
        } catch (message) {
            assert.ok(typeof message === 'string', "error should be a string");
        }
    });

    it('should retrieve recorded expectations', async function () {
        // retrieveRecordedExpectations returns expectations recorded by the
        // proxy. Since we are not proxying, this should return an empty array
        // (but the endpoint should work without error).
        var expectations = await client.retrieveRecordedExpectations("/.*");
        assert.ok(Array.isArray(expectations), "should return an array");
    });
});
