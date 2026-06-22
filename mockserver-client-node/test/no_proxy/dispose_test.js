'use strict';

// Smoke test for explicit resource management (TC39 `using` / `await using`).
// Uses a tiny local HTTP server standing in for MockServer so it can run
// without a real MockServer instance — it only asserts that disposing the
// client issues a PUT /mockserver/reset.

var { describe, it } = require('node:test');
var assert = require('node:assert/strict');
var http = require('http');
var mockServer = require('../../');
var mockServerClient = mockServer.mockServerClient;

function startStubServer() {
    return new Promise(function (resolve) {
        var hits = [];
        var server = http.createServer(function (req, res) {
            hits.push({ method: req.method, url: req.url });
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end('[]');
        });
        server.listen(0, '127.0.0.1', function () {
            resolve({ server: server, port: server.address().port, hits: hits });
        });
    });
}

describe('explicit resource management', function () {
    it('exposes Symbol.asyncDispose on the client', function () {
        var client = mockServerClient('127.0.0.1', 1080);
        assert.equal(typeof client[Symbol.asyncDispose], 'function');
    });

    it('resets the server when disposed via Symbol.asyncDispose', async function () {
        var stub = await startStubServer();
        try {
            var client = mockServerClient('127.0.0.1', stub.port);
            await client[Symbol.asyncDispose]();
            var resetHit = stub.hits.find(function (h) {
                return h.url === '/mockserver/reset';
            });
            assert.ok(resetHit, 'expected a request to /mockserver/reset');
            assert.equal(resetHit.method, 'PUT');
        } finally {
            stub.server.close();
        }
    });
});
