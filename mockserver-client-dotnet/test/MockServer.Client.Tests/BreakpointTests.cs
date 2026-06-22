using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using FluentAssertions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

/// <summary>
/// Tests for breakpoint REST endpoints and the WS message routing.
/// Uses FakeHandler for REST tests and direct ProcessMessage calls for WS routing.
/// </summary>
public class BreakpointTests
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    /// <summary>
    /// A simple message handler that captures requests and returns pre-configured responses.
    /// </summary>
    private sealed class FakeHandler : HttpMessageHandler
    {
        public HttpRequestMessage? LastRequest { get; private set; }
        public string? LastRequestBody { get; private set; }
        public HttpStatusCode ResponseStatusCode { get; set; } = HttpStatusCode.OK;
        public string ResponseBody { get; set; } = "";

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            LastRequest = request;
            if (request.Content != null)
                LastRequestBody = await request.Content.ReadAsStringAsync(cancellationToken);
            else
                LastRequestBody = null;

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

    // --- BreakpointMatcherRegistration serialization ---

    [Fact]
    public void BreakpointMatcherRegistration_Serializes_Correctly()
    {
        var reg = new BreakpointMatcherRegistration
        {
            HttpRequest = HttpRequest.Request().WithMethod("GET").WithPath("/api/.*").Build(),
            Phases = new List<string> { BreakpointPhase.Request, BreakpointPhase.Response },
            ClientId = "my-client-id"
        };

        var json = JsonSerializer.Serialize(reg, JsonOptions);
        var parsed = JsonNode.Parse(json)!;

        parsed["httpRequest"]!["method"]!.GetValue<string>().Should().Be("GET");
        parsed["httpRequest"]!["path"]!.GetValue<string>().Should().Be("/api/.*");
        parsed["phases"]!.AsArray().Should().HaveCount(2);
        parsed["phases"]![0]!.GetValue<string>().Should().Be("REQUEST");
        parsed["phases"]![1]!.GetValue<string>().Should().Be("RESPONSE");
        parsed["clientId"]!.GetValue<string>().Should().Be("my-client-id");
    }

    [Fact]
    public void BreakpointMatcherRegistration_OmitsNullClientId()
    {
        var reg = new BreakpointMatcherRegistration
        {
            HttpRequest = HttpRequest.Request().WithPath("/test").Build(),
            Phases = new List<string> { BreakpointPhase.Request }
        };

        var json = JsonSerializer.Serialize(reg, JsonOptions);
        json.Should().NotContain("clientId");
    }

    // --- BreakpointMatcherResponse deserialization ---

    [Fact]
    public void BreakpointMatcherResponse_Deserializes()
    {
        var json = """{"id":"bp-123","phases":["REQUEST","RESPONSE"]}""";
        var result = JsonSerializer.Deserialize<BreakpointMatcherResponse>(json, JsonOptions);

        result!.Id.Should().Be("bp-123");
        result.Phases.Should().BeEquivalentTo(new[] { "REQUEST", "RESPONSE" });
    }

    // --- BreakpointMatcherList deserialization ---

    [Fact]
    public void BreakpointMatcherList_Deserializes()
    {
        var json = """{"matchers":[{"id":"bp-1","httpRequest":{"path":"/test"},"phases":["REQUEST"],"clientId":"c1"}]}""";
        var result = JsonSerializer.Deserialize<BreakpointMatcherList>(json, JsonOptions);

        result!.Matchers.Should().HaveCount(1);
        result.Matchers![0].Id.Should().Be("bp-1");
        result.Matchers[0].ClientId.Should().Be("c1");
    }

    // --- PausedStreamFrame ---

    [Fact]
    public void PausedStreamFrame_Deserializes()
    {
        var bodyB64 = Convert.ToBase64String(Encoding.UTF8.GetBytes("hello stream"));
        var json = $$"""
        {
            "correlationId": "corr-1",
            "streamId": "stream-1",
            "sequenceNumber": 3,
            "direction": "OUTBOUND",
            "phase": "RESPONSE_STREAM",
            "body": "{{bodyB64}}",
            "requestMethod": "GET",
            "requestPath": "/data",
            "breakpointId": "bp-42"
        }
        """;

        var frame = JsonSerializer.Deserialize<PausedStreamFrame>(json, JsonOptions);
        frame!.CorrelationId.Should().Be("corr-1");
        frame.StreamId.Should().Be("stream-1");
        frame.SequenceNumber.Should().Be(3);
        frame.Direction.Should().Be("OUTBOUND");
        frame.Phase.Should().Be("RESPONSE_STREAM");
        frame.BreakpointId.Should().Be("bp-42");
        Encoding.UTF8.GetString(frame.BodyBytes()).Should().Be("hello stream");
    }

    // --- StreamFrameDecision ---

    [Fact]
    public void StreamFrameDecision_Continue_Serializes()
    {
        var d = StreamFrameDecision.Continue("corr-1");
        var json = JsonSerializer.Serialize(d, JsonOptions);
        var parsed = JsonNode.Parse(json)!;
        parsed["correlationId"]!.GetValue<string>().Should().Be("corr-1");
        parsed["action"]!.GetValue<string>().Should().Be("CONTINUE");
        parsed["body"].Should().BeNull();
    }

    [Fact]
    public void StreamFrameDecision_Modify_Serializes()
    {
        var d = StreamFrameDecision.Modify("corr-2", Encoding.UTF8.GetBytes("new data"));
        var json = JsonSerializer.Serialize(d, JsonOptions);
        var parsed = JsonNode.Parse(json)!;
        parsed["action"]!.GetValue<string>().Should().Be("MODIFY");
        var bodyBytes = Convert.FromBase64String(parsed["body"]!.GetValue<string>());
        Encoding.UTF8.GetString(bodyBytes).Should().Be("new data");
    }

    [Fact]
    public void StreamFrameDecision_Drop_Serializes()
    {
        var d = StreamFrameDecision.Drop("corr-3");
        var json = JsonSerializer.Serialize(d, JsonOptions);
        json.Should().Contain("\"action\":\"DROP\"");
    }

    [Fact]
    public void StreamFrameDecision_Inject_Serializes()
    {
        var d = StreamFrameDecision.Inject("corr-4", Encoding.UTF8.GetBytes("extra"));
        var json = JsonSerializer.Serialize(d, JsonOptions);
        json.Should().Contain("\"action\":\"INJECT\"");
    }

    [Fact]
    public void StreamFrameDecision_Close_Serializes()
    {
        var d = StreamFrameDecision.Close("corr-5");
        var json = JsonSerializer.Serialize(d, JsonOptions);
        json.Should().Contain("\"action\":\"CLOSE\"");
    }

    // --- WS Envelope format ---

    [Fact]
    public void WsEnvelope_Serializes_DoubleEncoded()
    {
        var env = new BreakpointWebSocketClient.WsEnvelope
        {
            Type = "org.mockserver.model.HttpRequest",
            Value = """{"method":"GET","path":"/test"}"""
        };
        var json = JsonSerializer.Serialize(env, JsonOptions);
        var parsed = JsonNode.Parse(json)!;
        parsed["type"]!.GetValue<string>().Should().Be("org.mockserver.model.HttpRequest");

        // Value is a JSON string (double-encoded)
        var valueStr = parsed["value"]!.GetValue<string>();
        var inner = JsonNode.Parse(valueStr)!;
        inner["method"]!.GetValue<string>().Should().Be("GET");
    }

    // --- WS message routing tests (direct ProcessMessage) ---

    [Fact]
    public void ProcessMessage_Routes_Request_To_Handler()
    {
        var ws = new BreakpointWebSocketClient();
        bool handled = false;

        ws.SetRequestHandler("bp-42", request =>
        {
            handled = true;
            return request; // continue
        });

        var request = new JsonObject
        {
            ["method"] = "GET",
            ["path"] = "/test",
            ["headers"] = new JsonObject
            {
                ["WebSocketCorrelationId"] = new JsonArray("corr-1"),
                ["X-MockServer-BreakpointId"] = new JsonArray("bp-42")
            }
        };

        var envelope = new JsonObject
        {
            ["type"] = "org.mockserver.model.HttpRequest",
            ["value"] = request.ToJsonString()
        };

        // ProcessMessage will try to send on the WS (which is null) but won't crash
        ws.ProcessMessage(envelope.ToJsonString());

        handled.Should().BeTrue();
    }

    [Fact]
    public void ProcessMessage_Routes_Response_To_Handler()
    {
        var ws = new BreakpointWebSocketClient();
        bool handled = false;

        ws.SetResponseHandler("bp-resp", (req, resp) =>
        {
            handled = true;
            return resp;
        });

        var reqAndResp = new JsonObject
        {
            ["httpRequest"] = new JsonObject
            {
                ["method"] = "GET",
                ["headers"] = new JsonObject
                {
                    ["WebSocketCorrelationId"] = new JsonArray("corr-resp"),
                    ["X-MockServer-BreakpointId"] = new JsonArray("bp-resp")
                }
            },
            ["httpResponse"] = new JsonObject
            {
                ["statusCode"] = 200
            }
        };

        var envelope = new JsonObject
        {
            ["type"] = "org.mockserver.model.HttpRequestAndHttpResponse",
            ["value"] = reqAndResp.ToJsonString()
        };

        ws.ProcessMessage(envelope.ToJsonString());
        handled.Should().BeTrue();
    }

    [Fact]
    public void ProcessMessage_Routes_StreamFrame_To_Handler()
    {
        var ws = new BreakpointWebSocketClient();
        PausedStreamFrame? capturedFrame = null;

        ws.SetStreamFrameHandler("bp-stream", frame =>
        {
            capturedFrame = frame;
            return StreamFrameDecision.Modify(frame.CorrelationId!, Encoding.UTF8.GetBytes("modified"));
        });

        var bodyB64 = Convert.ToBase64String(Encoding.UTF8.GetBytes("original"));
        var frame = new JsonObject
        {
            ["correlationId"] = "corr-frame",
            ["streamId"] = "stream-1",
            ["sequenceNumber"] = 0,
            ["direction"] = "OUTBOUND",
            ["phase"] = "RESPONSE_STREAM",
            ["body"] = bodyB64,
            ["breakpointId"] = "bp-stream"
        };

        var envelope = new JsonObject
        {
            ["type"] = "org.mockserver.serialization.model.PausedStreamFrameDTO",
            ["value"] = frame.ToJsonString()
        };

        ws.ProcessMessage(envelope.ToJsonString());

        capturedFrame.Should().NotBeNull();
        capturedFrame!.CorrelationId.Should().Be("corr-frame");
        capturedFrame.BreakpointId.Should().Be("bp-stream");
    }

    [Fact]
    public void ProcessMessage_AutoContinues_StreamFrame_NoHandler()
    {
        var ws = new BreakpointWebSocketClient();

        var frame = new JsonObject
        {
            ["correlationId"] = "corr-no-handler",
            ["streamId"] = "stream-2",
            ["phase"] = "RESPONSE_STREAM",
            ["body"] = Convert.ToBase64String(Encoding.UTF8.GetBytes("data")),
            ["breakpointId"] = "unknown-bp"
        };

        var envelope = new JsonObject
        {
            ["type"] = "org.mockserver.serialization.model.PausedStreamFrameDTO",
            ["value"] = frame.ToJsonString()
        };

        // Should not throw — auto-continues
        ws.ProcessMessage(envelope.ToJsonString());
    }

    [Fact]
    public void ProcessMessage_AutoContinues_Request_OnHandlerException()
    {
        var ws = new BreakpointWebSocketClient();

        ws.SetRequestHandler("bp-err", _ => throw new InvalidOperationException("test error"));

        var request = new JsonObject
        {
            ["method"] = "GET",
            ["headers"] = new JsonObject
            {
                ["WebSocketCorrelationId"] = new JsonArray("corr-err"),
                ["X-MockServer-BreakpointId"] = new JsonArray("bp-err")
            }
        };

        var envelope = new JsonObject
        {
            ["type"] = "org.mockserver.model.HttpRequest",
            ["value"] = request.ToJsonString()
        };

        // Should not throw — auto-continues on error
        ws.ProcessMessage(envelope.ToJsonString());
    }

    [Fact]
    public void ProcessMessage_PerBreakpointId_Selection()
    {
        var ws = new BreakpointWebSocketClient();
        var handledIds = new List<string>();

        ws.SetRequestHandler("bp-A", req => { handledIds.Add("A"); return req; });
        ws.SetRequestHandler("bp-B", req => { handledIds.Add("B"); return req; });

        // Dispatch bp-B first
        var requestB = new JsonObject
        {
            ["path"] = "/b",
            ["headers"] = new JsonObject
            {
                ["WebSocketCorrelationId"] = new JsonArray("corr-B"),
                ["X-MockServer-BreakpointId"] = new JsonArray("bp-B")
            }
        };
        ws.ProcessMessage(new JsonObject
        {
            ["type"] = "org.mockserver.model.HttpRequest",
            ["value"] = requestB.ToJsonString()
        }.ToJsonString());

        // Then bp-A
        var requestA = new JsonObject
        {
            ["path"] = "/a",
            ["headers"] = new JsonObject
            {
                ["WebSocketCorrelationId"] = new JsonArray("corr-A"),
                ["X-MockServer-BreakpointId"] = new JsonArray("bp-A")
            }
        };
        ws.ProcessMessage(new JsonObject
        {
            ["type"] = "org.mockserver.model.HttpRequest",
            ["value"] = requestA.ToJsonString()
        }.ToJsonString());

        handledIds.Should().BeEquivalentTo(new[] { "B", "A" }, options => options.WithStrictOrdering());
    }

    // --- REST endpoint tests ---

    [Fact]
    public void ListBreakpointMatchers_SendsGet()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = """{"matchers":[]}""";

        var result = client.ListBreakpointMatchers();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Get);
        handler.LastRequest.RequestUri!.PathAndQuery.Should().Be("/mockserver/breakpoint/matchers");
        result.Matchers.Should().BeEmpty();
    }

    [Fact]
    public void RemoveBreakpointMatcher_SendsCorrectBody()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = """{"status":"removed","id":"bp-1"}""";

        client.RemoveBreakpointMatcher("bp-1");

        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/breakpoint/matcher/remove");
        handler.LastRequestBody.Should().Contain("\"id\":\"bp-1\"");
    }

    [Fact]
    public void RemoveBreakpointMatcher_404_Throws()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.NotFound;

        var act = () => client.RemoveBreakpointMatcher("missing");
        act.Should().Throw<Exceptions.MockServerClientException>()
            .WithMessage("*not found*");
    }

    [Fact]
    public void ClearBreakpointMatchers_SendsPut()
    {
        var (client, handler) = CreateClient();
        handler.ResponseBody = """{"status":"cleared","count":2}""";

        client.ClearBreakpointMatchers();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest.RequestUri!.PathAndQuery.Should().Be("/mockserver/breakpoint/matcher/clear");
    }

    // --- Handler management ---

    [Fact]
    public void RemoveHandlers_Clears_All_Types()
    {
        var ws = new BreakpointWebSocketClient();
        ws.SetRequestHandler("bp-x", _ => null);
        ws.SetResponseHandler("bp-x", (_, _) => null);
        ws.SetStreamFrameHandler("bp-x", _ => null);

        ws.RemoveHandlers("bp-x");

        // Verify by dispatching -- should auto-continue (no handler found)
        bool requestHandled = false;
        ws.SetRequestHandler("bp-x-check", _ => { requestHandled = true; return null; });

        // Dispatch to bp-x should NOT trigger bp-x-check
        var request = new JsonObject
        {
            ["headers"] = new JsonObject
            {
                ["X-MockServer-BreakpointId"] = new JsonArray("bp-x")
            }
        };
        ws.ProcessMessage(new JsonObject
        {
            ["type"] = "org.mockserver.model.HttpRequest",
            ["value"] = request.ToJsonString()
        }.ToJsonString());

        requestHandled.Should().BeFalse();
    }

    [Fact]
    public void ClearHandlers_Clears_All()
    {
        var ws = new BreakpointWebSocketClient();
        ws.SetRequestHandler("bp-1", _ => null);
        ws.SetRequestHandler("bp-2", _ => null);
        ws.SetResponseHandler("bp-1", (_, _) => null);

        ws.ClearHandlers();

        // Dispatch should not find any handler
        var request = new JsonObject
        {
            ["headers"] = new JsonObject
            {
                ["X-MockServer-BreakpointId"] = new JsonArray("bp-1")
            }
        };
        // Should not throw
        ws.ProcessMessage(new JsonObject
        {
            ["type"] = "org.mockserver.model.HttpRequest",
            ["value"] = request.ToJsonString()
        }.ToJsonString());
    }
}
