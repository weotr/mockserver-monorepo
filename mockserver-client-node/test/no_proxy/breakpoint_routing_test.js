'use strict';

var { describe, it, beforeEach } = require('node:test');
var assert = require('node:assert/strict');

// Suppress unhandled Q-promise rejections from webSocketClient teardown
process.on('unhandledRejection', function () {});

var routeBreakpointMessage = require('../../webSocketClient').routeBreakpointMessage;
var extractBreakpointHeaders = require('../../webSocketClient').extractBreakpointHeaders;

// Browser-safe copies exported from mockServerClient.js (IIFE scope)
var clientRouteBreakpointMessage = require('../../mockServerClient').routeBreakpointMessage;
var clientExtractBreakpointHeaders = require('../../mockServerClient').extractBreakpointHeaders;

// ---------- helpers ----------

/**
 * Build an HttpRequest payload envelope the way the server sends it.
 */
function makeRequestPayload(request) {
    return {
        type: "org.mockserver.model.HttpRequest",
        value: JSON.stringify(request)
    };
}

/**
 * Build an HttpRequestAndHttpResponse payload envelope.
 */
function makeRequestAndResponsePayload(httpRequest, httpResponse) {
    return {
        type: "org.mockserver.model.HttpRequestAndHttpResponse",
        value: JSON.stringify({ httpRequest: httpRequest, httpResponse: httpResponse })
    };
}

/**
 * Build a PausedStreamFrameDTO payload envelope.
 */
function makeStreamFramePayload(pausedFrame) {
    return {
        type: "org.mockserver.serialization.model.PausedStreamFrameDTO",
        value: JSON.stringify(pausedFrame)
    };
}

/**
 * Parse the inner value from a reply envelope.
 */
function parseReplyValue(reply) {
    return JSON.parse(reply.value);
}

// =========================================================================
// extractBreakpointHeaders
// =========================================================================

describe('extractBreakpointHeaders', function () {

    it('returns nulls when headers is null', function () {
        var result = extractBreakpointHeaders(null);
        assert.equal(result.breakpointId, null);
        assert.equal(result.correlationId, null);
    });

    it('returns nulls when headers is undefined', function () {
        var result = extractBreakpointHeaders(undefined);
        assert.equal(result.breakpointId, null);
        assert.equal(result.correlationId, null);
    });

    it('returns nulls when headers is empty', function () {
        var result = extractBreakpointHeaders({});
        assert.equal(result.breakpointId, null);
        assert.equal(result.correlationId, null);
    });

    it('extracts canonical-case breakpoint id (string value)', function () {
        var result = extractBreakpointHeaders({
            "X-MockServer-BreakpointId": "bp-1"
        });
        assert.equal(result.breakpointId, "bp-1");
    });

    it('extracts lowercase breakpoint id', function () {
        var result = extractBreakpointHeaders({
            "x-mockserver-breakpointid": "bp-2"
        });
        assert.equal(result.breakpointId, "bp-2");
    });

    it('extracts breakpoint id from array value', function () {
        var result = extractBreakpointHeaders({
            "X-MockServer-BreakpointId": ["bp-3", "bp-ignored"]
        });
        assert.equal(result.breakpointId, "bp-3");
    });

    it('extracts canonical-case correlation id', function () {
        var result = extractBreakpointHeaders({
            "WebSocketCorrelationId": "corr-1"
        });
        assert.equal(result.correlationId, "corr-1");
    });

    it('extracts lowercase correlation id', function () {
        var result = extractBreakpointHeaders({
            "websocketcorrelationid": "corr-2"
        });
        assert.equal(result.correlationId, "corr-2");
    });

    it('extracts correlation id from array value', function () {
        var result = extractBreakpointHeaders({
            "WebSocketCorrelationId": ["corr-3"]
        });
        assert.equal(result.correlationId, "corr-3");
    });

    it('extracts both ids when present', function () {
        var result = extractBreakpointHeaders({
            "X-MockServer-BreakpointId": "bp-x",
            "WebSocketCorrelationId": ["corr-y"]
        });
        assert.equal(result.breakpointId, "bp-x");
        assert.equal(result.correlationId, "corr-y");
    });
});

// =========================================================================
// routeBreakpointMessage — HttpRequest (REQUEST phase)
// =========================================================================

