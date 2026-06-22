using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using FluentAssertions;
using MockServer.Client.Exceptions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

/// <summary>
/// Unit tests for typed stateful-scenario support: the new multi-response and cross-protocol
/// fields on <see cref="Expectation"/> (asserting exact contract JSON field names), and the
/// scenario REST helper (asserting the HTTP method, path and body of each request via a fake
/// HttpMessageHandler so no real server is needed).
/// </summary>
public class ScenarioTests
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

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
    // Expectation serialization — multi-response and cross-protocol fields
    // -------------------------------------------------------------------

    [Fact]
    public void Expectation_MultiResponseScenario_SerializesContractFieldNames()
    {
        var expectation = new Expectation
        {
            HttpRequest = HttpRequest.Request().WithPath("/deploy").Build(),
            ScenarioName = "Deploy",
            ScenarioState = "Deploying",
            NewScenarioState = "Deployed",
            HttpResponses = new List<HttpResponse>
            {
                HttpResponse.Response().WithStatusCode(202).Build(),
                HttpResponse.Response().WithStatusCode(200).Build()
            },
            ResponseMode = ResponseMode.WEIGHTED,
            ResponseWeights = new List<int> { 3, 1 },
            SwitchAfter = 2,
            CrossProtocolScenarios = new List<CrossProtocolScenario>
            {
                new()
                {
                    Trigger = CrossProtocolTrigger.DNS_QUERY,
                    MatchPattern = "deploy.svc",
                    ScenarioName = "Deploy",
                    TargetState = "Deployed"
                }
            }
        };

        var json = JsonSerializer.Serialize(expectation, JsonOptions);
        var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;

        root.GetProperty("scenarioName").GetString().Should().Be("Deploy");
        root.GetProperty("scenarioState").GetString().Should().Be("Deploying");
        root.GetProperty("newScenarioState").GetString().Should().Be("Deployed");

        var responses = root.GetProperty("httpResponses");
        responses.GetArrayLength().Should().Be(2);
        responses[0].GetProperty("statusCode").GetInt32().Should().Be(202);
        responses[1].GetProperty("statusCode").GetInt32().Should().Be(200);

        root.GetProperty("responseMode").GetString().Should().Be("WEIGHTED");

        var weights = root.GetProperty("responseWeights");
        weights.GetArrayLength().Should().Be(2);
        weights[0].GetInt32().Should().Be(3);
        weights[1].GetInt32().Should().Be(1);

        root.GetProperty("switchAfter").GetInt32().Should().Be(2);

        var cps = root.GetProperty("crossProtocolScenarios");
        cps.GetArrayLength().Should().Be(1);
        cps[0].GetProperty("trigger").GetString().Should().Be("DNS_QUERY");
        cps[0].GetProperty("matchPattern").GetString().Should().Be("deploy.svc");
        cps[0].GetProperty("scenarioName").GetString().Should().Be("Deploy");
        cps[0].GetProperty("targetState").GetString().Should().Be("Deployed");
    }

    [Fact]
    public void Expectation_NoScenarioFields_OmitsThemFromJson()
    {
        var expectation = new Expectation
        {
            HttpRequest = HttpRequest.Request().WithPath("/plain").Build(),
            HttpResponse = HttpResponse.Response().WithStatusCode(200).Build()
        };

        var json = JsonSerializer.Serialize(expectation, JsonOptions);
        var root = JsonDocument.Parse(json).RootElement;

        root.TryGetProperty("httpResponses", out _).Should().BeFalse();
        root.TryGetProperty("responseMode", out _).Should().BeFalse();
        root.TryGetProperty("responseWeights", out _).Should().BeFalse();
        root.TryGetProperty("switchAfter", out _).Should().BeFalse();
        root.TryGetProperty("crossProtocolScenarios", out _).Should().BeFalse();
        root.TryGetProperty("scenarioName", out _).Should().BeFalse();
    }

    [Fact]
    public void CrossProtocolScenario_OmitsMatchPatternWhenUnset()
    {
        var cps = new CrossProtocolScenario
        {
            Trigger = CrossProtocolTrigger.HTTP_REQUEST,
            ScenarioName = "S",
            TargetState = "Next"
        };

        var json = JsonSerializer.Serialize(cps, JsonOptions);
        var root = JsonDocument.Parse(json).RootElement;

        root.GetProperty("trigger").GetString().Should().Be("HTTP_REQUEST");
        root.TryGetProperty("matchPattern", out _).Should().BeFalse();
    }

    [Theory]
    [InlineData(CrossProtocolTrigger.DNS_QUERY, "DNS_QUERY")]
    [InlineData(CrossProtocolTrigger.WEBSOCKET_CONNECT, "WEBSOCKET_CONNECT")]
    [InlineData(CrossProtocolTrigger.GRPC_REQUEST, "GRPC_REQUEST")]
    [InlineData(CrossProtocolTrigger.HTTP_REQUEST, "HTTP_REQUEST")]
    public void CrossProtocolTrigger_SerializesExactStrings(CrossProtocolTrigger trigger, string expected)
    {
        var json = JsonSerializer.Serialize(new CrossProtocolScenario { Trigger = trigger }, JsonOptions);
        JsonDocument.Parse(json).RootElement.GetProperty("trigger").GetString().Should().Be(expected);
    }

    [Theory]
    [InlineData(ResponseMode.SEQUENTIAL, "SEQUENTIAL")]
    [InlineData(ResponseMode.RANDOM, "RANDOM")]
    [InlineData(ResponseMode.WEIGHTED, "WEIGHTED")]
    [InlineData(ResponseMode.SWITCH, "SWITCH")]
    public void ResponseMode_SerializesExactStrings(ResponseMode mode, string expected)
    {
        var expectation = new Expectation { ResponseMode = mode };
        var json = JsonSerializer.Serialize(expectation, JsonOptions);
        JsonDocument.Parse(json).RootElement.GetProperty("responseMode").GetString().Should().Be(expected);
    }

    [Fact]
    public void Expectation_ScenarioFields_RoundTripThroughJson()
    {
        var original = new Expectation
        {
            ResponseMode = ResponseMode.SWITCH,
            SwitchAfter = 4,
            ResponseWeights = new List<int> { 1, 2 },
            CrossProtocolScenarios = new List<CrossProtocolScenario>
            {
                new() { Trigger = CrossProtocolTrigger.GRPC_REQUEST, ScenarioName = "S", TargetState = "T" }
            }
        };

        var json = JsonSerializer.Serialize(original, JsonOptions);
        var back = JsonSerializer.Deserialize<Expectation>(json, JsonOptions);

        back.Should().NotBeNull();
        back!.ResponseMode.Should().Be(ResponseMode.SWITCH);
        back.SwitchAfter.Should().Be(4);
        back.ResponseWeights.Should().Equal(1, 2);
        back.CrossProtocolScenarios.Should().HaveCount(1);
        back.CrossProtocolScenarios![0].Trigger.Should().Be(CrossProtocolTrigger.GRPC_REQUEST);
        back.CrossProtocolScenarios![0].ScenarioName.Should().Be("S");
        back.CrossProtocolScenarios![0].TargetState.Should().Be("T");
    }

    // -------------------------------------------------------------------
    // Fluent builder — scenario setters flow into the upserted expectation
    // -------------------------------------------------------------------

    [Fact]
    public void FluentBuilder_MultiResponseScenario_UpsertsExpectedJson()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "[]";

        client.When(HttpRequest.Request().WithPath("/deploy").Build())
            .WithScenarioName("Deploy")
            .WithScenarioState("Deploying")
            .WithNewScenarioState("Deployed")
            .WithResponseMode(ResponseMode.WEIGHTED)
            .WithResponseWeights(new[] { 3, 1 })
            .WithSwitchAfter(2)
            .WithCrossProtocolScenario(new CrossProtocolScenario
            {
                Trigger = CrossProtocolTrigger.WEBSOCKET_CONNECT,
                ScenarioName = "Deploy",
                TargetState = "Deployed"
            })
            .Respond(new[]
            {
                HttpResponse.Response().WithStatusCode(202).Build(),
                HttpResponse.Response().WithStatusCode(200).Build()
            });

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/expectation");

        // Body is an array of one expectation; assert the wire field names.
        var body = handler.LastRequestBody!;
        body.Should().Contain("\"scenarioName\":\"Deploy\"");
        body.Should().Contain("\"scenarioState\":\"Deploying\"");
        body.Should().Contain("\"newScenarioState\":\"Deployed\"");
        body.Should().Contain("\"httpResponses\"");
        body.Should().Contain("\"responseMode\":\"WEIGHTED\"");
        body.Should().Contain("\"responseWeights\":[3,1]");
        body.Should().Contain("\"switchAfter\":2");
        body.Should().Contain("\"crossProtocolScenarios\"");
        body.Should().Contain("\"trigger\":\"WEBSOCKET_CONNECT\"");
        body.Should().Contain("\"targetState\":\"Deployed\"");
    }

    // -------------------------------------------------------------------
    // Scenario REST helper
    // -------------------------------------------------------------------

    [Fact]
    public void ScenarioState_SendsGetAndParsesResponse()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"scenarioName\":\"Deploy\",\"currentState\":\"Deploying\"}";

        var state = client.Scenario("Deploy").State();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Get);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/scenario/Deploy");
        state.ScenarioName.Should().Be("Deploy");
        state.CurrentState.Should().Be("Deploying");
    }

    [Fact]
    public void ScenarioSet_SendsPutWithStateBody()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"scenarioName\":\"Deploy\",\"currentState\":\"Deploying\"}";

        var state = client.Scenario("Deploy").Set("Deploying");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/scenario/Deploy");
        handler.LastRequestBody.Should().Contain("\"state\":\"Deploying\"");
        handler.LastRequestBody.Should().NotContain("transitionAfterMs");
        handler.LastRequestBody.Should().NotContain("nextState");
        state.CurrentState.Should().Be("Deploying");
    }

    [Fact]
    public void ScenarioSetTimed_SendsPutWithTransitionBody()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"scenarioName\":\"Deploy\",\"currentState\":\"Deploying\",\"nextState\":\"Deployed\",\"transitionAfterMs\":5000}";

        var state = client.Scenario("Deploy").Set("Deploying", 5000, "Deployed");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/scenario/Deploy");
        handler.LastRequestBody.Should().Contain("\"state\":\"Deploying\"");
        handler.LastRequestBody.Should().Contain("\"transitionAfterMs\":5000");
        handler.LastRequestBody.Should().Contain("\"nextState\":\"Deployed\"");
        state.NextState.Should().Be("Deployed");
        state.TransitionAfterMs.Should().Be(5000);
    }

    [Fact]
    public void ScenarioTrigger_SendsPutToTriggerPath()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"scenarioName\":\"Deploy\",\"currentState\":\"Failed\"}";

        var state = client.Scenario("Deploy").Trigger("Failed");

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/scenario/Deploy/trigger");
        handler.LastRequestBody.Should().Contain("\"newState\":\"Failed\"");
        state.CurrentState.Should().Be("Failed");
    }

    [Fact]
    public void Scenarios_SendsGetAndParsesList()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "{\"scenarios\":[{\"scenarioName\":\"Deploy\",\"currentState\":\"Deploying\"},{\"scenarioName\":\"Rollout\",\"currentState\":\"Idle\"}]}";

        var scenarios = client.Scenarios();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Get);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/scenario");
        scenarios.Should().HaveCount(2);
        scenarios[0].ScenarioName.Should().Be("Deploy");
        scenarios[0].CurrentState.Should().Be("Deploying");
        scenarios[1].ScenarioName.Should().Be("Rollout");
    }

    [Fact]
    public void Scenarios_EmptyBody_ReturnsEmptyList()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = "";

        client.Scenarios().Should().BeEmpty();
    }

    [Fact]
    public void ScenarioState_ThrowsOnServerError()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.InternalServerError;
        handler.ResponseBody = "boom";

        var act = () => client.Scenario("Deploy").State();
        act.Should().Throw<MockServerClientException>().WithMessage("*Deploy*");
    }

    [Fact]
    public void Scenario_EmptyName_Throws()
    {
        var (client, _) = CreateClient();
        var act = () => client.Scenario("");
        act.Should().Throw<ArgumentException>();
    }
}
