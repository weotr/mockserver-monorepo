// Demonstrates registering a RESPONSE-phase breakpoint that modifies a proxied
// response in-flight. This is MockServer's interactive breakpoint feature.
//
// How it works:
//   1. Create an "upstream" mock returning a canned JSON response.
//   2. Create a loopback forward (httpOverrideForwardedRequest + socketAddress)
//      so requests to /proxy are forwarded back to the SAME MockServer.
//   3. Register a RESPONSE-phase breakpoint whose handler modifies the response
//      body and adds a custom header.
//   4. Send a request to /proxy and print the modified response.
//
// Prerequisites:
//   - MockServer running on localhost:1080 WITH breakpoint support
//   - dotnet run

using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using MockServer.Client;
using MockServer.Client.Models;

using var client = new MockServerClient("localhost", 1080);

// -------------------------------------------------------------------
// 1. Upstream mock: GET /upstream -> 200 JSON
// -------------------------------------------------------------------
client.When(
    HttpRequest.Request()
        .WithMethod("GET")
        .WithPath("/upstream")
).Respond(
    HttpResponse.Response()
        .WithStatusCode(200)
        .WithHeader("Content-Type", "application/json")
        .WithBody("{\"source\":\"upstream\",\"modified\":false}")
);
Console.WriteLine("1. Created upstream expectation: GET /upstream -> 200");

// -------------------------------------------------------------------
// 2. Loopback forward via raw REST API (httpOverrideForwardedRequest)
// -------------------------------------------------------------------
var forwardPayload = JsonSerializer.Serialize(new object[]
{
    new
    {
        httpRequest = new { method = "GET", path = "/proxy" },
        httpOverrideForwardedRequest = new
        {
            httpRequest = new
            {
                path = "/upstream",
                socketAddress = new { host = "localhost", port = 1080, scheme = "HTTP" }
            }
        }
    }
});
using var http = new HttpClient();
var putContent = new StringContent(forwardPayload, Encoding.UTF8, "application/json");
var putResp = await http.PutAsync("http://localhost:1080/mockserver/expectation", putContent);
putResp.EnsureSuccessStatusCode();
Console.WriteLine("2. Created loopback forward: GET /proxy -> forward to /upstream (localhost:1080)");

// -------------------------------------------------------------------
// 3. Register a RESPONSE-phase breakpoint on /proxy
// -------------------------------------------------------------------
var bpId = client.AddRequestResponseBreakpoint(
    HttpRequest.Request().WithMethod("GET").WithPath("/proxy").Build(),
    // REQUEST handler: pass through unchanged
    request => request,
    // RESPONSE handler: modify the body and add a header
    (request, response) =>
    {
        Console.WriteLine("\n   [breakpoint] RESPONSE phase fired!");
        Console.WriteLine($"   [breakpoint] Original response body: {response["body"]}");

        // Replace the body
        response["body"] = "{\"source\":\"upstream\",\"modified\":true,\"breakpoint\":\"dotnet-client\"}";

        // Add a custom header
        if (response["headers"] is not JsonObject headers)
        {
            headers = new JsonObject();
            response["headers"] = headers;
        }
        headers["X-Modified-By"] = new JsonArray("dotnet-breakpoint-example");

        Console.WriteLine($"   [breakpoint] Modified response body: {response["body"]}");
        return response;
    }
);
Console.WriteLine($"3. Registered RESPONSE breakpoint (id={bpId}) on GET /proxy");

// -------------------------------------------------------------------
// 4. Send request to /proxy -- the breakpoint handler will fire
// -------------------------------------------------------------------
Console.WriteLine("\n4. Sending GET /proxy ...");

// Give the WebSocket a moment to be fully ready
await Task.Delay(200);

var proxyResp = await http.GetAsync("http://localhost:1080/proxy");
var body = await proxyResp.Content.ReadAsStringAsync();

Console.WriteLine("\n--- Response from GET /proxy ---");
Console.WriteLine($"Status:          {(int)proxyResp.StatusCode}");
Console.WriteLine($"Body:            {body}");
Console.WriteLine($"X-Modified-By:   {(proxyResp.Headers.TryGetValues("X-Modified-By", out var vals) ? string.Join(", ", vals) : "(not set)")}");

// -------------------------------------------------------------------
// Clean up
// -------------------------------------------------------------------
client.RemoveBreakpointMatcher(bpId);
client.Reset();
Console.WriteLine("\nAll expectations and breakpoints cleared.");
