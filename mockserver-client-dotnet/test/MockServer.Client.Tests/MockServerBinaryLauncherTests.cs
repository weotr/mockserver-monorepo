using System.IO;
using System.Net;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using FluentAssertions;
using Xunit;

namespace MockServer.Client.Tests;

/// <summary>
/// Unit tests for MockServerBinaryLauncher. All tests are hermetic (no network).
/// Tests that modify process-wide environment variables are serialized via a named
/// xUnit collection to prevent concurrent corruption.
/// </summary>
[Collection("MockServerLauncherTests")]
public class MockServerBinaryLauncherTests : IDisposable
{
    private readonly string _tempDir;
    private readonly Dictionary<string, string?> _savedEnvVars = new();

    public MockServerBinaryLauncherTests()
    {
        _tempDir = Path.Combine(Path.GetTempPath(), "mockserver-launcher-tests-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_tempDir);
    }

    public void Dispose()
    {
        // Restore environment variables
        foreach (var kv in _savedEnvVars)
            Environment.SetEnvironmentVariable(kv.Key, kv.Value);

        // Clean up temp directory
        try { Directory.Delete(_tempDir, recursive: true); } catch { /* best effort */ }
    }

    private void SetEnv(string name, string? value)
    {
        if (!_savedEnvVars.ContainsKey(name))
            _savedEnvVars[name] = Environment.GetEnvironmentVariable(name);
        Environment.SetEnvironmentVariable(name, value);
    }

    // ---------------------------------------------------------------
    // Version validation (H1)
    // ---------------------------------------------------------------

    [Theory]
    [InlineData("1.0.0")]
    [InlineData("7.0.1")]
    [InlineData("10.20.300")]
    [InlineData("1.0.0-beta.1")]
    [InlineData("1.0.0-rc.2")]
    [InlineData("1.0.0.SNAPSHOT")]
    public void ValidateVersion_AcceptsValidVersions(string version)
    {
        var act = () => MockServerBinaryLauncher.ValidateVersion(version);
        act.Should().NotThrow();
    }

    [Theory]
    [InlineData("")]
    [InlineData("abc")]
    [InlineData("1.0")]
    [InlineData("../../../etc/passwd")]
    [InlineData("1.0.0/../../attack")]
    [InlineData("1.0.0\\..\\attack")]
    [InlineData("1..0.0")]
    public void ValidateVersion_RejectsInvalidVersions(string version)
    {
        var act = () => MockServerBinaryLauncher.ValidateVersion(version);
        act.Should().Throw<ArgumentException>();
    }

    [Fact]
    public void ValidateVersion_RejectsNull()
    {
        var act = () => MockServerBinaryLauncher.ValidateVersion(null!);
        act.Should().Throw<ArgumentException>();
    }

    // ---------------------------------------------------------------
    // Path traversal protection (H1)
    // ---------------------------------------------------------------

    [Fact]
    public void AssertWithinBase_AcceptsChildPath()
    {
        var basePath = _tempDir;
        var child = Path.Combine(_tempDir, "subdir", "file.txt");
        var act = () => MockServerBinaryLauncher.AssertWithinBase(child, basePath);
        act.Should().NotThrow();
    }

    [Fact]
    public void AssertWithinBase_RejectsEscapingPath()
    {
        var basePath = Path.Combine(_tempDir, "cache");
        var escape = Path.Combine(_tempDir, "cache", "..", "outside");
        var act = () => MockServerBinaryLauncher.AssertWithinBase(escape, basePath);
        act.Should().Throw<InvalidOperationException>().WithMessage("*traversal*");
    }

    // ---------------------------------------------------------------
    // Platform resolution
    // ---------------------------------------------------------------

    [Fact]
    public void ResolvePlatform_ReturnsValidTokens()
    {
        var platform = MockServerBinaryLauncher.ResolvePlatform();

        platform.OsName.Should().BeOneOf("linux", "darwin", "windows");
        platform.Arch.Should().BeOneOf("x86_64", "aarch64");
        platform.Ext.Should().BeOneOf("tar.gz", "zip");
    }

    [Fact]
    public void ResolvePlatform_MacOsReturnsDarwin()
    {
        if (!RuntimeInformation.IsOSPlatform(OSPlatform.OSX))
            return; // Only meaningful on macOS

        var platform = MockServerBinaryLauncher.ResolvePlatform();
        platform.OsName.Should().Be("darwin");
        platform.Ext.Should().Be("tar.gz");
    }

    [Fact]
    public void ResolvePlatform_Arm64ReturnsAarch64()
    {
        if (RuntimeInformation.OSArchitecture != Architecture.Arm64)
            return; // Only meaningful on ARM64

        var platform = MockServerBinaryLauncher.ResolvePlatform();
        platform.Arch.Should().Be("aarch64");
    }

    [Fact]
    public void ResolvePlatform_X64ReturnsX86_64()
    {
        if (RuntimeInformation.OSArchitecture != Architecture.X64)
            return; // Only meaningful on x64

        var platform = MockServerBinaryLauncher.ResolvePlatform();
        platform.Arch.Should().Be("x86_64");
    }

    // ---------------------------------------------------------------
    // Bundle naming
    // ---------------------------------------------------------------

    [Fact]
    public void BundleBaseName_UsesCorrectFormat()
    {
        var platform = MockServerBinaryLauncher.ResolvePlatform();
        var (name, ext) = MockServerBinaryLauncher.BundleBaseName("1.2.3");

        name.Should().Be($"mockserver-1.2.3-{platform.OsName}-{platform.Arch}");
        ext.Should().Be(platform.Ext);
    }

    [Fact]
    public void BundleBaseName_WithPlatformInfo_UsesGivenPlatform()
    {
        var platform = new MockServerBinaryLauncher.PlatformInfo("linux", "x86_64", "tar.gz");
        var (name, ext) = MockServerBinaryLauncher.BundleBaseName("5.0.0", platform);

        name.Should().Be("mockserver-5.0.0-linux-x86_64");
        ext.Should().Be("tar.gz");
    }

    [Fact]
    public void BundleBaseName_DarwinAarch64_UsesCorrectTokens()
    {
        var platform = new MockServerBinaryLauncher.PlatformInfo("darwin", "aarch64", "tar.gz");
        var (name, ext) = MockServerBinaryLauncher.BundleBaseName("7.0.1", platform);

        name.Should().Be("mockserver-7.0.1-darwin-aarch64");
        ext.Should().Be("tar.gz");
    }

    [Fact]
    public void BundleBaseName_Windows_UsesZipExtension()
    {
        var platform = new MockServerBinaryLauncher.PlatformInfo("windows", "x86_64", "zip");
        var (name, ext) = MockServerBinaryLauncher.BundleBaseName("7.0.1", platform);

        name.Should().Be("mockserver-7.0.1-windows-x86_64");
        ext.Should().Be("zip");
    }

    // ---------------------------------------------------------------
    // Cache directory
    // ---------------------------------------------------------------

    [Fact]
    public void CacheDir_HonoursEnvOverride()
    {
        var customPath = Path.Combine(_tempDir, "custom-cache");
        SetEnv("MOCKSERVER_BINARY_CACHE", customPath);

        MockServerBinaryLauncher.CacheDir().Should().Be(customPath);
    }

    [Fact]
    public void CacheDir_DefaultIncludesMockserverBinaries()
    {
        SetEnv("MOCKSERVER_BINARY_CACHE", null);

        var result = MockServerBinaryLauncher.CacheDir();
        result.Should().EndWith(Path.Combine("mockserver", "binaries"));
    }

    [Fact]
    public void CacheDir_UsesXdgCacheHome_OnUnix()
    {
        if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
            return;

        var xdgPath = Path.Combine(_tempDir, "xdg-cache");
        SetEnv("MOCKSERVER_BINARY_CACHE", null);
        SetEnv("XDG_CACHE_HOME", xdgPath);

        var result = MockServerBinaryLauncher.CacheDir();
        result.Should().Be(Path.Combine(xdgPath, "mockserver", "binaries"));
    }

    // ---------------------------------------------------------------
    // Asset URL
    // ---------------------------------------------------------------

    [Fact]
    public void AssetUrl_DefaultPointsToGitHub()
    {
        SetEnv("MOCKSERVER_BINARY_BASE_URL", null);

        var url = MockServerBinaryLauncher.AssetUrl("7.0.1", "mockserver-7.0.1-darwin-aarch64.tar.gz");

        url.Should().Be(
            "https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-7.0.1/mockserver-7.0.1-darwin-aarch64.tar.gz");
    }

    [Fact]
    public void AssetUrl_HonoursEnvOverride()
    {
        SetEnv("MOCKSERVER_BINARY_BASE_URL", "https://mirror.example.com/assets/");

        var url = MockServerBinaryLauncher.AssetUrl("7.0.1", "mockserver-7.0.1-linux-x86_64.tar.gz");

        url.Should().Be("https://mirror.example.com/assets/mockserver-7.0.1-linux-x86_64.tar.gz");
    }

    [Fact]
    public void AssetUrl_StripsTrailingSlashFromBase()
    {
        SetEnv("MOCKSERVER_BINARY_BASE_URL", "https://mirror.example.com/assets///");

        var url = MockServerBinaryLauncher.AssetUrl("7.0.1", "file.tar.gz");

        url.Should().Be("https://mirror.example.com/assets/file.tar.gz");
    }

    [Theory]
    [InlineData("7.0.0-SNAPSHOT", true)]
    [InlineData("7.0.0-snapshot", true)]
    [InlineData("7.0.0-Snapshot", true)]
    [InlineData("7.1.0-SNAPSHOT", true)]
    [InlineData("7.0.0", false)]
    [InlineData("7.0.0-beta.1", false)]
    [InlineData("7.0.0-rc.1", false)]
    public void IsSnapshot_DetectsSnapshotVersions(string version, bool expected)
    {
        MockServerBinaryLauncher.IsSnapshot(version).Should().Be(expected);
    }

    [Fact]
    public void AssetUrl_SnapshotVersionUsesCdn()
    {
        SetEnv("MOCKSERVER_BINARY_BASE_URL", null);

        var url = MockServerBinaryLauncher.AssetUrl("7.1.0-SNAPSHOT", "mockserver-7.1.0-SNAPSHOT-darwin-aarch64.tar.gz");

        url.Should().Be(
            "https://downloads.mock-server.com/mockserver-7.1.0-SNAPSHOT/mockserver-7.1.0-SNAPSHOT-darwin-aarch64.tar.gz");
    }

    [Fact]
    public void AssetUrl_ReleaseVersionUsesGitHub()
    {
        SetEnv("MOCKSERVER_BINARY_BASE_URL", null);

        var url = MockServerBinaryLauncher.AssetUrl("7.0.0", "mockserver-7.0.0-darwin-aarch64.tar.gz");

        url.Should().Be(
            "https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-7.0.0/mockserver-7.0.0-darwin-aarch64.tar.gz");
    }

    [Fact]
    public void AssetUrl_EnvOverrideWinsOverSnapshot()
    {
        SetEnv("MOCKSERVER_BINARY_BASE_URL", "https://custom-mirror.example.com/bins");

        var url = MockServerBinaryLauncher.AssetUrl("7.1.0-SNAPSHOT", "mockserver-7.1.0-SNAPSHOT-linux-x86_64.tar.gz");

        url.Should().Be("https://custom-mirror.example.com/bins/mockserver-7.1.0-SNAPSHOT-linux-x86_64.tar.gz");
    }

    // ---------------------------------------------------------------
    // Launcher path
    // ---------------------------------------------------------------

    [Fact]
    public void LauncherPath_BuildsCorrectPath()
    {
        var dir = "/cache/7.0.1";
        var name = "mockserver-7.0.1-darwin-aarch64";

        var result = MockServerBinaryLauncher.LauncherPath(dir, name);

        if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
            result.Should().EndWith(Path.Combine(name, "bin", "mockserver.bat"));
        else
            result.Should().EndWith(Path.Combine(name, "bin", "mockserver"));
    }

    // ---------------------------------------------------------------
    // SHA-256 verification
    // ---------------------------------------------------------------

    [Fact]
    public void ComputeSha256_ReturnsCorrectHash()
    {
        var content = "hello world\n";
        var filePath = Path.Combine(_tempDir, "testfile.txt");
        File.WriteAllText(filePath, content, new UTF8Encoding(false));

        var hash = MockServerBinaryLauncher.ComputeSha256(filePath);

        // Known SHA-256 of "hello world\n" (UTF-8, no BOM, Unix newline)
        hash.Should().Be("a948904f2f0f479b8f8197694b30184b0d2ed1c1cd2a1ec0fb85d299a192a447");
    }

    [Fact]
    public void ParseSha256File_ParsesFirstToken()
    {
        var content = "abc123def456  filename.tar.gz\n";

        MockServerBinaryLauncher.ParseSha256File(content).Should().Be("abc123def456");
    }

    [Fact]
    public void ParseSha256File_HandlesHashOnly()
    {
        MockServerBinaryLauncher.ParseSha256File("abc123\n").Should().Be("abc123");
    }

    [Fact]
    public void ParseSha256File_ReturnsNull_ForEmpty()
    {
        MockServerBinaryLauncher.ParseSha256File("").Should().BeNull();
        MockServerBinaryLauncher.ParseSha256File("   ").Should().BeNull();
        MockServerBinaryLauncher.ParseSha256File("\n\n").Should().BeNull();
    }

    // ---------------------------------------------------------------
    // SHA-256 verification with a real fixture archive
    // ---------------------------------------------------------------

    /// <summary>
    /// Creates a tiny tar.gz fixture in the temp directory, along with its real SHA-256 file.
    /// Returns (archivePath, sha256Hash).
    /// </summary>
    private (string ArchivePath, string Sha256) CreateFixtureArchive(string name, string content)
    {
        // Create a small text file and tar.gz it
        var contentFile = Path.Combine(_tempDir, name + ".txt");
        File.WriteAllText(contentFile, content, new UTF8Encoding(false));

        var archivePath = Path.Combine(_tempDir, name + ".tar.gz");

        // Use tar to create the archive
        using var proc = new System.Diagnostics.Process();
        proc.StartInfo = new System.Diagnostics.ProcessStartInfo
        {
            FileName = "tar",
            Arguments = $"-czf \"{archivePath}\" -C \"{_tempDir}\" \"{name}.txt\"",
            UseShellExecute = false,
            RedirectStandardError = true
        };
        proc.Start();
        proc.StandardError.ReadToEnd(); // drain before WaitForExit
        proc.WaitForExit();
        proc.ExitCode.Should().Be(0, "tar should succeed");

        var sha256 = MockServerBinaryLauncher.ComputeSha256(archivePath);
        return (archivePath, sha256);
    }

    [Fact]
    public void Sha256Verification_CorrectHash_Passes()
    {
        var (archivePath, expectedHash) = CreateFixtureArchive("fixture-good", "fixture content");

        var actualHash = MockServerBinaryLauncher.ComputeSha256(archivePath);

        actualHash.Should().Be(expectedHash);
    }

    [Fact]
    public void ComputeSha256_ReturnsNonZeroHash_ForRealFile()
    {
        var (archivePath, _) = CreateFixtureArchive("fixture-nonzero", "fixture content");

        var actualHash = MockServerBinaryLauncher.ComputeSha256(archivePath);

        actualHash.Should().NotBe("0000000000000000000000000000000000000000000000000000000000000000");
        actualHash.Should().HaveLength(64, "SHA-256 hex string should be 64 characters");
    }

    // ---------------------------------------------------------------
    // Semver-aware version comparison (H7)
    // ---------------------------------------------------------------

    [Fact]
    public void CompareVersions_NumericSegments_SortCorrectly()
    {
        MockServerBinaryLauncher.CompareVersions("1.0.0", "2.0.0").Should().BeNegative();
        MockServerBinaryLauncher.CompareVersions("2.0.0", "1.0.0").Should().BePositive();
        MockServerBinaryLauncher.CompareVersions("1.0.0", "1.0.0").Should().Be(0);
    }

    [Fact]
    public void CompareVersions_HandlesMultiDigitSegments()
    {
        // This is the bug that lexicographic sort gets wrong: "10.0.0" < "9.0.0" lexicographically
        MockServerBinaryLauncher.CompareVersions("9.0.0", "10.0.0").Should().BeNegative();
        MockServerBinaryLauncher.CompareVersions("10.0.0", "9.0.0").Should().BePositive();
    }

    [Fact]
    public void CompareVersions_HandlesPreRelease()
    {
        MockServerBinaryLauncher.CompareVersions("1.0.0-alpha", "1.0.0-beta").Should().BeNegative();
        MockServerBinaryLauncher.CompareVersions("1.0.0-rc.1", "1.0.0-rc.2").Should().BeNegative();
    }

    [Fact]
    public void CompareVersions_SortsList_CorrectlyWithCrossDigitBoundary()
    {
        var versions = new List<string> { "1.0.0", "10.0.0", "2.0.0", "9.0.0", "11.0.0", "3.0.0" };
        versions.Sort(MockServerBinaryLauncher.CompareVersions);

        versions.Should().BeEquivalentTo(
            new[] { "1.0.0", "2.0.0", "3.0.0", "9.0.0", "10.0.0", "11.0.0" },
            options => options.WithStrictOrdering());
    }

    [Fact]
    public void CompareVersions_ReleaseOutranksPreRelease()
    {
        // A release (no pre-release suffix) must be GREATER than its pre-release
        MockServerBinaryLauncher.CompareVersions("7.0.0", "7.0.0-SNAPSHOT").Should().BePositive(
            "release 7.0.0 should rank higher than pre-release 7.0.0-SNAPSHOT");
        MockServerBinaryLauncher.CompareVersions("7.0.0-SNAPSHOT", "7.0.0").Should().BeNegative(
            "pre-release 7.0.0-SNAPSHOT should rank lower than release 7.0.0");
    }

    [Fact]
    public void CompareVersions_SortsList_WithPreReleaseCorrectly()
    {
        var versions = new List<string> { "7.0.0-SNAPSHOT", "7.0.0", "6.9.0", "7.0.1" };
        versions.Sort(MockServerBinaryLauncher.CompareVersions);

        versions.Should().BeEquivalentTo(
            new[] { "6.9.0", "7.0.0-SNAPSHOT", "7.0.0", "7.0.1" },
            options => options.WithStrictOrdering());
    }

    // ---------------------------------------------------------------
    // MOCKSERVER_SKIP_BINARY_DOWNLOAD
    // ---------------------------------------------------------------

    [Fact]
    public async Task EnsureBinary_SkipDownloadSet_ThrowsWhenNoCachedBinary()
    {
        SetEnv("MOCKSERVER_SKIP_BINARY_DOWNLOAD", "1");
        SetEnv("MOCKSERVER_BINARY_CACHE", Path.Combine(_tempDir, "empty-cache"));
        SetEnv("MOCKSERVER_BINARY_BASE_URL", null);

        var act = () => MockServerBinaryLauncher.EnsureBinaryAsync("99.99.99");

        await act.Should().ThrowAsync<InvalidOperationException>()
            .WithMessage("*MOCKSERVER_SKIP_BINARY_DOWNLOAD*");
    }

    [Fact]
    public async Task EnsureBinary_SkipDownloadSet_ReturnsPathIfCached()
    {
        SetEnv("MOCKSERVER_SKIP_BINARY_DOWNLOAD", "1");
        var cacheDir = Path.Combine(_tempDir, "skip-cache");
        SetEnv("MOCKSERVER_BINARY_CACHE", cacheDir);

        // Pre-seed the cache with a fake launcher
        var platform = MockServerBinaryLauncher.ResolvePlatform();
        var (bundleName, _) = MockServerBinaryLauncher.BundleBaseName("99.99.99", platform);
        var versionDir = Path.Combine(cacheDir, "99.99.99");
        var launcherPath = MockServerBinaryLauncher.LauncherPath(versionDir, bundleName);
        Directory.CreateDirectory(Path.GetDirectoryName(launcherPath)!);
        File.WriteAllText(launcherPath, "#!/bin/sh\necho mock");

        var result = await MockServerBinaryLauncher.EnsureBinaryAsync("99.99.99");

        result.Should().Be(launcherPath);
    }

    // ---------------------------------------------------------------
    // EnsureBinary with local fixture (hermetic, no real download)
    // ---------------------------------------------------------------

    [Fact]
    public async Task EnsureBinary_UsesCache_WhenLauncherExists()
    {
        var cacheDir = Path.Combine(_tempDir, "cached");
        SetEnv("MOCKSERVER_BINARY_CACHE", cacheDir);
        SetEnv("MOCKSERVER_SKIP_BINARY_DOWNLOAD", null);

        // Pre-seed cache
        var platform = MockServerBinaryLauncher.ResolvePlatform();
        var (bundleName, _) = MockServerBinaryLauncher.BundleBaseName("1.0.0", platform);
        var versionDir = Path.Combine(cacheDir, "1.0.0");
        var launcherPath = MockServerBinaryLauncher.LauncherPath(versionDir, bundleName);
        Directory.CreateDirectory(Path.GetDirectoryName(launcherPath)!);
        File.WriteAllText(launcherPath, "#!/bin/sh\necho cached");

        var logMessages = new List<string>();
        var result = await MockServerBinaryLauncher.EnsureBinaryAsync("1.0.0",
            new MockServerBinaryLauncher.EnsureBinaryOptions { Log = msg => logMessages.Add(msg) });

        result.Should().Be(launcherPath);
        logMessages.Should().ContainMatch("*cached*");
    }

    // ---------------------------------------------------------------
    // EnsureBinary with SHA-256 verification via local HTTP stub
    // ---------------------------------------------------------------

    /// <summary>
    /// A fake HttpMessageHandler that serves files from the temp directory.
    /// </summary>
    private sealed class LocalFileHandler : HttpMessageHandler
    {
        private readonly string _baseDir;

        public LocalFileHandler(string baseDir)
        {
            _baseDir = baseDir;
        }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var uri = request.RequestUri!;
            // The last segment is the filename
            var fileName = uri.Segments[^1];
            var filePath = Path.Combine(_baseDir, fileName);

            if (File.Exists(filePath))
            {
                var content = File.ReadAllBytes(filePath);
                var response = new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(content)
                };
                return Task.FromResult(response);
            }

            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        }
    }

    [Fact]
    public async Task EnsureBinary_DownloadsAndVerifiesSha256_WithLocalStub()
    {
        // Set up local "server" directory
        var serverDir = Path.Combine(_tempDir, "server");
        Directory.CreateDirectory(serverDir);

        var cacheDir = Path.Combine(_tempDir, "download-cache");
        SetEnv("MOCKSERVER_BINARY_CACHE", cacheDir);
        SetEnv("MOCKSERVER_SKIP_BINARY_DOWNLOAD", null);
        SetEnv("MOCKSERVER_BINARY_BASE_URL", "http://localhost:0/fake-release");

        // Build a fake archive that contains the launcher in the right place
        var version = "9.8.7";
        var platform = MockServerBinaryLauncher.ResolvePlatform();
        var (bundleName, ext) = MockServerBinaryLauncher.BundleBaseName(version, platform);
        var archiveFileName = $"{bundleName}.{ext}";

        // Create the directory structure that the archive should extract to
        var archiveSrcDir = Path.Combine(_tempDir, "archive-src");
        var launcherDir = Path.Combine(archiveSrcDir, bundleName, "bin");
        Directory.CreateDirectory(launcherDir);
        var launcherName = RuntimeInformation.IsOSPlatform(OSPlatform.Windows)
            ? "mockserver.bat" : "mockserver";
        File.WriteAllText(Path.Combine(launcherDir, launcherName), "#!/bin/sh\necho mock");

        // Create the tar.gz archive
        var archivePath = Path.Combine(serverDir, archiveFileName);
        using (var tarProc = new System.Diagnostics.Process())
        {
            tarProc.StartInfo = new System.Diagnostics.ProcessStartInfo
            {
                FileName = "tar",
                Arguments = $"-czf \"{archivePath}\" -C \"{archiveSrcDir}\" \"{bundleName}\"",
                UseShellExecute = false,
                RedirectStandardError = true
            };
            tarProc.Start();
            tarProc.StandardError.ReadToEnd();
            tarProc.WaitForExit();
            tarProc.ExitCode.Should().Be(0, "tar should create archive");
        }

        // Compute real SHA-256 and write .sha256 file
        var sha256 = MockServerBinaryLauncher.ComputeSha256(archivePath);
        File.WriteAllText(Path.Combine(serverDir, archiveFileName + ".sha256"), $"{sha256}  {archiveFileName}\n");

        // Run EnsureBinary with our local HTTP handler
        var httpClient = new HttpClient(new LocalFileHandler(serverDir));
        var logMessages = new List<string>();

        var result = await MockServerBinaryLauncher.EnsureBinaryAsync(version,
            new MockServerBinaryLauncher.EnsureBinaryOptions
            {
                HttpClient = httpClient,
                Log = msg => logMessages.Add(msg)
            });

        result.Should().Contain(bundleName);
        result.Should().Contain("bin");
        File.Exists(result).Should().BeTrue();
        logMessages.Should().ContainMatch("*Checksum verified*");
    }

    [Fact]
    public async Task EnsureBinary_ChecksumMismatch_Throws()
    {
        // Set up local "server" directory with wrong checksum
        var serverDir = Path.Combine(_tempDir, "server-bad");
        Directory.CreateDirectory(serverDir);

        var cacheDir = Path.Combine(_tempDir, "bad-checksum-cache");
        SetEnv("MOCKSERVER_BINARY_CACHE", cacheDir);
        SetEnv("MOCKSERVER_SKIP_BINARY_DOWNLOAD", null);
        SetEnv("MOCKSERVER_BINARY_BASE_URL", "http://localhost:0/fake-release");

        var version = "8.0.0";
        var platform = MockServerBinaryLauncher.ResolvePlatform();
        var (bundleName, ext) = MockServerBinaryLauncher.BundleBaseName(version, platform);
        var archiveFileName = $"{bundleName}.{ext}";

        // Create a minimal archive
        var archiveSrcDir = Path.Combine(_tempDir, "archive-src-bad");
        var contentDir = Path.Combine(archiveSrcDir, bundleName);
        Directory.CreateDirectory(contentDir);
        File.WriteAllText(Path.Combine(contentDir, "dummy.txt"), "dummy");

        var archivePath = Path.Combine(serverDir, archiveFileName);
        using (var tarProc = new System.Diagnostics.Process())
        {
            tarProc.StartInfo = new System.Diagnostics.ProcessStartInfo
            {
                FileName = "tar",
                Arguments = $"-czf \"{archivePath}\" -C \"{archiveSrcDir}\" \"{bundleName}\"",
                UseShellExecute = false,
                RedirectStandardError = true
            };
            tarProc.Start();
            tarProc.StandardError.ReadToEnd();
            tarProc.WaitForExit();
        }

        // Write a WRONG checksum
        File.WriteAllText(Path.Combine(serverDir, archiveFileName + ".sha256"),
            "0000000000000000000000000000000000000000000000000000000000000000  file\n");

        var httpClient = new HttpClient(new LocalFileHandler(serverDir));

        var act = () => MockServerBinaryLauncher.EnsureBinaryAsync(version,
            new MockServerBinaryLauncher.EnsureBinaryOptions { HttpClient = httpClient });

        await act.Should().ThrowAsync<InvalidOperationException>()
            .WithMessage("*Checksum mismatch*");

        // Both .part and .sha256 files should have been cleaned up (H3)
        var versionDir = Path.Combine(cacheDir, version);
        if (Directory.Exists(versionDir))
        {
            Directory.GetFiles(versionDir, "*.part").Should().BeEmpty("partial file should be deleted on failure");
            Directory.GetFiles(versionDir, "*.sha256").Should().BeEmpty(".sha256 file should be deleted on failure");
        }
    }

    [Fact]
    public async Task EnsureBinary_EmptyChecksumFile_Throws()
    {
        var serverDir = Path.Combine(_tempDir, "server-empty-sha");
        Directory.CreateDirectory(serverDir);

        var cacheDir = Path.Combine(_tempDir, "empty-sha-cache");
        SetEnv("MOCKSERVER_BINARY_CACHE", cacheDir);
        SetEnv("MOCKSERVER_SKIP_BINARY_DOWNLOAD", null);
        SetEnv("MOCKSERVER_BINARY_BASE_URL", "http://localhost:0/fake-release");

        var version = "8.1.0";
        var platform = MockServerBinaryLauncher.ResolvePlatform();
        var (bundleName, ext) = MockServerBinaryLauncher.BundleBaseName(version, platform);
        var archiveFileName = $"{bundleName}.{ext}";

        // Create a minimal archive
        var archiveSrcDir = Path.Combine(_tempDir, "archive-src-empty-sha");
        var contentDir = Path.Combine(archiveSrcDir, bundleName);
        Directory.CreateDirectory(contentDir);
        File.WriteAllText(Path.Combine(contentDir, "dummy.txt"), "dummy");
        var archivePath = Path.Combine(serverDir, archiveFileName);
        using (var tarProc = new System.Diagnostics.Process())
        {
            tarProc.StartInfo = new System.Diagnostics.ProcessStartInfo
            {
                FileName = "tar",
                Arguments = $"-czf \"{archivePath}\" -C \"{archiveSrcDir}\" \"{bundleName}\"",
                UseShellExecute = false,
                RedirectStandardError = true
            };
            tarProc.Start();
            tarProc.StandardError.ReadToEnd();
            tarProc.WaitForExit();
        }

        // Write an empty checksum file
        File.WriteAllText(Path.Combine(serverDir, archiveFileName + ".sha256"), "   \n");

        var httpClient = new HttpClient(new LocalFileHandler(serverDir));

        var act = () => MockServerBinaryLauncher.EnsureBinaryAsync(version,
            new MockServerBinaryLauncher.EnsureBinaryOptions { HttpClient = httpClient });

        await act.Should().ThrowAsync<InvalidOperationException>()
            .WithMessage("*empty or unparseable*");
    }

    // ---------------------------------------------------------------
    // Version validation in EnsureBinary (H1)
    // ---------------------------------------------------------------

    [Fact]
    public async Task EnsureBinary_InvalidVersion_ThrowsArgumentException()
    {
        SetEnv("MOCKSERVER_BINARY_CACHE", Path.Combine(_tempDir, "inv-cache"));

        var act = () => MockServerBinaryLauncher.EnsureBinaryAsync("../../../attack");

        await act.Should().ThrowAsync<ArgumentException>()
            .WithMessage("*path*");
    }

    // ---------------------------------------------------------------
    // Versioned cache pruning
    // ---------------------------------------------------------------

    [Fact]
    public void PruneOldVersions_RemovesOldVersions_KeepsCurrentAndOnePrevious()
    {
        var cacheBase = Path.Combine(_tempDir, "prune-test");
        Directory.CreateDirectory(cacheBase);

        // Create fake version directories
        Directory.CreateDirectory(Path.Combine(cacheBase, "1.0.0"));
        File.WriteAllText(Path.Combine(cacheBase, "1.0.0", "marker.txt"), "v1");

        Directory.CreateDirectory(Path.Combine(cacheBase, "2.0.0"));
        File.WriteAllText(Path.Combine(cacheBase, "2.0.0", "marker.txt"), "v2");

        Directory.CreateDirectory(Path.Combine(cacheBase, "3.0.0"));
        File.WriteAllText(Path.Combine(cacheBase, "3.0.0", "marker.txt"), "v3");

        Directory.CreateDirectory(Path.Combine(cacheBase, "4.0.0"));
        File.WriteAllText(Path.Combine(cacheBase, "4.0.0", "marker.txt"), "v4");

        // Prune, keeping 4.0.0 as current
        MockServerBinaryLauncher.PruneOldVersions(cacheBase, "4.0.0");

        // 4.0.0 (current) and 3.0.0 (one previous) should remain
        Directory.Exists(Path.Combine(cacheBase, "4.0.0")).Should().BeTrue("current version should be kept");
        Directory.Exists(Path.Combine(cacheBase, "3.0.0")).Should().BeTrue("one previous version should be kept");
        Directory.Exists(Path.Combine(cacheBase, "2.0.0")).Should().BeFalse("older versions should be removed");
        Directory.Exists(Path.Combine(cacheBase, "1.0.0")).Should().BeFalse("oldest version should be removed");
    }

    [Fact]
    public void PruneOldVersions_OnlyCurrentExists_NoOp()
    {
        var cacheBase = Path.Combine(_tempDir, "prune-single");
        Directory.CreateDirectory(cacheBase);
        Directory.CreateDirectory(Path.Combine(cacheBase, "1.0.0"));

        MockServerBinaryLauncher.PruneOldVersions(cacheBase, "1.0.0");

        Directory.Exists(Path.Combine(cacheBase, "1.0.0")).Should().BeTrue();
    }

    [Fact]
    public void PruneOldVersions_TwoVersions_KeepsBoth()
    {
        var cacheBase = Path.Combine(_tempDir, "prune-two");
        Directory.CreateDirectory(cacheBase);
        Directory.CreateDirectory(Path.Combine(cacheBase, "1.0.0"));
        Directory.CreateDirectory(Path.Combine(cacheBase, "2.0.0"));

        MockServerBinaryLauncher.PruneOldVersions(cacheBase, "2.0.0");

        Directory.Exists(Path.Combine(cacheBase, "1.0.0")).Should().BeTrue("only one previous, should be kept");
        Directory.Exists(Path.Combine(cacheBase, "2.0.0")).Should().BeTrue("current should be kept");
    }

    [Fact]
    public void PruneOldVersions_RemovesPartFiles()
    {
        var cacheBase = Path.Combine(_tempDir, "prune-parts");
        Directory.CreateDirectory(cacheBase);
        Directory.CreateDirectory(Path.Combine(cacheBase, "1.0.0"));

        // Create leftover .part files
        File.WriteAllText(Path.Combine(cacheBase, "1.0.0", "some-archive.tar.gz.part"), "partial");

        MockServerBinaryLauncher.PruneOldVersions(cacheBase, "1.0.0");

        Directory.GetFiles(Path.Combine(cacheBase, "1.0.0"), "*.part").Should().BeEmpty();
    }

    [Fact]
    public void PruneOldVersions_RemovesSha256TempFiles()
    {
        var cacheBase = Path.Combine(_tempDir, "prune-sha");
        Directory.CreateDirectory(cacheBase);
        Directory.CreateDirectory(Path.Combine(cacheBase, "1.0.0"));

        // Create leftover .sha256 files
        File.WriteAllText(Path.Combine(cacheBase, "1.0.0", "some-archive.tar.gz.sha256"), "abc123");

        MockServerBinaryLauncher.PruneOldVersions(cacheBase, "1.0.0");

        Directory.GetFiles(Path.Combine(cacheBase, "1.0.0"), "*.sha256").Should().BeEmpty();
    }

    [Fact]
    public void PruneOldVersions_NonExistentDirectory_NoOp()
    {
        // Should not throw
        MockServerBinaryLauncher.PruneOldVersions(Path.Combine(_tempDir, "nonexistent"), "1.0.0");
    }

    [Fact]
    public void PruneOldVersions_EmptyDirectory_NoOp()
    {
        var cacheBase = Path.Combine(_tempDir, "prune-empty");
        Directory.CreateDirectory(cacheBase);

        MockServerBinaryLauncher.PruneOldVersions(cacheBase, "1.0.0");

        Directory.Exists(cacheBase).Should().BeTrue();
    }

    [Fact]
    public void PruneOldVersions_WithManyVersions_KeepsOnlyCurrentAndOnePrevious()
    {
        var cacheBase = Path.Combine(_tempDir, "prune-many");
        Directory.CreateDirectory(cacheBase);

        // Create 10 version directories
        for (int i = 1; i <= 10; i++)
        {
            var vDir = Path.Combine(cacheBase, $"{i}.0.0");
            Directory.CreateDirectory(vDir);
            File.WriteAllText(Path.Combine(vDir, "marker.txt"), $"v{i}");
        }

        MockServerBinaryLauncher.PruneOldVersions(cacheBase, "10.0.0");

        var remaining = Directory.GetDirectories(cacheBase);
        remaining.Should().HaveCount(2);
        remaining.Select(Path.GetFileName).Should().Contain("10.0.0");
        remaining.Select(Path.GetFileName).Should().Contain("9.0.0",
            "semver-aware sort should keep 9.0.0 as the highest previous, not a lower version");
    }

    [Fact]
    public void PruneOldVersions_KeepsReleaseOverSnapshot()
    {
        // When a release and its SNAPSHOT both exist as "previous" versions,
        // the pruner must keep the release (higher by semver) and remove the SNAPSHOT.
        var cacheBase = Path.Combine(_tempDir, "prune-snapshot");
        Directory.CreateDirectory(cacheBase);

        Directory.CreateDirectory(Path.Combine(cacheBase, "7.0.0-SNAPSHOT"));
        Directory.CreateDirectory(Path.Combine(cacheBase, "7.0.0"));

        // Current version is 8.0.0 (not in the directories)
        MockServerBinaryLauncher.PruneOldVersions(cacheBase, "8.0.0");

        // 7.0.0 (release) should be kept as the one previous; 7.0.0-SNAPSHOT should be pruned
        Directory.Exists(Path.Combine(cacheBase, "7.0.0")).Should().BeTrue(
            "release 7.0.0 should be kept as the highest previous version");
        Directory.Exists(Path.Combine(cacheBase, "7.0.0-SNAPSHOT")).Should().BeFalse(
            "pre-release 7.0.0-SNAPSHOT should be pruned in favour of release 7.0.0");
    }

    [Fact]
    public void PruneOldVersions_CrossDigitBoundary_KeepsCorrectPrevious()
    {
        // Regression test: lexicographic sort would keep "9.0.0" as previous
        // when "10.0.0" is actually higher than "9.0.0"
        var cacheBase = Path.Combine(_tempDir, "prune-cross-digit");
        Directory.CreateDirectory(cacheBase);

        Directory.CreateDirectory(Path.Combine(cacheBase, "1.0.0"));
        Directory.CreateDirectory(Path.Combine(cacheBase, "2.0.0"));
        Directory.CreateDirectory(Path.Combine(cacheBase, "9.0.0"));
        Directory.CreateDirectory(Path.Combine(cacheBase, "10.0.0"));

        // Current is 11.0.0 (not in the cache dirs — just installed)
        MockServerBinaryLauncher.PruneOldVersions(cacheBase, "11.0.0");

        // Should keep 10.0.0 (highest previous by semver), NOT 9.0.0
        var remaining = Directory.GetDirectories(cacheBase)
            .Select(Path.GetFileName)
            .ToList();

        remaining.Should().Contain("10.0.0", "10.0.0 is the highest previous version");
        remaining.Should().NotContain("9.0.0", "9.0.0 should be pruned");
        remaining.Should().NotContain("2.0.0");
        remaining.Should().NotContain("1.0.0");
    }

    // ---------------------------------------------------------------
    // Full EnsureBinary flow with pruning verification
    // ---------------------------------------------------------------

    [Fact]
    public async Task EnsureBinary_PrunesOldVersionsAfterInstall()
    {
        var serverDir = Path.Combine(_tempDir, "server-prune");
        Directory.CreateDirectory(serverDir);

        var cacheDir = Path.Combine(_tempDir, "prune-full-cache");
        SetEnv("MOCKSERVER_BINARY_CACHE", cacheDir);
        SetEnv("MOCKSERVER_SKIP_BINARY_DOWNLOAD", null);
        SetEnv("MOCKSERVER_BINARY_BASE_URL", "http://localhost:0/fake-release");

        // Pre-create old version directories
        Directory.CreateDirectory(Path.Combine(cacheDir, "1.0.0"));
        File.WriteAllText(Path.Combine(cacheDir, "1.0.0", "marker.txt"), "old1");
        Directory.CreateDirectory(Path.Combine(cacheDir, "2.0.0"));
        File.WriteAllText(Path.Combine(cacheDir, "2.0.0", "marker.txt"), "old2");
        Directory.CreateDirectory(Path.Combine(cacheDir, "3.0.0"));
        File.WriteAllText(Path.Combine(cacheDir, "3.0.0", "marker.txt"), "old3");

        // Build a real installable archive for version 4.0.0
        var version = "4.0.0";
        var platform = MockServerBinaryLauncher.ResolvePlatform();
        var (bundleName, ext) = MockServerBinaryLauncher.BundleBaseName(version, platform);
        var archiveFileName = $"{bundleName}.{ext}";

        var archiveSrcDir = Path.Combine(_tempDir, "archive-src-prune");
        var launcherDir = Path.Combine(archiveSrcDir, bundleName, "bin");
        Directory.CreateDirectory(launcherDir);
        var launcherName = RuntimeInformation.IsOSPlatform(OSPlatform.Windows)
            ? "mockserver.bat" : "mockserver";
        File.WriteAllText(Path.Combine(launcherDir, launcherName), "#!/bin/sh\necho mock");

        var archivePath = Path.Combine(serverDir, archiveFileName);
        using (var tarProc = new System.Diagnostics.Process())
        {
            tarProc.StartInfo = new System.Diagnostics.ProcessStartInfo
            {
                FileName = "tar",
                Arguments = $"-czf \"{archivePath}\" -C \"{archiveSrcDir}\" \"{bundleName}\"",
                UseShellExecute = false,
                RedirectStandardError = true
            };
            tarProc.Start();
            tarProc.StandardError.ReadToEnd();
            tarProc.WaitForExit();
        }

        var sha256 = MockServerBinaryLauncher.ComputeSha256(archivePath);
        File.WriteAllText(Path.Combine(serverDir, archiveFileName + ".sha256"), $"{sha256}  {archiveFileName}\n");

        var httpClient = new HttpClient(new LocalFileHandler(serverDir));

        await MockServerBinaryLauncher.EnsureBinaryAsync(version,
            new MockServerBinaryLauncher.EnsureBinaryOptions { HttpClient = httpClient });

        // After install, old versions should be pruned:
        // 4.0.0 (current) and 3.0.0 (one previous) remain
        Directory.Exists(Path.Combine(cacheDir, "4.0.0")).Should().BeTrue("newly installed version");
        Directory.Exists(Path.Combine(cacheDir, "3.0.0")).Should().BeTrue("one previous version kept");
        Directory.Exists(Path.Combine(cacheDir, "2.0.0")).Should().BeFalse("older version pruned");
        Directory.Exists(Path.Combine(cacheDir, "1.0.0")).Should().BeFalse("oldest version pruned");
    }

    // ---------------------------------------------------------------
    // DefaultVersion matches project version
    // ---------------------------------------------------------------

    [Fact]
    public void DefaultVersion_MatchesPackageVersion()
    {
        // DefaultVersion is now derived from the assembly's informational version
        // attribute, which is populated from <Version> in MockServer.Client.csproj.
        // It should be a valid semver-ish string (X.Y.Z or X.Y.Z-prerelease).
        var version = MockServerBinaryLauncher.DefaultVersion;
        version.Should().NotBeNullOrEmpty();
        version.Should().MatchRegex(@"^\d+\.\d+\.\d+");
    }
}

/// <summary>
/// xUnit test collection to serialize tests that mutate process-wide environment variables.
/// </summary>
[CollectionDefinition("MockServerLauncherTests")]
public class MockServerLauncherTestsCollection : ICollectionFixture<MockServerLauncherTestsCollectionFixture>
{
}

/// <summary>
/// Empty fixture class required by xUnit's collection mechanism.
/// </summary>
public class MockServerLauncherTestsCollectionFixture
{
}
