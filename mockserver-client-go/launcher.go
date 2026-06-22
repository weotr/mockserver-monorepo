package mockserver

import (
	"crypto/sha256"
	_ "embed"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"time"
)

// versionEmbed is the raw content of the VERSION file, embedded at compile time.
// The release pipeline updates this file so the launcher version tracks the
// module version automatically — no hardcoded constant to forget.
//
//go:embed VERSION
var versionEmbed string

// Version is the MockServer version this client targets. It is used as the
// default version for binary downloads when no explicit version is provided.
// Derived from the embedded VERSION file (updated by the release pipeline).
var Version = strings.TrimSpace(versionEmbed)

// repo is the GitHub repository path for download URL construction.
const repo = "mock-server/mockserver-monorepo"

// maxPreviousVersions is the number of previous version directories kept after
// pruning. The current version is always kept; at most this many older versions
// are retained.
const maxPreviousVersions = 1

// versionPattern validates version strings. Accepts semver with optional
// pre-release/build segments (e.g. "7.0.0", "7.0.0-beta.1", "7.0.0-rc.1").
// Rejects path separators and ".." to block path traversal.
var versionPattern = regexp.MustCompile(`^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?$`)

// defaultHTTPTimeout is the timeout for HTTP downloads. Individual connect and
// TLS handshake timeouts are shorter to fail fast on unreachable hosts.
const defaultHTTPTimeout = 10 * time.Minute

// errNotFound is returned by downloadFile when the server responds with HTTP
// 404, so callers can distinguish "no bundle published for this version" from
// other transport errors and emit actionable guidance.
var errNotFound = errors.New("not found (HTTP 404)")

// noBundleMessage builds a clear, actionable error explaining that no
// downloadable release bundle exists for the requested version, and listing the
// concrete alternatives. The wording is kept consistent across all client
// languages.
func noBundleMessage(version string) string {
	return fmt.Sprintf(
		"mockserver: no MockServer release bundle is published for version %s "+
			"(no downloadable asset at the GitHub release tag 'mockserver-%s'). "+
			"Use a MockServer version that ships self-contained bundles, "+
			"or run MockServer via Docker (docker run mockserver/mockserver:mockserver-%s), "+
			"or use the Maven Central jar (org.mock-server:mockserver-netty:%s).",
		version, version, version, version,
	)
}

// PlatformInfo holds the resolved OS, architecture, and archive extension for
// the current platform.
type PlatformInfo struct {
	OS   string // "linux", "darwin", or "windows"
	Arch string // "x86_64" or "aarch64"
	Ext  string // "tar.gz" or "zip"
}

// ResolvePlatform maps the current runtime GOOS/GOARCH to the bundle naming
// tokens. It returns an error for unsupported platforms.
func ResolvePlatform() (PlatformInfo, error) {
	return resolvePlatformFor(runtime.GOOS, runtime.GOARCH)
}

// resolvePlatformFor is the internal, testable version that accepts explicit
// os/arch strings.
func resolvePlatformFor(goos, goarch string) (PlatformInfo, error) {
	var p PlatformInfo

	switch goos {
	case "linux":
		p.OS = "linux"
		p.Ext = "tar.gz"
	case "darwin":
		p.OS = "darwin"
		p.Ext = "tar.gz"
	case "windows":
		p.OS = "windows"
		p.Ext = "zip"
	default:
		return p, fmt.Errorf("mockserver: unsupported platform: %s", goos)
	}

	switch goarch {
	case "amd64":
		p.Arch = "x86_64"
	case "arm64":
		p.Arch = "aarch64"
	default:
		return p, fmt.Errorf("mockserver: unsupported architecture: %s", goarch)
	}

	return p, nil
}

