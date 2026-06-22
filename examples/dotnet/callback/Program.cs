// Demonstrates MockServer callbacks from the .NET client against a live MockServer.
//
//   1. object callback (closure) - client.MockWithCallback registers a C# closure
//      that derives the response dynamically from the incoming request, dispatched
//      over the callback WebSocket. A real data-plane request asserts the dynamic body.
//   2. class callback (declarative, REST-only) - an expectation carrying an
//      httpResponseClassCallback referencing a server-side class. The server accepts
//      and stores it (the class need not exist to validate the wire shape).
//
// Prerequisites: MockServer running (defaults to localhost:1080)
//   docker run -d -p 1080:1080 mockserver/mockserver
//
// Server location is read from MOCKSERVER_HOST (default localhost) and
// MOCKSERVER_PORT (default 1080). Exits 0 only if every check passes.

using MockServer.Client;
using MockServer.Client.Models;

var host = Environment.GetEnvironmentVariable("MOCKSERVER_HOST") is { Length: > 0 } h ? h : "localhost";
var port = int.TryParse(Environment.GetEnvironmentVariable("MOCKSERVER_PORT"), out var p) ? p : 1080;
var baseUrl = $"http://{host}:{port}";

using var client = new MockServerClient(host, port);
using var http = new HttpClient { BaseAddress = new Uri(baseUrl) };

var failures = new List<string>();

await RunCheck("object_callback", ObjectCallback);
await RunCheck("class_callback", ClassCallback);

if (failures.Count > 0)
{
    Console.WriteLine();
    foreach (var f in failures) Console.WriteLine($"FAIL: {f}");
    Environment.Exit(1);
}

Console.WriteLine("\nAll callback checks passed.");
Environment.Exit(0);

// -------------------------------------------------------------------
// Harness
// -------------------------------------------------------------------

async Task RunCheck(string name, Func<Task> body)
{
    try
    {
        client.Reset();
        await body();
        Console.WriteLine($"PASS: {name}");
    }
    catch (Exception ex)
    {
        failures.Add($"{name}: {ex.Message}");
        Console.WriteLine($"ERROR in {name}: {ex.Message}");
    }
}

// -------------------------------------------------------------------
// 1. Object callback - the closure derives the response from the request
// -------------------------------------------------------------------
async Task ObjectCallback()
{
    // Register a request matcher whose response is produced by a C# closure.
    // The closure echoes the request path into the response body, proving the
    // response is computed dynamically per request (not a static expectation).
    client.MockWithCallback(
        HttpRequest.Request().WithMethod("GET").WithPath("/dynamic"),
        request =>
        {
            var path = request.Path ?? "(none)";
            return HttpResponse.Response()
                .WithStatusCode(201)
                .WithHeader("X-Echo-Path", path)
                .WithBody($"{{\"handledPath\":\"{path}\",\"dynamic\":true}}");
        });

    var resp = await http.GetAsync("/dynamic");
    AssertStatus(201, resp, "GET /dynamic object-callback status");

    var body = await resp.Content.ReadAsStringAsync();
    if (!body.Contains("\"handledPath\":\"/dynamic\""))
        throw new Exception($"GET /dynamic: expected closure-derived body, got: {body}");
    if (!body.Contains("\"dynamic\":true"))
        throw new Exception($"GET /dynamic: expected dynamic marker, got: {body}");

    if (resp.Headers.TryGetValues("X-Echo-Path", out var echo))
    {
        var v = echo.FirstOrDefault();
        if (v != "/dynamic")
            throw new Exception($"GET /dynamic: expected X-Echo-Path=/dynamic, got: {v}");
    }
    else
    {
        throw new Exception("GET /dynamic: missing X-Echo-Path response header");
    }
}

// -------------------------------------------------------------------
// 2. Class callback - declarative, REST-only; server accepts the wire shape
// -------------------------------------------------------------------
async Task ClassCallback()
{
    // The server stores an expectation referencing a server-side callback class.
    // The class need not exist to register the expectation; we validate that the
    // control plane accepts the httpResponseClassCallback wire shape and that the
    // expectation is then retrievable with the callbackClass intact.
    client.When(HttpRequest.Request().WithMethod("GET").WithPath("/class-callback"))
        .RespondWithClassCallback("org.mockserver.examples.MyExpectationResponseCallback");

    var active = client.RetrieveActiveExpectations(
        HttpRequest.Request().WithPath("/class-callback"));

    var match = active.FirstOrDefault(e =>
        e.HttpResponseClassCallback?.CallbackClass
            == "org.mockserver.examples.MyExpectationResponseCallback");

    if (match == null)
        throw new Exception(
            "class callback: server did not store an expectation with the expected httpResponseClassCallback.callbackClass");
}

// -------------------------------------------------------------------
// Assertion helpers
// -------------------------------------------------------------------

void AssertStatus(int expected, HttpResponseMessage actual, string what)
{
    if ((int)actual.StatusCode != expected)
        throw new Exception($"{what}: expected status {expected} but got {(int)actual.StatusCode}");
}
