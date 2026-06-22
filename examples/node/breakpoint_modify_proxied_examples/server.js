/**
 * Modify a proxied exchange with an interactive breakpoint.
 *
 * This example uses a self-forwarding (loopback) setup so it is entirely
 * self-contained -- no external upstream server is required.
 *
 * Flow:
 *   1. Create a mock "upstream" endpoint:  GET /upstream/greeting -> 200 JSON
 *   2. Create a forward expectation:       GET /service/greeting  -> forwards
 *      to /upstream/greeting on the SAME MockServer (via socketAddress loopback)
 *   3. Register a RESPONSE-phase breakpoint on /service/greeting whose handler
 *      modifies the upstream response before it reaches the caller.
 *   4. Issue GET /service/greeting and print the modified response.
 *
 * The RESPONSE phase fires reliably on matched forward expectations.
 *
 * Prerequisites:
 *   - MockServer running on localhost:1080 with breakpoint support
 *   - npm install (in this directory)
 */

var mockServerClient = require('mockserver-client').mockServerClient;

var MOCK_HOST = "localhost";
var MOCK_PORT = 1080;

var client = mockServerClient(MOCK_HOST, MOCK_PORT);

// Step 1: Create a mock "upstream" endpoint that returns a JSON greeting
client.mockAnyResponse({
    "httpRequest": {
        "method": "GET",
        "path": "/upstream/greeting"
    },
    "httpResponse": {
        "statusCode": 200,
        "headers": {
            "content-type": ["application/json"]
        },
        "body": JSON.stringify({
            "message": "Hello from upstream",
            "source": "original"
        })
    }
}).then(function () {
    console.log("1. Created upstream mock: GET /upstream/greeting -> 200 JSON");

    // Step 2: Create a forward expectation that loops back to the same server
    return client.mockAnyResponse({
        "httpRequest": {
            "method": "GET",
            "path": "/service/greeting"
        },
        "httpOverrideForwardedRequest": {
            "httpRequest": {
                "path": "/upstream/greeting",
                "socketAddress": {
                    "host": MOCK_HOST,
                    "port": MOCK_PORT,
                    "scheme": "HTTP"
                }
            }
        }
    });
}).then(function () {
    console.log("2. Created forward expectation: GET /service/greeting -> loopback to /upstream/greeting");

    // Step 3: Register a RESPONSE-phase breakpoint that modifies the proxied response
    return client.addBreakpoint(
        {
            "method": "GET",
            "path": "/service/greeting"
        },
        ["RESPONSE"],
        null,  // no request handler (REQUEST phase not intercepted)
        function (request, response) {
            // Modify the upstream response before it reaches the caller
            console.log("3. Breakpoint fired! Original response body: " + response.body);

            var originalBody = {};
            try {
                originalBody = JSON.parse(response.body);
            } catch (e) {
                // keep empty
            }

            // Add a field and change the source
            originalBody.source = "modified-by-breakpoint";
            originalBody.injectedField = "this was added by the breakpoint handler";

            // Return the modified response
            return {
                "statusCode": response.statusCode,
                "headers": response.headers,
                "body": JSON.stringify(originalBody)
            };
        },
        null   // no stream frame handler
    );
}).then(function (breakpointId) {
    console.log("   Breakpoint registered with id: " + breakpointId);
    console.log("4. Sending GET /service/greeting ...");

    // Step 4: Make the actual request through the forwarded path
    var http = require("http");
    var options = {
        hostname: MOCK_HOST,
        port: MOCK_PORT,
        path: "/service/greeting",
        method: "GET"
    };
    var req = http.request(options, function (res) {
        var body = "";
        res.on("data", function (chunk) { body += chunk; });
        res.on("end", function () {
            console.log("\n--- Response received ---");
            console.log("Status: " + res.statusCode);
            console.log("Body:   " + body);

            try {
                var parsed = JSON.parse(body);
                if (parsed.source === "modified-by-breakpoint") {
                    console.log("\nBreakpoint successfully modified the proxied response!");
                }
            } catch (e) {
                // ignore parse errors in output
            }

            // Clean up
            client.clearBreakpointMatchers().then(function () {
                console.log("\nBreakpoint matchers cleared.");
                client.reset().then(function () {
                    console.log("MockServer reset.");
                });
            });
        });
    });
    req.on("error", function (err) {
        console.error("Request error: " + err.message);
    });
    req.end();

}).then(null, function (error) {
    console.error("Error: " + error);
});
