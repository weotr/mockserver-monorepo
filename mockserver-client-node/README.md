# mockserver-client-node 

> Communicate with a [MockServer](https://mock-server.com/) from any node or grunt build

[![Build status](https://badge.buildkite.com/368c3b69e959f29725d8ab582f8d75dedddceee196d39b6d28.svg?style=square&theme=slack)](https://buildkite.com/mockserver/mockserver-client-node)

[![NPM](https://nodei.co/npm/mockserver-client.png?downloads=true&stars=true)](https://nodei.co/npm/mockserver-client/) 

# Community

* Roadmap:&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<a href="https://github.com/orgs/mock-server/projects/1"><img height="20px" src="https://mock-server.com/images/GitHub_Logo-md.png" alt="GitHub Project"></a>
* Feature Requests:&nbsp;&nbsp;&nbsp;<a href="https://github.com/mock-server/mockserver-monorepo/issues"><img height="20px" src="https://mock-server.com/images/GitHub_Logo-md.png" alt="Github Issues"></a>
* Issues / Bugs:&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<a href="https://github.com/mock-server/mockserver-monorepo/issues"><img height="20px" src="https://mock-server.com/images/GitHub_Logo-md.png" alt="Github Issues"></a>
* Discussions:&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<a href="https://github.com/mock-server/mockserver-monorepo/discussions"><img height="20px" src="https://mock-server.com/images/GitHub_Logo-md.png" alt="GitHub Discussions"></a>

## Getting Started

[MockServer](https://mock-server.com/) allows you to mock any system you integrate with via HTTP or HTTPS (i.e. (REST) services, web sites, etc). Please note that it is a third party project that needs java.

This npm module allows any grunt or node project to easily communicate with a running [MockServer](https://mock-server.com/) instance.

As an addition to this module for communicating with a running MockServer there is a second project that can be used to start and stop a MockServer called [mockserver-node](https://www.npmjs.org/package/mockserver-node).

The MockServer client can be created as follows:

```js
var mockServer = require('mockserver-client'),
    mockServerClient = mockServer.mockServerClient // MockServer and proxy client
```
**Note:** this assumes you have an instance of MockServer running on port 1080.
For more information on how to do so check [mockserver-node](https://www.npmjs.org/package/mockserver-node).

## Setup Expectation

A simple expectation can be set up as follows:

```js
mockServerClient("localhost", 1080)
    .mockSimpleResponse('/somePath', { name: 'value' }, 203)
    .then(
        function(result) {
            // do something next
        }, 
        function(error) {
            // handle error
        }
    );
```

A more complex expectation can be set up like this:

```js
mockServerClient("localhost", 1080)
    .mockAnyResponse(
        {
            'httpRequest': {
                'method': 'POST',
                'path': '/somePath',
                'queryStringParameters': [
                    {
                        'name': 'test',
                        'values': [ 'true' ]
                    }
                ],
                'body': {
                    'type': "STRING",
                    'value': 'someBody'
                }
            },
            'httpResponse': {
                'statusCode': 200,
                'body': JSON.stringify({ name: 'value' }),
                'delay': {
                    'timeUnit': 'MILLISECONDS',
                    'value': 250
                }
            },
            'times': {
                'remainingTimes': 1,
                'unlimited': false
            }
        }
    )
    .then(
        function(result) {
            // do something next
        }, 
        function(error) {
            // handle error
        }
    );
```

For the full documentation see [MockServer - Creating Expectations](https://mock-server.com/mock_server/creating_expectations.html).

### Advanced Response Builders

For non-HTTP and streaming protocols there are dedicated builders that take a path (or full request matcher)
plus the response action object, with optional `times`, `priority`, `timeToLive`, and `id` arguments:

```javascript
// Server-Sent Events (SSE)
client.respondWithSse('/events', {
    events: [
        { event: 'message', data: 'first' },
        { event: 'message', data: 'second' }
    ],
    closeConnection: true
});

// WebSocket
client.respondWithWebSocket('/ws', {
    messages: [ { text: 'hello' } ],
    closeConnection: false
});

// DNS
client.respondWithDns('example.com', {
    answerRecords: [
        { name: 'example.com', type: 'A', ttl: 300, value: '127.0.0.1' }
    ],
    responseCode: 'NOERROR'
});

// Raw binary
client.respondWithBinary('/binary', {
    binaryData: Buffer.from('hello').toString('base64')
});

// gRPC server-streaming
client.respondWithGrpcStream('/my.Service/StreamItems', {
    statusName: 'OK',
    messages: [
        { json: '{"value":"first"}' },
        { json: '{"value":"second"}' }
    ],
    closeConnection: true
});
```

## Verify Requests

It is also possible to verify that request were made:

```js
mockServerClient("localhost", 1080)
    .verify(
        {
            'method': 'POST',
            'path': '/somePath',
            'body': 'someBody'
        }, 
        1, true
    )
    .then(
        function() {
            // do something next
        }, 
        function(failure) {
            // handle verification failure
        }
    );
```
It is furthermore possible to verify that sequences of requests were made in a specific order:

```js
mockServerClient("localhost", 1080)
    .verifySequence(
        {
            'method': 'POST',
            'path': '/somePathOne',
            'body': 'someBody'
        },
        {
            'method': 'GET',
            'path': '/somePathTwo'
        },
        {
            'method': 'GET',
            'path': '/somePathThree'
        }
    )
    .then(
        function() {
            // do something next
        }, 
        function(failure) {
            // handle verification failure
        }
    );
```

For the full documentation see [MockServer - Verifying Requests](https://mock-server.com/mock_server/verification.html).

## Interactive Breakpoints

The client supports matcher-driven interactive breakpoints over the callback WebSocket. Register a breakpoint matcher to pause forwarded/proxied exchanges at specific phases and inspect/modify/continue them via callback handlers.

### Register a breakpoint

```js
// REQUEST phase only
client.addRequestBreakpoint(
    { path: '/api/.*' },
    function (request) {
        // inspect or modify the request; return a request to continue or a response to abort
        request.path = '/api/modified';
        return request;
    }
).then(function (breakpointId) {
    console.log('Breakpoint registered:', breakpointId);
});

// REQUEST + RESPONSE phases
client.addRequestAndResponseBreakpoint(
    { path: '/api/.*' },
    function (request) { return request; },           // REQUEST handler
    function (request, response) { return response; } // RESPONSE handler
).then(function (breakpointId) {
    console.log('Breakpoint registered:', breakpointId);
});

// All phases with stream frame handler
client.addBreakpoint(
    { path: '/stream/.*' },
    ['REQUEST', 'RESPONSE', 'RESPONSE_STREAM', 'INBOUND_STREAM'],
    function (request) { return request; },
    function (request, response) { return response; },
    function (pausedFrame) {
        // pausedFrame has: correlationId, streamId, sequenceNumber, direction, phase, body (base64)
        return { action: 'CONTINUE' };
        // Other actions: MODIFY (with body), DROP, INJECT (with body), CLOSE
    }
).then(function (breakpointId) {
    console.log('Breakpoint registered:', breakpointId);
});
```

### Manage breakpoints

```js
// List all matchers
client.listBreakpointMatchers().then(function (result) {
    console.log(result.matchers);
});

// Remove a specific matcher
client.removeBreakpointMatcher(breakpointId);

// Clear all matchers
client.clearBreakpointMatchers();
```

## Using in tests

The client supports TC39 explicit resource management, so a `using` / `await using`
binding resets the server automatically when it goes out of scope — no manual
`afterEach(() => client.reset())` needed:

```js
const { mockServerClient } = require('mockserver-client');

it('records the request', async () => {
    await using client = mockServerClient('localhost', 1080);
    // ... register expectations, make requests ...
    // the server is reset when `client` goes out of scope
});
```

If your runtime/transpiler does not yet support `await using`, reset explicitly
in a Jest/Mocha hook:

```js
const { mockServerClient } = require('mockserver-client');
const client = mockServerClient('localhost', 1080);

afterEach(() => client.reset());
```

## Start / Launch MockServer

This package (`mockserver-client`) is the REST/WebSocket client for communicating with a running MockServer. To **download and launch** a local MockServer instance (no Java or Docker required), use the companion [`mockserver-node`](https://www.npmjs.org/package/mockserver-node) package:

```shell
npx -p mockserver-node mockserver run -p 1080
```

`mockserver-node` downloads a self-contained platform bundle (`mockserver-<version>-<os>-<arch>`) from the GitHub Release, verifies its SHA-256, caches it per-user, and starts it. See the [mockserver-node README](https://www.npmjs.org/package/mockserver-node) for full details including environment variables (`MOCKSERVER_BINARY_BASE_URL`, `MOCKSERVER_BINARY_CACHE`, `MOCKSERVER_SKIP_BINARY_DOWNLOAD`) and supported platforms (linux/darwin/windows on x86_64/aarch64).

## Contributing
In lieu of a formal styleguide, take care to maintain the existing coding style. Add unit tests for any new or changed functionality. Lint and test your code using [Grunt](http://gruntjs.com/).

## Changelog

All notable and significant changes are detailed in the [MockServer changelog](https://github.com/mock-server/mockserver-monorepo/blob/master/changelog.md) 

---

Task submitted by [James D Bloom](https://blog.jamesdbloom.com)

## AI Assistant Integration

MockServer includes a built-in [MCP](https://modelcontextprotocol.io) (Model Context Protocol) server that enables AI coding assistants to create expectations, verify requests, and debug HTTP traffic programmatically.

- **MCP Endpoint:** `http://localhost:1080/mockserver/mcp`
- **AI Documentation:** [llms.txt](https://www.mock-server.com/llms.txt)
- **Setup Guide:** [AI Integration](https://www.mock-server.com/mock_server/ai_mcp_setup.html)
