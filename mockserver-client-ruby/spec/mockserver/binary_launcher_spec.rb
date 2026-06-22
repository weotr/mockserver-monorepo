# frozen_string_literal: true

require 'digest'
require 'fileutils'
require 'timeout'
require 'tmpdir'

RSpec.describe MockServer::BinaryLauncher do
  # -------------------------------------------------------------------
  # Platform / architecture detection
  # -------------------------------------------------------------------
  describe '.resolve_platform' do
    it 'returns a hash with os_name, arch, and ext' do
      result = described_class.resolve_platform
      expect(result).to be_a(Hash)
      expect(result.keys).to contain_exactly(:os_name, :arch, :ext)
    end

    it 'maps the current platform to valid tokens' do
      result = described_class.resolve_platform
      expect(%w[linux darwin windows]).to include(result[:os_name])
      expect(%w[x86_64 aarch64]).to include(result[:arch])
      expect(%w[tar.gz zip]).to include(result[:ext])
    end

    it 'uses tar.gz for linux' do
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('linux-gnu')
      allow(RbConfig::CONFIG).to receive(:[]).with('host_cpu').and_return('x86_64')

      result = described_class.resolve_platform
      expect(result).to eq({ os_name: 'linux', arch: 'x86_64', ext: 'tar.gz' })
    end

    it 'uses tar.gz for darwin' do
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('darwin24')
      allow(RbConfig::CONFIG).to receive(:[]).with('host_cpu').and_return('arm64')

      result = described_class.resolve_platform
      expect(result).to eq({ os_name: 'darwin', arch: 'aarch64', ext: 'tar.gz' })
    end

    it 'uses zip for windows' do
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('mswin32')
      allow(RbConfig::CONFIG).to receive(:[]).with('host_cpu').and_return('x64')

      result = described_class.resolve_platform
      expect(result).to eq({ os_name: 'windows', arch: 'x86_64', ext: 'zip' })
    end

    it 'maps x86_64 / x64 / amd64 to x86_64' do
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('linux')

      %w[x86_64 x64 amd64].each do |cpu|
        allow(RbConfig::CONFIG).to receive(:[]).with('host_cpu').and_return(cpu)
        expect(described_class.resolve_platform[:arch]).to eq('x86_64')
      end
    end

    it 'maps aarch64 / arm64 to aarch64' do
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('linux')

      %w[aarch64 arm64].each do |cpu|
        allow(RbConfig::CONFIG).to receive(:[]).with('host_cpu').and_return(cpu)
        expect(described_class.resolve_platform[:arch]).to eq('aarch64')
      end
    end

    it 'raises on unsupported platform' do
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('freebsd13')

      expect { described_class.resolve_platform }.to raise_error(
        MockServer::Error, /unsupported platform.*freebsd/
      )
    end

    it 'raises on unsupported architecture' do
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('linux')
      allow(RbConfig::CONFIG).to receive(:[]).with('host_cpu').and_return('s390x')

      expect { described_class.resolve_platform }.to raise_error(
        MockServer::Error, /unsupported architecture.*s390x/
      )
    end
  end

  # -------------------------------------------------------------------
  # Bundle naming
  # -------------------------------------------------------------------
  describe '.bundle_base_name' do
    it 'returns name and ext for a given version' do
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('linux-gnu')
      allow(RbConfig::CONFIG).to receive(:[]).with('host_cpu').and_return('x86_64')

      result = described_class.bundle_base_name('7.0.0')
      expect(result[:name]).to eq('mockserver-7.0.0-linux-x86_64')
      expect(result[:ext]).to eq('tar.gz')
    end

    it 'includes darwin for macOS' do
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('darwin24')
      allow(RbConfig::CONFIG).to receive(:[]).with('host_cpu').and_return('aarch64')

      result = described_class.bundle_base_name('6.1.0')
      expect(result[:name]).to eq('mockserver-6.1.0-darwin-aarch64')
      expect(result[:ext]).to eq('tar.gz')
    end

    it 'includes windows with zip extension' do
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('mingw64')
      allow(RbConfig::CONFIG).to receive(:[]).with('host_cpu').and_return('x86_64')

      result = described_class.bundle_base_name('7.0.0')
      expect(result[:name]).to eq('mockserver-7.0.0-windows-x86_64')
      expect(result[:ext]).to eq('zip')
    end
  end

  # -------------------------------------------------------------------
  # Cache directory resolution
  # -------------------------------------------------------------------
  describe '.cache_dir' do
    around do |example|
      saved = ENV.to_h.slice('MOCKSERVER_BINARY_CACHE', 'XDG_CACHE_HOME', 'LOCALAPPDATA')
      example.run
    ensure
      ENV['MOCKSERVER_BINARY_CACHE'] = saved['MOCKSERVER_BINARY_CACHE']
      ENV['XDG_CACHE_HOME'] = saved['XDG_CACHE_HOME']
      ENV['LOCALAPPDATA'] = saved['LOCALAPPDATA']
    end

    it 'uses MOCKSERVER_BINARY_CACHE when set' do
      ENV['MOCKSERVER_BINARY_CACHE'] = '/custom/cache'
      expect(described_class.cache_dir).to eq('/custom/cache')
    end

    it 'uses XDG_CACHE_HOME on non-Windows' do
      ENV.delete('MOCKSERVER_BINARY_CACHE')
      ENV['XDG_CACHE_HOME'] = '/xdg/cache'

      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('linux')

      expect(described_class.cache_dir).to eq('/xdg/cache/mockserver/binaries')
    end

    it 'falls back to ~/.cache on non-Windows without XDG' do
      ENV.delete('MOCKSERVER_BINARY_CACHE')
      ENV.delete('XDG_CACHE_HOME')

      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('linux')

      expect(described_class.cache_dir).to eq(
        File.join(Dir.home, '.cache', 'mockserver', 'binaries')
      )
    end

    it 'uses LOCALAPPDATA on Windows' do
      ENV.delete('MOCKSERVER_BINARY_CACHE')
      ENV['LOCALAPPDATA'] = 'C:\\Users\\Test\\AppData\\Local'

      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('mswin32')

      expect(described_class.cache_dir).to eq(
        File.join('C:\\Users\\Test\\AppData\\Local', 'mockserver', 'binaries')
      )
    end
  end

  # -------------------------------------------------------------------
  # Asset URL construction
  # -------------------------------------------------------------------
  describe '.asset_url' do
    around do |example|
      saved = ENV['MOCKSERVER_BINARY_BASE_URL']
      example.run
    ensure
      ENV['MOCKSERVER_BINARY_BASE_URL'] = saved
    end

    it 'uses the default GitHub URL' do
      ENV.delete('MOCKSERVER_BINARY_BASE_URL')
      url = described_class.asset_url('7.0.0', 'mockserver-7.0.0-linux-x86_64.tar.gz')
      expect(url).to eq(
        'https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-7.0.0/mockserver-7.0.0-linux-x86_64.tar.gz'
      )
    end

    it 'uses MOCKSERVER_BINARY_BASE_URL when set' do
      ENV['MOCKSERVER_BINARY_BASE_URL'] = 'https://mirror.example.com/files/'
      url = described_class.asset_url('7.0.0', 'mockserver-7.0.0-linux-x86_64.tar.gz')
      expect(url).to eq(
        'https://mirror.example.com/files/mockserver-7.0.0-linux-x86_64.tar.gz'
      )
    end

    it 'strips trailing slashes from the base URL' do
      ENV['MOCKSERVER_BINARY_BASE_URL'] = 'https://mirror.example.com/files///'
      url = described_class.asset_url('7.0.0', 'file.tar.gz')
      expect(url).to eq('https://mirror.example.com/files/file.tar.gz')
    end

    it 'preserves interior slashes while stripping only trailing ones' do
      ENV['MOCKSERVER_BINARY_BASE_URL'] = 'https://host//a//b//'
      url = described_class.asset_url('7.0.0', 'file.tar.gz')
      expect(url).to eq('https://host//a//b/file.tar.gz')
    end

    it 'handles a pathological slash run without quadratic backtracking (ReDoS guard)' do
      ENV['MOCKSERVER_BINARY_BASE_URL'] = "https://host/#{'/' * 100_000}x"
      result = nil
      expect do
        Timeout.timeout(2) do
          result = described_class.asset_url('7.0.0', 'file.tar.gz')
        end
      end.not_to raise_error
      # No trailing slash to strip, so the base is returned unchanged.
      expect(result).to end_with('x/file.tar.gz')
    end

    it 'uses the CDN URL for SNAPSHOT versions' do
      ENV.delete('MOCKSERVER_BINARY_BASE_URL')
      url = described_class.asset_url('7.1.0-SNAPSHOT', 'mockserver-7.1.0-SNAPSHOT-linux-x86_64.tar.gz')
      expect(url).to eq(
        'https://downloads.mock-server.com/mockserver-7.1.0-SNAPSHOT/mockserver-7.1.0-SNAPSHOT-linux-x86_64.tar.gz'
      )
    end

    it 'uses GitHub Releases for release versions' do
      ENV.delete('MOCKSERVER_BINARY_BASE_URL')
      url = described_class.asset_url('7.0.0', 'mockserver-7.0.0-linux-x86_64.tar.gz')
      expect(url).to eq(
        'https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-7.0.0/mockserver-7.0.0-linux-x86_64.tar.gz'
      )
    end

    it 'uses MOCKSERVER_BINARY_BASE_URL even for SNAPSHOT versions when set' do
      ENV['MOCKSERVER_BINARY_BASE_URL'] = 'https://custom-mirror.example.com/bins'
      url = described_class.asset_url('7.1.0-SNAPSHOT', 'mockserver-7.1.0-SNAPSHOT-linux-x86_64.tar.gz')
      expect(url).to eq(
        'https://custom-mirror.example.com/bins/mockserver-7.1.0-SNAPSHOT-linux-x86_64.tar.gz'
      )
    end

    it 'detects SNAPSHOT case-insensitively' do
      ENV.delete('MOCKSERVER_BINARY_BASE_URL')
      url = described_class.asset_url('7.1.0-snapshot', 'file.tar.gz')
      expect(url).to start_with('https://downloads.mock-server.com/')
    end
  end

  # -------------------------------------------------------------------
  # Launcher path
  # -------------------------------------------------------------------
  describe '.launcher_path' do
    it 'returns the mockserver executable path' do
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('linux')

      path = described_class.launcher_path('/cache/7.0.0', 'mockserver-7.0.0-linux-x86_64')
      expect(path).to eq('/cache/7.0.0/mockserver-7.0.0-linux-x86_64/bin/mockserver')
    end

    it 'returns mockserver.bat on Windows' do
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('mswin32')

      path = described_class.launcher_path('/cache/7.0.0', 'mockserver-7.0.0-windows-x86_64')
      expect(path).to eq('/cache/7.0.0/mockserver-7.0.0-windows-x86_64/bin/mockserver.bat')
    end
  end

  # -------------------------------------------------------------------
  # H1: Version validation
  # -------------------------------------------------------------------
  describe 'version validation (H1)' do
    let(:tmpdir) { Dir.mktmpdir('mockserver-version-test') }
    let(:log) { Logger.new(File::NULL) }

    before do
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('linux-gnu')
      allow(RbConfig::CONFIG).to receive(:[]).with('host_cpu').and_return('x86_64')
    end

    after do
      FileUtils.rm_rf(tmpdir)
    end

    around do |example|
      saved = ENV.to_h.slice('MOCKSERVER_BINARY_CACHE', 'MOCKSERVER_BINARY_BASE_URL')
      ENV['MOCKSERVER_BINARY_CACHE'] = File.join(tmpdir, 'cache')
      example.run
    ensure
      ENV['MOCKSERVER_BINARY_CACHE'] = saved['MOCKSERVER_BINARY_CACHE']
      ENV['MOCKSERVER_BINARY_BASE_URL'] = saved['MOCKSERVER_BINARY_BASE_URL']
    end

    it 'accepts valid semver versions' do
      # Stub SKIP so we get a MockServer::Error (not a WebMock error) after
      # version validation passes — this proves the version was accepted.
      ENV['MOCKSERVER_SKIP_BINARY_DOWNLOAD'] = '1'
      %w[7.0.0 6.1.0 99.0.0-test 1.2.3-beta.1 10.20.30-rc1].each do |ver|
        expect {
          described_class.ensure_launcher(version: ver, log: log)
        }.to raise_error(MockServer::Error, /MOCKSERVER_SKIP_BINARY_DOWNLOAD/)
      end
    ensure
      ENV.delete('MOCKSERVER_SKIP_BINARY_DOWNLOAD')
    end

    it 'rejects versions with path separators' do
      expect {
        described_class.ensure_launcher(version: '../../../etc/passwd', log: log)
      }.to raise_error(MockServer::Error, /invalid version.*path traversal/)
    end

    it 'rejects versions with forward slashes' do
      expect {
        described_class.ensure_launcher(version: '7.0.0/../../etc', log: log)
      }.to raise_error(MockServer::Error, /invalid version.*path traversal/)
    end

    it 'rejects versions with backslashes' do
      expect {
        described_class.ensure_launcher(version: '7.0.0\\..\\..', log: log)
      }.to raise_error(MockServer::Error, /invalid version.*path traversal/)
    end

    it 'rejects versions that do not match the semver pattern' do
      expect {
        described_class.ensure_launcher(version: 'not-a-version', log: log)
      }.to raise_error(MockServer::Error, /invalid version format/)
    end

    it 'rejects empty version strings' do
      expect {
        described_class.ensure_launcher(version: '', log: log)
      }.to raise_error(MockServer::Error, /invalid version format/)
    end
  end

  # -------------------------------------------------------------------
  # SHA-256 verification (using a real local fixture)
  # -------------------------------------------------------------------
  describe 'SHA-256 verification via ensure_launcher' do
    let(:tmpdir) { Dir.mktmpdir('mockserver-binary-test') }
    let(:version) { '99.0.0' }
    let(:log) { Logger.new(File::NULL) }

    # We need to set up the platform stubs once to get consistent naming
    before do
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('linux-gnu')
      allow(RbConfig::CONFIG).to receive(:[]).with('host_cpu').and_return('x86_64')
    end

    after do
      FileUtils.rm_rf(tmpdir)
    end

    let(:bundle_name) { "mockserver-#{version}-linux-x86_64" }
    let(:archive_name) { "#{bundle_name}.tar.gz" }

    # Create a minimal tar.gz archive containing the expected launcher
    def create_fixture_archive(fixture_dir)
      # Create the directory structure that should be in the archive
      inner_dir = File.join(fixture_dir, bundle_name, 'bin')
      FileUtils.mkdir_p(inner_dir)
      launcher = File.join(inner_dir, 'mockserver')
      File.write(launcher, "#!/bin/sh\necho mock\n")
      File.chmod(0o755, launcher)

      # Create the tar.gz
      archive_path = File.join(fixture_dir, archive_name)
      system('tar', '-czf', archive_path, '-C', fixture_dir, bundle_name)

      # Clean up the source directory (only the archive should remain for serving)
      FileUtils.rm_rf(File.join(fixture_dir, bundle_name))

      archive_path
    end

    it 'succeeds with a correct SHA-256 checksum' do
      fixture_dir = File.join(tmpdir, 'fixtures')
      FileUtils.mkdir_p(fixture_dir)
      archive_path = create_fixture_archive(fixture_dir)

      # Write the correct sha256
      sha = Digest::SHA256.file(archive_path).hexdigest
      File.write("#{archive_path}.sha256", "#{sha}  #{archive_name}\n")

      # Point the downloader at the local fixtures via file:// URLs
      cache = File.join(tmpdir, 'cache')
      ENV['MOCKSERVER_BINARY_CACHE'] = cache
      ENV['MOCKSERVER_BINARY_BASE_URL'] = "file://#{fixture_dir}"

      result = described_class.ensure_launcher(version: version, log: log)
      expect(result).to end_with("#{bundle_name}/bin/mockserver")
      expect(File.exist?(result)).to be true
      expect(File.size(result)).to be > 0
    ensure
      ENV.delete('MOCKSERVER_BINARY_CACHE')
      ENV.delete('MOCKSERVER_BINARY_BASE_URL')
    end

    it 'raises a clear, actionable error when no release bundle exists (HTTP 404)' do
      cache = File.join(tmpdir, 'cache')
      ENV['MOCKSERVER_BINARY_CACHE'] = cache
      ENV['MOCKSERVER_BINARY_BASE_URL'] = 'https://example.invalid'

      # Simulate the GitHub release tag existing but shipping no bundle.
      allow(described_class).to receive(:download_file)
        .and_raise(MockServer::BinaryLauncher::NotFoundError, 'HTTP 404')

      expect do
        described_class.ensure_launcher(version: version, log: log)
      end.to raise_error(MockServer::Error) { |e|
        expect(e.message).to include(version)
        expect(e.message).to include('no MockServer release bundle')
        expect(e.message).to include("docker run mockserver/mockserver:mockserver-#{version}")
        expect(e.message).to include("org.mock-server:mockserver-netty:#{version}")
      }
    ensure
      ENV.delete('MOCKSERVER_BINARY_CACHE')
      ENV.delete('MOCKSERVER_BINARY_BASE_URL')
    end

    it 'fails with a wrong SHA-256 checksum' do
      fixture_dir = File.join(tmpdir, 'fixtures')
      FileUtils.mkdir_p(fixture_dir)
      create_fixture_archive(fixture_dir)

      # Write a wrong sha256
      File.write(
        File.join(fixture_dir, "#{archive_name}.sha256"),
        "0000000000000000000000000000000000000000000000000000000000000000  #{archive_name}\n"
      )

      cache = File.join(tmpdir, 'cache')
      ENV['MOCKSERVER_BINARY_CACHE'] = cache
      ENV['MOCKSERVER_BINARY_BASE_URL'] = "file://#{fixture_dir}"

      expect {
        described_class.ensure_launcher(version: version, log: log)
      }.to raise_error(MockServer::Error, /checksum mismatch/)

      # The .part file should have been cleaned up
      version_dir = File.join(cache, version)
      part_files = Dir.glob(File.join(version_dir, '*.part'))
      expect(part_files).to be_empty

      # H3: The .sha256 file should also have been cleaned up
      sha_files = Dir.glob(File.join(version_dir, '*.sha256'))
      expect(sha_files).to be_empty
    ensure
      ENV.delete('MOCKSERVER_BINARY_CACHE')
      ENV.delete('MOCKSERVER_BINARY_BASE_URL')
    end

    it 'fails with an empty checksum file' do
      fixture_dir = File.join(tmpdir, 'fixtures')
      FileUtils.mkdir_p(fixture_dir)
      create_fixture_archive(fixture_dir)

      # Write an empty sha256 file
      File.write(File.join(fixture_dir, "#{archive_name}.sha256"), "  \n")

      cache = File.join(tmpdir, 'cache')
      ENV['MOCKSERVER_BINARY_CACHE'] = cache
      ENV['MOCKSERVER_BINARY_BASE_URL'] = "file://#{fixture_dir}"

      expect {
        described_class.ensure_launcher(version: version, log: log)
      }.to raise_error(MockServer::Error, /empty or unparseable/)
    ensure
      ENV.delete('MOCKSERVER_BINARY_CACHE')
      ENV.delete('MOCKSERVER_BINARY_BASE_URL')
    end

    it 'cleans up both .part and .sha256 on checksum failure' do
      fixture_dir = File.join(tmpdir, 'fixtures')
      FileUtils.mkdir_p(fixture_dir)
      create_fixture_archive(fixture_dir)

      # Write a wrong sha256
      File.write(
        File.join(fixture_dir, "#{archive_name}.sha256"),
        "0000000000000000000000000000000000000000000000000000000000000000  #{archive_name}\n"
      )

      cache = File.join(tmpdir, 'cache')
      ENV['MOCKSERVER_BINARY_CACHE'] = cache
      ENV['MOCKSERVER_BINARY_BASE_URL'] = "file://#{fixture_dir}"

      expect {
        described_class.ensure_launcher(version: version, log: log)
      }.to raise_error(MockServer::Error, /checksum mismatch/)

      version_dir = File.join(cache, version)

      # Both temp files must be gone
      expect(Dir.glob(File.join(version_dir, '*.part'))).to be_empty
      expect(Dir.glob(File.join(version_dir, '*.sha256'))).to be_empty
    ensure
      ENV.delete('MOCKSERVER_BINARY_CACHE')
      ENV.delete('MOCKSERVER_BINARY_BASE_URL')
    end

    it 'reuses a cached launcher on second call' do
      fixture_dir = File.join(tmpdir, 'fixtures')
      FileUtils.mkdir_p(fixture_dir)
      archive_path = create_fixture_archive(fixture_dir)

      sha = Digest::SHA256.file(archive_path).hexdigest
      File.write("#{archive_path}.sha256", "#{sha}  #{archive_name}\n")

      cache = File.join(tmpdir, 'cache')
      ENV['MOCKSERVER_BINARY_CACHE'] = cache
      ENV['MOCKSERVER_BINARY_BASE_URL'] = "file://#{fixture_dir}"

      # First call downloads
      result1 = described_class.ensure_launcher(version: version, log: log)
      expect(File.exist?(result1)).to be true

      # Remove the fixture so it can't be re-downloaded
      FileUtils.rm_rf(fixture_dir)

      # Second call uses cache
      result2 = described_class.ensure_launcher(version: version, log: log)
      expect(result2).to eq(result1)
    ensure
      ENV.delete('MOCKSERVER_BINARY_CACHE')
      ENV.delete('MOCKSERVER_BINARY_BASE_URL')
    end
  end

  # -------------------------------------------------------------------
  # MOCKSERVER_SKIP_BINARY_DOWNLOAD behaviour
  # -------------------------------------------------------------------
  describe 'MOCKSERVER_SKIP_BINARY_DOWNLOAD' do
    let(:tmpdir) { Dir.mktmpdir('mockserver-skip-test') }
    let(:log) { Logger.new(File::NULL) }

    before do
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('linux-gnu')
      allow(RbConfig::CONFIG).to receive(:[]).with('host_cpu').and_return('x86_64')
    end

    after do
      FileUtils.rm_rf(tmpdir)
    end

    it 'raises when set and no cached binary exists' do
      cache = File.join(tmpdir, 'empty-cache')
      ENV['MOCKSERVER_BINARY_CACHE'] = cache
      ENV['MOCKSERVER_SKIP_BINARY_DOWNLOAD'] = '1'

      expect {
        described_class.ensure_launcher(version: '99.0.0', log: log)
      }.to raise_error(MockServer::Error, /MOCKSERVER_SKIP_BINARY_DOWNLOAD.*no cached binary/)
    ensure
      ENV.delete('MOCKSERVER_BINARY_CACHE')
      ENV.delete('MOCKSERVER_SKIP_BINARY_DOWNLOAD')
    end

    it 'succeeds when set and a cached binary exists' do
      version = '99.0.0'
      bundle_name = "mockserver-#{version}-linux-x86_64"
      cache = File.join(tmpdir, 'seeded-cache')

      # Pre-seed the cache
      launcher_dir = File.join(cache, version, bundle_name, 'bin')
      FileUtils.mkdir_p(launcher_dir)
      launcher = File.join(launcher_dir, 'mockserver')
      File.write(launcher, "#!/bin/sh\necho mock\n")
      File.chmod(0o755, launcher)

      ENV['MOCKSERVER_BINARY_CACHE'] = cache
      ENV['MOCKSERVER_SKIP_BINARY_DOWNLOAD'] = '1'

      result = described_class.ensure_launcher(version: version, log: log)
      expect(result).to eq(launcher)
    ensure
      ENV.delete('MOCKSERVER_BINARY_CACHE')
      ENV.delete('MOCKSERVER_SKIP_BINARY_DOWNLOAD')
    end
  end

  # -------------------------------------------------------------------
  # Versioned cache pruning
  # -------------------------------------------------------------------
  describe '.prune_old_versions' do
    let(:tmpdir) { Dir.mktmpdir('mockserver-prune-test') }
    let(:log) { Logger.new(File::NULL) }
    let(:cache) { File.join(tmpdir, 'cache') }

    before do
      FileUtils.mkdir_p(cache)
    end

    after do
      FileUtils.rm_rf(tmpdir)
    end

    around do |example|
      saved = ENV['MOCKSERVER_BINARY_CACHE']
      ENV['MOCKSERVER_BINARY_CACHE'] = cache
      example.run
    ensure
      ENV['MOCKSERVER_BINARY_CACHE'] = saved
    end

    it 'removes old version directories keeping the current one' do
      # Create fake version directories
      FileUtils.mkdir_p(File.join(cache, '5.0.0'))
      FileUtils.mkdir_p(File.join(cache, '6.0.0'))
      FileUtils.mkdir_p(File.join(cache, '7.0.0'))

      described_class.prune_old_versions('7.0.0', log: log)

      # Current version must remain
      expect(File.directory?(File.join(cache, '7.0.0'))).to be true
      # One previous version kept (6.0.0 — highest semver old)
      expect(File.directory?(File.join(cache, '6.0.0'))).to be true
      # Oldest old (5.0.0) removed
      expect(File.directory?(File.join(cache, '5.0.0'))).to be false
    end

    it 'keeps current + up to MAX_PREVIOUS_VERSIONS_TO_KEEP' do
      FileUtils.mkdir_p(File.join(cache, '1.0.0'))
      FileUtils.mkdir_p(File.join(cache, '2.0.0'))
      FileUtils.mkdir_p(File.join(cache, '3.0.0'))
      FileUtils.mkdir_p(File.join(cache, '4.0.0'))

      described_class.prune_old_versions('4.0.0', log: log)

      expect(File.directory?(File.join(cache, '4.0.0'))).to be true
      expect(File.directory?(File.join(cache, '3.0.0'))).to be true  # kept (highest semver old)
      expect(File.directory?(File.join(cache, '2.0.0'))).to be false # removed
      expect(File.directory?(File.join(cache, '1.0.0'))).to be false # removed
    end

    # H7: Semver-aware ordering — NOT lexicographic
    it 'uses semver-aware ordering not lexicographic' do
      # Lexicographic would sort "9.0.0" > "10.0.0" (because '9' > '1')
      # Semver-aware must sort 10.0.0 > 9.0.0
      FileUtils.mkdir_p(File.join(cache, '2.0.0'))
      FileUtils.mkdir_p(File.join(cache, '9.0.0'))
      FileUtils.mkdir_p(File.join(cache, '10.0.0'))
      FileUtils.mkdir_p(File.join(cache, '11.0.0'))

      described_class.prune_old_versions('11.0.0', log: log)

      # Current stays
      expect(File.directory?(File.join(cache, '11.0.0'))).to be true
      # 10.0.0 is the highest old — kept
      expect(File.directory?(File.join(cache, '10.0.0'))).to be true
      # 9.0.0 and 2.0.0 removed
      expect(File.directory?(File.join(cache, '9.0.0'))).to be false
      expect(File.directory?(File.join(cache, '2.0.0'))).to be false
    end

    it 'handles semver pre-release ordering correctly' do
      FileUtils.mkdir_p(File.join(cache, '7.0.0-alpha'))
      FileUtils.mkdir_p(File.join(cache, '7.0.0-beta'))
      FileUtils.mkdir_p(File.join(cache, '7.0.0'))

      described_class.prune_old_versions('7.0.0', log: log)

      expect(File.directory?(File.join(cache, '7.0.0'))).to be true
      # beta > alpha lexicographically, so beta is kept
      expect(File.directory?(File.join(cache, '7.0.0-beta'))).to be true
      expect(File.directory?(File.join(cache, '7.0.0-alpha'))).to be false
    end

    # H7: Pre-release MUST sort lower than its release counterpart
    it 'prunes pre-release versions before their release counterpart (H7)' do
      FileUtils.mkdir_p(File.join(cache, '7.0.0-SNAPSHOT'))
      FileUtils.mkdir_p(File.join(cache, '7.0.0-rc1'))
      FileUtils.mkdir_p(File.join(cache, '7.0.0'))

      described_class.prune_old_versions('7.0.0', log: log)

      # Current release stays
      expect(File.directory?(File.join(cache, '7.0.0'))).to be true
      # rc1 > SNAPSHOT (both pre-release), rc1 is highest old -> kept
      expect(File.directory?(File.join(cache, '7.0.0-rc1'))).to be true
      # SNAPSHOT is lowest -> pruned
      expect(File.directory?(File.join(cache, '7.0.0-SNAPSHOT'))).to be false
    end

    it 'keeps the release when current is pre-release (H7 releases outrank pre-releases)' do
      FileUtils.mkdir_p(File.join(cache, '7.0.0'))
      FileUtils.mkdir_p(File.join(cache, '6.9.0'))
      FileUtils.mkdir_p(File.join(cache, '7.1.0-SNAPSHOT'))

      # Current is a pre-release
      described_class.prune_old_versions('7.1.0-SNAPSHOT', log: log)

      expect(File.directory?(File.join(cache, '7.1.0-SNAPSHOT'))).to be true
      # 7.0.0 release > 6.9.0 release, so 7.0.0 is kept
      expect(File.directory?(File.join(cache, '7.0.0'))).to be true
      expect(File.directory?(File.join(cache, '6.9.0'))).to be false
    end

    it 'does nothing when only the current version exists' do
      FileUtils.mkdir_p(File.join(cache, '7.0.0'))

      expect {
        described_class.prune_old_versions('7.0.0', log: log)
      }.not_to raise_error

      expect(File.directory?(File.join(cache, '7.0.0'))).to be true
    end

    it 'does nothing when the cache directory does not exist' do
      ENV['MOCKSERVER_BINARY_CACHE'] = File.join(tmpdir, 'nonexistent')

      expect {
        described_class.prune_old_versions('7.0.0', log: log)
      }.not_to raise_error
    end

    it 'removes leftover .part files at all directory levels' do
      FileUtils.mkdir_p(File.join(cache, '7.0.0'))

      # Part file at base level
      base_part = File.join(cache, 'something.part')
      File.write(base_part, 'leftover')

      # Part file inside a version dir (the actual production location)
      nested_part = File.join(cache, '7.0.0', 'archive.tar.gz.part')
      File.write(nested_part, 'leftover')

      described_class.prune_old_versions('7.0.0', log: log)

      expect(File.exist?(base_part)).to be false
      expect(File.exist?(nested_part)).to be false
    end

    it 'does not remove regular files at the base level' do
      FileUtils.mkdir_p(File.join(cache, '7.0.0'))
      regular_file = File.join(cache, 'notes.txt')
      File.write(regular_file, 'keep me')

      described_class.prune_old_versions('7.0.0', log: log)

      expect(File.exist?(regular_file)).to be true
    end

    # COR-06: directory vanished between Dir.entries and File.realpath
    it 'survives a directory being removed concurrently' do
      phantom = File.join(cache, '5.0.0')
      FileUtils.mkdir_p(phantom)
      FileUtils.mkdir_p(File.join(cache, '7.0.0'))

      # Simulate concurrent deletion: realpath raises ENOENT
      call_count = 0
      allow(File).to receive(:realpath).and_wrap_original do |method, *args|
        call_count += 1
        # On the first call to realpath with the phantom path, simulate it vanishing
        if args[0] == phantom
          raise Errno::ENOENT, "No such file or directory - #{phantom}"
        end

        method.call(*args)
      end

      expect {
        described_class.prune_old_versions('7.0.0', log: log)
      }.not_to raise_error
    end

    it 'does not delete paths outside the cache base (symlink attack)' do
      outside = File.join(tmpdir, 'outside_target')
      FileUtils.mkdir_p(outside)
      File.write(File.join(outside, 'precious.txt'), 'do not delete')

      # Create a symlink inside cache that points outside
      FileUtils.mkdir_p(File.join(cache, '7.0.0'))
      symlink = File.join(cache, '5.0.0')
      File.symlink(outside, symlink)

      described_class.prune_old_versions('7.0.0', log: log)

      # The outside directory should still exist with its content
      expect(File.exist?(File.join(outside, 'precious.txt'))).to be true
    end
  end

  # -------------------------------------------------------------------
  # Full ensure_launcher flow with pruning
  # -------------------------------------------------------------------
  describe 'ensure_launcher triggers pruning' do
    let(:tmpdir) { Dir.mktmpdir('mockserver-full-flow-test') }
    let(:log) { Logger.new(File::NULL) }
    let(:version) { '99.1.0' }

    before do
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('linux-gnu')
      allow(RbConfig::CONFIG).to receive(:[]).with('host_cpu').and_return('x86_64')
    end

    after do
      FileUtils.rm_rf(tmpdir)
    end

    it 'prunes old versions after a successful install' do
      bundle_name = "mockserver-#{version}-linux-x86_64"
      archive_name = "#{bundle_name}.tar.gz"

      fixture_dir = File.join(tmpdir, 'fixtures')
      FileUtils.mkdir_p(fixture_dir)

      # Create fixture archive
      inner_dir = File.join(fixture_dir, bundle_name, 'bin')
      FileUtils.mkdir_p(inner_dir)
      File.write(File.join(inner_dir, 'mockserver'), "#!/bin/sh\necho mock\n")
      File.chmod(0o755, File.join(inner_dir, 'mockserver'))
      archive_path = File.join(fixture_dir, archive_name)
      system('tar', '-czf', archive_path, '-C', fixture_dir, bundle_name)
      FileUtils.rm_rf(File.join(fixture_dir, bundle_name))

      sha = Digest::SHA256.file(archive_path).hexdigest
      File.write("#{archive_path}.sha256", "#{sha}  #{archive_name}\n")

      cache = File.join(tmpdir, 'cache')
      FileUtils.mkdir_p(cache)

      # Pre-create two old version directories
      old1 = File.join(cache, '90.0.0')
      old2 = File.join(cache, '91.0.0')
      FileUtils.mkdir_p(old1)
      FileUtils.mkdir_p(old2)

      ENV['MOCKSERVER_BINARY_CACHE'] = cache
      ENV['MOCKSERVER_BINARY_BASE_URL'] = "file://#{fixture_dir}"

      described_class.ensure_launcher(version: version, log: log)

      # Current version present
      expect(File.directory?(File.join(cache, version))).to be true
      # Newest old kept (91.0.0 — higher semver)
      expect(File.directory?(old2)).to be true
      # Oldest old removed (90.0.0)
      expect(File.directory?(old1)).to be false
    ensure
      ENV.delete('MOCKSERVER_BINARY_CACHE')
      ENV.delete('MOCKSERVER_BINARY_BASE_URL')
    end

    it 'does NOT prune on a cache hit' do
      bundle_name = "mockserver-#{version}-linux-x86_64"

      cache = File.join(tmpdir, 'cache')
      FileUtils.mkdir_p(cache)

      # Pre-seed the cache with the current version
      launcher_dir = File.join(cache, version, bundle_name, 'bin')
      FileUtils.mkdir_p(launcher_dir)
      launcher = File.join(launcher_dir, 'mockserver')
      File.write(launcher, "#!/bin/sh\necho mock\n")
      File.chmod(0o755, launcher)

      # Pre-create an old version that should NOT be pruned on cache hit
      old_dir = File.join(cache, '1.0.0')
      FileUtils.mkdir_p(old_dir)

      ENV['MOCKSERVER_BINARY_CACHE'] = cache

      described_class.ensure_launcher(version: version, log: log)

      # Old version should still exist (pruning only after install, not cache hit)
      expect(File.directory?(old_dir)).to be true
    ensure
      ENV.delete('MOCKSERVER_BINARY_CACHE')
    end
  end

  # -------------------------------------------------------------------
  # H3: Post-extract path traversal verification
  # -------------------------------------------------------------------
  describe 'post-extract path traversal verification (H3)' do
    let(:tmpdir) { Dir.mktmpdir('mockserver-traversal-test') }
    let(:version) { '99.2.0' }
    let(:log) { Logger.new(File::NULL) }

    before do
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('linux-gnu')
      allow(RbConfig::CONFIG).to receive(:[]).with('host_cpu').and_return('x86_64')
    end

    after do
      FileUtils.rm_rf(tmpdir)
    end

    it 'accepts a normal archive that stays within the version dir' do
      bundle_name = "mockserver-#{version}-linux-x86_64"
      archive_name = "#{bundle_name}.tar.gz"

      fixture_dir = File.join(tmpdir, 'fixtures')
      FileUtils.mkdir_p(fixture_dir)

      # Create a normal archive
      inner_dir = File.join(fixture_dir, bundle_name, 'bin')
      FileUtils.mkdir_p(inner_dir)
      File.write(File.join(inner_dir, 'mockserver'), "#!/bin/sh\necho mock\n")
      File.chmod(0o755, File.join(inner_dir, 'mockserver'))
      archive_path = File.join(fixture_dir, archive_name)
      system('tar', '-czf', archive_path, '-C', fixture_dir, bundle_name)
      FileUtils.rm_rf(File.join(fixture_dir, bundle_name))

      sha = Digest::SHA256.file(archive_path).hexdigest
      File.write("#{archive_path}.sha256", "#{sha}  #{archive_name}\n")

      cache = File.join(tmpdir, 'cache')
      ENV['MOCKSERVER_BINARY_CACHE'] = cache
      ENV['MOCKSERVER_BINARY_BASE_URL'] = "file://#{fixture_dir}"

      # Should succeed — no path traversal
      result = described_class.ensure_launcher(version: version, log: log)
      expect(File.exist?(result)).to be true
    ensure
      ENV.delete('MOCKSERVER_BINARY_CACHE')
      ENV.delete('MOCKSERVER_BINARY_BASE_URL')
    end

    it 'detects a symlink that escapes the version directory' do
      dir = File.join(tmpdir, 'ver')
      FileUtils.mkdir_p(File.join(dir, 'bundle', 'bin'))
      File.write(File.join(dir, 'bundle', 'bin', 'mockserver'), 'ok')

      # Create a symlink that escapes the version dir
      outside = File.join(tmpdir, 'escape_target')
      FileUtils.mkdir_p(outside)
      File.write(File.join(outside, 'secret.txt'), 'leaked')
      File.symlink(outside, File.join(dir, 'bundle', 'escaped'))

      expect {
        described_class.send(:verify_extracted_paths!, dir)
      }.to raise_error(MockServer::Error, /tar path traversal detected/)
    end

    it 'passes when all entries are within the directory' do
      dir = File.join(tmpdir, 'clean_ver')
      FileUtils.mkdir_p(File.join(dir, 'bundle', 'bin'))
      File.write(File.join(dir, 'bundle', 'bin', 'mockserver'), 'ok')
      File.write(File.join(dir, 'bundle', 'README'), 'readme')

      expect {
        described_class.send(:verify_extracted_paths!, dir)
      }.not_to raise_error
    end
  end

  # -------------------------------------------------------------------
  # H3/FEA-03: Windows zip extraction code path
  # -------------------------------------------------------------------
  describe 'Windows zip extraction (H3/FEA-03)' do
    let(:tmpdir) { Dir.mktmpdir('mockserver-winzip-test') }
    let(:version) { '99.3.0' }
    let(:log) { Logger.new(File::NULL) }

    before do
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('mswin32')
      allow(RbConfig::CONFIG).to receive(:[]).with('host_cpu').and_return('x86_64')
    end

    after do
      FileUtils.rm_rf(tmpdir)
    end

    it 'uses extract_zip_windows for .zip on Windows platform' do
      archive = File.join(tmpdir, 'test.zip')
      dir = File.join(tmpdir, 'dest')
      FileUtils.mkdir_p(dir)
      File.write(archive, 'fake-zip')

      # Stub system calls to track which extraction path is taken
      expect(described_class).to receive(:extract_zip_windows).with(archive, dir).and_return(true)

      described_class.send(:extract_archive, archive, dir, 'zip')
    end

    it 'uses tar for non-zip archives even on Windows' do
      archive = File.join(tmpdir, 'test.tar.gz')
      dir = File.join(tmpdir, 'dest')
      FileUtils.mkdir_p(dir)
      File.write(archive, 'fake-tar')

      expect(described_class).to receive(:system).with('tar', '-xf', archive, '-C', dir).and_return(true)

      described_class.send(:extract_archive, archive, dir, 'tar.gz')
    end

    it 'falls back to PowerShell when tar.exe fails on Windows' do
      archive = File.join(tmpdir, 'test.zip')
      dir = File.join(tmpdir, 'dest')
      FileUtils.mkdir_p(dir)
      File.write(archive, 'fake-zip')

      # tar fails first
      call_count = 0
      allow(described_class).to receive(:system) do |*args|
        call_count += 1
        if args[0] == 'tar'
          false  # tar.exe not found or failed
        elsif args[0] == 'powershell.exe'
          # Verify PowerShell is called with the right arguments
          expect(args[1]).to eq('-NoProfile')
          expect(args[2]).to eq('-NoLogo')
          expect(args[3]).to eq('-Command')
          expect(args[4]).to include('Expand-Archive')
          expect(args[4]).to include('-LiteralPath')
          true
        end
      end

      described_class.send(:extract_zip_windows, archive, dir)
      expect(call_count).to eq(2) # tar attempted, then powershell
    end

    it 'raises when both tar and PowerShell fail on Windows' do
      archive = File.join(tmpdir, 'test.zip')
      dir = File.join(tmpdir, 'dest')
      FileUtils.mkdir_p(dir)
      File.write(archive, 'fake-zip')

      allow(described_class).to receive(:system).and_return(false)

      expect {
        described_class.send(:extract_zip_windows, archive, dir)
      }.to raise_error(MockServer::Error, /neither tar.exe nor PowerShell/)
    end

    it 'exercises the full ensure_launcher flow with zip on Windows' do
      bundle_name = "mockserver-#{version}-windows-x86_64"
      archive_name = "#{bundle_name}.zip"

      fixture_dir = File.join(tmpdir, 'fixtures')
      FileUtils.mkdir_p(fixture_dir)

      # Create a directory structure that matches what the zip would contain
      inner_dir = File.join(fixture_dir, bundle_name, 'bin')
      FileUtils.mkdir_p(inner_dir)
      bat_file = File.join(inner_dir, 'mockserver.bat')
      File.write(bat_file, "@echo off\necho mock\n")

      # Create a .zip using the system zip command
      archive_path = File.join(fixture_dir, archive_name)
      system('cd', fixture_dir, '&&', 'zip', '-r', archive_path, bundle_name)

      # If zip is not available, create manually with tar (macOS/Linux testing)
      # The extraction code will be stubbed anyway
      unless File.exist?(archive_path)
        # Create the zip-like archive as a tar for the fixture (we'll stub extraction)
        system('tar', '-cf', archive_path, '-C', fixture_dir, bundle_name)
      end

      FileUtils.rm_rf(File.join(fixture_dir, bundle_name))

      sha = Digest::SHA256.file(archive_path).hexdigest
      File.write("#{archive_path}.sha256", "#{sha}  #{archive_name}\n")

      cache = File.join(tmpdir, 'cache')
      ENV['MOCKSERVER_BINARY_CACHE'] = cache
      ENV['MOCKSERVER_BINARY_BASE_URL'] = "file://#{fixture_dir}"

      # Stub extract_archive to simulate successful extraction by creating
      # the expected files manually (we can't run real Windows extraction on macOS/Linux)
      allow(described_class).to receive(:extract_archive) do |_archive, dir, _ext|
        dest_inner = File.join(dir, bundle_name, 'bin')
        FileUtils.mkdir_p(dest_inner)
        File.write(File.join(dest_inner, 'mockserver.bat'), "@echo off\necho mock\n")
      end

      result = described_class.ensure_launcher(version: version, log: log)
      expect(result).to end_with("#{bundle_name}/bin/mockserver.bat")
      expect(File.exist?(result)).to be true
    ensure
      ENV.delete('MOCKSERVER_BINARY_CACHE')
      ENV.delete('MOCKSERVER_BINARY_BASE_URL')
    end
  end

  # -------------------------------------------------------------------
  # H7: Semver comparison unit tests
  # -------------------------------------------------------------------
  describe 'compare_versions (H7 semver)' do
    def cmp(a, b)
      described_class.send(:compare_versions, a, b)
    end

    it 'sorts numerically, not lexicographically' do
      expect(cmp('10.0.0', '9.0.0')).to eq(1)
      expect(cmp('2.10.0', '2.9.0')).to eq(1)
    end

    it 'treats equal versions as equal' do
      expect(cmp('7.0.0', '7.0.0')).to eq(0)
    end

    it 'compares patch versions' do
      expect(cmp('7.0.1', '7.0.0')).to eq(1)
      expect(cmp('7.0.0', '7.0.1')).to eq(-1)
    end

    # H7: Pre-release MUST sort LOWER than its release
    it 'sorts pre-release LOWER than its release (H7)' do
      expect(cmp('7.0.0-SNAPSHOT', '7.0.0')).to eq(-1)
      expect(cmp('7.0.0-alpha', '7.0.0')).to eq(-1)
      expect(cmp('7.0.0-beta.1', '7.0.0')).to eq(-1)
      expect(cmp('7.0.0-rc1', '7.0.0')).to eq(-1)
    end

    it 'sorts release HIGHER than its pre-release' do
      expect(cmp('7.0.0', '7.0.0-SNAPSHOT')).to eq(1)
      expect(cmp('7.0.0', '7.0.0-alpha')).to eq(1)
    end

    it 'sorts pre-release tags lexicographically' do
      expect(cmp('7.0.0-alpha', '7.0.0-beta')).to eq(-1)
      expect(cmp('7.0.0-beta', '7.0.0-alpha')).to eq(1)
    end

    it 'sorts pre-release numeric segments numerically' do
      expect(cmp('7.0.0-rc.2', '7.0.0-rc.10')).to eq(-1)
      expect(cmp('7.0.0-rc.10', '7.0.0-rc.2')).to eq(1)
    end

    it 'sorts numeric pre-release segment lower than string (semver 11.4.3)' do
      expect(cmp('7.0.0-1', '7.0.0-alpha')).to eq(-1)
      expect(cmp('7.0.0-alpha', '7.0.0-1')).to eq(1)
    end

    it 'handles SNAPSHOT specifically per H7 requirement' do
      expect(cmp('7.0.0-SNAPSHOT', '7.0.0')).to eq(-1)
      expect(cmp('7.0.0', '7.0.0-SNAPSHOT')).to eq(1)
      expect(cmp('7.0.0-SNAPSHOT', '6.9.9')).to eq(1) # different core
    end
  end

  # -------------------------------------------------------------------
  # H4: Windows .bat spawn
  # -------------------------------------------------------------------
  describe '.start Windows .bat handling' do
    let(:tmpdir) { Dir.mktmpdir('mockserver-win-test') }
    let(:log) { Logger.new(File::NULL) }

    before do
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('mswin32')
      allow(RbConfig::CONFIG).to receive(:[]).with('host_cpu').and_return('x86_64')
    end

    after do
      FileUtils.rm_rf(tmpdir)
    end

    it 'spawns via cmd.exe /c on Windows' do
      launcher_path = File.join(tmpdir, 'mockserver.bat')
      File.write(launcher_path, '@echo off')

      # Stub ensure_launcher to return our fake bat file
      allow(described_class).to receive(:ensure_launcher).and_return(launcher_path)

      # Intercept Process.spawn to verify it uses cmd.exe
      expect(Process).to receive(:spawn).with(
        'cmd.exe', '/c', launcher_path,
        '-serverPort', '1080',
        hash_including(:out, :err)
      ).and_return(12345)

      handle = described_class.start(port: 1080, log: log)
      expect(handle.pid).to eq(12345)
    end
  end

  # -------------------------------------------------------------------
  # H5: Unix spawn with stdout/stderr draining
  # -------------------------------------------------------------------
  describe '.start Unix spawn' do
    let(:tmpdir) { Dir.mktmpdir('mockserver-unix-test') }
    let(:log) { Logger.new(File::NULL) }

    before do
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('linux-gnu')
      allow(RbConfig::CONFIG).to receive(:[]).with('host_cpu').and_return('x86_64')
    end

    after do
      FileUtils.rm_rf(tmpdir)
    end

    it 'spawns with stdout/stderr redirected to avoid pipe deadlock' do
      launcher_path = File.join(tmpdir, 'mockserver')
      File.write(launcher_path, "#!/bin/sh\necho mock\n")
      File.chmod(0o755, launcher_path)

      allow(described_class).to receive(:ensure_launcher).and_return(launcher_path)

      expect(Process).to receive(:spawn).with(
        launcher_path,
        '-serverPort', '1080',
        hash_including(:out, :err)
      ).and_return(12345)

      handle = described_class.start(port: 1080, log: log)
      expect(handle.pid).to eq(12345)
    end
  end

  # -------------------------------------------------------------------
  # ServerHandle
  # -------------------------------------------------------------------
  describe MockServer::BinaryLauncher::ServerHandle do
    describe '#stop' do
      it 'terminates the process' do
        # Spawn a long-running sleep process to test termination
        pid = Process.spawn('sleep', '300')
        handle = described_class.new(pid: pid, port: 9999, launcher: '/fake')

        expect(handle.running?).to be true
        handle.stop(timeout: 2)
        expect(handle.running?).to be false
        expect(handle.pid).to be_nil
      end

      it 'is safe when process is already gone' do
        pid = Process.spawn('true')
        Process.waitpid(pid) # let it finish
        handle = described_class.new(pid: pid, port: 9999, launcher: '/fake')

        expect { handle.stop }.not_to raise_error
      end

      it 'is safe when pid is nil' do
        handle = described_class.new(pid: nil, port: 9999, launcher: '/fake')
        expect { handle.stop }.not_to raise_error
      end
    end

    describe '#running?' do
      it 'returns false when pid is nil' do
        handle = described_class.new(pid: nil, port: 9999, launcher: '/fake')
        expect(handle.running?).to be false
      end

      it 'returns true for a live process' do
        pid = Process.spawn('sleep', '300')
        handle = described_class.new(pid: pid, port: 9999, launcher: '/fake')
        expect(handle.running?).to be true
      ensure
        Process.kill('KILL', pid) rescue nil
        Process.waitpid(pid) rescue nil
      end

      it 'returns false for a nonexistent process (Errno::ESRCH)' do
        # Use a PID that almost certainly does not exist
        handle = described_class.new(pid: 2_000_000_000, port: 9999, launcher: '/fake')
        expect(handle.running?).to be false
      end
    end
  end

  # -------------------------------------------------------------------
  # Integration test (skipped unless a real bundle is downloadable)
  # -------------------------------------------------------------------
  describe 'live download integration', :integration do
    before do
      skip 'Live binary download test — set MOCKSERVER_TEST_LIVE_DOWNLOAD=1 to enable' unless ENV['MOCKSERVER_TEST_LIVE_DOWNLOAD']
    end

    it 'downloads and extracts the real binary for the current version' do
      tmpdir = Dir.mktmpdir('mockserver-live-test')
      ENV['MOCKSERVER_BINARY_CACHE'] = tmpdir
      log = Logger.new($stdout, level: Logger::INFO)

      launcher = described_class.ensure_launcher(log: log)
      expect(File.exist?(launcher)).to be true
      expect(File.size(launcher)).to be > 0
    ensure
      ENV.delete('MOCKSERVER_BINARY_CACHE')
      FileUtils.rm_rf(tmpdir) if tmpdir
    end
  end

  # -------------------------------------------------------------------
  # Version defaulting
  # -------------------------------------------------------------------
  describe 'version defaulting' do
    it 'defaults to MockServer::VERSION' do
      expect(MockServer::VERSION).not_to be_nil
      expect(MockServer::VERSION).not_to be_empty

      # Verify that ensure_launcher would use the right version by
      # checking the bundle name it would construct
      allow(RbConfig::CONFIG).to receive(:[]).and_call_original
      allow(RbConfig::CONFIG).to receive(:[]).with('host_os').and_return('linux-gnu')
      allow(RbConfig::CONFIG).to receive(:[]).with('host_cpu').and_return('x86_64')

      meta = described_class.bundle_base_name(MockServer::VERSION)
      expect(meta[:name]).to include(MockServer::VERSION)
    end
  end
end
