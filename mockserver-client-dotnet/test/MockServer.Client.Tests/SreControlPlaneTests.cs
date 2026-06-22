using System.Net;
using System.Net.Http;
using System.Text;
using FluentAssertions;
using MockServer.Client.Exceptions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

/// <summary>
/// Unit tests for the SRE control-plane methods (load scenarios, service chaos, SLO verdicts,
/// preemption and chaos experiments). Uses a fake HttpMessageHandler so no real server is needed,
/// asserting the HTTP method, path and serialized body of each request.
/// </summary>
public class SreControlPlaneTests
{
    private sealed class FakeHandler : HttpMessageHandler
    {
        public HttpRequestMessage? LastRequest { get; private set; }
        public string? LastRequestBody { get; private set; }
        public HttpStatusCode ResponseStatusCode { get; set; } = HttpStatusCode.OK;
        public string ResponseBody { get; set; } = "";

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            LastRequest = request;
            LastRequestBody = request.Content != null
                ? await request.Content.ReadAsStringAsync(cancellationToken)
                : null;

            return new HttpResponseMessage(ResponseStatusCode)
            {
                Content = new StringContent(ResponseBody, Encoding.UTF8, "application/json")
            };
        }
    }

    private static (MockServerClient Client, FakeHandler Handler) CreateClient()
    {
        var handler = new FakeHandler();
        var httpClient = new HttpClient(handler);
        var client = new MockServerClient("http://localhost:1080", httpClient);
        return (client, handler);
    }

    // -------------------------------------------------------------------
    // Load-scenario registry
    // -------------------------------------------------------------------

    [Fact]
    public void LoadScenario_RegistersWithPutAndCamelCaseBody()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"name\":\"checkout-load\",\"state\":\"LOADED\"}";

        var scenario = new LoadScenario
        {
            Name = "checkout-load",
            TemplateType = LoadTemplateType.VELOCITY,
            MaxRequests = 5000,
            StartDelayMillis = 2000,
            Labels = new Dictionary<string, string> { ["team"] = "checkout" },
            Profile = new LoadProfile
            {
                Stages = new List<LoadStage>
                {
                    LoadStage.RampVus(0, 10, 30000, RampCurve.LINEAR),
                    LoadStage.ConstantVus(10, 60000),
                    LoadStage.Pause(5000)
                }
            },
            Steps = new List<LoadStep>
            {
                new()
                {
                    Name = "get-item",
                    Request = HttpRequest.Request().WithMethod("GET").WithPath("/api/item/1").Build(),
                    ThinkTime = new Delay { TimeUnit = TimeUnit.MILLISECONDS, Value = 20 }
                }
            }
        };

        var registered = client.LoadScenario(scenario);

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario");
        handler.LastRequestBody.Should().Contain("\"name\":\"checkout-load\"");
        handler.LastRequestBody.Should().Contain("\"templateType\":\"VELOCITY\"");
        handler.LastRequestBody.Should().Contain("\"maxRequests\":5000");
        handler.LastRequestBody.Should().Contain("\"startDelayMillis\":2000");
        handler.LastRequestBody.Should().Contain("\"profile\"");
        handler.LastRequestBody.Should().Contain("\"stages\"");
        handler.LastRequestBody.Should().Contain("\"type\":\"VU\"");
        handler.LastRequestBody.Should().Contain("\"type\":\"PAUSE\"");
        handler.LastRequestBody.Should().Contain("\"curve\":\"LINEAR\"");
        // A meaningful zero (startVus=0) must still be serialised.
        handler.LastRequestBody.Should().Contain("\"startVus\":0");
        handler.LastRequestBody.Should().Contain("\"endVus\":10");
        handler.LastRequestBody.Should().Contain("\"durationMillis\":30000");
        handler.LastRequestBody.Should().Contain("\"vus\":10");
        handler.LastRequestBody.Should().Contain("\"durationMillis\":5000");
        handler.LastRequestBody.Should().Contain("\"steps\"");
        handler.LastRequestBody.Should().Contain("\"thinkTime\"");

        // Registration parses {name,state} and does NOT start the scenario.
        registered.Name.Should().Be("checkout-load");
        registered.State.Should().Be(LoadScenarioState.LOADED);
    }

    [Fact]
    public void LoadScenario_DoesNotThrow403_RegistrationAlwaysAllowed()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"name\":\"x\",\"state\":\"LOADED\"}";

        var scenario = new LoadScenario { Name = "x", Profile = new LoadProfile(), Steps = new List<LoadStep>() };

        var registered = client.LoadScenario(scenario);

        registered.State.Should().Be(LoadScenarioState.LOADED);
    }

    [Fact]
    public void LoadScenario_ThrowsOnInvalid400()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.BadRequest;
        handler.ResponseBody = "{\"error\":\"missing profile\"}";

        var act = () => client.LoadScenario(new LoadScenario { Name = "x" });
        act.Should().Throw<MockServerClientException>().WithMessage("*Invalid load scenario*");
    }

    [Fact]
    public void LoadScenarios_ListsAllWithGet()
    {
        var (client, handler) = CreateClient();
        // The server emits the live status fields FLAT on each entry (siblings of
        // name/state/definition), present only once the scenario has run.
        handler.ResponseBody =
            "{\"scenarios\":[" +
            "{\"name\":\"a\",\"state\":\"RUNNING\",\"definition\":{\"name\":\"a\"}," +
            "\"currentVus\":3,\"stageIndex\":1,\"stageType\":\"RATE\",\"currentTarget\":50,\"requestsSent\":42,\"p95Millis\":12.5}," +
            "{\"name\":\"b\",\"state\":\"LOADED\",\"definition\":{\"name\":\"b\"}}" +
            "]}";

        var list = client.LoadScenarios();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Get);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario");
        list.Scenarios.Should().HaveCount(2);
        list.Scenarios[0].Name.Should().Be("a");
        list.Scenarios[0].State.Should().Be(LoadScenarioState.RUNNING);
        list.Scenarios[0].Definition!.Name.Should().Be("a");
        // Flat live fields are populated directly on the entry (inherited LoadScenarioStatus).
        list.Scenarios[0].RequestsSent.Should().Be(42);
        list.Scenarios[0].P95Millis.Should().Be(12.5);
        list.Scenarios[0].CurrentVus.Should().Be(3);
        list.Scenarios[0].StageIndex.Should().Be(1);
        list.Scenarios[0].StageType.Should().Be("RATE");
        list.Scenarios[0].CurrentTarget.Should().Be(50);
        // A never-run LOADED scenario carries no live fields.
        list.Scenarios[1].State.Should().Be(LoadScenarioState.LOADED);
        list.Scenarios[1].RequestsSent.Should().BeNull();
        list.Scenarios[1].CurrentVus.Should().BeNull();
    }

    [Fact]
    public void GetLoadScenario_FetchesOneByName()
    {
        var (client, handler) = CreateClient();
        // A COMPLETED scenario: live fields are FLAT siblings of name/state/definition.
        handler.ResponseBody = "{\"name\":\"checkout load\",\"state\":\"COMPLETED\",\"startDelayMillis\":2000,\"definition\":{\"name\":\"checkout load\"},\"requestsSent\":100,\"succeeded\":98,\"failed\":2,\"p95Millis\":9}";

        var entry = client.GetLoadScenario("checkout load");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Get);
        // Name is URL-escaped in the path.
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario/checkout%20load");
        entry.Name.Should().Be("checkout load");
        entry.State.Should().Be(LoadScenarioState.COMPLETED);
        entry.StartDelayMillis.Should().Be(2000);
        // Flat live fields populate directly on the entry.
        entry.RequestsSent.Should().Be(100);
        entry.Succeeded.Should().Be(98);
        entry.Failed.Should().Be(2);
        entry.P95Millis.Should().Be(9);
    }

    [Fact]
    public void GetLoadScenario_Throws404WhenAbsent()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.NotFound;
        handler.ResponseBody = "{\"error\":\"not found\"}";

        var act = () => client.GetLoadScenario("missing");
        act.Should().Throw<MockServerClientException>().WithMessage("*missing*404*");
    }

    [Fact]
    public void DeleteLoadScenario_RemovesOneByName()
    {
        var (client, handler) = CreateClient();

        client.DeleteLoadScenario("a");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Delete);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario/a");
    }

    [Fact]
    public void ClearLoadScenarios_DeletesAll()
    {
        var (client, handler) = CreateClient();

        client.ClearLoadScenarios();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Delete);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario");
    }

    [Fact]
    public void StartLoadScenarios_SendsNamesArray()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody =
            "{\"started\":[{\"name\":\"a\",\"state\":\"PENDING\"},{\"name\":\"b\",\"state\":\"RUNNING\"}],\"status\":\"ok\"}";

        var result = client.StartLoadScenarios("a", "b");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario/start");
        handler.LastRequestBody.Should().Contain("\"names\":[\"a\",\"b\"]");
        result.Started.Should().HaveCount(2);
        result.Started[0].Name.Should().Be("a");
        result.Started[0].State.Should().Be(LoadScenarioState.PENDING);
        result.Started[1].State.Should().Be(LoadScenarioState.RUNNING);
        result.Status.Should().Be("ok");
    }

    [Fact]
    public void StartLoadScenarios_Throws403WhenDisabled()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.Forbidden;
        handler.ResponseBody = "{\"error\":\"load generation not enabled\"}";

        var act = () => client.StartLoadScenarios("a");
        act.Should().Throw<MockServerClientException>().WithMessage("*loadGenerationEnabled*");
    }

    [Fact]
    public void StartLoadScenarios_Throws404OnUnknownName()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.NotFound;
        handler.ResponseBody = "{\"error\":\"unknown scenario\"}";

        var act = () => client.StartLoadScenarios("nope");
        act.Should().Throw<MockServerClientException>().WithMessage("*404*");
    }

    [Fact]
    public void StartLoadScenarios_ThrowsWhenNoNames()
    {
        var (client, _) = CreateClient();

        var act = () => client.StartLoadScenarios();
        act.Should().Throw<ArgumentException>();
    }

    [Fact]
    public void StopLoadScenarios_SendsNamesArray()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"stopped\":[{\"name\":\"a\",\"state\":\"STOPPED\"}],\"status\":\"ok\"}";

        var result = client.StopLoadScenarios("a");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario/stop");
        handler.LastRequestBody.Should().Contain("\"names\":[\"a\"]");
        result.Stopped.Should().HaveCount(1);
        result.Stopped[0].Name.Should().Be("a");
        result.Stopped[0].State.Should().Be(LoadScenarioState.STOPPED);
        result.Status.Should().Be("ok");
    }

    [Fact]
    public void StopLoadScenarios_SendsEmptyBodyToStopAll()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"stopped\":[],\"status\":\"ok\"}";

        client.StopLoadScenarios();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario/stop");
        handler.LastRequestBody.Should().BeEmpty();
    }

    [Fact]
    public void RunLoadScenario_RegistersThenStarts()
    {
        var (client, handler) = CreateClient();
        // The convenience helper issues two PUTs; the fake handler records only the last
        // (the /start call), which is what we assert on. Both share the same response body.
        handler.ResponseBody = "{\"name\":\"checkout-load\",\"state\":\"LOADED\",\"started\":[{\"name\":\"checkout-load\",\"state\":\"PENDING\"}],\"status\":\"ok\"}";

        var scenario = new LoadScenario { Name = "checkout-load", Profile = new LoadProfile(), Steps = new List<LoadStep>() };

        var result = client.RunLoadScenario(scenario);

        // Final request is the start call.
        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/loadScenario/start");
        handler.LastRequestBody.Should().Contain("\"names\":[\"checkout-load\"]");
        result.Started.Should().ContainSingle(r => r.Name == "checkout-load");
    }

    // -------------------------------------------------------------------
    // Service chaos
    // -------------------------------------------------------------------

    [Fact]
    public void SetServiceChaos_SendsPutWithHostChaosAndTtl()
    {
        var (client, handler) = CreateClient();

        var profile = new ServiceChaosProfile
        {
            ErrorStatus = 503,
            ErrorProbability = 0.3,
            Latency = new Delay { TimeUnit = TimeUnit.MILLISECONDS, Value = 200 }
        };

        client.SetServiceChaos("payments.internal:8443", profile, 60000);

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/serviceChaos");
        handler.LastRequestBody.Should().Contain("\"host\":\"payments.internal:8443\"");
        handler.LastRequestBody.Should().Contain("\"chaos\"");
        handler.LastRequestBody.Should().Contain("\"errorStatus\":503");
        handler.LastRequestBody.Should().Contain("\"errorProbability\":0.3");
        handler.LastRequestBody.Should().Contain("\"latency\"");
        handler.LastRequestBody.Should().Contain("\"ttlMillis\":60000");
    }

    [Fact]
    public void SetServiceChaos_OmitsTtlWhenNull()
    {
        var (client, handler) = CreateClient();

        client.SetServiceChaos("api.svc", new ServiceChaosProfile { ErrorProbability = 0.5 });

        handler.LastRequestBody.Should().NotContain("ttlMillis");
    }

    [Fact]
    public void RemoveServiceChaos_SendsRemoveFlag()
    {
        var (client, handler) = CreateClient();

        client.RemoveServiceChaos("api.svc");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/serviceChaos");
        handler.LastRequestBody.Should().Contain("\"host\":\"api.svc\"");
        handler.LastRequestBody.Should().Contain("\"remove\":true");
    }

    [Fact]
    public void ClearServiceChaos_SendsClearFlag()
    {
        var (client, handler) = CreateClient();

        client.ClearServiceChaos();

        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/serviceChaos");
        handler.LastRequestBody.Should().Contain("\"clear\":true");
    }

    // -------------------------------------------------------------------
    // SLO verdicts
    // -------------------------------------------------------------------

    [Fact]
    public void VerifySlo_SendsPutAndParsesPassVerdict()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.OK;
        handler.ResponseBody = "{\"name\":\"checkout-slo\",\"result\":\"PASS\",\"sampleCount\":50}";

        var criteria = new SloCriteria
        {
            Name = "checkout-slo",
            Window = SloWindow.Lookback(60000),
            MinimumSampleCount = 20,
            UpstreamHosts = new List<string> { "payments.svc" },
            Objectives = new List<SloObjective>
            {
                new() { Sli = Sli.LATENCY_P95, Comparator = SloComparator.LESS_THAN, Threshold = 250.0, Scope = SloScope.FORWARD }
            }
        };

        var verdict = client.VerifySlo(criteria);

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/verifySLO");
        handler.LastRequestBody.Should().Contain("\"name\":\"checkout-slo\"");
        handler.LastRequestBody.Should().Contain("\"type\":\"LOOKBACK\"");
        handler.LastRequestBody.Should().Contain("\"lookbackMillis\":60000");
        handler.LastRequestBody.Should().Contain("\"sli\":\"LATENCY_P95\"");
        handler.LastRequestBody.Should().Contain("\"comparator\":\"LESS_THAN\"");
        handler.LastRequestBody.Should().Contain("\"threshold\":250");
        handler.LastRequestBody.Should().Contain("\"upstreamHosts\"");

        verdict.Result.Should().Be(SloResult.PASS);
        verdict.Name.Should().Be("checkout-slo");
        verdict.SampleCount.Should().Be(50);
    }

    [Fact]
    public void VerifySlo_ReturnsVerdictOnFail406()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.NotAcceptable; // 406 = FAIL
        handler.ResponseBody = "{\"name\":\"checkout-slo\",\"result\":\"FAIL\"}";

        var verdict = client.VerifySlo(new SloCriteria
        {
            Objectives = new List<SloObjective> { new() { Sli = Sli.ERROR_RATE, Comparator = SloComparator.LESS_THAN, Threshold = 0.01 } }
        });

        verdict.Result.Should().Be(SloResult.FAIL);
    }

    [Fact]
    public void VerifySlo_ThrowsOnDisabled400()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.BadRequest;
        handler.ResponseBody = "{\"error\":\"SLO tracking disabled\"}";

        var act = () => client.VerifySlo(new SloCriteria { Objectives = new List<SloObjective>() });
        act.Should().Throw<MockServerClientException>().WithMessage("*sloTrackingEnabled*");
    }

    // -------------------------------------------------------------------
    // Preemption
    // -------------------------------------------------------------------

    [Fact]
    public void SetPreemption_SendsPutWithBodyAndParsesStatus()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"state\":\"draining\",\"inFlight\":3,\"drainRemainingMillis\":9000,\"mode\":\"both\"}";

        var status = client.SetPreemption(new PreemptionRequest
        {
            Mode = PreemptionMode.both,
            DrainMillis = 10000,
            TtlMillis = 60000,
            LastStreamId = 3
        });

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/preemption");
        handler.LastRequestBody.Should().Contain("\"mode\":\"both\"");
        handler.LastRequestBody.Should().Contain("\"drainMillis\":10000");
        handler.LastRequestBody.Should().Contain("\"ttlMillis\":60000");
        handler.LastRequestBody.Should().Contain("\"lastStreamId\":3");

        status.State.Should().Be("draining");
        status.InFlight.Should().Be(3);
        status.DrainRemainingMillis.Should().Be(9000);
        status.Mode.Should().Be("both");
    }

    [Fact]
    public void SetPreemption_SendsEmptyBodyWhenNullRequest()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"state\":\"draining\"}";

        client.SetPreemption();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/preemption");
        handler.LastRequestBody.Should().BeEmpty();
    }

    [Fact]
    public void PreemptionStatus_SendsGetAndParsesStatus()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"state\":\"inactive\",\"inFlight\":0}";

        var status = client.PreemptionStatus();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Get);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/preemption");
        status.State.Should().Be("inactive");
        status.InFlight.Should().Be(0);
    }

    [Fact]
    public void ClearPreemption_SendsDelete()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"state\":\"inactive\"}";

        client.ClearPreemption();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Delete);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/preemption");
    }

    // -------------------------------------------------------------------
    // Chaos experiment
    // -------------------------------------------------------------------

    [Fact]
    public void StartChaosExperiment_SendsPutWithStages()
    {
        var (client, handler) = CreateClient();

        var experiment = new ChaosExperiment
        {
            Name = "gradual-degradation",
            Loop = false,
            Stages = new List<ChaosExperimentStage>
            {
                new()
                {
                    DurationMillis = 10000,
                    Profiles = new Dictionary<string, ServiceChaosProfile>
                    {
                        ["api.example.com"] = new() { ErrorStatus = 500, ErrorProbability = 0.1 }
                    }
                }
            }
        };

        client.StartChaosExperiment(experiment);

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/chaosExperiment");
        handler.LastRequestBody.Should().Contain("\"name\":\"gradual-degradation\"");
        handler.LastRequestBody.Should().Contain("\"stages\"");
        handler.LastRequestBody.Should().Contain("\"durationMillis\":10000");
        handler.LastRequestBody.Should().Contain("\"profiles\"");
        handler.LastRequestBody.Should().Contain("\"api.example.com\"");
        handler.LastRequestBody.Should().Contain("\"errorStatus\":500");
        handler.LastRequestBody.Should().Contain("\"errorProbability\":0.1");
    }

    [Fact]
    public void StartChaosExperiment_ThrowsOnBadRequest()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.BadRequest;
        handler.ResponseBody = "{\"error\":\"too many stages\"}";

        var act = () => client.StartChaosExperiment(new ChaosExperiment { Stages = new List<ChaosExperimentStage>() });
        act.Should().Throw<MockServerClientException>().WithMessage("*Invalid chaos experiment*");
    }
}
