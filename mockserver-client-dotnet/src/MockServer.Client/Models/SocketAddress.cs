using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// The host/port/scheme to use when connecting the socket for a forwarded request,
/// independent of the request line and <c>Host</c> header. Mirrors
/// <c>org.mockserver.model.SocketAddress</c>.
/// </summary>
public sealed class SocketAddress
{
    [JsonPropertyName("host")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Host { get; set; }

    [JsonPropertyName("port")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? Port { get; set; }

    /// <summary>The connection scheme, either <c>HTTP</c> or <c>HTTPS</c>.</summary>
    [JsonPropertyName("scheme")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    [JsonConverter(typeof(JsonStringEnumConverter))]
    public SocketScheme? Scheme { get; set; }
}

/// <summary>Connection scheme for a <see cref="SocketAddress"/>.</summary>
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum SocketScheme
{
    HTTP,
    HTTPS
}
