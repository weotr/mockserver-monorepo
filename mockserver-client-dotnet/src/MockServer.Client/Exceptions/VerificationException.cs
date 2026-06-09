namespace MockServer.Client.Exceptions;

/// <summary>
/// Thrown when a MockServer verification fails (HTTP 406).
/// </summary>
public class VerificationException : MockServerClientException
{
    public VerificationException(string message) : base(message) { }
}
