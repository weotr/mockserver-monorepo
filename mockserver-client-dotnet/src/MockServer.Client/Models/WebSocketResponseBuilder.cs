namespace MockServer.Client.Models;

/// <summary>
/// Fluent builder for <see cref="HttpWebSocketResponse"/>.
/// </summary>
public sealed class WebSocketResponseBuilder
{
    private readonly HttpWebSocketResponse _response = new();

    public WebSocketResponseBuilder WithSubprotocol(string subprotocol)
    {
        _response.Subprotocol = subprotocol;
        return this;
    }

    /// <summary>
    /// Appends a message to the WebSocket stream.
    /// </summary>
    public WebSocketResponseBuilder WithMessage(WebSocketMessage message)
    {
        _response.Messages ??= new List<WebSocketMessage>();
        _response.Messages.Add(message);
        return this;
    }

    /// <summary>
    /// Appends a textual message to the WebSocket stream.
    /// </summary>
    public WebSocketResponseBuilder WithTextMessage(string text)
        => WithMessage(WebSocketMessage.OfText(text));

    /// <summary>
    /// Appends a binary message (raw bytes, Base64-encoded for the wire).
    /// </summary>
    public WebSocketResponseBuilder WithBinaryMessage(byte[] bytes)
        => WithMessage(WebSocketMessage.OfBinary(bytes));

    public WebSocketResponseBuilder WithCloseConnection(bool closeConnection = true)
    {
        _response.CloseConnection = closeConnection;
        return this;
    }

    public WebSocketResponseBuilder WithDelay(TimeUnit timeUnit, long value)
    {
        _response.Delay = new Delay { TimeUnit = timeUnit, Value = value };
        return this;
    }

    public WebSocketResponseBuilder WithPrimary(bool primary = true)
    {
        _response.Primary = primary;
        return this;
    }

    public HttpWebSocketResponse Build() => _response;

    public static implicit operator HttpWebSocketResponse(WebSocketResponseBuilder builder) => builder.Build();
}
