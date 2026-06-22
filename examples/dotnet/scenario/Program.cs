// Demonstrates MockServer's stateful-scenario features from the .NET client by
// running all 5 canonical scenarios in sequence against a live MockServer,
// exercising each with real HTTP requests and asserting the outcome.
//
// Scenarios:
//   1. state_machine      - login flow gated by scenario state
//   2. sequential_cycling - one expectation, multiple cycling responses
//   3. timed_transition   - scenario REST helper with a timed auto-transition
//   4. external_trigger   - scenario REST helper advanced by an external trigger
//   5. cross_protocol     - a request on one path advances a scenario used by another
//
// Prerequisites: MockServer running (defaults to localhost:1080)
//   docker run -d -p 1080:1080 mockserver/mockserver
//
// Server location is read from MOCKSERVER_HOST (default localhost) and
// MOCKSERVER_PORT (default 1080). Exits 0 only if every scenario passes.

using System.Text.Json;
using MockServer.Client;
using MockServer.Client.Models;

var host = Environment.GetEnvironmentVariable("MOCKSERVER_HOST") is { Length: > 0 } h ? h : "localhost";
var port = int.TryParse(Environment.GetEnvironmentVariable("MOCKSERVER_PORT"), out var p) ? p : 1080;
var baseUrl = $"http://{host}:{port}";

using var client = new MockServerClient(host, port);
using var http = new HttpClient { BaseAddress = new Uri(baseUrl) };

var failures = new List<string>();

await RunScenario("state_machine", StateMachine);
await RunScenario("sequential_cycling", SequentialCycling);
await RunScenario("timed_transition", TimedTransition);
await RunScenario("external_trigger", ExternalTrigger);
await RunScenario("cross_protocol", CrossProtocol);

if (failures.Count > 0)
{
    Console.WriteLine();
    foreach (var f in failures) Console.WriteLine($"FAIL: {f}");
    Environment.Exit(1);
}

Console.WriteLine("\nAll scenarios passed.");
Environment.Exit(0);

// -------------------------------------------------------------------
// Harness
// -------------------------------------------------------------------

async Task RunScenario(string name, Func<Task> body)
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
// 1. state_machine - login flow
// -------------------------------------------------------------------
async Task StateMachine()
{
    // Default start state is "Started".
    client.When(HttpRequest.Request().WithMethod("POST").WithPath("/login"), Times.Once())
        .WithScenarioName("LoginFlow")
        .WithScenarioState("Started")
        .WithNewScenarioState("LoggedIn")
        .Respond(HttpResponse.Response().WithStatusCode(200).WithBody("{\"token\":\"abc123\"}"));

    client.When(HttpRequest.Request().WithMethod("GET").WithPath("/profile"))
        .WithScenarioName("LoginFlow")
        .WithScenarioState("LoggedIn")
        .Respond(HttpResponse.Response().WithStatusCode(200).WithBody("{\"name\":\"Alice\"}"));

    client.When(HttpRequest.Request().WithMethod("GET").WithPath("/profile"))
        .WithScenarioName("LoginFlow")
        .WithScenarioState("Started")
        .Respond(HttpResponse.Response().WithStatusCode(401).WithBody("{\"error\":\"Not authenticated\"}"));

    // Before login: profile is unauthenticated.
    var before = await http.GetAsync("/profile");
    AssertStatus(401, before, "GET /profile before login");

    // Login advances the scenario to LoggedIn and returns a token.
    var login = await http.PostAsync("/login", null);
    AssertStatus(200, login, "POST /login");
    AssertJsonField(await login.Content.ReadAsStringAsync(), "token", "abc123", "POST /login token");

    // After login: profile is authenticated.
    var after = await http.GetAsync("/profile");
    AssertStatus(200, after, "GET /profile after login");
    AssertJsonField(await after.Content.ReadAsStringAsync(), "name", "Alice", "GET /profile name");
}

// -------------------------------------------------------------------
// 2. sequential_cycling - multiple responses, one expectation
// -------------------------------------------------------------------
async Task SequentialCycling()
{
    client.When(HttpRequest.Request().WithMethod("GET").WithPath("/api/status"))
        .WithResponseMode(ResponseMode.SEQUENTIAL)
        .Respond(new[]
        {
            HttpResponse.Response().WithStatusCode(200).WithBody("{\"status\":\"ok\"}").Build(),
            HttpResponse.Response().WithStatusCode(503).WithBody("{\"status\":\"degraded\"}").Build(),
            HttpResponse.Response().WithStatusCode(200).WithBody("{\"status\":\"ok\"}").Build()
        });

    // 4 calls: 200, 503, 200, then cycles back to the first (200).
    int[] expected = { 200, 503, 200, 200 };
    for (var i = 0; i < expected.Length; i++)
    {
        var resp = await http.GetAsync("/api/status");
        AssertStatus(expected[i], resp, $"GET /api/status call {i + 1}");
    }
}

