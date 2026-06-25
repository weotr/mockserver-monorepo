'use strict';

/*
 * Unit tests for the fluent client.when(...).respond(...) DSL (no running
 * server). Mirrors scenario_helper_test.js: the HTTP transport module
 * (sendRequest.js) is mocked so the tests assert the exact method + path +
 * expectation body the client PUTs to /mockserver/expectation.
 *
 * The DSL is purely additive sugar over mockAnyResponse / mockWithCallback, so
 * these tests pin the JSON shape the chain builds for each terminal action
 * (respond/forward/error) and for the builder refinements
 * (withTimes/withTimeToLive/withPriority/withId).
 */

var { describe, it, beforeEach, afterEach } = require('node:test');
var assert = require('node:assert/strict');

var sendRequestModule = require('../../sendRequest');
var mockServerClient = require('../../').mockServerClient;

// Suppress benign unhandled rejections from internal Q promises.
process.on('unhandledRejection', function () {});

// Every captured call: { method, host, port, path, body }
var captured;

function makeStub(method, responseBody) {
    return function () {
        return function (host, port, path, jsonBody) {
            captured.push({
                method: method,
                host: host,
                port: port,
                path: path,
                body: (typeof jsonBody === 'string' || jsonBody === undefined)
                    ? jsonBody
                    : JSON.parse(JSON.stringify(jsonBody))
            });
            return Promise.resolve({statusCode: 201, body: responseBody});
        };
    };
}

var originalSend, originalGet, originalDelete, originalBinary;

beforeEach(function () {
    captured = [];
    originalSend = sendRequestModule.sendRequest;
    originalGet = sendRequestModule.sendGetRequest;
    originalDelete = sendRequestModule.sendDeleteRequest;
    originalBinary = sendRequestModule.sendBinaryRequest;
});

afterEach(function () {
    sendRequestModule.sendRequest = originalSend;
    sendRequestModule.sendGetRequest = originalGet;
    sendRequestModule.sendDeleteRequest = originalDelete;
    sendRequestModule.sendBinaryRequest = originalBinary;
});

function client() {
    sendRequestModule.sendRequest = makeStub('PUT', JSON.stringify({}));
    sendRequestModule.sendGetRequest = makeStub('GET', undefined);
    sendRequestModule.sendDeleteRequest = makeStub('DELETE', undefined);
    sendRequestModule.sendBinaryRequest = makeStub('PUT', undefined);
    return mockServerClient('localhost', 1080);
}

describe('client.when(request).respond(response)', function () {

    it('PUTs an expectation with the request matcher and httpResponse action', async function () {
        await client()
            .when({path: '/somePath'})
            .respond({statusCode: 200, body: 'some_response_body'});

        assert.equal(captured.length, 1);
        assert.equal(captured[0].method, 'PUT');
        assert.equal(captured[0].path, '/mockserver/expectation');
        assert.deepEqual(captured[0].body.httpRequest, {path: '/somePath'});
        assert.deepEqual(captured[0].body.httpResponse, {statusCode: 200, body: 'some_response_body'});
    });

    it('defaults times to a single non-unlimited match when not supplied', async function () {
        await client().when({path: '/p'}).respond({statusCode: 200});

        assert.deepEqual(captured[0].body.times, {remainingTimes: 1, unlimited: false});
        // unset positional args must not leak into the JSON
        assert.equal(captured[0].body.priority, undefined);
        assert.equal(captured[0].body.timeToLive, undefined);
        assert.equal(captured[0].body.id, undefined);
    });

    it('passes positional times / timeToLive / priority through', async function () {
        await client()
            .when({path: '/p'}, 3, {timeToLive: 60, timeUnit: 'SECONDS', unlimited: false}, 12)
            .respond({statusCode: 200});

        assert.deepEqual(captured[0].body.times, {remainingTimes: 3, unlimited: false});
        assert.deepEqual(captured[0].body.timeToLive, {timeToLive: 60, timeUnit: 'SECONDS', unlimited: false});
        assert.equal(captured[0].body.priority, 12);
    });

    it('accepts a plain path string as the request matcher', async function () {
        await client().when('/stringPath').respond({statusCode: 204});

        assert.equal(captured[0].body.httpRequest.path, '/stringPath');
        assert.equal(captured[0].body.httpResponse.statusCode, 204);
    });
});

