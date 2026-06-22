namespace MockServer.Client.Models;

/// <summary>
/// Fluent builder for <see cref="GrpcStreamResponse"/>.
/// </summary>
public sealed class GrpcStreamResponseBuilder
{
    private readonly GrpcStreamResponse _response = new();

    public GrpcStreamResponseBuilder WithStatusName(string statusName)
    {
        _response.StatusName = statusName;
        return this;
    }

    public GrpcStreamResponseBuilder WithStatusMessage(string statusMessage)
    {
        _response.StatusMessage = statusMessage;
        return this;
    }

    public GrpcStreamResponseBuilder WithHeader(string name, params string[] values)
    {
        _response.Headers ??= new Dictionary<string, List<string>>();
        _response.Headers[name] = new List<string>(values);
        return this;
    }

    /// <summary>
    /// Appends a message to the gRPC stream.
    /// </summary>
    public GrpcStreamResponseBuilder WithMessage(GrpcStreamMessage message)
    {
        _response.Messages ??= new List<GrpcStreamMessage>();
        _response.Messages.Add(message);
        return this;
    }

    /// <summary>
    /// Appends a message built from its JSON-encoded protobuf payload.
    /// </summary>
    public GrpcStreamResponseBuilder WithMessage(string json)
        => WithMessage(GrpcStreamMessage.OfJson(json));

    public GrpcStreamResponseBuilder WithCloseConnection(bool closeConnection = true)
    {
        _response.CloseConnection = closeConnection;
        return this;
    }

    public GrpcStreamResponseBuilder WithDelay(TimeUnit timeUnit, long value)
    {
        _response.Delay = new Delay { TimeUnit = timeUnit, Value = value };
        return this;
    }

    public GrpcStreamResponseBuilder WithPrimary(bool primary = true)
    {
        _response.Primary = primary;
        return this;
    }

    public GrpcStreamResponse Build() => _response;

    public static implicit operator GrpcStreamResponse(GrpcStreamResponseBuilder builder) => builder.Build();
}
