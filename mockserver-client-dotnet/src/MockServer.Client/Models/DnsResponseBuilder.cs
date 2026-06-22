namespace MockServer.Client.Models;

/// <summary>
/// Fluent builder for <see cref="DnsResponse"/>.
/// </summary>
public sealed class DnsResponseBuilder
{
    private readonly DnsResponse _response = new();

    public DnsResponseBuilder WithResponseCode(string responseCode)
    {
        _response.ResponseCode = responseCode;
        return this;
    }

    /// <summary>
    /// Appends a record to the answer section.
    /// </summary>
    public DnsResponseBuilder WithAnswerRecord(DnsRecord record)
    {
        _response.AnswerRecords ??= new List<DnsRecord>();
        _response.AnswerRecords.Add(record);
        return this;
    }

    /// <summary>
    /// Appends a record to the authority section.
    /// </summary>
    public DnsResponseBuilder WithAuthorityRecord(DnsRecord record)
    {
        _response.AuthorityRecords ??= new List<DnsRecord>();
        _response.AuthorityRecords.Add(record);
        return this;
    }

    /// <summary>
    /// Appends a record to the additional section.
    /// </summary>
    public DnsResponseBuilder WithAdditionalRecord(DnsRecord record)
    {
        _response.AdditionalRecords ??= new List<DnsRecord>();
        _response.AdditionalRecords.Add(record);
        return this;
    }

    public DnsResponseBuilder WithDelay(TimeUnit timeUnit, long value)
    {
        _response.Delay = new Delay { TimeUnit = timeUnit, Value = value };
        return this;
    }

    public DnsResponseBuilder WithPrimary(bool primary = true)
    {
        _response.Primary = primary;
        return this;
    }

    public DnsResponse Build() => _response;

    public static implicit operator DnsResponse(DnsResponseBuilder builder) => builder.Build();
}
