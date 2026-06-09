namespace Testcontainers.MockServer;

using DotNet.Testcontainers.Containers;

/// <summary>
/// A Testcontainers container for MockServer. Starts a <c>mockserver/mockserver</c> Docker image,
/// waits for the server to become ready, and provides connection helpers.
/// <para>
/// MockServer serves HTTP, HTTPS, SOCKS, and HTTP CONNECT on a single unified port (default 1080).
/// </para>
/// </summary>
public sealed class MockServerContainer : DockerContainer
{
    /// <summary>
    /// The default MockServer version matching this package release.
    /// </summary>
    public const string DefaultVersion = "7.0.0";

    /// <summary>
    /// The default container port (HTTP, HTTPS, SOCKS, and HTTP CONNECT all served on one port).
    /// </summary>
    public const int DefaultPort = 1080;

    private readonly MockServerConfiguration _configuration;

    /// <summary>
    /// Initializes a new instance of the <see cref="MockServerContainer" /> class.
    /// </summary>
    /// <param name="configuration">The container configuration.</param>
    public MockServerContainer(MockServerConfiguration configuration)
        : base(configuration)
    {
        _configuration = configuration;
    }

    /// <summary>
    /// Gets the mapped host port for MockServer.
    /// </summary>
    /// <returns>The host port mapped to the MockServer container port.</returns>
    public ushort GetMappedPort()
    {
        return GetMappedPublicPort(DefaultPort);
    }

    /// <summary>
    /// Gets the HTTP endpoint URL for MockServer.
    /// </summary>
    /// <returns>The URL in the form <c>http://host:port</c>.</returns>
    public string GetUrl()
    {
        return $"http://{Hostname}:{GetMappedPort()}";
    }

    /// <summary>
    /// Gets the HTTPS endpoint URL for MockServer.
    /// MockServer serves HTTP and HTTPS on the same unified port.
    /// </summary>
    /// <returns>The URL in the form <c>https://host:port</c>.</returns>
    public string GetSecureUrl()
    {
        return $"https://{Hostname}:{GetMappedPort()}";
    }

    /// <summary>
    /// Gets the MockServer connection string in the format <c>host:port</c>.
    /// </summary>
    /// <returns>The connection string.</returns>
    public string GetConnectionString()
    {
        return $"{Hostname}:{GetMappedPort()}";
    }
}
