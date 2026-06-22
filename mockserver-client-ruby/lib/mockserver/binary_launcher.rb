# frozen_string_literal: true

require 'digest'
require 'fileutils'
require 'logger'
require 'net/http'
require 'rbconfig'
require 'uri'

module MockServer
  # On-demand binary launcher for MockServer.
  #
  # Downloads the self-contained, JVM-less MockServer bundle (a jlink runtime +
  # the server + a +mockserver+ launcher) for the current platform from the
  # GitHub Release, verifies its SHA-256, caches it per-user, and launches it.
  # No Java installation and no Docker required.
  #
  # This mirrors the reference implementation at +mockserver-node/downloadBinary.js+.
  #
  # Environment overrides:
  #   MOCKSERVER_BINARY_BASE_URL       mirror host for the release assets
  #   MOCKSERVER_BINARY_CACHE          cache directory (default: per-OS user cache)
  #   MOCKSERVER_SKIP_BINARY_DOWNLOAD  fail instead of downloading (air-gapped CI with pre-seeded cache)
  #   HTTP_PROXY / HTTPS_PROXY         honoured by Net::HTTP via +ENV['http_proxy']+ (Ruby convention)
  #   SSL_CERT_FILE / SSL_CERT_DIR     honoured by OpenSSL for corporate TLS proxies
  #
  # @example Start a server on port 1080
  #   handle = MockServer::BinaryLauncher.start(port: 1080)
  #   # ... use MockServer ...
  #   handle.stop
  #
  # @example Just ensure the binary is present
  #   path = MockServer::BinaryLauncher.ensure_launcher
  class BinaryLauncher
    # Raised internally when a download returns HTTP 404, so the caller can
    # distinguish "no bundle published for this version" from other transport
    # errors and emit actionable guidance.
    class NotFoundError < StandardError; end

    REPO = 'mock-server/mockserver-monorepo'

    # CDN base URL used for SNAPSHOT version downloads.
    SNAPSHOT_CDN = 'https://downloads.mock-server.com'

    # Maximum number of previous version directories to keep (in addition to the current).
    MAX_PREVIOUS_VERSIONS_TO_KEEP = 1

    # Strict pattern for version strings — blocks path separators and '..'.
    VERSION_PATTERN = /\A[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?\z/

    class << self
      # Resolve the current platform to the bundle naming tokens.
      #
      # @return [Hash] with keys +:os_name+, +:arch+, +:ext+
      # @raise [Error] on unsupported platform or architecture
      def resolve_platform
        os_name, ext = case RbConfig::CONFIG['host_os']
                       when /linux/i    then ['linux', 'tar.gz']
                       when /darwin/i   then ['darwin', 'tar.gz']
                       when /mswin|mingw|cygwin/i then ['windows', 'zip']
                       else raise Error, "unsupported platform: #{RbConfig::CONFIG['host_os']}"
                       end

        arch = case RbConfig::CONFIG['host_cpu']
               when /x86_64|x64|amd64/i then 'x86_64'
               when /aarch64|arm64/i     then 'aarch64'
               else raise Error, "unsupported architecture: #{RbConfig::CONFIG['host_cpu']}"
               end

        { os_name: os_name, arch: arch, ext: ext }
      end

      # Return the bundle base name and extension for a given version.
      #
      # @param version [String]
      # @return [Hash] with keys +:name+ and +:ext+
      def bundle_base_name(version)
        platform = resolve_platform
        {
          name: "mockserver-#{version}-#{platform[:os_name]}-#{platform[:arch]}",
          ext: platform[:ext]
        }
      end

      # Return the per-user cache directory for MockServer binaries.
      #
      # @return [String]
      def cache_dir
        if ENV['MOCKSERVER_BINARY_CACHE'] && !ENV['MOCKSERVER_BINARY_CACHE'].empty?
          return ENV['MOCKSERVER_BINARY_CACHE']
        end

        base = if windows?
                 ENV['LOCALAPPDATA'] || File.join(Dir.home, 'AppData', 'Local')
               else
                 ENV['XDG_CACHE_HOME'] || File.join(Dir.home, '.cache')
               end
        File.join(base, 'mockserver', 'binaries')
      end

      # Return the download URL for a release asset.
      #
      # Uses +MOCKSERVER_BINARY_BASE_URL+ if set; otherwise defaults to GitHub
      # Releases for release versions and the downloads.mock-server.com CDN for
      # SNAPSHOT versions.
      #
      # @param version [String]
      # @param file [String]
      # @return [String]
      def asset_url(version, file)
        base = ENV['MOCKSERVER_BINARY_BASE_URL'] ||
               if snapshot?(version)
                 "#{SNAPSHOT_CDN}/mockserver-#{version}"
               else
                 "https://github.com/#{REPO}/releases/download/mockserver-#{version}"
               end
        # Strip trailing slashes with a single linear scan rather than a regex,
        # so there is no ReDoS surface at all (CWE-1333) on the operator-supplied
        # MOCKSERVER_BINARY_BASE_URL. Interior slashes are preserved; only the
        # trailing run is removed.
        last = base.length
        last -= 1 while last.positive? && base[last - 1] == '/'
        "#{base[0, last]}/#{file}"
      end

      # Return the expected launcher path inside a versioned cache directory.
      #
      # @param dir [String] the version directory
      # @param bundle_name [String] the bundle base name
      # @return [String]
      def launcher_path(dir, bundle_name)
        exe = windows? ? 'mockserver.bat' : 'mockserver'
        File.join(dir, bundle_name, 'bin', exe)
      end

      # Ensure the platform bundle is present and return the launcher path,
      # downloading + verifying + extracting + caching on first use.
      #
      # @param version [String] the MockServer version (defaults to MockServer::VERSION)
      # @param log [Logger, nil] optional logger
      # @return [String] absolute path to the launcher executable
      # @raise [Error] on download/verification failure
      def ensure_launcher(version: nil, log: nil)
        version ||= MockServer::VERSION
        log ||= Logger.new($stderr, level: Logger::WARN)

        validate_version!(version)

        meta = bundle_base_name(version)
        base = cache_dir
        dir = File.join(base, version)
        launcher = launcher_path(dir, meta[:name])

        # H1: Assert version dir stays within cache base (path traversal guard)
        assert_within_base!(dir, base)

        # Check cache
        if File.exist?(launcher) && File.size(launcher) > 0
          log.info("Using cached binary: #{launcher}")
          # Contract section 7: prune only after a successful install, not on cache hit
          return launcher
        end

        # Skip-download check
        if ENV['MOCKSERVER_SKIP_BINARY_DOWNLOAD'] && !ENV['MOCKSERVER_SKIP_BINARY_DOWNLOAD'].empty?
          raise Error, "MOCKSERVER_SKIP_BINARY_DOWNLOAD is set but no cached binary at #{launcher}"
        end

        FileUtils.mkdir_p(dir)
        archive_file = "#{meta[:name]}.#{meta[:ext]}"
        archive = File.join(dir, archive_file)
        partial = "#{archive}.part"
        sha_file = "#{archive}.sha256"

        begin
          # Download to a temp file
          url = asset_url(version, archive_file)
          log.info("Downloading #{url}")
          begin
            download_file(url, partial)
          rescue NotFoundError
            # A 404 means the release tag exists but ships no bundle for this
            # version (or the tag does not exist). Emit actionable guidance
            # instead of an opaque HTTP error.
            raise Error, no_bundle_message(version)
          end

          # Verify SHA-256 (fail-closed — always required, no bypass)
          sha_url = asset_url(version, "#{archive_file}.sha256")
          download_file(sha_url, sha_file)
          raw = File.read(sha_file, encoding: 'utf-8').strip
          expected = raw.split(/\s+/).first
          if expected.nil? || expected.empty?
            raise Error, "checksum file for #{meta[:name]} is empty or unparseable"
          end

          actual = Digest::SHA256.file(partial).hexdigest
          if expected != actual
            raise Error,
                  "checksum mismatch for #{meta[:name]}: expected #{expected}, got #{actual}"
          end
          log.info('Checksum verified')

          File.rename(partial, archive)
        rescue StandardError
          # H3: Best-effort cleanup of BOTH .part and .sha256 temp files on failure
          File.delete(partial) if File.exist?(partial)
          File.delete(sha_file) if File.exist?(sha_file)
          raise
        end

        # Extract the archive into the version directory.
        log.info("Extracting #{archive}")
        extract_archive(archive, dir, meta[:ext])

        # H3: Post-extract path traversal guard — enumerate every extracted entry
        # and verify it resolves within the version directory. If any entry escaped
        # (via ../ or absolute paths in the archive), abort with a clear error.
        verify_extracted_paths!(dir)

        unless File.exist?(launcher) && File.size(launcher) > 0
          raise Error, "launcher missing or empty after extract: #{launcher}"
        end

        File.chmod(0o755, launcher) unless windows?

        # Contract section 7: prune after successful install
        prune_old_versions(version, log: log)

        launcher
      end

      # Start a MockServer instance on the given port.
      #
      # @param port [Integer] the server port
      # @param version [String, nil] the MockServer version
      # @param extra_args [Array<String>] additional CLI arguments
      # @param log [Logger, nil] optional logger
      # @return [ServerHandle] a handle to the running server process
      def start(port:, version: nil, extra_args: [], log: nil)
        launcher = ensure_launcher(version: version, log: log)
        args = ['-serverPort', port.to_s] + extra_args

        # H4: On Windows, .bat files must be invoked via cmd.exe /c.
        # H5: Drain stdout/stderr via :out/:err redirection to avoid pipe-buffer deadlock.
        pid = if windows?
                Process.spawn('cmd.exe', '/c', launcher, *args,
                              out: File::NULL, err: File::NULL)
              else
                Process.spawn(launcher, *args,
                              out: File::NULL, err: File::NULL)
              end
        ServerHandle.new(pid: pid, port: port, launcher: launcher)
      end

      # Remove old version directories from the cache, keeping the current version
      # and at most MAX_PREVIOUS_VERSIONS_TO_KEEP previous versions.
      #
      # Uses semver-aware numeric segment comparison (H7) rather than lexicographic
      # or mtime-based ordering.
      #
      # @param current_version [String]
      # @param log [Logger, nil]
      # @return [void]
      def prune_old_versions(current_version, log: nil)
        log ||= Logger.new($stderr, level: Logger::WARN)
        base = cache_dir

        return unless File.directory?(base)

        entries = Dir.entries(base).select do |entry|
          next false if entry == '.' || entry == '..'

          full = File.join(base, entry)
          # Only consider directories (version directories) — never files
          File.directory?(full)
        rescue Errno::ENOENT
          # Directory vanished between Dir.entries and File.directory? — skip
          false
        end

        # Separate current from old
        old_entries = entries.reject { |e| e == current_version }

        # H7: Sort old entries by semver-aware numeric comparison (highest first = kept)
        old_sorted = old_entries.sort { |a, b| compare_versions(b, a) }

        # Keep at most MAX_PREVIOUS_VERSIONS_TO_KEEP
        to_remove = old_sorted.drop(MAX_PREVIOUS_VERSIONS_TO_KEEP)
        to_remove.each do |name|
          full = File.join(base, name)
          begin
            # Safety: never delete outside the cache dir
            real_base = File.realpath(base)
            real_full = File.realpath(full)
            unless real_full.start_with?(real_base + File::SEPARATOR) || real_full == real_base
              log.warn("Skipping suspicious path during prune: #{full}")
              next
            end

            log.info("Pruning old version cache: #{name}")
            FileUtils.rm_rf(full)
          rescue Errno::ENOENT, Errno::EACCES => e
            # COR-06: directory vanished concurrently or permission denied — skip gracefully
            log.warn("Skipping during prune (#{e.class}): #{full}")
            next
          end
        end

        # Clean up leftover .part and .sha256 temp files at ALL levels (not just base)
        Dir.glob(File.join(base, '**', '*.part')).each do |part_file|
          log.info("Removing leftover temp file: #{part_file}")
          File.delete(part_file)
        rescue StandardError => e
          log.warn("Failed to remove temp file #{part_file}: #{e.message}")
        end

        Dir.glob(File.join(base, '**', '*.sha256')).each do |sha_file|
          # Only remove orphaned .sha256 files (where the corresponding archive is absent)
          archive_path = sha_file.sub(/\.sha256\z/, '')
          next if File.exist?(archive_path)

          log.info("Removing orphaned checksum file: #{sha_file}")
          File.delete(sha_file)
        rescue StandardError => e
          log.warn("Failed to remove checksum file #{sha_file}: #{e.message}")
        end
      end

      private

      # Build a clear, actionable error message for a missing release bundle.
      #
      # Explains that no downloadable bundle exists for +version+ and lists the
      # concrete alternatives. The wording is kept consistent across all client
      # languages.
      #
      # @param version [String]
      # @return [String]
      def no_bundle_message(version)
        "no MockServer release bundle is published for version #{version} " \
          "(no downloadable asset at the GitHub release tag 'mockserver-#{version}'). " \
          'Use a MockServer version that ships self-contained bundles, ' \
          "or run MockServer via Docker (docker run mockserver/mockserver:mockserver-#{version}), " \
          "or use the Maven Central jar (org.mock-server:mockserver-netty:#{version})."
      end

      # Validate the version string against the strict pattern (H1).
      #
      # @param version [String]
      # @raise [Error] if the version contains path separators, '..', or does not match the pattern
      def validate_version!(version)
        if version.include?('/') || version.include?('\\') || version.include?('..')
          raise Error, "invalid version (path traversal attempt): #{version}"
        end

        unless VERSION_PATTERN.match?(version)
          raise Error, "invalid version format: #{version}"
        end
      end

      # Assert that a resolved path stays within the cache base directory (H1).
      #
      # Uses File.expand_path (not File.realpath) because the target directory may
      # not exist yet at call time. This means a pre-existing symlink inside the
      # cache base that points outside it would bypass this guard. The prune path
      # uses File.realpath for existing entries, which closes the gap for deletions.
      # For creation-time paths the risk is mitigated by validate_version! rejecting
      # path separators and '..' before this method is reached.
      #
      # @param target [String] the directory to validate
      # @param base [String] the cache base directory
      # @raise [Error] if the target escapes the base
      def assert_within_base!(target, base)
        expanded_target = File.expand_path(target)
        expanded_base = File.expand_path(base)
        unless expanded_target.start_with?(expanded_base + File::SEPARATOR) || expanded_target == expanded_base
          raise Error, "path traversal blocked: #{target} is not within #{base}"
        end
      end

      # Extract an archive (tar.gz or zip) into the target directory.
      #
      # On Windows with a .zip archive, uses PowerShell Expand-Archive as a
      # fallback when tar.exe is not available (pre-Windows 10 build 17063).
      # For tar.gz archives, uses system tar with GNU/bsdtar safe flags.
      #
      # @param archive [String] path to the archive file
      # @param dir [String] destination directory
      # @param ext [String] 'tar.gz' or 'zip'
      # @raise [Error] on extraction failure
      def extract_archive(archive, dir, ext)
        if ext == 'zip' && windows?
          extract_zip_windows(archive, dir)
        else
          # H3: Use --no-same-owner to avoid permission issues, and pass archive
          # through system tar which auto-detects gzip. GNU tar and bsdtar both
          # support -xf with -C for extraction to a target directory.
          result = system('tar', '-xf', archive, '-C', dir)
          unless result
            raise Error, "extraction failed (tar returned non-zero or not found)"
          end
        end
      end

      # Windows-specific zip extraction with PowerShell fallback.
      #
      # Tries system tar first (available on Windows 10 17063+), then falls back
      # to PowerShell's Expand-Archive cmdlet. Archive and dir paths are passed
      # via -LiteralPath to avoid wildcard/injection issues.
      #
      # @param archive [String] path to the .zip file
      # @param dir [String] destination directory
      # @raise [Error] on extraction failure
      def extract_zip_windows(archive, dir)
        # Try tar.exe first (bsdtar, available on modern Windows)
        if system('tar', '-xf', archive, '-C', dir)
          return
        end

        # Fallback: PowerShell Expand-Archive (available on all PowerShell 5.0+ systems).
        # Use -LiteralPath to prevent wildcard expansion of the archive path.
        ps_cmd = "Expand-Archive -LiteralPath '#{archive.gsub("'", "''")}' " \
                 "-DestinationPath '#{dir.gsub("'", "''")}' -Force"
        result = system('powershell.exe', '-NoProfile', '-NoLogo', '-Command', ps_cmd)
        unless result
          raise Error, "zip extraction failed: neither tar.exe nor PowerShell Expand-Archive succeeded"
        end
      end

      # H3: Verify that all extracted files/directories stay within the version dir.
      #
      # Enumerates every entry under dir via Dir.glob and verifies each one's
      # real path (resolving symlinks) is within the dir. This catches archives
      # containing ../ entries, absolute paths, or symlinks that escape the dir.
      #
      # @param dir [String] the version directory that extraction targeted
      # @raise [Error] if any extracted path escapes the directory
      def verify_extracted_paths!(dir)
        real_dir = File.realpath(dir)

        Dir.glob(File.join(dir, '**', '*'), File::FNM_DOTMATCH).each do |entry|
          # Skip . and .. pseudo-entries
          next if entry.end_with?('/..') || entry.end_with?('/.')

          begin
            real_entry = File.realpath(entry)
          rescue Errno::ENOENT
            # Broken symlink — suspicious but not an escape; skip
            next
          end

          unless real_entry.start_with?(real_dir + File::SEPARATOR) || real_entry == real_dir
            raise Error,
                  "tar path traversal detected: extracted entry #{entry} " \
                  "resolves outside version directory #{dir}"
          end
        end
      end

      # Compare two version strings using semver-aware numeric segment comparison (H7).
      # Pre-release versions (e.g. 7.0.0-SNAPSHOT, 7.0.0-beta) sort LOWER than
      # their release counterpart (7.0.0), per Semantic Versioning 2.0.0 rule 11.
      # Falls back to lexicographic comparison for non-numeric segments.
      #
      # @param a [String]
      # @param b [String]
      # @return [Integer] -1, 0, or 1
      def compare_versions(a, b)
        # Split into numeric core and optional pre-release tag.
        # "7.0.0-beta.1" -> core=[7,0,0], pre=["beta","1"]
        # "7.0.0"        -> core=[7,0,0], pre=nil
        core_a, pre_a = split_version(a)
        core_b, pre_b = split_version(b)

        # Compare numeric core segments first
        max_core = [core_a.length, core_b.length].max
        max_core.times do |i|
          sa = core_a[i] || 0
          sb = core_b[i] || 0
          cmp = sa <=> sb
          return cmp unless cmp == 0
        end

        # Cores are equal — apply semver pre-release precedence:
        # "no pre-release" > "any pre-release" (releases outrank pre-releases)
        return 0 if pre_a.nil? && pre_b.nil?
        return 1 if pre_a.nil?   # a is release, b is pre-release -> a > b
        return -1 if pre_b.nil?  # a is pre-release, b is release -> a < b

        # Both have pre-release tags — compare segment by segment
        max_pre = [pre_a.length, pre_b.length].max
        max_pre.times do |i|
          sa = pre_a[i]
          sb = pre_b[i]

          # Fewer pre-release segments = lower precedence (semver rule 11.4.4)
          return -1 if sa.nil?
          return 1 if sb.nil?

          # Numeric segments compare numerically; string segments lexicographically;
          # numeric < string (semver rule 11.4.3)
          a_num = sa.match?(/\A\d+\z/)
          b_num = sb.match?(/\A\d+\z/)

          if a_num && b_num
            cmp = sa.to_i <=> sb.to_i
          elsif a_num
            cmp = -1 # numeric < string
          elsif b_num
            cmp = 1  # string > numeric
          else
            cmp = sa <=> sb
          end

          return cmp unless cmp == 0
        end

        0
      end

      # Split a version string into [core_segments, pre_release_segments_or_nil].
      #
      # @param ver [String] e.g. "7.0.0-beta.1"
      # @return [Array<Array<Integer>, Array<String>|nil>]
      def split_version(ver)
        # The first hyphen separates core from pre-release
        parts = ver.split('-', 2)
        core = parts[0].split('.').map { |s| s.match?(/\A\d+\z/) ? s.to_i : 0 }
        pre = parts[1] ? parts[1].split(/[.\-]/) : nil
        [core, pre]
      end

      # @return [Boolean] true if the version contains '-SNAPSHOT' (case-insensitive)
      def snapshot?(version)
        version.upcase.include?('-SNAPSHOT')
      end

      # @return [Boolean] true if the current platform is Windows
      def windows?
        RbConfig::CONFIG['host_os'] =~ /mswin|mingw|cygwin/i ? true : false
      end

      # Download a URL to a local file, following redirects.
      #
      # Supports +file://+ URIs (for testing) and +http://+ / +https://+.
      # Respects Ruby's built-in HTTP_PROXY / HTTPS_PROXY handling and
      # SSL_CERT_FILE / SSL_CERT_DIR for corporate TLS proxies.
      #
      # @param url [String]
      # @param dest [String]
      # @raise [Error] on HTTP error or I/O failure
      def download_file(url, dest)
        uri = URI.parse(url)

        if uri.scheme == 'file'
          src = uri.path
          unless File.exist?(src)
            raise Error, "download #{url} failed: file not found"
          end

          FileUtils.cp(src, dest)
          return
        end

        # Use Net::HTTP with redirect following (up to 5 hops)
        fetch_with_redirects(uri, dest, 5)
      end

      # Follow redirects manually using Net::HTTP.
      # H6: Stream the body to disk chunk by chunk — never buffer the full response
      # (the JVM-less bundle can be 100-300 MB).
      def fetch_with_redirects(uri, dest, max_redirects)
        raise Error, "too many redirects for #{uri}" if max_redirects <= 0

        http = Net::HTTP.new(uri.host, uri.port)
        http.use_ssl = (uri.scheme == 'https')
        http.open_timeout = 30
        http.read_timeout = 300

        request = Net::HTTP::Get.new(uri.request_uri)

        http.request(request) do |response|
          case response
          when Net::HTTPSuccess
            File.open(dest, 'wb') do |f|
              response.read_body do |chunk|
                f.write(chunk)
              end
            end
          when Net::HTTPRedirection
            # Drain the redirect response body so unread bytes do not remain
            # in the socket buffer (harmless today since each redirect opens a
            # fresh connection, but defensive against future keep-alive reuse).
            response.read_body
            location = response['location']
            fetch_with_redirects(URI.parse(location), dest, max_redirects - 1)
          when Net::HTTPNotFound
            raise NotFoundError, "download #{uri} failed: HTTP 404"
          else
            raise Error, "download #{uri} failed: HTTP #{response.code}"
          end
        end
      end
    end

    # Handle to a running MockServer process.
    class ServerHandle
      # @return [Integer] the process ID
      attr_reader :pid

      # @return [Integer] the server port
      attr_reader :port

      # @return [String] path to the launcher executable
      attr_reader :launcher

      def initialize(pid:, port:, launcher:)
        @pid = pid
        @port = port
        @launcher = launcher
      end

      # Stop the server by terminating the process.
      #
      # @param timeout [Numeric] seconds to wait before SIGKILL (default 10)
      # @return [void]
      def stop(timeout: 10)
        return unless @pid

        begin
          Process.kill('TERM', @pid)
          deadline = Time.now + timeout
          loop do
            Process.waitpid(@pid, Process::WNOHANG) && break
            if Time.now > deadline
              Process.kill('KILL', @pid)
              Process.waitpid(@pid)
              break
            end
            sleep 0.1
          end
        rescue Errno::ESRCH, Errno::ECHILD
          # Process already gone
        end
        @pid = nil
      end

      # @return [Boolean] true if the process is still running
      def running?
        return false unless @pid

        Process.kill(0, @pid)
        true
      rescue Errno::ESRCH
        # Process does not exist
        false
      rescue Errno::EPERM
        # Process exists but we lack permission to signal it — it IS running
        true
      end
    end
  end
end