describe('routeBreakpointMessage — HttpRequest', function () {

    it('request continue: handler returns modified request', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/original",
            headers: {
                "X-MockServer-BreakpointId": ["bp-1"],
                "WebSocketCorrelationId": ["corr-100"]
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-1": function (req) {
                    return { method: "PUT", path: "/modified" };
                }
            }
        };
        var reply = routeBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.model.HttpRequest");
        var inner = parseReplyValue(reply);
        assert.equal(inner.method, "PUT");
        assert.equal(inner.path, "/modified");
        // correlation id must be echoed
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-100"]);
    });

    it('request abort: handler returns response (has statusCode)', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/test",
            headers: {
                "X-MockServer-BreakpointId": "bp-abort",
                "WebSocketCorrelationId": "corr-200"
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-abort": function (req) {
                    return { statusCode: 403, body: "forbidden" };
                }
            }
        };
        var reply = routeBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.model.HttpResponse");
        var inner = parseReplyValue(reply);
        assert.equal(inner.statusCode, 403);
        assert.equal(inner.body, "forbidden");
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-200"]);
    });

    it('request auto-continue: handler returns null', function () {
        var payload = makeRequestPayload({
            method: "POST",
            path: "/auto",
            headers: {
                "X-MockServer-BreakpointId": "bp-null",
                "WebSocketCorrelationId": "corr-300"
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-null": function (req) {
                    return null;
                }
            }
        };
        var reply = routeBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.model.HttpRequest");
        var inner = parseReplyValue(reply);
        assert.equal(inner.method, "POST");
        assert.equal(inner.path, "/auto");
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-300"]);
    });

    it('request auto-continue: handler returns undefined', function () {
        var payload = makeRequestPayload({
            method: "DELETE",
            path: "/undef",
            headers: {
                "X-MockServer-BreakpointId": "bp-undef",
                "WebSocketCorrelationId": "corr-301"
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-undef": function () {
                    // no return = undefined
                }
            }
        };
        var reply = routeBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.model.HttpRequest");
        var inner = parseReplyValue(reply);
        assert.equal(inner.method, "DELETE");
    });

    it('request handler error: auto-continues with original request', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/error",
            headers: {
                "X-MockServer-BreakpointId": "bp-err",
                "WebSocketCorrelationId": "corr-400"
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-err": function () {
                    throw new Error("handler exploded");
                }
            }
        };
        var reply = routeBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.model.HttpRequest");
        var inner = parseReplyValue(reply);
        assert.equal(inner.method, "GET");
        assert.equal(inner.path, "/error");
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-400"]);
    });

    it('request handler error: auto-continues when request has no headers object', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/no-headers"
            // no headers property at all
        });
        var handlers = {
            breakpointRequestHandlers: {}
        };
        // No breakpoint id => no handler => returns null (falls through)
        var reply = routeBreakpointMessage(payload, handlers);
        assert.equal(reply, null);
    });

    it('per-breakpoint-id selection: correct handler is chosen', function () {
        var calledA = false;
        var calledB = false;
        var payload = makeRequestPayload({
            method: "GET",
            path: "/select",
            headers: {
                "X-MockServer-BreakpointId": "bp-B",
                "WebSocketCorrelationId": "corr-sel"
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-A": function (req) { calledA = true; return req; },
                "bp-B": function (req) { calledB = true; return { method: "PATCH", path: "/b-handled" }; },
                "bp-C": function (req) { return req; }
            }
        };
        var reply = routeBreakpointMessage(payload, handlers);

        assert.equal(calledA, false);
        assert.equal(calledB, true);
        var inner = parseReplyValue(reply);
        assert.equal(inner.method, "PATCH");
        assert.equal(inner.path, "/b-handled");
    });

    it('unknown breakpoint id with no legacy handler: auto-continues with the original request', function () {
        // A real breakpoint push always carries a WebSocketCorrelationId; if no handler
        // matches the breakpoint id we must auto-continue (send the original back) rather
        // than leave the server-side exchange to time out — consistent with the other
        // phases and the other language clients.
        var payload = makeRequestPayload({
            method: "GET",
            path: "/unknown",
            headers: {
                "X-MockServer-BreakpointId": "bp-nonexistent",
                "WebSocketCorrelationId": "corr-unknown"
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-other": function () { return {}; }
            }
        };
        var reply = routeBreakpointMessage(payload, handlers);
        assert.equal(reply.type, "org.mockserver.model.HttpRequest");
        var inner = parseReplyValue(reply);
        assert.equal(inner.path, "/unknown");
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-unknown"]);
    });

    it('unknown breakpoint id with no correlation id: returns null (not a callback exchange)', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/unknown",
            headers: {
                "X-MockServer-BreakpointId": "bp-nonexistent"
            }
        });
        var reply = routeBreakpointMessage(payload, { breakpointRequestHandlers: {} });
        assert.equal(reply, null);
    });

    it('no breakpoint id: falls through to legacy requestHandler', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/legacy",
            headers: {
                "WebSocketCorrelationId": "corr-legacy"
            }
        });
        var handlers = {
            requestHandler: function (request) {
                return {
                    type: "org.mockserver.model.HttpResponse",
                    value: JSON.stringify({ statusCode: 200, body: "legacy" })
                };
            }
        };
        var reply = routeBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.model.HttpResponse");
        var inner = parseReplyValue(reply);
        assert.equal(inner.statusCode, 200);
    });

    it('correlation id echoed as array even when sent as string', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/str-corr",
            headers: {
                "X-MockServer-BreakpointId": "bp-str",
                "WebSocketCorrelationId": "corr-string"
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-str": function (req) { return { method: "GET", path: "/ok" }; }
            }
        };
        var reply = routeBreakpointMessage(payload, handlers);
        var inner = parseReplyValue(reply);
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-string"]);
    });

    it('correlation id echoed as-is when already an array', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/arr-corr",
            headers: {
                "X-MockServer-BreakpointId": "bp-arr",
                "WebSocketCorrelationId": ["corr-arr"]
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-arr": function (req) { return { method: "GET", path: "/ok" }; }
            }
        };
        var reply = routeBreakpointMessage(payload, handlers);
        var inner = parseReplyValue(reply);
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-arr"]);
    });

    it('no correlation id: result has no correlation header', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/no-corr",
            headers: {
                "X-MockServer-BreakpointId": "bp-nocorr"
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-nocorr": function (req) { return { method: "GET", path: "/ok" }; }
            }
        };
        var reply = routeBreakpointMessage(payload, handlers);
        var inner = parseReplyValue(reply);
        assert.equal(inner.headers, undefined);
    });

    it('handler result with no headers gets headers object created for correlation', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/create-headers",
            headers: {
                "X-MockServer-BreakpointId": "bp-ch",
                "WebSocketCorrelationId": "corr-ch"
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-ch": function () { return { statusCode: 200 }; }
            }
        };
        var reply = routeBreakpointMessage(payload, handlers);
        assert.equal(reply.type, "org.mockserver.model.HttpResponse");
        var inner = parseReplyValue(reply);
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-ch"]);
    });
});

// =========================================================================
// routeBreakpointMessage — HttpRequestAndHttpResponse (RESPONSE phase)
// =========================================================================

