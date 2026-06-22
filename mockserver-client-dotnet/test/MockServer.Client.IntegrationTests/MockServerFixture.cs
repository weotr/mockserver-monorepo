using Xunit;

namespace MockServer.Client.IntegrationTests;

/// <summary>
/// Reusable xUnit <see cref="IAsyncLifetime"/> fixture that provides a
/// <see cref="MockServerClient"/> and automatically resets the server before and
/// after each test (or test class), so recorded requests, expectations and logs
/// never leak between tests.
///
/// <para>Per-test reset — implement <see cref="IAsyncLifetime"/> on the test class
/// by deriving from this fixture:</para>
/// <code>
/// public class MyTests : MockServerFixture
/// {
///     [SkippableFact]
///     public async Task RecordsRequest()
///     {
///         SkipIfNoServer();
///         // Client is reset before this test runs and again after it finishes.
///         Client!.MockAnyResponse(...);
///     }
/// }
/// </code>
///
/// <para>Shared-instance reset — use it as an <c>IClassFixture</c> / collection
/// fixture when a single client should be shared across a class, resetting once
/// before the first test and once after the last.</para>
///
/// <para>The server URL is read from the <c>MOCKSERVER_URL</c> environment
/// variable (for example <c>http://localhost:1080</c>). When it is unset
/// <see cref="Client"/> is <see langword="null"/> and the lifecycle hooks are
/// no-ops, so tests can be skipped gracefully with <see cref="SkipIfNoServer"/>.</para>
/// </summary>
public class MockServerFixture : IAsyncLifetime
{
    /// <summary>
    /// The MockServer client, or <see langword="null"/> when <c>MOCKSERVER_URL</c>
    /// is not set.
    /// </summary>
    protected MockServerClient? Client { get; private set; }

    /// <summary>
    /// Skip the current test when no MockServer instance is configured.
    /// </summary>
    protected void SkipIfNoServer()
    {
        Skip.If(Client == null, "MOCKSERVER_URL environment variable not set; skipping integration test.");
    }

    /// <summary>
    /// Create the client (if configured) and reset the server to a clean state.
    /// </summary>
    public async Task InitializeAsync()
    {
        var url = Environment.GetEnvironmentVariable("MOCKSERVER_URL");
        if (string.IsNullOrEmpty(url))
        {
            Client = null;
            return;
        }

        var uri = new Uri(url);
        Client = new MockServerClient(uri.Host, uri.Port, secure: uri.Scheme == "https");
        await Client.ResetAsync().ConfigureAwait(false);
    }

    /// <summary>
    /// Reset the server again and dispose the client.
    /// </summary>
    public async Task DisposeAsync()
    {
        if (Client != null)
        {
            await Client.ResetAsync().ConfigureAwait(false);
            Client.Dispose();
            Client = null;
        }
    }
}
