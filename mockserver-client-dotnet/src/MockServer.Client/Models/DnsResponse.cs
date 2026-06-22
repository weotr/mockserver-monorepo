using System.Text.Json.Serialization;

namespace MockServer.Client.Models;

/// <summary>
/// A single DNS resource record within a <see cref="DnsResponse"/>.
/// </summary>
public sealed class DnsRecord
{
    [JsonPropertyName("name")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Name { get; set; }

    [JsonPropertyName("type")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Type { get; set; }

    [JsonPropertyName("dnsClass")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? DnsClass { get; set; }

    [JsonPropertyName("ttl")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? Ttl { get; set; }

    [JsonPropertyName("value")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Value { get; set; }

    [JsonPropertyName("priority")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? Priority { get; set; }

    [JsonPropertyName("weight")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? Weight { get; set; }

    [JsonPropertyName("port")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? Port { get; set; }

    /// <summary>An IPv4 address (A) record.</summary>
    public static DnsRecord ARecord(string name, string ip) => new() { Name = name, Type = "A", Value = ip };

    /// <summary>An IPv6 address (AAAA) record.</summary>
    public static DnsRecord AaaaRecord(string name, string ip) => new() { Name = name, Type = "AAAA", Value = ip };

    /// <summary>A canonical name (CNAME) record.</summary>
    public static DnsRecord CnameRecord(string name, string cname) => new() { Name = name, Type = "CNAME", Value = cname };

    /// <summary>A mail exchanger (MX) record.</summary>
    public static DnsRecord MxRecord(string name, int priority, string exchange)
        => new() { Name = name, Type = "MX", Priority = priority, Value = exchange };

    /// <summary>A service locator (SRV) record.</summary>
    public static DnsRecord SrvRecord(string name, int priority, int weight, int port, string target)
        => new() { Name = name, Type = "SRV", Priority = priority, Weight = weight, Port = port, Value = target };

    /// <summary>A text (TXT) record.</summary>
    public static DnsRecord TxtRecord(string name, string text) => new() { Name = name, Type = "TXT", Value = text };

    /// <summary>A pointer (PTR) record.</summary>
    public static DnsRecord PtrRecord(string name, string pointer) => new() { Name = name, Type = "PTR", Value = pointer };
}

/// <summary>
/// Represents a DNS response action for MockServer. When MockServer is acting
/// as a DNS server, the configured records are returned for matched queries.
/// </summary>
public sealed class DnsResponse
{
    [JsonPropertyName("responseCode")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? ResponseCode { get; set; }

    [JsonPropertyName("answerRecords")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<DnsRecord>? AnswerRecords { get; set; }

    [JsonPropertyName("authorityRecords")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<DnsRecord>? AuthorityRecords { get; set; }

    [JsonPropertyName("additionalRecords")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<DnsRecord>? AdditionalRecords { get; set; }

    [JsonPropertyName("delay")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Delay? Delay { get; set; }

    [JsonPropertyName("primary")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? Primary { get; set; }

    /// <summary>
    /// Creates a new DNS response builder.
    /// </summary>
    public static DnsResponseBuilder Response() => new();
}