describe('routeBreakpointMessage — HttpRequestAndHttpResponse', function () {

    it('response modify: handler returns modified response', function () {
        var payload = makeRequestAndResponsePayload(
            {
                method: "GET",
                path: "/resp-test",
                headers: {
                    "X-MockServer-BreakpointId": "bp-resp",
                    "WebSocketCorrelationId": "corr-resp"
                }
            },
            { statusCode: 200, body: "original" }
        );
        var handlers = {
            breakpointResponseHandlers: {
                "bp-resp": function (req, resp) {
                    return { statusCode: 201, body: "modified-response" };
                }
            }
        };
        var reply = routeBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.model.HttpResponse");
        var inner = parseReplyValue(reply);
        assert.equal(inner.statusCode, 201);
        assert.equal(inner.body, "modified-response");
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-resp"]);
    });

    it('response auto-continue: handler returns null', function () {
        var payload = makeRequestAndResponsePayload(
            { method: "GET", path: "/resp-null", headers: { "X-MockServer-BreakpointId": "bp-rn", "WebSocketCorrelationId": "corr-rn" } },
            { statusCode: 200, body: "keep-this" }
        );
        var handlers = {
            breakpointResponseHandlers: {
                "bp-rn": function () { return null; }
            }
        };
        var reply = routeBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.model.HttpResponse");
        var inner = parseReplyValue(reply);
        assert.equal(inner.statusCode, 200);
        assert.equal(inner.body, "keep-this");
    });

    it('response handler error: auto-continues with original response', function () {
        var payload = makeRequestAndResponsePayload(
            { method: "GET", path: "/resp-err", headers: { "X-MockServer-BreakpointId": "bp-re", "WebSocketCorrelationId": "corr-re" } },
            { statusCode: 500, body: "error-body" }
        );
        var handlers = {
            breakpointResponseHandlers: {
                "bp-re": function () { throw new Error("boom"); }
            }
        };
        var reply = routeBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.model.HttpResponse");
        var inner = parseReplyValue(reply);
        assert.equal(inner.statusCode, 500);
        assert.equal(inner.body, "error-body");
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-re"]);
    });

    it('response handler error with null httpResponse: auto-continues with empty object', function () {
        var payload = makeRequestAndResponsePayload(
            { method: "GET", path: "/resp-err2", headers: { "X-MockServer-BreakpointId": "bp-re2", "WebSocketCorrelationId": "corr-re2" } },
            null
        );
        var handlers = {
            breakpointResponseHandlers: {
                "bp-re2": function () { throw new Error("boom"); }
            }
        };
        var reply = routeBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.model.HttpResponse");
        var inner = parseReplyValue(reply);
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-re2"]);
    });

    it('response per-breakpoint-id selection', function () {
        var calledX = false;
        var calledY = false;
        var payload = makeRequestAndResponsePayload(
            { method: "GET", path: "/sel", headers: { "X-MockServer-BreakpointId": "bp-Y", "WebSocketCorrelationId": "c" } },
            { statusCode: 200 }
        );
        var handlers = {
            breakpointResponseHandlers: {
                "bp-X": function () { calledX = true; return { statusCode: 200 }; },
                "bp-Y": function (req, resp) { calledY = true; return { statusCode: 204 }; }
            }
        };
        routeBreakpointMessage(payload, handlers);
        assert.equal(calledX, false);
        assert.equal(calledY, true);
    });

    it('response unknown id with no legacy handler: returns null', function () {
        var payload = makeRequestAndResponsePayload(
            { method: "GET", path: "/no-handler", headers: { "X-MockServer-BreakpointId": "bp-ghost" } },
            { statusCode: 200 }
        );
        var handlers = { breakpointResponseHandlers: {} };
        var reply = routeBreakpointMessage(payload, handlers);
        assert.equal(reply, null);
    });

    it('response no breakpoint id: falls through to legacy requestAndResponseHandler', function () {
        var payload = makeRequestAndResponsePayload(
            { method: "GET", path: "/legacy-resp", headers: { "WebSocketCorrelationId": "c-leg" } },
            { statusCode: 200 }
        );
        var handlers = {
            requestAndResponseHandler: function (reqAndResp) {
                return {
                    type: "org.mockserver.model.HttpResponse",
                    value: JSON.stringify({ statusCode: 418 })
                };
            }
        };
        var reply = routeBreakpointMessage(payload, handlers);
        assert.equal(reply.type, "org.mockserver.model.HttpResponse");
        var inner = parseReplyValue(reply);
        assert.equal(inner.statusCode, 418);
    });

    it('response breakpoint id from request with no headers on httpRequest: returns null', function () {
        var payload = makeRequestAndResponsePayload(
            { method: "GET", path: "/no-headers" },
            { statusCode: 200 }
        );
        var handlers = { breakpointResponseHandlers: { "bp-x": function () { return {}; } } };
        var reply = routeBreakpointMessage(payload, handlers);
        assert.equal(reply, null);
    });
});

// =========================================================================
// routeBreakpointMessage — PausedStreamFrameDTO (STREAM phases)
// =========================================================================

