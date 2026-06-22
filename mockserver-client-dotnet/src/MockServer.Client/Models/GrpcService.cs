using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// A gRPC service registered from an uploaded protobuf descriptor set.
/// </summary>
public sealed class GrpcService
{
    /// <summary>Fully-qualified service name (e.g. "example.Greeter").</summary>
    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    /// <summary>The methods declared by this service.</summary>
    [JsonPropertyName("methods")]
    public List<GrpcMethod> Methods { get; set; } = new();
}

/// <summary>
/// A method declared by a <see cref="GrpcService"/>.
/// </summary>
public sealed class GrpcMethod
{
    /// <summary>Method name (e.g. "SayHello").</summary>
    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    /// <summary>Fully-qualified input (request) message type.</summary>
    [JsonPropertyName("inputType")]
    public string InputType { get; set; } = "";

    /// <summary>Fully-qualified output (response) message type.</summary>
    [JsonPropertyName("outputType")]
    public string OutputType { get; set; } = "";

    /// <summary>True if the client streams a sequence of request messages.</summary>
    [JsonPropertyName("clientStreaming")]
    public bool ClientStreaming { get; set; }

    /// <summary>True if the server streams a sequence of response messages.</summary>
    [JsonPropertyName("serverStreaming")]
    public bool ServerStreaming { get; set; }
}
