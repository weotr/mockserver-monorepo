namespace Testcontainers.MockServer.Tests;

using FluentAssertions;
using Xunit;

/// <summary>
/// Unit tests for <see cref="MockServerBuilder" /> configuration shaping.
/// These tests verify builder configuration WITHOUT starting Docker.
/// </summary>
public class MockServerBuilderTest
{
    [Fact]
    public void DefaultImageUsesCorrectRepository()
    {
        // The default image should be mockserver/mockserver with a version-pinned tag.
        var expectedImage = $"mockserver/mockserver:mockserver-{MockServerContainer.DefaultVersion}";
        MockServerBuilder.MockServerImage.Should().Be("mockserver/mockserver");
        MockServerBuilder.DefaultTag.Should().Be($"mockserver-{MockServerContainer.DefaultVersion}");
    }

    [Fact]
    public void DefaultPortIs1080()
    {
        MockServerBuilder.MockServerPort.Should().Be(1080);
        MockServerContainer.DefaultPort.Should().Be(1080);
    }

    [Fact]
    public void DefaultVersionMatchesExpected()
    {
        // The version should be derived from the MockServer release (stripped -SNAPSHOT).
        MockServerContainer.DefaultVersion.Should().Be("7.0.0");
    }

    [Fact]
    public void BuilderCreatesContainerInstance()
    {
        // Verify that Build() produces a non-null MockServerContainer.
        var container = new MockServerBuilder().Build();
        container.Should().NotBeNull();
        container.Should().BeOfType<MockServerContainer>();
    }

    [Fact]
    public void WithLogLevelIsChainable()
    {
        // Verify fluent chaining does not throw and produces a valid builder.
        var builder = new MockServerBuilder()
            .WithLogLevel("DEBUG");

        builder.Should().NotBeNull();
        var container = builder.Build();
        container.Should().NotBeNull();
    }

    [Fact]
    public void WithMockServerPropertyIsChainable()
    {
        var builder = new MockServerBuilder()
            .WithMockServerProperty("MOCKSERVER_MAX_EXPECTATIONS", "500");

        builder.Should().NotBeNull();
        var container = builder.Build();
        container.Should().NotBeNull();
    }

    [Fact]
    public void FluentChainingAcrossMultipleHelpers()
    {
        var container = new MockServerBuilder()
            .WithLogLevel("WARN")
            .WithMockServerProperty("MOCKSERVER_MAX_EXPECTATIONS", "200")
            .WithEnvironment("MOCKSERVER_TRANSPARENT_PROXY_ENABLED", "true")
            .Build();

        container.Should().NotBeNull();
    }

    [Fact]
    public void WithCustomImageOverridesDefault()
    {
        var container = new MockServerBuilder()
            .WithImage("mockserver/mockserver:latest")
            .Build();

        container.Should().NotBeNull();
    }

    [Fact]
    public void WithCustomPortBindingOverridesDefault()
    {
        var container = new MockServerBuilder()
            .WithPortBinding(9090, MockServerBuilder.MockServerPort)
            .Build();

        container.Should().NotBeNull();
    }
}