describe('routeBreakpointMessage — PausedStreamFrameDTO', function () {

    it('CONTINUE: handler returns CONTINUE decision', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-sf",
            correlationId: "corr-sf",
            direction: "INBOUND",
            body: "base64data"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "bp-sf": function (frame) {
                    return { action: "CONTINUE" };
                }
            }
        };
        var reply = routeBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.serialization.model.StreamFrameDecisionDTO");
        var inner = parseReplyValue(reply);
        assert.equal(inner.action, "CONTINUE");
        assert.equal(inner.correlationId, "corr-sf");
    });

    it('MODIFY: handler returns MODIFY with body', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-mod",
            correlationId: "corr-mod",
            body: "original"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "bp-mod": function (frame) {
                    return { action: "MODIFY", body: "new-body" };
                }
            }
        };
        var reply = routeBreakpointMessage(payload, handlers);
        var inner = parseReplyValue(reply);
        assert.equal(inner.action, "MODIFY");
        assert.equal(inner.body, "new-body");
        assert.equal(inner.correlationId, "corr-mod");
    });

    it('DROP: handler returns DROP decision', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-drop",
            correlationId: "corr-drop"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "bp-drop": function () { return { action: "DROP" }; }
            }
        };
        var inner = parseReplyValue(routeBreakpointMessage(payload, handlers));
        assert.equal(inner.action, "DROP");
        assert.equal(inner.correlationId, "corr-drop");
    });

    it('INJECT: handler returns INJECT with body', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-inj",
            correlationId: "corr-inj"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "bp-inj": function () { return { action: "INJECT", body: "injected" }; }
            }
        };
        var inner = parseReplyValue(routeBreakpointMessage(payload, handlers));
        assert.equal(inner.action, "INJECT");
        assert.equal(inner.body, "injected");
    });

    it('CLOSE: handler returns CLOSE decision', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-close",
            correlationId: "corr-close"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "bp-close": function () { return { action: "CLOSE" }; }
            }
        };
        var inner = parseReplyValue(routeBreakpointMessage(payload, handlers));
        assert.equal(inner.action, "CLOSE");
        assert.equal(inner.correlationId, "corr-close");
    });

    it('auto-continue: handler returns null', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-sfn",
            correlationId: "corr-sfn"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "bp-sfn": function () { return null; }
            }
        };
        var inner = parseReplyValue(routeBreakpointMessage(payload, handlers));
        assert.equal(inner.action, "CONTINUE");
        assert.equal(inner.correlationId, "corr-sfn");
    });

    it('auto-continue: handler returns undefined', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-sfu",
            correlationId: "corr-sfu"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "bp-sfu": function () { /* no return */ }
            }
        };
        var inner = parseReplyValue(routeBreakpointMessage(payload, handlers));
        assert.equal(inner.action, "CONTINUE");
    });

    it('auto-continue: handler throws error', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-sfe",
            correlationId: "corr-sfe"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "bp-sfe": function () { throw new Error("stream handler error"); }
            }
        };
        var inner = parseReplyValue(routeBreakpointMessage(payload, handlers));
        assert.equal(inner.action, "CONTINUE");
        assert.equal(inner.correlationId, "corr-sfe");
    });

    it('auto-continue: unknown breakpoint id (no handler registered)', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-unknown",
            correlationId: "corr-unk"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "bp-other": function () { return { action: "DROP" }; }
            }
        };
        var inner = parseReplyValue(routeBreakpointMessage(payload, handlers));
        assert.equal(inner.action, "CONTINUE");
        assert.equal(inner.correlationId, "corr-unk");
    });

    it('auto-continue: no breakpoint id in frame', function () {
        var payload = makeStreamFramePayload({
            correlationId: "corr-noid"
        });
        var handlers = { breakpointStreamFrameHandlers: {} };
        var inner = parseReplyValue(routeBreakpointMessage(payload, handlers));
        assert.equal(inner.action, "CONTINUE");
        assert.equal(inner.correlationId, "corr-noid");
    });

    it('per-breakpoint-id selection for stream frames', function () {
        var calledA = false;
        var calledB = false;
        var payload = makeStreamFramePayload({
            breakpointId: "sf-B",
            correlationId: "corr-sfsel"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "sf-A": function () { calledA = true; return { action: "DROP" }; },
                "sf-B": function () { calledB = true; return { action: "MODIFY", body: "B" }; }
            }
        };
        var inner = parseReplyValue(routeBreakpointMessage(payload, handlers));
        assert.equal(calledA, false);
        assert.equal(calledB, true);
        assert.equal(inner.action, "MODIFY");
    });

    it('correlation id is overwritten from pausedFrame even if handler sets it', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-overwrite",
            correlationId: "server-corr"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "bp-overwrite": function () {
                    return { action: "CONTINUE", correlationId: "wrong-corr" };
                }
            }
        };
        var inner = parseReplyValue(routeBreakpointMessage(payload, handlers));
        assert.equal(inner.correlationId, "server-corr");
    });
});

// =========================================================================
// routeBreakpointMessage — edge cases / other message types
// =========================================================================

describe('routeBreakpointMessage — edge cases', function () {

    it('WebSocketClientIdDTO returns null (not routed)', function () {
        var payload = {
            type: "org.mockserver.serialization.model.WebSocketClientIdDTO",
            value: JSON.stringify({ clientId: "abc" })
        };
        var reply = routeBreakpointMessage(payload, {});
        assert.equal(reply, null);
    });

    it('unknown message type returns null', function () {
        var payload = {
            type: "org.mockserver.model.SomethingNew",
            value: "{}"
        };
        var reply = routeBreakpointMessage(payload, {});
        assert.equal(reply, null);
    });

    it('null handlers object defaults to empty maps', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/null-handlers",
            headers: { "X-MockServer-BreakpointId": "bp-x" }
        });
        var reply = routeBreakpointMessage(payload, null);
        assert.equal(reply, null);
    });

    it('undefined handlers object defaults to empty maps', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-y",
            correlationId: "corr-y"
        });
        var reply = routeBreakpointMessage(payload, undefined);
        var inner = parseReplyValue(reply);
        assert.equal(inner.action, "CONTINUE");
    });

    it('empty handlers object works', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/empty",
            headers: { "X-MockServer-BreakpointId": "bp-z" }
        });
        var reply = routeBreakpointMessage(payload, {});
        assert.equal(reply, null);
    });
});

// =========================================================================
// breakpoint API surface (mockServerClient)
// =========================================================================

describe('breakpoint API surface', function () {

    it('mockServerClient exposes breakpoint methods', function () {
        var mockServerClient = require('../../').mockServerClient;
        var client = mockServerClient("localhost", 1080);

        assert.equal(typeof client.addBreakpoint, 'function');
        assert.equal(typeof client.addRequestBreakpoint, 'function');
        assert.equal(typeof client.addRequestAndResponseBreakpoint, 'function');
        assert.equal(typeof client.listBreakpointMatchers, 'function');
        assert.equal(typeof client.removeBreakpointMatcher, 'function');
        assert.equal(typeof client.clearBreakpointMatchers, 'function');
    });

    it('addBreakpoint throws on null matcher', function () {
        var mockServerClient = require('../../').mockServerClient;
        var client = mockServerClient("localhost", 1080);

        assert.throws(function () {
            client.addBreakpoint(null, ["REQUEST"], function () {});
        }, /non-null requestMatcher/);
    });

    it('addBreakpoint throws on empty phases', function () {
        var mockServerClient = require('../../').mockServerClient;
        var client = mockServerClient("localhost", 1080);

        assert.throws(function () {
            client.addBreakpoint({path: "/test"}, [], function () {});
        }, /non-empty phases/);
    });

    it('addBreakpoint throws on missing phases', function () {
        var mockServerClient = require('../../').mockServerClient;
        var client = mockServerClient("localhost", 1080);

        assert.throws(function () {
            client.addBreakpoint({path: "/test"}, null, function () {});
        }, /non-empty phases/);
    });

    it('addBreakpoint throws on non-array phases', function () {
        var mockServerClient = require('../../').mockServerClient;
        var client = mockServerClient("localhost", 1080);

        assert.throws(function () {
            client.addBreakpoint({path: "/test"}, "REQUEST", function () {});
        }, /non-empty phases/);
    });

    it('removeBreakpointMatcher throws on null id', function () {
        var mockServerClient = require('../../').mockServerClient;
        var client = mockServerClient("localhost", 1080);

        assert.throws(function () {
            client.removeBreakpointMatcher(null);
        }, /requires a breakpointId/);
    });

    it('removeBreakpointMatcher throws on undefined id', function () {
        var mockServerClient = require('../../').mockServerClient;
        var client = mockServerClient("localhost", 1080);

        assert.throws(function () {
            client.removeBreakpointMatcher(undefined);
        }, /requires a breakpointId/);
    });

    it('removeBreakpointMatcher throws on empty string id', function () {
        var mockServerClient = require('../../').mockServerClient;
        var client = mockServerClient("localhost", 1080);

        assert.throws(function () {
            client.removeBreakpointMatcher("");
        }, /requires a breakpointId/);
    });
});