// -------------------------------------------------------------------
// 3. timed_transition - scenario REST helper, timed auto-transition
// -------------------------------------------------------------------
async Task TimedTransition()
{
    client.When(HttpRequest.Request().WithMethod("GET").WithPath("/status"))
        .WithScenarioName("DeployFlow")
        .WithScenarioState("Deploying")
        .Respond(HttpResponse.Response().WithStatusCode(200).WithBody("{\"status\":\"deploying\"}"));

    client.When(HttpRequest.Request().WithMethod("GET").WithPath("/status"))
        .WithScenarioName("DeployFlow")
        .WithScenarioState("Deployed")
        .Respond(HttpResponse.Response().WithStatusCode(200).WithBody("{\"status\":\"complete\"}"));

    // Start in Deploying, auto-transition to Deployed after 1000ms.
    client.Scenario("DeployFlow").Set("Deploying", 1000, "Deployed");

    var deploying = await http.GetAsync("/status");
    AssertStatus(200, deploying, "GET /status while deploying");
    AssertJsonField(await deploying.Content.ReadAsStringAsync(), "status", "deploying", "GET /status deploying body");

    await Task.Delay(1300);

    var complete = await http.GetAsync("/status");
    AssertStatus(200, complete, "GET /status after transition");
    AssertJsonField(await complete.Content.ReadAsStringAsync(), "status", "complete", "GET /status complete body");
}

// -------------------------------------------------------------------
// 4. external_trigger - scenario REST helper, external trigger
// -------------------------------------------------------------------
async Task ExternalTrigger()
{
    client.When(HttpRequest.Request().WithMethod("GET").WithPath("/health"))
        .WithScenarioName("HealthFlow")
        .WithScenarioState("Started")
        .Respond(HttpResponse.Response().WithStatusCode(200).WithBody("{\"status\":\"healthy\"}"));

    client.When(HttpRequest.Request().WithMethod("GET").WithPath("/health"))
        .WithScenarioName("HealthFlow")
        .WithScenarioState("Down")
        .Respond(HttpResponse.Response().WithStatusCode(503).WithBody("{\"status\":\"down\"}"));

    var healthy = await http.GetAsync("/health");
    AssertStatus(200, healthy, "GET /health before trigger");
    AssertJsonField(await healthy.Content.ReadAsStringAsync(), "status", "healthy", "GET /health healthy body");

    // Externally trigger the scenario into the Down state.
    client.Scenario("HealthFlow").Trigger("Down");

    var down = await http.GetAsync("/health");
    AssertStatus(503, down, "GET /health after trigger");
    AssertJsonField(await down.Content.ReadAsStringAsync(), "status", "down", "GET /health down body");
}

// -------------------------------------------------------------------
// 5. cross_protocol - crossProtocolScenarios (HTTP_REQUEST trigger)
// -------------------------------------------------------------------
async Task CrossProtocol()
{
    // A request to /events fires an HTTP_REQUEST trigger that advances the
    // "ConnFlow" scenario to "Connected". The same mechanism advances scenarios
    // from DNS_QUERY / WEBSOCKET_CONNECT / GRPC_REQUEST events.
    client.When(HttpRequest.Request().WithMethod("GET").WithPath("/events"))
        .WithCrossProtocolScenario(new CrossProtocolScenario
        {
            Trigger = CrossProtocolTrigger.HTTP_REQUEST,
            MatchPattern = "/events",
            ScenarioName = "ConnFlow",
            TargetState = "Connected"
        })
        .Respond(HttpResponse.Response().WithStatusCode(200).WithBody("{\"events\":\"subscribed\"}"));

    client.When(HttpRequest.Request().WithMethod("GET").WithPath("/api/conn-status"))
        .WithScenarioName("ConnFlow")
        .WithScenarioState("Connected")
        .Respond(HttpResponse.Response().WithStatusCode(200).WithBody("{\"status\":\"connected\"}"));

    // Before the trigger fires, /api/conn-status is unmatched (404).
    var before = await http.GetAsync("/api/conn-status");
    AssertStatus(404, before, "GET /api/conn-status before trigger");

    // Hitting /events fires the cross-protocol trigger.
    var events = await http.GetAsync("/events");
    AssertStatus(200, events, "GET /events");

    // Now the scenario is Connected, so /api/conn-status matches.
    var after = await http.GetAsync("/api/conn-status");
    AssertStatus(200, after, "GET /api/conn-status after trigger");
    AssertJsonField(await after.Content.ReadAsStringAsync(), "status", "connected", "GET /api/conn-status body");
}

// -------------------------------------------------------------------
// Assertion helpers
// -------------------------------------------------------------------

void AssertStatus(int expected, HttpResponseMessage actual, string what)
{
    if ((int)actual.StatusCode != expected)
        throw new Exception($"{what}: expected status {expected} but got {(int)actual.StatusCode}");
}

void AssertJsonField(string json, string field, string expected, string what)
{
    using var doc = JsonDocument.Parse(json);
    if (!doc.RootElement.TryGetProperty(field, out var value))
        throw new Exception($"{what}: missing field \"{field}\" in {json}");
    var actual = value.GetString();
    if (actual != expected)
        throw new Exception($"{what}: expected {field}=\"{expected}\" but got \"{actual}\"");
}
