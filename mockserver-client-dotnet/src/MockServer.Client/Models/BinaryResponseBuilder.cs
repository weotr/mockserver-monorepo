namespace MockServer.Client.Models;

/// <summary>
/// Fluent builder for <see cref="BinaryResponse"/>.
/// </summary>
public sealed class BinaryResponseBuilder
{
    private readonly BinaryResponse _response = new();

    /// <summary>
    /// Sets the response payload from raw bytes (Base64-encoded for the wire).
    /// </summary>
    public BinaryResponseBuilder WithBytes(byte[] bytes)
    {
        _response.BinaryData = Convert.ToBase64String(bytes);
        return this;
    }

    /// <summary>
    /// Sets the response payload from an already-Base64-encoded string.
    /// </summary>
    public BinaryResponseBuilder WithBase64(string base64)
    {
        _response.BinaryData = base64;
        return this;
    }

    public BinaryResponseBuilder WithDelay(TimeUnit timeUnit, long value)
    {
        _response.Delay = new Delay { TimeUnit = timeUnit, Value = value };
        return this;
    }

    public BinaryResponseBuilder WithPrimary(bool primary = true)
    {
        _response.Primary = primary;
        return this;
    }

    public BinaryResponse Build() => _response;

    public static implicit operator BinaryResponse(BinaryResponseBuilder builder) => builder.Build();
}