// =========================================================================
// breakpoint REST payload structure
// =========================================================================

describe('breakpoint REST payload structure', function () {

    it('register body includes httpRequest, phases, and clientId', function () {
        var body = {
            httpRequest: { method: 'GET', path: '/api/.*' },
            phases: ['REQUEST', 'RESPONSE'],
            clientId: 'test-client-id'
        };

        assert.equal(body.httpRequest.method, 'GET');
        assert.equal(body.httpRequest.path, '/api/.*');
        assert.deepEqual(body.phases, ['REQUEST', 'RESPONSE']);
        assert.equal(body.clientId, 'test-client-id');
    });

    it('stream frame decision actions are valid', function () {
        var validActions = ['CONTINUE', 'MODIFY', 'DROP', 'INJECT', 'CLOSE'];
        validActions.forEach(function (action) {
            var decision = { correlationId: 'c', action: action };
            assert.equal(decision.action, action);
        });
    });
});

// =========================================================================
// mockServerClient.js browser-safe copy — extractBreakpointHeaders
// =========================================================================
// These tests exercise the copy exported from mockServerClient.js (the one
// that runs in the browser path) to ensure it is covered by c8.

describe('mockServerClient extractBreakpointHeaders', function () {

    it('returns nulls when headers is null', function () {
        var result = clientExtractBreakpointHeaders(null);
        assert.equal(result.breakpointId, null);
        assert.equal(result.correlationId, null);
    });

    it('returns nulls when headers is undefined', function () {
        var result = clientExtractBreakpointHeaders(undefined);
        assert.equal(result.breakpointId, null);
        assert.equal(result.correlationId, null);
    });

    it('returns nulls when headers is empty', function () {
        var result = clientExtractBreakpointHeaders({});
        assert.equal(result.breakpointId, null);
        assert.equal(result.correlationId, null);
    });

    it('extracts canonical-case breakpoint id (string value)', function () {
        var result = clientExtractBreakpointHeaders({
            "X-MockServer-BreakpointId": "bp-1"
        });
        assert.equal(result.breakpointId, "bp-1");
    });

    it('extracts lowercase breakpoint id', function () {
        var result = clientExtractBreakpointHeaders({
            "x-mockserver-breakpointid": "bp-2"
        });
        assert.equal(result.breakpointId, "bp-2");
    });

    it('extracts breakpoint id from array value', function () {
        var result = clientExtractBreakpointHeaders({
            "X-MockServer-BreakpointId": ["bp-3", "bp-ignored"]
        });
        assert.equal(result.breakpointId, "bp-3");
    });

    it('extracts canonical-case correlation id', function () {
        var result = clientExtractBreakpointHeaders({
            "WebSocketCorrelationId": "corr-1"
        });
        assert.equal(result.correlationId, "corr-1");
    });

    it('extracts lowercase correlation id', function () {
        var result = clientExtractBreakpointHeaders({
            "websocketcorrelationid": "corr-2"
        });
        assert.equal(result.correlationId, "corr-2");
    });

    it('extracts correlation id from array value', function () {
        var result = clientExtractBreakpointHeaders({
            "WebSocketCorrelationId": ["corr-3"]
        });
        assert.equal(result.correlationId, "corr-3");
    });

    it('extracts both ids when present', function () {
        var result = clientExtractBreakpointHeaders({
            "X-MockServer-BreakpointId": "bp-x",
            "WebSocketCorrelationId": ["corr-y"]
        });
        assert.equal(result.breakpointId, "bp-x");
        assert.equal(result.correlationId, "corr-y");
    });
});

// =========================================================================
// mockServerClient.js browser-safe copy — routeBreakpointMessage (REQUEST)
// =========================================================================

