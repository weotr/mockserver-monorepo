'use strict';

var crypto = require('crypto');
var { describe, it, beforeEach } = require('node:test');
var assert = require('node:assert/strict');
var mockServer = require('../../');
var mockServerClient = mockServer.mockServerClient;
var http = require('http');

// Narrow suppressor for unhandled Q-promise rejections caused by WebSocket
// reconnect attempts during reset()/teardown — see no_proxy test file for
// full rationale.  Only suppress known benign teardown patterns.
process.on('unhandledRejection', function (reason) {
    var msg = String(reason || '');
    if (/ECONNREFUSED/.test(msg) ||
        /Can't connect to MockServer/.test(msg) ||
        /Max reconnect attempts/.test(msg) ||
        /WebSocket/.test(msg)) {
        return;
    }
    console.error('[unhandledRejection — NOT suppressed]', reason);
});

var mockServerHost = process.env.MOCKSERVER_HOST || "localhost";
var mockServerPort = parseInt(process.env.MOCKSERVER_PORT, 10) || 1080;

function guid() {
    return crypto.randomUUID();
}

function sendRequest(method, host, port, path, jsonBody, headers) {
    return new Promise(function (resolve, reject) {
        var body = (typeof jsonBody === "string" ? jsonBody : JSON.stringify(jsonBody || ""));
        var options = {
            method: method,
            host: mockServerHost,
            port: mockServerPort,
            headers: headers || {},
            path: path
        };
        options.headers.Host = host + ":" + port;
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

describe('proxy client node (with proxy)', { concurrency: 1 }, function () {
    var client;
    var uuid = guid();

    beforeEach(async function () {
        client = mockServerClient(mockServerHost, mockServerPort);
        await client.reset();
    });

    it('should create full expectation with string body', async function () {
        await client.mockAnyResponse({
            'httpRequest': {
                'method': 'POST',
                'path': '/somePath',
                'queryStringParameters': [ { 'name': 'test', 'values': ['true'] } ],
                'body': { 'type': "STRING", 'string': 'someBody' }
            },
            'httpResponse': {
                'statusCode': 200,
                'body': JSON.stringify({name: 'value'}),
                'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 }
            },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/otherPath"), function (err) { return err === "404 Not Found"; });

        var response = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody");
        assert.equal(response.statusCode, 200);
        assert.equal(response.body, '{"name":"value"}');

        await assert.rejects(sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody"), function (err) { return err === "404 Not Found"; });
    });

    it('should create full expectation with string body over tls', async function () {
        var clientOverTls = mockServerClient(mockServerHost, mockServerPort, undefined, true);
        await clientOverTls.mockAnyResponse({
            'httpRequest': {
                'method': 'POST',
                'path': '/somePath',
                'queryStringParameters': [ { 'name': 'test', 'values': ['true'] } ],
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
        client.setDefaultHeaders([ {"name": "x-test-default", "values": ["default-value"]} ]);
        await client.mockAnyResponse([
            { 'httpRequest': { 'path': '/somePathOne' }, 'httpResponse': { 'body': JSON.stringify({name: 'one'}) } },
            { 'httpRequest': { 'path': '/somePathTwo' }, 'httpResponse': { 'body': JSON.stringify({name: 'two'}), 'headers': [ {"name": "x-test", "values": ["test-value"]} ] } },
            { 'httpRequest': { 'path': '/somePathThree' }, 'httpResponse': { 'body': JSON.stringify({name: 'three'}) } }
        ]);

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.headers["x-test-default"], "default-value");

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo", "someBody");
        assert.equal(r2.headers["x-test-default"], "default-value");
        assert.equal(r2.headers["x-test"], "test-value");

        var r3 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathThree", "someBody");
        assert.equal(r3.headers["x-test-default"], "default-value");
    });

    it('should match on method only', async function () {
        await client.mockAnyResponse({
            'httpRequest': { 'method': 'GET' },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'first_body'}) },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });
        await client.mockAnyResponse({
            'httpRequest': { 'method': 'POST' },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'second_body'}) },
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
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'first_body'}) },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });
        await client.mockAnyResponse({
            'httpRequest': { 'path': '/secondPath' },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'second_body'}) },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/otherPath"), function (err) { return err === "404 Not Found"; });

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/firstPath");
        assert.equal(r1.body, '{"name":"first_body"}');

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/secondPath");
        assert.equal(r2.body, '{"name":"second_body"}');
    });

    it('should match on cookies only', async function () {
        await client.mockAnyResponse({
            'httpRequest': { 'cookies': [ { 'name': 'cookie', 'value': 'first' } ] },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'first_body'}) },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        var r = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Cookie': 'cookie=first'});
        assert.equal(r.statusCode, 200);
        assert.equal(r.body, '{"name":"first_body"}');
    });

    it('should create simple response expectation', async function () {
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 203);

        await assert.rejects(sendRequest("POST", mockServerHost, mockServerPort, "/otherPath"), function (err) { return err === "404 Not Found"; });

        var r = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody");
        assert.equal(r.statusCode, 203);
        assert.equal(r.body, '{"name":"value"}');
    });

    it('should verify exact number of requests have been sent', async function () {
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 203);
        await sendRequest("POST", mockServerHost, mockServerPort, "/somePath", "someBody");
        await client.verify({ 'method': 'POST', 'path': '/somePath', 'body': 'someBody' }, 1, 1);
    });

    it('should pass when correct sequence of requests have been sent', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);
        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");

        await client.verifySequence(
            { 'method': 'POST', 'path': '/somePathOne', 'body': 'someBody' },
            { 'method': 'GET', 'path': '/somePathTwo' }
        );
    });

    it('should clear expectations and logs by path', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathTwo', {name: 'value'}, 200);

        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        await client.clear('/somePathOne');
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne"), function (err) { return err === "404 Not Found"; });

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");
        assert.equal(r2.statusCode, 200);
    });

    it('should reset expectations', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        await client.reset();

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne"), function (err) { return err === "404 Not Found"; });
    });

    it('should retrieve expectations using object matcher', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        var expectations = await client.retrieveActiveExpectations({ "httpRequest": { "path": "/somePathOne" } });
        assert.equal(expectations.length, 1);
        assert.equal(expectations[0].httpRequest.path, '/somePathOne');
    });

    it('should retrieve requests using object matcher', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");

        var requests = await client.retrieveRecordedRequests({ "httpRequest": { "path": "/somePathOne" } });
        assert.equal(requests.length, 1);
        assert.equal(requests[0].path, '/somePathOne');
    });

    it('should retrieve requests and responses', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");

        var responses = await client.retrieveRecordedRequestsAndResponses({ "httpRequest": { "path": "/somePathOne" } });
        assert.equal(responses.length, 1);
        assert.equal(responses[0].httpRequest.path, '/somePathOne');
        assert.equal(responses[0].httpResponse.statusCode, 201);
    });

    it('should retrieve log messages', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");

        var logMessages = await client.retrieveLogMessages({ "httpRequest": { "path": "/somePathOne" } });
        assert.ok(logMessages.length > 0, "should have log messages");
    });
});