describe('client.when(...) fluent builder refinements', function () {

    it('withTimes / withTimeToLive / withPriority / withId set expectation fields', async function () {
        await client()
            .when({path: '/p'})
            .withTimes(5)
            .withTimeToLive({timeToLive: 30, timeUnit: 'SECONDS', unlimited: false})
            .withPriority(7)
            .withId('expectation-1')
            .respond({statusCode: 200});

        assert.deepEqual(captured[0].body.times, {remainingTimes: 5, unlimited: false});
        assert.deepEqual(captured[0].body.timeToLive, {timeToLive: 30, timeUnit: 'SECONDS', unlimited: false});
        assert.equal(captured[0].body.priority, 7);
        assert.equal(captured[0].body.id, 'expectation-1');
    });

    it('withTimes accepts a full Times object', async function () {
        await client()
            .when({path: '/p'})
            .withTimes({remainingTimes: 0, unlimited: true})
            .respond({statusCode: 200});

        assert.deepEqual(captured[0].body.times, {remainingTimes: 0, unlimited: true});
    });
});

describe('client.when(...) terminal actions other than respond', function () {

    it('.forward(forward) sets the httpForward action', async function () {
        await client()
            .when({path: '/p'})
            .forward({host: 'localhost', port: 8081, scheme: 'HTTP'});

        assert.deepEqual(captured[0].body.httpForward, {host: 'localhost', port: 8081, scheme: 'HTTP'});
        assert.equal(captured[0].body.httpResponse, undefined);
    });

    it('.error(error) sets the httpError action', async function () {
        await client()
            .when({path: '/p'})
            .error({dropConnection: true});

        assert.deepEqual(captured[0].body.httpError, {dropConnection: true});
        assert.equal(captured[0].body.httpResponse, undefined);
    });

    it('.respond(classCallback) maps a callbackClass action to httpResponseClassCallback', async function () {
        await client()
            .when({path: '/p'})
            .respond({callbackClass: 'com.example.MyResponseCallback'});

        assert.deepEqual(captured[0].body.httpResponseClassCallback, {callbackClass: 'com.example.MyResponseCallback'});
        assert.equal(captured[0].body.httpResponse, undefined);
    });

    it('.respond(template) maps a template action to httpResponseTemplate', async function () {
        await client()
            .when({path: '/p'})
            .respond({templateType: 'MUSTACHE', template: '{ "statusCode": 200 }'});

        assert.deepEqual(captured[0].body.httpResponseTemplate, {templateType: 'MUSTACHE', template: '{ "statusCode": 200 }'});
        assert.equal(captured[0].body.httpResponse, undefined);
    });

    it('.forward(classCallback) maps a callbackClass action to httpForwardClassCallback', async function () {
        await client()
            .when({path: '/p'})
            .forward({callbackClass: 'com.example.MyForwardCallback'});

        assert.deepEqual(captured[0].body.httpForwardClassCallback, {callbackClass: 'com.example.MyForwardCallback'});
        assert.equal(captured[0].body.httpForward, undefined);
    });

    it('.forward(template) maps a template action to httpForwardTemplate', async function () {
        await client()
            .when({path: '/p'})
            .forward({templateType: 'MUSTACHE', template: '{ "path": "/other" }'});

        assert.deepEqual(captured[0].body.httpForwardTemplate, {templateType: 'MUSTACHE', template: '{ "path": "/other" }'});
        assert.equal(captured[0].body.httpForward, undefined);
        assert.equal(captured[0].body.httpForwardClassCallback, undefined);
    });

    it('.forward(override) maps an httpRequest-carrying action to httpOverrideForwardedRequest', async function () {
        await client()
            .when({path: '/p'})
            .forward({httpRequest: {host: 'localhost', port: 8081, path: '/rewritten'}});

        assert.deepEqual(captured[0].body.httpOverrideForwardedRequest, {httpRequest: {host: 'localhost', port: 8081, path: '/rewritten'}});
        assert.equal(captured[0].body.httpForward, undefined);
    });
});

describe('client.when(...) local callback terminals', function () {

    // .callback() / .forwardCallback() open the callback WebSocket and are thin
    // delegations to the already-tested mockWithCallback / mockWithForwardCallback
    // (covered by class_callback_test.js and the live mock_server_node_client
    // integration tests). The transport stub here does not stand up a WebSocket,
    // so these tests only assert the chain exposes the delegating terminals.
    it('exposes .callback and .forwardCallback delegating terminals', function () {
        var chain = client().when({path: '/p'});
        assert.equal(typeof chain.callback, 'function');
        assert.equal(typeof chain.forwardCallback, 'function');
    });
});