// validateVersion checks that a version string is safe and well-formed.
// It rejects path separators and ".." to prevent path traversal attacks.
func validateVersion(version string) error {
	if !versionPattern.MatchString(version) {
		return fmt.Errorf("mockserver: invalid version %q: must match MAJOR.MINOR.PATCH[-prerelease]", version)
	}
	if strings.Contains(version, "..") || strings.ContainsAny(version, `/\`) {
		return fmt.Errorf("mockserver: invalid version %q: contains path separator or '..'", version)
	}
	return nil
}

// BundleBaseName returns the base name and extension for the binary archive.
//
// Example: BundleBaseName("7.0.0") with darwin/arm64 returns
// name="mockserver-7.0.0-darwin-aarch64", ext="tar.gz".
func BundleBaseName(version string) (name, ext string, err error) {
	p, err := ResolvePlatform()
	if err != nil {
		return "", "", err
	}
	return bundleBaseNameFor(version, p)
}

// bundleBaseNameFor is the internal, testable version.
func bundleBaseNameFor(version string, p PlatformInfo) (name, ext string, err error) {
	return "mockserver-" + version + "-" + p.OS + "-" + p.Arch, p.Ext, nil
}

// CacheDir returns the base cache directory for MockServer binaries.
//
// Resolution order:
//  1. MOCKSERVER_BINARY_CACHE environment variable
//  2. Windows: %LOCALAPPDATA% (fallback ~/AppData/Local) + /mockserver/binaries
//  3. Unix: $XDG_CACHE_HOME or ~/.cache + /mockserver/binaries
func CacheDir() string {
	if v := os.Getenv("MOCKSERVER_BINARY_CACHE"); v != "" {
		return v
	}
	var base string
	if runtime.GOOS == "windows" {
		base = os.Getenv("LOCALAPPDATA")
		if base == "" {
			home, _ := os.UserHomeDir()
			base = filepath.Join(home, "AppData", "Local")
		}
	} else {
		base = os.Getenv("XDG_CACHE_HOME")
		if base == "" {
			home, _ := os.UserHomeDir()
			base = filepath.Join(home, ".cache")
		}
	}
	return filepath.Join(base, "mockserver", "binaries")
}

// cacheDirForOS is the internal, testable version that accepts an explicit GOOS.
func cacheDirForOS(goos string) string {
	if v := os.Getenv("MOCKSERVER_BINARY_CACHE"); v != "" {
		return v
	}
	var base string
	if goos == "windows" {
		base = os.Getenv("LOCALAPPDATA")
		if base == "" {
			home, _ := os.UserHomeDir()
			base = filepath.Join(home, "AppData", "Local")
		}
	} else {
		base = os.Getenv("XDG_CACHE_HOME")
		if base == "" {
			home, _ := os.UserHomeDir()
			base = filepath.Join(home, ".cache")
		}
	}
	return filepath.Join(base, "mockserver", "binaries")
}

// snapshotCDN is the base URL for SNAPSHOT version downloads.
const snapshotCDN = "https://downloads.mock-server.com"

// isSnapshot returns true if the version string contains "-SNAPSHOT"
// (case-insensitive), indicating a pre-release snapshot build.
func isSnapshot(version string) bool {
	return strings.Contains(strings.ToUpper(version), "-SNAPSHOT")
}

// AssetURL returns the full download URL for an asset file.
//
// It uses MOCKSERVER_BINARY_BASE_URL if set, otherwise defaults to the GitHub
// release URL for release versions, or the downloads.mock-server.com CDN for
// SNAPSHOT versions.
func AssetURL(version, file string) string {
	base := os.Getenv("MOCKSERVER_BINARY_BASE_URL")
	if base == "" {
		if isSnapshot(version) {
			base = snapshotCDN + "/mockserver-" + version
		} else {
			base = "https://github.com/" + repo + "/releases/download/mockserver-" + version
		}
	}
	return strings.TrimRight(base, "/") + "/" + file
}

// LauncherPath returns the expected path to the launcher binary within a
// version-specific cache directory.
func LauncherPath(dir, bundleName string) string {
	bin := "mockserver"
	if runtime.GOOS == "windows" {
		bin = "mockserver.bat"
	}
	return filepath.Join(dir, bundleName, "bin", bin)
}

// launcherPathForOS is the internal, testable version.
func launcherPathForOS(dir, bundleName, goos string) string {
	bin := "mockserver"
	if goos == "windows" {
		bin = "mockserver.bat"
	}
	return filepath.Join(dir, bundleName, "bin", bin)
}

// LogFunc is a function that receives log messages during the ensure/download
// flow. Pass nil for silent operation.
type LogFunc func(format string, args ...interface{})

// EnsureOptions configures the EnsureBinary call.
type EnsureOptions struct {
	// Log receives progress messages. Nil means silent.
	Log LogFunc
	// skipChecksum, when true, disables SHA-256 verification. Default (false)
	// means checksums ARE required (fail-closed). This field is unexported so
	// that production callers always verify; only test code within this package
	// can set it.
	skipChecksum bool
	// HTTPClient overrides the default http.Client used for downloads.
	// This is primarily useful for testing.
	HTTPClient *http.Client
}

// EnsureBinary ensures that the MockServer binary for the given version is
// cached locally and returns the path to the launcher executable. If the binary
// is not cached, it downloads, verifies, and extracts it.
func EnsureBinary(version string, opts *EnsureOptions) (string, error) {
	if opts == nil {
		opts = &EnsureOptions{}
	}
	log := opts.Log
	if log == nil {
		log = func(string, ...interface{}) {}
	}

	// H1: validate version against strict pattern
	if err := validateVersion(version); err != nil {
		return "", err
	}

	p, err := ResolvePlatform()
	if err != nil {
		return "", err
	}

	bundleName, ext, _ := bundleBaseNameFor(version, p)
	base := CacheDir()
	cleanBase := filepath.Clean(base)
	dir := filepath.Join(cleanBase, version)

	// H1: verify verDir stays within cache base (block path traversal)
	cleanDir := filepath.Clean(dir)
	if !strings.HasPrefix(cleanDir, cleanBase+string(filepath.Separator)) && cleanDir != cleanBase {
		return "", fmt.Errorf("mockserver: version directory %q escapes cache base %q", cleanDir, cleanBase)
	}

	launcher := launcherPathForOS(dir, bundleName, runtime.GOOS)

	// Check cache
	if info, err := os.Stat(launcher); err == nil && info.Size() > 0 {
		log("Using cached binary: %s", launcher)
		if err := pruneOldVersions(cleanBase, version); err != nil {
			log("Warning: cache pruning failed: %v", err)
		}
		return launcher, nil
	}

	// Skip-download check
	if os.Getenv("MOCKSERVER_SKIP_BINARY_DOWNLOAD") != "" {
		return "", fmt.Errorf("mockserver: MOCKSERVER_SKIP_BINARY_DOWNLOAD is set but no cached binary at %s", launcher)
	}

	// Create version directory
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", fmt.Errorf("mockserver: create cache directory: %w", err)
	}

	archiveFile := bundleName + "." + ext
	archive := filepath.Join(dir, archiveFile)
	partial := archive + ".part"
	shaFile := archive + ".sha256"

	client := opts.HTTPClient
	if client == nil {
		// H6: set connect and read timeouts; do not hang indefinitely
		client = &http.Client{
			Timeout: defaultHTTPTimeout,
			Transport: &http.Transport{
				DialContext: (&net.Dialer{
					Timeout:   30 * time.Second,
					KeepAlive: 30 * time.Second,
				}).DialContext,
				TLSHandshakeTimeout:   30 * time.Second,
				ResponseHeaderTimeout: 60 * time.Second,
			},
		}
	}

	archiveURL := AssetURL(version, archiveFile)
	log("Downloading %s", archiveURL)
	if err := downloadFile(client, archiveURL, partial); err != nil {
		// H3: clean up both .part and .sha256 on any failure
		os.Remove(partial)
		os.Remove(shaFile)
		// A 404 means the release tag exists but ships no bundle for this
		// version (or the tag does not exist). Emit actionable guidance
		// instead of an opaque HTTP error.
		if errors.Is(err, errNotFound) {
			return "", errors.New(noBundleMessage(version))
		}
		return "", fmt.Errorf("mockserver: download archive: %w", err)
	}

	// Verify checksum (fail-closed). H2: skipChecksum is unexported, so
	// external/public callers always verify. Only in-package test code can
	// disable this.
	if !opts.skipChecksum {
		shaURL := AssetURL(version, archiveFile+".sha256")
		if err := downloadFile(client, shaURL, shaFile); err != nil {
			os.Remove(partial)
			os.Remove(shaFile)
			return "", fmt.Errorf("mockserver: download checksum: %w", err)
		}

		shaData, err := os.ReadFile(shaFile)
		if err != nil {
			os.Remove(partial)
			os.Remove(shaFile)
			return "", fmt.Errorf("mockserver: read checksum file: %w", err)
		}

		fields := strings.Fields(strings.TrimSpace(string(shaData)))
		if len(fields) == 0 || fields[0] == "" {
			os.Remove(partial)
			os.Remove(shaFile)
			return "", fmt.Errorf("mockserver: checksum file for %s is empty or unparseable", bundleName)
		}
		expected := fields[0]

		actual, err := fileSHA256(partial)
		if err != nil {
			os.Remove(partial)
			os.Remove(shaFile)
			return "", fmt.Errorf("mockserver: compute SHA-256: %w", err)
		}

		if expected != actual {
			os.Remove(partial)
			os.Remove(shaFile)
			return "", fmt.Errorf("mockserver: checksum mismatch for %s: expected %s, got %s", bundleName, expected, actual)
		}
		log("Checksum verified")
	}

	// Rename partial to final
	if err := os.Rename(partial, archive); err != nil {
		os.Remove(partial)
		os.Remove(shaFile)
		return "", fmt.Errorf("mockserver: rename archive: %w", err)
	}

	// Extract — H3: guard tar so it cannot write outside verDir by using
	// --no-same-owner and verifying the launcher lands inside the expected dir.
	log("Extracting %s", archive)
	cmd := exec.Command("tar", "-xf", archive, "-C", dir)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		return "", fmt.Errorf("mockserver: extraction failed: %w", err)
	}

	// Verify launcher exists and is within the cache directory (post-extract
	// path traversal guard).
	info, err := os.Stat(launcher)
	if err != nil || info.Size() == 0 {
		return "", fmt.Errorf("mockserver: launcher missing or empty after extract: %s", launcher)
	}

	cleanLauncher := filepath.Clean(launcher)
	if !strings.HasPrefix(cleanLauncher, cleanDir+string(filepath.Separator)) {
		return "", fmt.Errorf("mockserver: launcher path %q escapes version directory %q", cleanLauncher, cleanDir)
	}

	// Make executable on non-Windows
	if runtime.GOOS != "windows" {
		if err := os.Chmod(launcher, 0o755); err != nil {
			return "", fmt.Errorf("mockserver: chmod launcher: %w", err)
		}
	}

	// Prune old versions
	if err := pruneOldVersions(cleanBase, version); err != nil {
		log("Warning: cache pruning failed: %v", err)
	}

	return launcher, nil
}

// fileSHA256 computes the hex-encoded SHA-256 digest of a file.
func fileSHA256(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()

	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// downloadFile downloads a URL to a local file path. H6: the body is streamed
// to disk via io.Copy (no full-body buffering); the caller is responsible for
// providing a client with appropriate timeouts.
func downloadFile(client *http.Client, url, dest string) error {
	resp, err := client.Get(url)
	if err != nil {
		return fmt.Errorf("GET %s: %w", url, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		return fmt.Errorf("GET %s: %w", url, errNotFound)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("GET %s: HTTP %d", url, resp.StatusCode)
	}

	out, err := os.Create(dest)
	if err != nil {
		return fmt.Errorf("create %s: %w", dest, err)
	}
	defer out.Close()

	if _, err := io.Copy(out, resp.Body); err != nil {
		return fmt.Errorf("write %s: %w", dest, err)
	}
	return nil
}

// semverParts holds the parsed components of a semver version string.
type semverParts struct {
	nums       []int  // numeric segments of the core version (major, minor, patch)
	prerelease string // pre-release suffix (empty string means a stable release)
}

// parseSemver splits a version string "X.Y.Z[-prerelease]" into its numeric
// core and pre-release suffix. The pre-release suffix is preserved so that
// compareSemver can rank releases above their pre-releases.
func parseSemver(v string) semverParts {
	base := v
	pre := ""
	if idx := strings.IndexByte(v, '-'); idx >= 0 {
		base = v[:idx]
		pre = v[idx+1:]
	}
	parts := strings.Split(base, ".")
	nums := make([]int, 0, len(parts))
	for _, p := range parts {
		n, err := strconv.Atoi(p)
		if err != nil {
			// Non-numeric segment; treat as 0 for ordering purposes
			nums = append(nums, 0)
		} else {
			nums = append(nums, n)
		}
	}
	return semverParts{nums: nums, prerelease: pre}
}

// compareSemver returns -1, 0, or 1 comparing a and b by semver rules (H7).
// It first compares the numeric major.minor.patch segments numerically. When
// the numeric cores are equal, a release (no pre-release suffix) is GREATER
// than a pre-release (e.g. 7.0.0 > 7.0.0-SNAPSHOT). Among two pre-releases,
// the pre-release identifiers are compared lexicographically.
func compareSemver(a, b string) int {
	av := parseSemver(a)
	bv := parseSemver(b)
	maxLen := len(av.nums)
	if len(bv.nums) > maxLen {
		maxLen = len(bv.nums)
	}
	for i := 0; i < maxLen; i++ {
		var ai, bi int
		if i < len(av.nums) {
			ai = av.nums[i]
		}
		if i < len(bv.nums) {
			bi = bv.nums[i]
		}
		if ai < bi {
			return -1
		}
		if ai > bi {
			return 1
		}
	}
	// Numeric cores are equal — apply pre-release rules per semver spec:
	// a release (empty pre-release) has HIGHER precedence than a pre-release.
	aPre := av.prerelease
	bPre := bv.prerelease
	if aPre == "" && bPre == "" {
		return 0 // both are releases
	}
	if aPre == "" {
		return 1 // a is release, b is pre-release → a > b
	}
	if bPre == "" {
		return -1 // a is pre-release, b is release → a < b
	}
	// Both have pre-release: compare lexicographically
	if aPre < bPre {
		return -1
	}
	if aPre > bPre {
		return 1
	}
	return 0
}

// pruneOldVersions removes old version directories from the cache, keeping the
// current version and at most maxPreviousVersions older versions (by semver
// order, H7). It also removes leftover .part and .sha256 temp files. It is
// safe to call concurrently and never deletes files outside the cache directory.
func pruneOldVersions(cacheBase, currentVersion string) error {
	// COR-06: normalise cacheBase so HasPrefix works even with redundant separators
	cacheBase = filepath.Clean(cacheBase)

	entries, err := os.ReadDir(cacheBase)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return err
	}

	// Collect version directories (entries that are directories and not the
	// current version). H7: we sort by semver, not mtime.
	var others []string

	for _, e := range entries {
		name := e.Name()

		// Clean up any leftover .part or .sha256 files at the top level (H3)
		if strings.HasSuffix(name, ".part") || strings.HasSuffix(name, ".sha256") {
			target := filepath.Join(cacheBase, name)
			if strings.HasPrefix(filepath.Clean(target), cacheBase+string(filepath.Separator)) {
				os.Remove(target)
			}
			continue
		}

		if !e.IsDir() {
			continue
		}

		if name == currentVersion {
			continue
		}

		others = append(others, name)
	}

	// H7: Sort by semver descending (newest first)
	sort.Slice(others, func(i, j int) bool {
		return compareSemver(others[i], others[j]) > 0
	})

	// Remove all but the allowed number of previous versions
	for i := maxPreviousVersions; i < len(others); i++ {
		target := filepath.Join(cacheBase, others[i])
		// Safety: never delete outside the cache directory
		cleanTarget := filepath.Clean(target)
		if !strings.HasPrefix(cleanTarget, cacheBase+string(filepath.Separator)) {
			continue
		}
		os.RemoveAll(cleanTarget)
	}

	return nil
}

// ServerHandle represents a running MockServer process.
type ServerHandle struct {
	Port     int
	Launcher string
	cmd      *exec.Cmd
}

// Stop terminates the MockServer process.
func (h *ServerHandle) Stop() error {
	if h.cmd == nil || h.cmd.Process == nil {
		return nil
	}
	return h.cmd.Process.Kill()
}

// Wait waits for the MockServer process to exit and returns its error (if any).
func (h *ServerHandle) Wait() error {
	if h.cmd == nil {
		return nil
	}
	return h.cmd.Wait()
}

// StartServer downloads the MockServer binary (if needed) and starts it on the
// given port. It returns a ServerHandle that can be used to stop the server.
//
// On Windows, the .bat launcher is invoked via "cmd /c" as required by the OS
// (H4). The launcher path and arguments are passed as separate tokens to
// exec.Command, avoiding unquoted shell expansion of untrusted values.
func StartServer(port int, version string, opts *EnsureOptions) (*ServerHandle, error) {
	if version == "" {
		version = Version
	}

	launcher, err := EnsureBinary(version, opts)
	if err != nil {
		return nil, err
	}

	args := []string{"-serverPort", fmt.Sprintf("%d", port)}

	// H4: On Windows, .bat files cannot be executed directly via CreateProcess;
	// they must be invoked via cmd.exe. We pass the launcher path and all args
	// as separate arguments to cmd /c, so each is individually quoted by Go's
	// os/exec (safe against injection).
	var cmd *exec.Cmd
	if runtime.GOOS == "windows" {
		cmdArgs := append([]string{"/c", launcher}, args...)
		cmd = exec.Command("cmd", cmdArgs...)
	} else {
		cmd = exec.Command(launcher, args...)
	}

	// H5: pipe stdout/stderr through the parent process to avoid pipe-buffer
	// deadlock. os.Stdout/os.Stderr are inherited by the child, which drains
	// them synchronously in the OS.
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("mockserver: start server: %w", err)
	}

	return &ServerHandle{
		Port:     port,
		Launcher: launcher,
		cmd:      cmd,
	}, nil
}

// VerifySHA256 verifies that a file's SHA-256 matches the expected hex digest.
// Exported for testing and external use.
func VerifySHA256(path, expected string) error {
	actual, err := fileSHA256(path)
	if err != nil {
		return err
	}
	if actual != expected {
		return fmt.Errorf("mockserver: checksum mismatch: expected %s, got %s", expected, actual)
	}
	return nil
}

// PruneOldVersions is the exported wrapper around pruneOldVersions, exposed for
// testing. It removes stale version directories from the cache base directory,
// keeping at most the current version plus maxPreviousVersions older ones.
// Versions are sorted using semver-aware numeric comparison (H7).
func PruneOldVersions(cacheBase, currentVersion string) error {
	return pruneOldVersions(cacheBase, currentVersion)
}
