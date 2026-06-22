using System.Text.Json;
using System.Text.Json.Nodes;
using FluentAssertions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

/// <summary>
/// Tests for class callbacks (REST serialization) and object/closure callbacks
/// (WebSocket reply-envelope routing via direct ProcessMessage, mirroring BreakpointTests).
/// </summary>
public class CallbackTests
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    // --- Class callback serialization ---

    [Fact]
    public void HttpResponseClassCallback_Serializes_Correctly()
    {
        var expectation = new Expectation
        {
            HttpRequest = HttpRequest.Request().WithMethod("GET").WithPath("/api").Build(),
            HttpResponseClassCallback = new HttpClassCallback
            {
                CallbackClass = "com.example.MyCallback",
                Delay = new Delay { TimeUnit = TimeUnit.MILLISECONDS, Value = 50 },
                Primary = true
            }
        };

        var json = JsonSerializer.Serialize(expectation, JsonOptions);
        var parsed = JsonNode.Parse(json)!;

        parsed["httpResponseClassCallback"]!["callbackClass"]!.GetValue<string>()
            .Should().Be("com.example.MyCallback");
        parsed["httpResponseClassCallback"]!["delay"]!["value"]!.GetValue<long>().Should().Be(50);
        parsed["httpResponseClassCallback"]!["primary"]!.GetValue<bool>().Should().BeTrue();
    }

    [Fact]
    public void HttpForwardClassCallback_Serializes_Correctly()
    {
        var expectation = new Expectation
        {
            HttpRequest = HttpRequest.Request().WithPath("/fwd").Build(),
            HttpForwardClassCallback = HttpClassCallback.Of("com.example.FwdCallback")
        };

        var json = JsonSerializer.Serialize(expectation, JsonOptions);
        var parsed = JsonNode.Parse(json)!;

        parsed["httpForwardClassCallback"]!["callbackClass"]!.GetValue<string>()
            .Should().Be("com.example.FwdCallback");
        // optional fields omitted when null
        parsed["httpForwardClassCallback"]!.AsObject().ContainsKey("delay").Should().BeFalse();
        parsed["httpForwardClassCallback"]!.AsObject().ContainsKey("primary").Should().BeFalse();
    }

    [Fact]
    public void ClassCallback_OmitsNull_DelayAndPrimary()
    {
        var json = JsonSerializer.Serialize(HttpClassCallback.Of("c.X"), JsonOptions);
        json.Should().Contain("\"callbackClass\":\"c.X\"");
        json.Should().NotContain("delay");
        json.Should().NotContain("primary");
    }

    // --- Object callback serialization ---

    [Fact]
    public void HttpResponseObjectCallback_Serializes_ClientId()
    {
        var expectation = new Expectation
        {
            HttpRequest = HttpRequest.Request().WithPath("/obj").Build(),
            HttpResponseObjectCallback = new HttpObjectCallback { ClientId = "ws-client-1" }
        };

        var json = JsonSerializer.Serialize(expectation, JsonOptions);
        var parsed = JsonNode.Parse(json)!;

        parsed["httpResponseObjectCallback"]!["clientId"]!.GetValue<string>().Should().Be("ws-client-1");
        // responseCallback/delay/primary omitted when null
        parsed["httpResponseObjectCallback"]!.AsObject().ContainsKey("responseCallback").Should().BeFalse();
    }

    // --- Object callback WS routing: reply envelope ---

    [Fact]
    public void ObjectResponseCallback_Produces_HttpResponse_Reply_EchoingCorrelationId()
    {
        var ws = new BreakpointWebSocketClient();
        JsonObject? capturedReply = null;
        string? capturedType = null;

        ws.SetSendInterceptorForTest((type, valueJson) =>
        {
            capturedType = type;
            capturedReply = JsonNode.Parse(valueJson)!.AsObject();
        });

        ws.SetObjectResponseHandler(request =>
        {
            var path = request["path"]?.GetValue<string>();
            return new JsonObject
            {
                ["statusCode"] = 200,
                ["body"] = $"handled {path}"
            };
        });

        // A request frame WITHOUT X-MockServer-BreakpointId is an object-callback dispatch.
        var request = new JsonObject
        {
            ["method"] = "GET",
            ["path"] = "/dynamic",
            ["headers"] = new JsonObject
            {
                ["WebSocketCorrelationId"] = new JsonArray("corr-obj-1")
            }
        };
        var envelope = new JsonObject
        {
            ["type"] = "org.mockserver.model.HttpRequest",
            ["value"] = request.ToJsonString()
        };

        ws.ProcessMessage(envelope.ToJsonString());

        capturedType.Should().Be("org.mockserver.model.HttpResponse");
        capturedReply.Should().NotBeNull();
        capturedReply!["statusCode"]!.GetValue<int>().Should().Be(200);
        capturedReply["body"]!.GetValue<string>().Should().Be("handled /dynamic");
        // WebSocketCorrelationId echoed back as a header array
        capturedReply["headers"]!["WebSocketCorrelationId"]!.AsArray()[0]!.GetValue<string>()
            .Should().Be("corr-obj-1");
    }

    [Fact]
    public void ObjectForwardCallback_Produces_HttpRequest_Reply()
    {
        var ws = new BreakpointWebSocketClient();
        string? capturedType = null;
        JsonObject? capturedReply = null;

        ws.SetSendInterceptorForTest((type, valueJson) =>
        {
            capturedType = type;
            capturedReply = JsonNode.Parse(valueJson)!.AsObject();
        });

        ws.SetObjectForwardHandler(request =>
        {
            request["path"] = "/rewritten";
            return request;
        });

        var request = new JsonObject
        {
            ["method"] = "GET",
            ["path"] = "/original",
            ["headers"] = new JsonObject
            {
                ["WebSocketCorrelationId"] = new JsonArray("corr-fwd-1")
            }
        };
        var envelope = new JsonObject
        {
            ["type"] = "org.mockserver.model.HttpRequest",
            ["value"] = request.ToJsonString()
        };

        ws.ProcessMessage(envelope.ToJsonString());

        capturedType.Should().Be("org.mockserver.model.HttpRequest");
        capturedReply!["path"]!.GetValue<string>().Should().Be("/rewritten");
        capturedReply["headers"]!["WebSocketCorrelationId"]!.AsArray()[0]!.GetValue<string>()
            .Should().Be("corr-fwd-1");
    }

    [Fact]
    public void BreakpointRequest_Still_Routes_To_BreakpointHandler_NotObjectCallback()
    {
        var ws = new BreakpointWebSocketClient();
        bool breakpointHandled = false;
        bool objectHandled = false;

        ws.SetRequestHandler("bp-1", req => { breakpointHandled = true; return req; });
        ws.SetObjectResponseHandler(req => { objectHandled = true; return new JsonObject { ["statusCode"] = 200 }; });

        // Frame WITH a breakpoint id must go to the breakpoint handler.
        var request = new JsonObject
        {
            ["path"] = "/bp",
            ["headers"] = new JsonObject
            {
                ["WebSocketCorrelationId"] = new JsonArray("corr-bp"),
                ["X-MockServer-BreakpointId"] = new JsonArray("bp-1")
            }
        };
        ws.ProcessMessage(new JsonObject
        {
            ["type"] = "org.mockserver.model.HttpRequest",
            ["value"] = request.ToJsonString()
        }.ToJsonString());

        breakpointHandled.Should().BeTrue();
        objectHandled.Should().BeFalse();
    }
}
