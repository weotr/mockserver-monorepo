'use strict';

/*
 * Unit tests for the stateful-scenario client surface (no running server).
 *
 * These tests mock the HTTP transport module (sendRequest.js) so they can
 * assert the exact method + path + body the client emits for the
 * /mockserver/scenario REST endpoints, and that the new typed Expectation
 * scenario fields (responseWeights, switchAfter, crossProtocolScenarios) pass
 * straight through to the serialized expectation JSON without being stripped.
 *
 * The MockServer client binds its transport at construction time via
 *   require('./sendRequest').sendRequest(tls, caCertPath)
 * so we replace the module's exported factories with capturing stubs BEFORE
 * constructing the client.
 */

var { describe, it, beforeEach, afterEach } = require('node:test');
var assert = require('node:assert/strict');

var sendRequestModule = require('../../sendRequest');
var mockServerClient = require('../../').mockServerClient;

// Suppress benign unhandled rejections from internal Q promises.
process.on('unhandledRejection', function () {});

// ---------------------------------------------------------------------------
// Transport mocking
// ---------------------------------------------------------------------------

// Every captured call: { method, host, port, path, body }
var captured;

// A resolved-promise stub whose resolved value is configurable per test.
function makeStub(method, responseBody) {
    return function () {
        // factory(tls, caCertPath) -> request fn
        return function (host, port, path, jsonBody) {
            captured.push({
                method: method,
                host: host,
                port: port,
                path: path,
                // mirror sendRequest.js: objects are JSON-stringified on the wire
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

// ---------------------------------------------------------------------------
// Scenario REST helper
// ---------------------------------------------------------------------------

describe('client.scenario(name)', function () {

    it('.set(state) issues PUT /mockserver/scenario/{name} with {state}', async function () {
        var client = clientWith(JSON.stringify({scenarioName: 'Deploy', currentState: 'Deploying'}));
        var result = await client.scenario('Deploy').set('Deploying');

        assert.equal(captured.length, 1);
        assert.equal(captured[0].method, 'PUT');
        assert.equal(captured[0].path, '/mockserver/scenario/Deploy');
        assert.deepEqual(captured[0].body, {state: 'Deploying'});
        assert.deepEqual(result, {scenarioName: 'Deploy', currentState: 'Deploying'});
    });

    it('.set(state, {transitionAfterMs, nextState}) adds the timed-transition fields', async function () {
        var client = clientWith(JSON.stringify({
            scenarioName: 'Deploy', currentState: 'Deploying', nextState: 'Deployed', transitionAfterMs: 5000
        }));
        await client.scenario('Deploy').set('Deploying', {transitionAfterMs: 5000, nextState: 'Deployed'});

        assert.equal(captured[0].method, 'PUT');
        assert.equal(captured[0].path, '/mockserver/scenario/Deploy');
        assert.deepEqual(captured[0].body, {state: 'Deploying', transitionAfterMs: 5000, nextState: 'Deployed'});
    });

    it('.set(state) omits transitionAfterMs/nextState when no options are given', async function () {
        var client = clientWith(JSON.stringify({scenarioName: 'Deploy', currentState: 'Idle'}));
        await client.scenario('Deploy').set('Idle');

        assert.deepEqual(Object.keys(captured[0].body), ['state']);
    });

    it('.trigger(newState) issues PUT /mockserver/scenario/{name}/trigger with {newState}', async function () {
        var client = clientWith(JSON.stringify({scenarioName: 'Deploy', currentState: 'Failed'}));
        var result = await client.scenario('Deploy').trigger('Failed');

        assert.equal(captured[0].method, 'PUT');
        assert.equal(captured[0].path, '/mockserver/scenario/Deploy/trigger');
        assert.deepEqual(captured[0].body, {newState: 'Failed'});
        assert.deepEqual(result, {scenarioName: 'Deploy', currentState: 'Failed'});
    });

    it('.state() issues GET /mockserver/scenario/{name}', async function () {
        var client = clientWith(undefined, JSON.stringify({scenarioName: 'Deploy', currentState: 'Deploying'}));
        var result = await client.scenario('Deploy').state();

        assert.equal(captured[0].method, 'GET');
        assert.equal(captured[0].path, '/mockserver/scenario/Deploy');
        assert.equal(captured[0].body, undefined);
        assert.deepEqual(result, {scenarioName: 'Deploy', currentState: 'Deploying'});
    });

    it('URL-encodes scenario names with reserved characters', async function () {
        var client = clientWith(JSON.stringify({scenarioName: 'a/b c', currentState: 'X'}));
        await client.scenario('a/b c').set('X');

        assert.equal(captured[0].path, '/mockserver/scenario/a%2Fb%20c');
    });
});

describe('client.scenarios()', function () {
    it('issues GET /mockserver/scenario and parses the list', async function () {
        var listBody = {
            scenarios: [
                {scenarioName: 'Deploy', currentState: 'Deploying'},
                {scenarioName: 'Login', currentState: 'LoggedOut'}
            ]
        };
        var client = clientWith(undefined, JSON.stringify(listBody));
        var result = await client.scenarios();

        assert.equal(captured[0].method, 'GET');
        assert.equal(captured[0].path, '/mockserver/scenario');
        assert.deepEqual(result, listBody);
    });
});

// ---------------------------------------------------------------------------
// Expectation passthrough — new typed scenario fields must reach the wire
// ---------------------------------------------------------------------------

describe('mockAnyResponse passthrough of scenario fields', function () {

    it('serializes responseWeights, switchAfter and responseMode unchanged', async function () {
        var client = clientWith(JSON.stringify({}));
        await client.mockAnyResponse({
            httpRequest: {path: '/weighted'},
            httpResponses: [{statusCode: 200}, {statusCode: 500}],
            responseMode: 'WEIGHTED',
            responseWeights: [3, 1],
            switchAfter: 2
        });

        var sent = captured[0].body;
        assert.equal(captured[0].path, '/mockserver/expectation');
        assert.equal(sent.responseMode, 'WEIGHTED');
        assert.deepEqual(sent.responseWeights, [3, 1]);
        assert.equal(sent.switchAfter, 2);
        assert.deepEqual(sent.httpResponses, [{statusCode: 200}, {statusCode: 500}]);
    });

    it('serializes scenarioName/scenarioState/newScenarioState unchanged', async function () {
        var client = clientWith(JSON.stringify({}));
        await client.mockAnyResponse({
            httpRequest: {path: '/sm'},
            httpResponse: {statusCode: 200},
            scenarioName: 'Deploy',
            scenarioState: 'Deploying',
            newScenarioState: 'Deployed'
        });

        var sent = captured[0].body;
        assert.equal(sent.scenarioName, 'Deploy');
        assert.equal(sent.scenarioState, 'Deploying');
        assert.equal(sent.newScenarioState, 'Deployed');
    });

    it('serializes crossProtocolScenarios unchanged (array of trigger objects)', async function () {
        var client = clientWith(JSON.stringify({}));
        var cps = [
            {trigger: 'DNS_QUERY', matchPattern: 'api.example.com', scenarioName: 'Deploy', targetState: 'Resolving'},
            {trigger: 'WEBSOCKET_CONNECT', scenarioName: 'Deploy', targetState: 'Connected'}
        ];
        await client.mockAnyResponse({
            httpRequest: {path: '/cps'},
            httpResponse: {statusCode: 200},
            crossProtocolScenarios: cps
        });

        assert.deepEqual(captured[0].body.crossProtocolScenarios, cps);
    });
});
