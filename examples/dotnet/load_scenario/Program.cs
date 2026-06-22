// Demonstrates MockServer's Load Scenario registry from the .NET client.
//
// A "load scenario" is a named, server-side traffic generator: you register it
// once (its profile of ramp/hold/pause stages and the request steps it drives),
// then start/stop it by name. While running it generates synthetic traffic
// against the data plane and reports live throughput/latency status. This is the
// registry workflow exercised with the async .NET client:
//
//   client.LoadScenarioAsync(scenario)        register/upsert (PUT /mockserver/loadScenario)
//   client.StartLoadScenariosAsync(names)      start one/many (PUT .../start)
//   client.LoadScenariosAsync()                list all (GET /mockserver/loadScenario)
//   client.GetLoadScenarioAsync(name)          one scenario + live status (GET .../{name})
//   client.StopLoadScenariosAsync(names)       stop one/many; no args = stop all (PUT .../stop)
//   client.RunLoadScenarioAsync(scenario)      register + start in one call
//   client.DeleteLoadScenarioAsync(name)       delete one (DELETE .../{name})
//   client.ClearLoadScenariosAsync()           clear the registry (DELETE /mockserver/loadScenario)
//
// IMPORTANT: the server must be started with load generation enabled, otherwise
// starting returns HTTP 403:
//   java -Dmockserver.loadGenerationEnabled=true -jar mockserver-netty-...-jar-with-dependencies.jar -serverPort 1080
//   (or env MOCKSERVER_LOAD_GENERATION_ENABLED=true). Registering is always allowed.
//
// Prints "PASS" and exits 0 on success; exits non-zero on the first failure.
//
// Server location is read from MOCKSERVER_HOST (default localhost) and
// MOCKSERVER_PORT (default 1080).

using MockServer.Client;
using MockServer.Client.Models;

var host = Environment.GetEnvironmentVariable("MOCKSERVER_HOST") is { Length: > 0 } h ? h : "localhost";
var port = int.TryParse(Environment.GetEnvironmentVariable("MOCKSERVER_PORT"), out var p) ? p : 1080;

using var client = new MockServerClient(host, port);

// A realistic multi-stage scenario built from the typed model: a linear RATE
// ramp (5 -> 50 req/s, capped at 50 VUs), then a 25-VU hold, then a PAUSE. Two
// Velocity-templated steps drive each iteration ($!iteration.index varies the
// request). StartDelayMillis defers load for half a second after start. Stage
// VUs are kept within the default safety cap of 50 (loadGenerationMaxVirtualUsers).
LoadScenario BuildScenario() => new()
{
    Name = "checkout-load",
    TemplateType = LoadTemplateType.VELOCITY,
    MaxRequests = 100000,
    StartDelayMillis = 500,
    Labels = new Dictionary<string, string> { ["team"] = "payments", ["env"] = "staging" },
    Profile = new LoadProfile
    {
        Stages =
        {
            new LoadStage
            {
                Type = LoadStageType.RATE, StartRate = 5, EndRate = 50,
                DurationMillis = 30000, Curve = RampCurve.LINEAR, MaxVus = 50
            },
            LoadStage.ConstantVus(25, 60000),
            LoadStage.Pause(10000)
        }
    },
    Steps = new List<LoadStep>
    {
        new()
        {
            Name = "browse",
            Request = HttpRequest.Request().WithMethod("GET").WithPath("/products/$!iteration.index"),
            ThinkTime = new Delay { TimeUnit = TimeUnit.MILLISECONDS, Value = 500 }
        },
        new()
        {
            Name = "checkout",
            Request = HttpRequest.Request().WithMethod("POST").WithPath("/cart/checkout")
                .WithBody("{\"item\":\"$!iteration.index\",\"qty\":1}"),
            Labels = new Dictionary<string, string> { ["critical"] = "true" }
        }
    }
};

try
{
    // A catch-all target expectation so generated traffic gets a 200 to measure.
    client.When(HttpRequest.Request().WithPath("/.*"))
        .Respond(HttpResponse.Response().WithStatusCode(200).WithBody("ok"));

    var scenario = BuildScenario();

    // 1. Register (does NOT start it yet).
    await client.LoadScenarioAsync(scenario);
    Console.WriteLine("registered \"checkout-load\"");

    // 2. Start it (StartLoadScenariosAsync takes params string[] — one or many names).
    await client.StartLoadScenariosAsync("checkout-load");
    Console.WriteLine("started \"checkout-load\"");
    await Task.Delay(1500);

    // 3. List all registered scenarios.
    var list = await client.LoadScenariosAsync();
    var running = list.Scenarios.Any(s => s.Name == "checkout-load" && s.State == LoadScenarioState.RUNNING);
    if (!running)
        throw new Exception("checkout-load is not RUNNING in the list (is loadGenerationEnabled=true?)");
    Console.WriteLine("listed: " + string.Join(", ", list.Scenarios.Select(s => $"{s.Name}={s.State}")));

    // One scenario's live status (throughput/latency, current stage, ...).
    var status = await client.GetLoadScenarioAsync("checkout-load");
    Console.WriteLine($"status: state={status.State} stageType={status.StageType} " +
                      $"currentTarget={status.CurrentTarget} requestsSent={status.RequestsSent}");

    // 4. Stop it (StopLoadScenariosAsync with no args stops ALL running scenarios).
    await client.StopLoadScenariosAsync("checkout-load");
    Console.WriteLine("stopped \"checkout-load\"");

    // Tidy up the registry.
    await client.ClearLoadScenariosAsync();

    Console.WriteLine("PASS");
    return 0;
}
catch (Exception ex)
{
    Console.Error.WriteLine($"FAIL: {ex.Message}");
    return 1;
}
