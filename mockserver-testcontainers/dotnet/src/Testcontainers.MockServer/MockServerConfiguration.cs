namespace Testcontainers.MockServer;

using Docker.DotNet.Models;
using DotNet.Testcontainers.Builders;
using DotNet.Testcontainers.Configurations;

/// <inheritdoc cref="ContainerConfiguration" />
public sealed class MockServerConfiguration : ContainerConfiguration
{
    /// <summary>
    /// Gets the configured log level, or <c>null</c> if not set.
    /// </summary>
    public string? LogLevel { get; }

    /// <summary>
    /// Initializes a new instance of the <see cref="MockServerConfiguration" /> class.
    /// </summary>
    /// <param name="logLevel">The MockServer log level.</param>
    public MockServerConfiguration(string? logLevel = null)
    {
        LogLevel = logLevel;
    }

    /// <summary>
    /// Initializes a new instance of the <see cref="MockServerConfiguration" /> class.
    /// </summary>
    /// <param name="resourceConfiguration">The Docker resource configuration.</param>
    public MockServerConfiguration(IResourceConfiguration<CreateContainerParameters> resourceConfiguration)
        : base(resourceConfiguration)
    {
    }

    /// <summary>
    /// Initializes a new instance of the <see cref="MockServerConfiguration" /> class.
    /// </summary>
    /// <param name="resourceConfiguration">The Docker container configuration.</param>
    public MockServerConfiguration(IContainerConfiguration resourceConfiguration)
        : base(resourceConfiguration)
    {
    }

    /// <summary>
    /// Initializes a new instance of the <see cref="MockServerConfiguration" /> class
    /// by merging two configurations. Values from <paramref name="newValue"/> take precedence.
    /// </summary>
    /// <param name="oldValue">The old configuration.</param>
    /// <param name="newValue">The new configuration.</param>
    public MockServerConfiguration(MockServerConfiguration oldValue, MockServerConfiguration newValue)
        : base(oldValue, newValue)
    {
        LogLevel = BuildConfiguration.Combine(oldValue.LogLevel, newValue.LogLevel);
    }
}