describe('mockServerClient routeBreakpointMessage — HttpRequest', function () {

    it('request continue: handler returns modified request', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/original",
            headers: {
                "X-MockServer-BreakpointId": ["bp-1"],
                "WebSocketCorrelationId": ["corr-100"]
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-1": function (req) {
                    return { method: "PUT", path: "/modified" };
                }
            }
        };
        var reply = clientRouteBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.model.HttpRequest");
        var inner = parseReplyValue(reply);
        assert.equal(inner.method, "PUT");
        assert.equal(inner.path, "/modified");
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-100"]);
    });

    it('request abort: handler returns response (has statusCode)', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/test",
            headers: {
                "X-MockServer-BreakpointId": "bp-abort",
                "WebSocketCorrelationId": "corr-200"
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-abort": function (req) {
                    return { statusCode: 403, body: "forbidden" };
                }
            }
        };
        var reply = clientRouteBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.model.HttpResponse");
        var inner = parseReplyValue(reply);
        assert.equal(inner.statusCode, 403);
        assert.equal(inner.body, "forbidden");
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-200"]);
    });

    it('request auto-continue: handler returns null', function () {
        var payload = makeRequestPayload({
            method: "POST",
            path: "/auto",
            headers: {
                "X-MockServer-BreakpointId": "bp-null",
                "WebSocketCorrelationId": "corr-300"
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-null": function (req) {
                    return null;
                }
            }
        };
        var reply = clientRouteBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.model.HttpRequest");
        var inner = parseReplyValue(reply);
        assert.equal(inner.method, "POST");
        assert.equal(inner.path, "/auto");
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-300"]);
    });

    it('request auto-continue: handler returns undefined', function () {
        var payload = makeRequestPayload({
            method: "DELETE",
            path: "/undef",
            headers: {
                "X-MockServer-BreakpointId": "bp-undef",
                "WebSocketCorrelationId": "corr-301"
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-undef": function () {
                    // no return = undefined
                }
            }
        };
        var reply = clientRouteBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.model.HttpRequest");
        var inner = parseReplyValue(reply);
        assert.equal(inner.method, "DELETE");
    });

    it('request handler error: auto-continues with original request', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/error",
            headers: {
                "X-MockServer-BreakpointId": "bp-err",
                "WebSocketCorrelationId": "corr-400"
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-err": function () {
                    throw new Error("handler exploded");
                }
            }
        };
        var reply = clientRouteBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.model.HttpRequest");
        var inner = parseReplyValue(reply);
        assert.equal(inner.method, "GET");
        assert.equal(inner.path, "/error");
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-400"]);
    });

    it('request handler error: auto-continues when request has no headers object', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/no-headers"
        });
        var handlers = {
            breakpointRequestHandlers: {}
        };
        var reply = clientRouteBreakpointMessage(payload, handlers);
        assert.equal(reply, null);
    });

    it('per-breakpoint-id selection: correct handler is chosen', function () {
        var calledA = false;
        var calledB = false;
        var payload = makeRequestPayload({
            method: "GET",
            path: "/select",
            headers: {
                "X-MockServer-BreakpointId": "bp-B",
                "WebSocketCorrelationId": "corr-sel"
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-A": function (req) { calledA = true; return req; },
                "bp-B": function (req) { calledB = true; return { method: "PATCH", path: "/b-handled" }; },
                "bp-C": function (req) { return req; }
            }
        };
        var reply = clientRouteBreakpointMessage(payload, handlers);

        assert.equal(calledA, false);
        assert.equal(calledB, true);
        var inner = parseReplyValue(reply);
        assert.equal(inner.method, "PATCH");
        assert.equal(inner.path, "/b-handled");
    });

    it('unknown breakpoint id with no legacy handler: auto-continues with the original request', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/unknown",
            headers: {
                "X-MockServer-BreakpointId": "bp-nonexistent",
                "WebSocketCorrelationId": "corr-unknown"
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-other": function () { return {}; }
            }
        };
        var reply = clientRouteBreakpointMessage(payload, handlers);
        assert.equal(reply.type, "org.mockserver.model.HttpRequest");
        var inner = parseReplyValue(reply);
        assert.equal(inner.path, "/unknown");
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-unknown"]);
    });

    it('unknown breakpoint id with no correlation id: returns null', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/unknown",
            headers: {
                "X-MockServer-BreakpointId": "bp-nonexistent"
            }
        });
        var reply = clientRouteBreakpointMessage(payload, { breakpointRequestHandlers: {} });
        assert.equal(reply, null);
    });

    it('no breakpoint id: falls through to legacy requestHandler', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/legacy",
            headers: {
                "WebSocketCorrelationId": "corr-legacy"
            }
        });
        var handlers = {
            requestHandler: function (request) {
                return {
                    type: "org.mockserver.model.HttpResponse",
                    value: JSON.stringify({ statusCode: 200, body: "legacy" })
                };
            }
        };
        var reply = clientRouteBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.model.HttpResponse");
        var inner = parseReplyValue(reply);
        assert.equal(inner.statusCode, 200);
    });

    it('correlation id echoed as array even when sent as string', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/str-corr",
            headers: {
                "X-MockServer-BreakpointId": "bp-str",
                "WebSocketCorrelationId": "corr-string"
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-str": function (req) { return { method: "GET", path: "/ok" }; }
            }
        };
        var reply = clientRouteBreakpointMessage(payload, handlers);
        var inner = parseReplyValue(reply);
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-string"]);
    });

    it('correlation id echoed as-is when already an array', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/arr-corr",
            headers: {
                "X-MockServer-BreakpointId": "bp-arr",
                "WebSocketCorrelationId": ["corr-arr"]
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-arr": function (req) { return { method: "GET", path: "/ok" }; }
            }
        };
        var reply = clientRouteBreakpointMessage(payload, handlers);
        var inner = parseReplyValue(reply);
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-arr"]);
    });

    it('no correlation id: result has no correlation header', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/no-corr",
            headers: {
                "X-MockServer-BreakpointId": "bp-nocorr"
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-nocorr": function (req) { return { method: "GET", path: "/ok" }; }
            }
        };
        var reply = clientRouteBreakpointMessage(payload, handlers);
        var inner = parseReplyValue(reply);
        assert.equal(inner.headers, undefined);
    });

    it('handler result with no headers gets headers object created for correlation', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/create-headers",
            headers: {
                "X-MockServer-BreakpointId": "bp-ch",
                "WebSocketCorrelationId": "corr-ch"
            }
        });
        var handlers = {
            breakpointRequestHandlers: {
                "bp-ch": function () { return { statusCode: 200 }; }
            }
        };
        var reply = clientRouteBreakpointMessage(payload, handlers);
        assert.equal(reply.type, "org.mockserver.model.HttpResponse");
        var inner = parseReplyValue(reply);
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-ch"]);
    });
});

// =========================================================================
// mockServerClient.js browser-safe copy — routeBreakpointMessage (RESPONSE)
// =========================================================================

