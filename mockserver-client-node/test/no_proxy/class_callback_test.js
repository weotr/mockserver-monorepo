'use strict';

/*
 * Unit tests for the class-callback client surface (no running server).
 *
 * Class callbacks are a pure-JSON, REST-only mechanism: the expectation simply
 * carries an httpResponseClassCallback / httpForwardClassCallback action naming
 * a server-side class. No callback WebSocket is involved, so these tests can
 * fully verify the serialized wire shape by mocking the HTTP transport module
 * (sendRequest.js) and asserting the exact PUT body the client emits.
 *
 * They assert two things:
 *   1. client.respondWithClassCallback(...) / forwardWithClassCallback(...)
 *      serialize the right top-level action property with the callbackClass.
 *   2. A raw expectation object carrying httpResponseClassCallback passed to
 *      mockAnyResponse(...) reaches the wire un-stripped (the Node client
 *      serializes raw expectation objects, so unknown/advanced keys must pass
 *      straight through).
 *
 * The MockServer client binds its transport at construction time via
 *   require('./sendRequest').sendRequest(tls, caCertPath)
 * so the module's exported factories are replaced with capturing stubs BEFORE
 * constructing the client.
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
            return Promise.resolve({statusCode: 200, body: responseBody});
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

function clientWith(putBody, getBody) {
    sendRequestModule.sendRequest = makeStub('PUT', putBody);
    sendRequestModule.sendGetRequest = makeStub('GET', getBody);
    sendRequestModule.sendDeleteRequest = makeStub('DELETE', undefined);
    sendRequestModule.sendBinaryRequest = makeStub('PUT', undefined);
    return mockServerClient('localhost', 1080);
}

describe('client.respondWithClassCallback(...)', function () {

    it('serializes httpResponseClassCallback.callbackClass from a class-name string', async function () {
        var client = clientWith(undefined);
        await client.respondWithClassCallback('/somePath', 'com.example.MyResponseCallback');

        assert.equal(captured.length, 1);
        assert.equal(captured[0].method, 'PUT');
        assert.equal(captured[0].path, '/mockserver/expectation');

        var expectation = captured[0].body;
        assert.equal(expectation.httpRequest.path, '/somePath');
        assert.deepEqual(expectation.httpResponseClassCallback, {
            callbackClass: 'com.example.MyResponseCallback'
        });
        // It is a class callback, NOT an object callback.
        assert.equal(expectation.httpResponseObjectCallback, undefined);
    });

    it('passes through a full action object with delay and primary', async function () {
        var client = clientWith(undefined);
        await client.respondWithClassCallback('/somePath', {
            callbackClass: 'com.example.MyResponseCallback',
            delay: {timeUnit: 'MILLISECONDS', value: 100},
            primary: true
        });

        var callback = captured[0].body.httpResponseClassCallback;
        assert.equal(callback.callbackClass, 'com.example.MyResponseCallback');
        assert.deepEqual(callback.delay, {timeUnit: 'MILLISECONDS', value: 100});
        assert.equal(callback.primary, true);
    });

    it('honours a full request matcher object and times/priority/id', async function () {
        var client = clientWith(undefined);
        await client.respondWithClassCallback(
            {method: 'POST', path: '/api'},
            'com.example.MyResponseCallback',
            3,
            10,
            {timeToLive: 60, timeUnit: 'SECONDS', unlimited: false},
            'cb-id-1'
        );

        var expectation = captured[0].body;
        assert.equal(expectation.httpRequest.method, 'POST');
        assert.equal(expectation.httpRequest.path, '/api');
        assert.equal(expectation.httpResponseClassCallback.callbackClass, 'com.example.MyResponseCallback');
        assert.deepEqual(expectation.times, {remainingTimes: 3, unlimited: false});
        assert.equal(expectation.priority, 10);
        assert.equal(expectation.id, 'cb-id-1');
        assert.deepEqual(expectation.timeToLive, {timeToLive: 60, timeUnit: 'SECONDS', unlimited: false});
    });
});

describe('client.forwardWithClassCallback(...)', function () {

    it('serializes httpForwardClassCallback.callbackClass', async function () {
        var client = clientWith(undefined);
        await client.forwardWithClassCallback('/somePath', 'com.example.MyForwardCallback');

        var expectation = captured[0].body;
        assert.equal(expectation.httpRequest.path, '/somePath');
        assert.deepEqual(expectation.httpForwardClassCallback, {
            callbackClass: 'com.example.MyForwardCallback'
        });
        assert.equal(expectation.httpForwardObjectCallback, undefined);
    });
});

describe('mockAnyResponse(...) with a raw class-callback expectation', function () {

    it('passes httpResponseClassCallback straight through un-stripped', async function () {
        var client = clientWith(undefined);
        await client.mockAnyResponse({
            httpRequest: {path: '/some.*'},
            httpResponseClassCallback: {
                callbackClass: 'org.mockserver.examples.SomeCallback'
            },
            times: {remainingTimes: 1, unlimited: false}
        });

        var expectation = captured[0].body;
        assert.deepEqual(expectation.httpResponseClassCallback, {
            callbackClass: 'org.mockserver.examples.SomeCallback'
        });
        // The raw expectation declares a class callback, so the default-response
        // header logic must NOT inject a default httpResponse.
        assert.equal(expectation.httpResponse, undefined);
    });

    it('passes httpForwardClassCallback straight through un-stripped', async function () {
        var client = clientWith(undefined);
        await client.mockAnyResponse({
            httpRequest: {path: '/forward.*'},
            httpForwardClassCallback: {
                callbackClass: 'org.mockserver.examples.SomeForwardCallback'
            }
        });

        var expectation = captured[0].body;
        assert.deepEqual(expectation.httpForwardClassCallback, {
            callbackClass: 'org.mockserver.examples.SomeForwardCallback'
        });
        assert.equal(expectation.httpResponse, undefined);
    });
});
