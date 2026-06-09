using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// Represents a list of ports (used for status and bind responses).
/// </summary>
public sealed class Ports
{
    [JsonPropertyName("ports")]
    public List<int> PortList { get; set; } = new();
}