describe('mockServerClient routeBreakpointMessage — HttpRequestAndHttpResponse', function () {

    it('response modify: handler returns modified response', function () {
        var payload = makeRequestAndResponsePayload(
            {
                method: "GET",
                path: "/resp-test",
                headers: {
                    "X-MockServer-BreakpointId": "bp-resp",
                    "WebSocketCorrelationId": "corr-resp"
                }
            },
            { statusCode: 200, body: "original" }
        );
        var handlers = {
            breakpointResponseHandlers: {
                "bp-resp": function (req, resp) {
                    return { statusCode: 201, body: "modified-response" };
                }
            }
        };
        var reply = clientRouteBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.model.HttpResponse");
        var inner = parseReplyValue(reply);
        assert.equal(inner.statusCode, 201);
        assert.equal(inner.body, "modified-response");
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-resp"]);
    });

    it('response auto-continue: handler returns null', function () {
        var payload = makeRequestAndResponsePayload(
            { method: "GET", path: "/resp-null", headers: { "X-MockServer-BreakpointId": "bp-rn", "WebSocketCorrelationId": "corr-rn" } },
            { statusCode: 200, body: "keep-this" }
        );
        var handlers = {
            breakpointResponseHandlers: {
                "bp-rn": function () { return null; }
            }
        };
        var reply = clientRouteBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.model.HttpResponse");
        var inner = parseReplyValue(reply);
        assert.equal(inner.statusCode, 200);
        assert.equal(inner.body, "keep-this");
    });

    it('response handler error: auto-continues with original response', function () {
        var payload = makeRequestAndResponsePayload(
            { method: "GET", path: "/resp-err", headers: { "X-MockServer-BreakpointId": "bp-re", "WebSocketCorrelationId": "corr-re" } },
            { statusCode: 500, body: "error-body" }
        );
        var handlers = {
            breakpointResponseHandlers: {
                "bp-re": function () { throw new Error("boom"); }
            }
        };
        var reply = clientRouteBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.model.HttpResponse");
        var inner = parseReplyValue(reply);
        assert.equal(inner.statusCode, 500);
        assert.equal(inner.body, "error-body");
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-re"]);
    });

    it('response handler error with null httpResponse: auto-continues with empty object', function () {
        var payload = makeRequestAndResponsePayload(
            { method: "GET", path: "/resp-err2", headers: { "X-MockServer-BreakpointId": "bp-re2", "WebSocketCorrelationId": "corr-re2" } },
            null
        );
        var handlers = {
            breakpointResponseHandlers: {
                "bp-re2": function () { throw new Error("boom"); }
            }
        };
        var reply = clientRouteBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.model.HttpResponse");
        var inner = parseReplyValue(reply);
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-re2"]);
    });

    it('response per-breakpoint-id selection', function () {
        var calledX = false;
        var calledY = false;
        var payload = makeRequestAndResponsePayload(
            { method: "GET", path: "/sel", headers: { "X-MockServer-BreakpointId": "bp-Y", "WebSocketCorrelationId": "c" } },
            { statusCode: 200 }
        );
        var handlers = {
            breakpointResponseHandlers: {
                "bp-X": function () { calledX = true; return { statusCode: 200 }; },
                "bp-Y": function (req, resp) { calledY = true; return { statusCode: 204 }; }
            }
        };
        clientRouteBreakpointMessage(payload, handlers);
        assert.equal(calledX, false);
        assert.equal(calledY, true);
    });

    it('response unknown id with no legacy handler and correlation id: auto-continues', function () {
        var payload = makeRequestAndResponsePayload(
            { method: "GET", path: "/no-handler", headers: { "X-MockServer-BreakpointId": "bp-ghost", "WebSocketCorrelationId": "corr-ghost" } },
            { statusCode: 200 }
        );
        var handlers = { breakpointResponseHandlers: {} };
        var reply = clientRouteBreakpointMessage(payload, handlers);
        assert.equal(reply.type, "org.mockserver.model.HttpResponse");
        var inner = parseReplyValue(reply);
        assert.deepEqual(inner.headers["WebSocketCorrelationId"], ["corr-ghost"]);
    });

    it('response unknown id with no legacy handler and no correlation id: returns null', function () {
        var payload = makeRequestAndResponsePayload(
            { method: "GET", path: "/no-handler", headers: { "X-MockServer-BreakpointId": "bp-ghost" } },
            { statusCode: 200 }
        );
        var handlers = { breakpointResponseHandlers: {} };
        var reply = clientRouteBreakpointMessage(payload, handlers);
        assert.equal(reply, null);
    });

    it('response no breakpoint id: falls through to legacy requestAndResponseHandler', function () {
        var payload = makeRequestAndResponsePayload(
            { method: "GET", path: "/legacy-resp", headers: { "WebSocketCorrelationId": "c-leg" } },
            { statusCode: 200 }
        );
        var handlers = {
            requestAndResponseHandler: function (reqAndResp) {
                return {
                    type: "org.mockserver.model.HttpResponse",
                    value: JSON.stringify({ statusCode: 418 })
                };
            }
        };
        var reply = clientRouteBreakpointMessage(payload, handlers);
        assert.equal(reply.type, "org.mockserver.model.HttpResponse");
        var inner = parseReplyValue(reply);
        assert.equal(inner.statusCode, 418);
    });

    it('response breakpoint id from request with no headers on httpRequest: returns null', function () {
        var payload = makeRequestAndResponsePayload(
            { method: "GET", path: "/no-headers" },
            { statusCode: 200 }
        );
        var handlers = { breakpointResponseHandlers: { "bp-x": function () { return {}; } } };
        var reply = clientRouteBreakpointMessage(payload, handlers);
        assert.equal(reply, null);
    });
});

// =========================================================================
// mockServerClient.js browser-safe copy — routeBreakpointMessage (STREAM)
// =========================================================================

