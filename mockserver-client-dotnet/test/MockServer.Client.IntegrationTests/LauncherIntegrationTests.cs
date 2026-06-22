using FluentAssertions;
using Xunit;

namespace MockServer.Client.IntegrationTests;

/// <summary>
/// Integration test for the binary launcher that downloads a real MockServer bundle.
/// Skipped when no real bundle is available at the default download URL (i.e., the version
/// has not been released yet). Set MOCKSERVER_BINARY_INTEGRATION_TEST=1 to force the test.
/// </summary>
public class LauncherIntegrationTests : IDisposable
{
    private MockServerBinaryLauncher? _launcher;

    public void Dispose()
    {
        _launcher?.Dispose();
    }

    private static bool ShouldRun()
    {
        return !string.IsNullOrEmpty(
            Environment.GetEnvironmentVariable("MOCKSERVER_BINARY_INTEGRATION_TEST"));
    }

    [SkippableFact]
    public async Task EnsureBinary_DownloadsRealBundle_WhenAvailable()
    {
        Skip.IfNot(ShouldRun(),
            "MOCKSERVER_BINARY_INTEGRATION_TEST not set; skipping real download test.");

        var logMessages = new List<string>();
        var launcherPath = await MockServerBinaryLauncher.EnsureBinaryAsync(
            options: new MockServerBinaryLauncher.EnsureBinaryOptions
            {
                Log = msg => logMessages.Add(msg)
            });

        launcherPath.Should().NotBeNullOrEmpty();
        File.Exists(launcherPath).Should().BeTrue("launcher should exist on disk");
        new FileInfo(launcherPath).Length.Should().BeGreaterThan(0, "launcher should not be empty");
    }

    [SkippableFact]
    public async Task Start_LaunchesRealServer_WhenAvailable()
    {
        Skip.IfNot(ShouldRun(),
            "MOCKSERVER_BINARY_INTEGRATION_TEST not set; skipping real server start test.");

        _launcher = await MockServerBinaryLauncher.StartAsync(18765);
        _launcher.Process.Should().NotBeNull();
        _launcher.Process!.HasExited.Should().BeFalse("server process should still be running");

        // Give the server a moment to start
        await Task.Delay(TimeSpan.FromSeconds(3));

        // Verify it's still alive
        _launcher.Process.HasExited.Should().BeFalse();

        // Stop and verify
        _launcher.Stop();
        _launcher.Process.HasExited.Should().BeTrue("server should have stopped");
    }
}
