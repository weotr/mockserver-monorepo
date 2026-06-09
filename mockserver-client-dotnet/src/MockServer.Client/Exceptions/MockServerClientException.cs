namespace MockServer.Client.Exceptions;

/// <summary>
/// Base exception for MockServer client errors.
/// </summary>
public class MockServerClientException : Exception
{
    public MockServerClientException(string message) : base(message) { }
    public MockServerClientException(string message, Exception innerException) : base(message, innerException) { }
}