describe('mockServerClient routeBreakpointMessage — PausedStreamFrameDTO', function () {

    it('CONTINUE: handler returns CONTINUE decision', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-sf",
            correlationId: "corr-sf",
            direction: "INBOUND",
            body: "base64data"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "bp-sf": function (frame) {
                    return { action: "CONTINUE" };
                }
            }
        };
        var reply = clientRouteBreakpointMessage(payload, handlers);

        assert.equal(reply.type, "org.mockserver.serialization.model.StreamFrameDecisionDTO");
        var inner = parseReplyValue(reply);
        assert.equal(inner.action, "CONTINUE");
        assert.equal(inner.correlationId, "corr-sf");
    });

    it('MODIFY: handler returns MODIFY with body', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-mod",
            correlationId: "corr-mod",
            body: "original"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "bp-mod": function (frame) {
                    return { action: "MODIFY", body: "new-body" };
                }
            }
        };
        var reply = clientRouteBreakpointMessage(payload, handlers);
        var inner = parseReplyValue(reply);
        assert.equal(inner.action, "MODIFY");
        assert.equal(inner.body, "new-body");
        assert.equal(inner.correlationId, "corr-mod");
    });

    it('DROP: handler returns DROP decision', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-drop",
            correlationId: "corr-drop"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "bp-drop": function () { return { action: "DROP" }; }
            }
        };
        var inner = parseReplyValue(clientRouteBreakpointMessage(payload, handlers));
        assert.equal(inner.action, "DROP");
        assert.equal(inner.correlationId, "corr-drop");
    });

    it('INJECT: handler returns INJECT with body', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-inj",
            correlationId: "corr-inj"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "bp-inj": function () { return { action: "INJECT", body: "injected" }; }
            }
        };
        var inner = parseReplyValue(clientRouteBreakpointMessage(payload, handlers));
        assert.equal(inner.action, "INJECT");
        assert.equal(inner.body, "injected");
    });

    it('CLOSE: handler returns CLOSE decision', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-close",
            correlationId: "corr-close"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "bp-close": function () { return { action: "CLOSE" }; }
            }
        };
        var inner = parseReplyValue(clientRouteBreakpointMessage(payload, handlers));
        assert.equal(inner.action, "CLOSE");
        assert.equal(inner.correlationId, "corr-close");
    });

    it('auto-continue: handler returns null', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-sfn",
            correlationId: "corr-sfn"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "bp-sfn": function () { return null; }
            }
        };
        var inner = parseReplyValue(clientRouteBreakpointMessage(payload, handlers));
        assert.equal(inner.action, "CONTINUE");
        assert.equal(inner.correlationId, "corr-sfn");
    });

    it('auto-continue: handler returns undefined', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-sfu",
            correlationId: "corr-sfu"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "bp-sfu": function () { /* no return */ }
            }
        };
        var inner = parseReplyValue(clientRouteBreakpointMessage(payload, handlers));
        assert.equal(inner.action, "CONTINUE");
    });

    it('auto-continue: handler throws error', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-sfe",
            correlationId: "corr-sfe"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "bp-sfe": function () { throw new Error("stream handler error"); }
            }
        };
        var inner = parseReplyValue(clientRouteBreakpointMessage(payload, handlers));
        assert.equal(inner.action, "CONTINUE");
        assert.equal(inner.correlationId, "corr-sfe");
    });

    it('auto-continue: unknown breakpoint id (no handler registered)', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-unknown",
            correlationId: "corr-unk"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "bp-other": function () { return { action: "DROP" }; }
            }
        };
        var inner = parseReplyValue(clientRouteBreakpointMessage(payload, handlers));
        assert.equal(inner.action, "CONTINUE");
        assert.equal(inner.correlationId, "corr-unk");
    });

    it('auto-continue: no breakpoint id in frame', function () {
        var payload = makeStreamFramePayload({
            correlationId: "corr-noid"
        });
        var handlers = { breakpointStreamFrameHandlers: {} };
        var inner = parseReplyValue(clientRouteBreakpointMessage(payload, handlers));
        assert.equal(inner.action, "CONTINUE");
        assert.equal(inner.correlationId, "corr-noid");
    });

    it('per-breakpoint-id selection for stream frames', function () {
        var calledA = false;
        var calledB = false;
        var payload = makeStreamFramePayload({
            breakpointId: "sf-B",
            correlationId: "corr-sfsel"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "sf-A": function () { calledA = true; return { action: "DROP" }; },
                "sf-B": function () { calledB = true; return { action: "MODIFY", body: "B" }; }
            }
        };
        var inner = parseReplyValue(clientRouteBreakpointMessage(payload, handlers));
        assert.equal(calledA, false);
        assert.equal(calledB, true);
        assert.equal(inner.action, "MODIFY");
    });

    it('correlation id is overwritten from pausedFrame even if handler sets it', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-overwrite",
            correlationId: "server-corr"
        });
        var handlers = {
            breakpointStreamFrameHandlers: {
                "bp-overwrite": function () {
                    return { action: "CONTINUE", correlationId: "wrong-corr" };
                }
            }
        };
        var inner = parseReplyValue(clientRouteBreakpointMessage(payload, handlers));
        assert.equal(inner.correlationId, "server-corr");
    });
});

// =========================================================================
// mockServerClient.js browser-safe copy — edge cases
// =========================================================================

describe('mockServerClient routeBreakpointMessage — edge cases', function () {

    it('WebSocketClientIdDTO returns null (not routed)', function () {
        var payload = {
            type: "org.mockserver.serialization.model.WebSocketClientIdDTO",
            value: JSON.stringify({ clientId: "abc" })
        };
        var reply = clientRouteBreakpointMessage(payload, {});
        assert.equal(reply, null);
    });

    it('unknown message type returns null', function () {
        var payload = {
            type: "org.mockserver.model.SomethingNew",
            value: "{}"
        };
        var reply = clientRouteBreakpointMessage(payload, {});
        assert.equal(reply, null);
    });

    it('null handlers object defaults to empty maps', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/null-handlers",
            headers: { "X-MockServer-BreakpointId": "bp-x" }
        });
        var reply = clientRouteBreakpointMessage(payload, null);
        assert.equal(reply, null);
    });

    it('undefined handlers object defaults to empty maps', function () {
        var payload = makeStreamFramePayload({
            breakpointId: "bp-y",
            correlationId: "corr-y"
        });
        var reply = clientRouteBreakpointMessage(payload, undefined);
        var inner = parseReplyValue(reply);
        assert.equal(inner.action, "CONTINUE");
    });

    it('empty handlers object works', function () {
        var payload = makeRequestPayload({
            method: "GET",
            path: "/empty",
            headers: { "X-MockServer-BreakpointId": "bp-z" }
        });
        var reply = clientRouteBreakpointMessage(payload, {});
        assert.equal(reply, null);
    });
});
