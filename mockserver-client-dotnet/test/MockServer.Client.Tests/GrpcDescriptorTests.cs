using System.Net;
using System.Net.Http;
using System.Text;
using FluentAssertions;
using MockServer.Client.Exceptions;
using MockServer.Client.Models;
using Xunit;

namespace MockServer.Client.Tests;

/// <summary>
/// Unit tests for the gRPC descriptor management API (upload / retrieve / clear).
/// Uses a fake HttpMessageHandler so no real server is required.
/// </summary>
public class GrpcDescriptorTests
{
    /// <summary>
    /// Captures the outgoing request including the raw request body bytes and content type.
    /// </summary>
    private sealed class FakeHandler : HttpMessageHandler
    {
        public HttpRequestMessage? LastRequest { get; private set; }
        public byte[]? LastRequestBytes { get; private set; }
        public string? LastContentType { get; private set; }
        public HttpStatusCode ResponseStatusCode { get; set; } = HttpStatusCode.OK;
        public string ResponseBody { get; set; } = "";

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            LastRequest = request;
            if (request.Content != null)
            {
                LastRequestBytes = await request.Content.ReadAsByteArrayAsync(cancellationToken);
                LastContentType = request.Content.Headers.ContentType?.MediaType;
            }
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

    [Fact]
    public void UploadGrpcDescriptor_SendsRawBytesAsOctetStream()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.Created;
        var descriptor = new byte[] { 0x0A, 0x10, 0x00, 0xFF, 0x42 };

        client.UploadGrpcDescriptor(descriptor);

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/grpc/descriptors");
        handler.LastContentType.Should().Be("application/octet-stream");
        // Raw, NOT base64-encoded — the bytes are sent verbatim.
        handler.LastRequestBytes.Should().Equal(descriptor);
    }

    [Fact]
    public void UploadGrpcDescriptor_EmptyThrows()
    {
        var (client, _) = CreateClient();

        var act = () => client.UploadGrpcDescriptor(Array.Empty<byte>());

        act.Should().Throw<ArgumentException>();
    }

    [Fact]
    public void UploadGrpcDescriptor_ThrowsOnError()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.BadRequest;
        handler.ResponseBody = "could not parse descriptor";

        var act = () => client.UploadGrpcDescriptor(new byte[] { 0x01, 0x02 });

        act.Should().Throw<MockServerClientException>().WithMessage("*could not parse descriptor*");
    }

    [Fact]
    public void RetrieveGrpcServices_SendsCorrectRequestAndParsesResponse()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.OK;
        handler.ResponseBody = """
        [
          {
            "name": "example.Greeter",
            "methods": [
              {
                "name": "SayHello",
                "inputType": "example.HelloRequest",
                "outputType": "example.HelloReply",
                "clientStreaming": false,
                "serverStreaming": false
              },
              {
                "name": "Chat",
                "inputType": "example.ChatMessage",
                "outputType": "example.ChatMessage",
                "clientStreaming": true,
                "serverStreaming": true
              }
            ]
          }
        ]
        """;

        var services = client.RetrieveGrpcServices();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/grpc/services");

        services.Should().HaveCount(1);
        services[0].Name.Should().Be("example.Greeter");
        services[0].Methods.Should().HaveCount(2);

        var sayHello = services[0].Methods[0];
        sayHello.Name.Should().Be("SayHello");
        sayHello.InputType.Should().Be("example.HelloRequest");
        sayHello.OutputType.Should().Be("example.HelloReply");
        sayHello.ClientStreaming.Should().BeFalse();
        sayHello.ServerStreaming.Should().BeFalse();

        var chat = services[0].Methods[1];
        chat.ClientStreaming.Should().BeTrue();
        chat.ServerStreaming.Should().BeTrue();
    }

    [Fact]
    public void RetrieveGrpcServices_EmptyResponseReturnsEmptyArray()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.OK;
        handler.ResponseBody = "[]";

        var services = client.RetrieveGrpcServices();

        services.Should().NotBeNull();
        services.Should().BeEmpty();
    }

    [Fact]
    public void RetrieveGrpcServices_ThrowsOnError()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.InternalServerError;
        handler.ResponseBody = "boom";

        var act = () => client.RetrieveGrpcServices();

        act.Should().Throw<MockServerClientException>();
    }

    [Fact]
    public void ClearGrpcDescriptors_SendsCorrectRequest()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.OK;

        client.ClearGrpcDescriptors();

        handler.LastRequest!.Method.Should().Be(HttpMethod.Put);
        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/grpc/clear");
    }

    [Fact]
    public void ClearGrpcDescriptors_ThrowsOnError()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.InternalServerError;

        var act = () => client.ClearGrpcDescriptors();

        act.Should().Throw<MockServerClientException>();
    }

    [Fact]
    public async Task UploadGrpcDescriptorAsync_SendsRawBytes()
    {
        var (client, handler) = CreateClient();
        handler.ResponseStatusCode = HttpStatusCode.Created;
        var descriptor = new byte[] { 0x7F, 0x00, 0x01 };

        await client.UploadGrpcDescriptorAsync(descriptor);

        handler.LastRequest!.RequestUri!.PathAndQuery.Should().Be("/mockserver/grpc/descriptors");
        handler.LastContentType.Should().Be("application/octet-stream");
        handler.LastRequestBytes.Should().Equal(descriptor);
    }
}
