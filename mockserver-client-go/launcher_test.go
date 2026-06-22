package mockserver

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

// --- Platform/Architecture Resolution Tests ---

func TestResolvePlatform_Linux_AMD64(t *testing.T) {
	p, err := resolvePlatformFor("linux", "amd64")
	if err != nil {
		t.Fatal(err)
	}
	if p.OS != "linux" {
		t.Errorf("expected OS linux, got %s", p.OS)
	}
	if p.Arch != "x86_64" {
		t.Errorf("expected Arch x86_64, got %s", p.Arch)
	}
	if p.Ext != "tar.gz" {
		t.Errorf("expected Ext tar.gz, got %s", p.Ext)
	}
}

func TestResolvePlatform_Linux_ARM64(t *testing.T) {
	p, err := resolvePlatformFor("linux", "arm64")
	if err != nil {
		t.Fatal(err)
	}
	if p.OS != "linux" || p.Arch != "aarch64" || p.Ext != "tar.gz" {
		t.Errorf("unexpected: %+v", p)
	}
}

func TestResolvePlatform_Darwin_AMD64(t *testing.T) {
	p, err := resolvePlatformFor("darwin", "amd64")
	if err != nil {
		t.Fatal(err)
	}
	if p.OS != "darwin" || p.Arch != "x86_64" || p.Ext != "tar.gz" {
		t.Errorf("unexpected: %+v", p)
	}
}

func TestResolvePlatform_Darwin_ARM64(t *testing.T) {
	p, err := resolvePlatformFor("darwin", "arm64")
	if err != nil {
		t.Fatal(err)
	}
	if p.OS != "darwin" || p.Arch != "aarch64" || p.Ext != "tar.gz" {
		t.Errorf("unexpected: %+v", p)
	}
}

func TestResolvePlatform_Windows_AMD64(t *testing.T) {
	p, err := resolvePlatformFor("windows", "amd64")
	if err != nil {
		t.Fatal(err)
	}
	if p.OS != "windows" || p.Arch != "x86_64" || p.Ext != "zip" {
		t.Errorf("unexpected: %+v", p)
	}
}

func TestResolvePlatform_Windows_ARM64(t *testing.T) {
	p, err := resolvePlatformFor("windows", "arm64")
	if err != nil {
		t.Fatal(err)
	}
	if p.OS != "windows" || p.Arch != "aarch64" || p.Ext != "zip" {
		t.Errorf("unexpected: %+v", p)
	}
}

func TestResolvePlatform_UnsupportedOS(t *testing.T) {
	_, err := resolvePlatformFor("freebsd", "amd64")
	if err == nil {
		t.Fatal("expected error for unsupported OS")
	}
	if !strings.Contains(err.Error(), "unsupported platform") {
		t.Errorf("unexpected error: %v", err)
	}
}

func TestResolvePlatform_UnsupportedArch(t *testing.T) {
	_, err := resolvePlatformFor("linux", "386")
	if err == nil {
		t.Fatal("expected error for unsupported arch")
	}
	if !strings.Contains(err.Error(), "unsupported architecture") {
		t.Errorf("unexpected error: %v", err)
	}
}

// --- Version Validation Tests (H1) ---

func TestValidateVersion_Valid(t *testing.T) {
	valids := []string{
		"7.0.0",
		"6.1.0",
		"7.0.1",
		"1.0.0-beta.1",
		"1.0.0-rc.1",
		"10.20.300",
		"1.0.0-test",
	}
	for _, v := range valids {
		if err := validateVersion(v); err != nil {
			t.Errorf("validateVersion(%q) returned unexpected error: %v", v, err)
		}
	}
}

func TestValidateVersion_Invalid(t *testing.T) {
	invalids := []string{
		"",
		"abc",
		"7.0",
		"../../../etc/passwd",
		"7.0.0/../../etc",
		"7.0.0\\..\\etc",
		"7.0.0; rm -rf /",
		"7.0.0\n7.0.1",
		".7.0.0",
		"v7.0.0",
	}
	for _, v := range invalids {
		if err := validateVersion(v); err == nil {
			t.Errorf("validateVersion(%q) should have returned an error", v)
		}
	}
}

// --- Bundle Naming Tests ---

func TestBundleBaseName(t *testing.T) {
	tests := []struct {
		version  string
		platform PlatformInfo
		wantName string
		wantExt  string
	}{
		{"7.0.0", PlatformInfo{"linux", "x86_64", "tar.gz"}, "mockserver-7.0.0-linux-x86_64", "tar.gz"},
		{"7.0.0", PlatformInfo{"darwin", "aarch64", "tar.gz"}, "mockserver-7.0.0-darwin-aarch64", "tar.gz"},
		{"6.1.0", PlatformInfo{"windows", "x86_64", "zip"}, "mockserver-6.1.0-windows-x86_64", "zip"},
		{"7.0.1", PlatformInfo{"linux", "aarch64", "tar.gz"}, "mockserver-7.0.1-linux-aarch64", "tar.gz"},
	}

	for _, tt := range tests {
		name, ext, err := bundleBaseNameFor(tt.version, tt.platform)
		if err != nil {
			t.Errorf("bundleBaseNameFor(%s, %+v) error: %v", tt.version, tt.platform, err)
			continue
		}
		if name != tt.wantName {
			t.Errorf("bundleBaseNameFor(%s, %+v) name = %s, want %s", tt.version, tt.platform, name, tt.wantName)
		}
		if ext != tt.wantExt {
			t.Errorf("bundleBaseNameFor(%s, %+v) ext = %s, want %s", tt.version, tt.platform, ext, tt.wantExt)
		}
	}
}

// --- Cache Path Resolution Tests ---

func TestCacheDir_CustomEnv(t *testing.T) {
	t.Setenv("MOCKSERVER_BINARY_CACHE", "/custom/cache/path")
	got := CacheDir()
	if got != "/custom/cache/path" {
		t.Errorf("expected /custom/cache/path, got %s", got)
	}
}

func TestCacheDir_XDGCache(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("XDG_CACHE_HOME test not applicable on Windows")
	}
	t.Setenv("MOCKSERVER_BINARY_CACHE", "")
	t.Setenv("XDG_CACHE_HOME", "/xdg/cache")
	got := CacheDir()
	want := filepath.Join("/xdg/cache", "mockserver", "binaries")
	if got != want {
		t.Errorf("expected %s, got %s", want, got)
	}
}

func TestCacheDir_DefaultUnix(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Unix default test not applicable on Windows")
	}
	t.Setenv("MOCKSERVER_BINARY_CACHE", "")
	t.Setenv("XDG_CACHE_HOME", "")
	got := CacheDir()
	home, _ := os.UserHomeDir()
	want := filepath.Join(home, ".cache", "mockserver", "binaries")
	if got != want {
		t.Errorf("expected %s, got %s", want, got)
	}
}

// --- Asset URL Tests ---

func TestAssetURL_Default(t *testing.T) {
	t.Setenv("MOCKSERVER_BINARY_BASE_URL", "")
	url := AssetURL("7.0.0", "mockserver-7.0.0-darwin-aarch64.tar.gz")
	want := "https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-7.0.0/mockserver-7.0.0-darwin-aarch64.tar.gz"
	if url != want {
		t.Errorf("expected %s, got %s", want, url)
	}
}

func TestAssetURL_CustomBase(t *testing.T) {
	t.Setenv("MOCKSERVER_BINARY_BASE_URL", "https://mirror.example.com/assets/")
	url := AssetURL("7.0.0", "mockserver-7.0.0-linux-x86_64.tar.gz")
	want := "https://mirror.example.com/assets/mockserver-7.0.0-linux-x86_64.tar.gz"
	if url != want {
		t.Errorf("expected %s, got %s", want, url)
	}
}

func TestAssetURL_CustomBaseNoTrailingSlash(t *testing.T) {
	t.Setenv("MOCKSERVER_BINARY_BASE_URL", "https://mirror.example.com/assets")
	url := AssetURL("7.0.0", "file.tar.gz")
	want := "https://mirror.example.com/assets/file.tar.gz"
	if url != want {
		t.Errorf("expected %s, got %s", want, url)
	}
}

// --- SNAPSHOT vs Release URL selection ---

func TestIsSnapshot(t *testing.T) {
	tests := []struct {
		version string
		want    bool
	}{
		{"7.0.0", false},
		{"7.0.0-SNAPSHOT", true},
		{"7.0.0-snapshot", true},
		{"7.0.0-Snapshot", true},
		{"7.0.0-beta.1", false},
		{"7.0.0-rc.1", false},
		{"7.1.0-SNAPSHOT", true},
	}
	for _, tt := range tests {
		got := isSnapshot(tt.version)
		if got != tt.want {
			t.Errorf("isSnapshot(%q) = %v, want %v", tt.version, got, tt.want)
		}
	}
}

func TestAssetURL_SnapshotUsesCDN(t *testing.T) {
	t.Setenv("MOCKSERVER_BINARY_BASE_URL", "")
	url := AssetURL("7.1.0-SNAPSHOT", "mockserver-7.1.0-SNAPSHOT-darwin-aarch64.tar.gz")
	want := "https://downloads.mock-server.com/mockserver-7.1.0-SNAPSHOT/mockserver-7.1.0-SNAPSHOT-darwin-aarch64.tar.gz"
	if url != want {
		t.Errorf("expected %s, got %s", want, url)
	}
}

func TestAssetURL_ReleaseUsesGitHub(t *testing.T) {
	t.Setenv("MOCKSERVER_BINARY_BASE_URL", "")
	url := AssetURL("7.0.0", "mockserver-7.0.0-darwin-aarch64.tar.gz")
	want := "https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-7.0.0/mockserver-7.0.0-darwin-aarch64.tar.gz"
	if url != want {
		t.Errorf("expected %s, got %s", want, url)
	}
}

func TestAssetURL_EnvOverrideWinsOverSnapshot(t *testing.T) {
	t.Setenv("MOCKSERVER_BINARY_BASE_URL", "https://custom-mirror.example.com/bins")
	url := AssetURL("7.1.0-SNAPSHOT", "mockserver-7.1.0-SNAPSHOT-linux-x86_64.tar.gz")
	want := "https://custom-mirror.example.com/bins/mockserver-7.1.0-SNAPSHOT-linux-x86_64.tar.gz"
	if url != want {
		t.Errorf("expected %s, got %s", want, url)
	}
}

// --- Launcher Path Tests ---

func TestLauncherPath_Unix(t *testing.T) {
	got := launcherPathForOS("/cache/7.0.0", "mockserver-7.0.0-darwin-aarch64", "darwin")
	want := filepath.Join("/cache/7.0.0", "mockserver-7.0.0-darwin-aarch64", "bin", "mockserver")
	if got != want {
		t.Errorf("expected %s, got %s", want, got)
	}
}

func TestLauncherPath_Windows(t *testing.T) {
	got := launcherPathForOS("C:\\cache\\7.0.0", "mockserver-7.0.0-windows-x86_64", "windows")
	want := filepath.Join("C:\\cache\\7.0.0", "mockserver-7.0.0-windows-x86_64", "bin", "mockserver.bat")
	if got != want {
		t.Errorf("expected %s, got %s", want, got)
	}
}

// --- SHA-256 Verification Tests ---

func TestVerifySHA256_Match(t *testing.T) {
	dir := t.TempDir()
	content := []byte("hello mockserver binary content")
	path := filepath.Join(dir, "test-archive.tar.gz")
	if err := os.WriteFile(path, content, 0o644); err != nil {
		t.Fatal(err)
	}

	// Compute expected hash
	h := sha256.Sum256(content)
	expected := hex.EncodeToString(h[:])

	if err := VerifySHA256(path, expected); err != nil {
		t.Errorf("expected checksum match, got error: %v", err)
	}
}

func TestVerifySHA256_Mismatch(t *testing.T) {
	dir := t.TempDir()
	content := []byte("hello mockserver binary content")
	path := filepath.Join(dir, "test-archive.tar.gz")
	if err := os.WriteFile(path, content, 0o644); err != nil {
		t.Fatal(err)
	}

	err := VerifySHA256(path, "0000000000000000000000000000000000000000000000000000000000000000")
	if err == nil {
		t.Fatal("expected checksum mismatch error, got nil")
	}
	if !strings.Contains(err.Error(), "checksum mismatch") {
		t.Errorf("unexpected error message: %v", err)
	}
}

func TestVerifySHA256_MissingFile(t *testing.T) {
	err := VerifySHA256("/nonexistent/file", "abc123")
	if err == nil {
		t.Fatal("expected error for missing file, got nil")
	}
}

// --- SKIP_BINARY_DOWNLOAD Tests ---

func TestEnsureBinary_SkipDownload(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("MOCKSERVER_BINARY_CACHE", dir)
	t.Setenv("MOCKSERVER_SKIP_BINARY_DOWNLOAD", "true")
	t.Setenv("MOCKSERVER_BINARY_BASE_URL", "")

	_, err := EnsureBinary("99.99.99", nil)
	if err == nil {
		t.Fatal("expected error when MOCKSERVER_SKIP_BINARY_DOWNLOAD is set")
	}
	if !strings.Contains(err.Error(), "MOCKSERVER_SKIP_BINARY_DOWNLOAD") {
		t.Errorf("unexpected error: %v", err)
	}
}

// --- Cache Reuse Test ---

func TestEnsureBinary_CachedLauncher(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("MOCKSERVER_BINARY_CACHE", dir)
	t.Setenv("MOCKSERVER_SKIP_BINARY_DOWNLOAD", "true")

	// Create a fake cached launcher
	p, err := ResolvePlatform()
	if err != nil {
		t.Fatal(err)
	}
	bundleName, _, _ := bundleBaseNameFor("99.99.99", p)
	launcherDir := filepath.Join(dir, "99.99.99", bundleName, "bin")
	if err := os.MkdirAll(launcherDir, 0o755); err != nil {
		t.Fatal(err)
	}
	launcherFile := filepath.Join(launcherDir, "mockserver")
	if runtime.GOOS == "windows" {
		launcherFile = filepath.Join(launcherDir, "mockserver.bat")
	}
	if err := os.WriteFile(launcherFile, []byte("#!/bin/sh\necho mock"), 0o755); err != nil {
		t.Fatal(err)
	}

	// Even with SKIP set, a cached binary should be returned
	got, err := EnsureBinary("99.99.99", nil)
	if err != nil {
		t.Fatalf("expected cached binary to be found, got error: %v", err)
	}
	if got != launcherFile {
		t.Errorf("expected %s, got %s", launcherFile, got)
	}
}

// --- Version Validation in EnsureBinary (H1) ---

func TestEnsureBinary_RejectsInvalidVersion(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("MOCKSERVER_BINARY_CACHE", dir)
	t.Setenv("MOCKSERVER_SKIP_BINARY_DOWNLOAD", "")

	invalids := []string{
		"../../../etc",
		"7.0.0/../../etc",
		"not-a-version",
		"",
	}
	for _, v := range invalids {
		_, err := EnsureBinary(v, nil)
		if err == nil {
			t.Errorf("EnsureBinary(%q) should have returned an error", v)
		}
	}
}

// --- Download + Checksum Verification (local HTTP server) ---

// createTestArchive builds a proper gzip-compressed tar archive containing a
// fake launcher binary at <bundleName>/bin/<launcherName>. It uses exec.Command
// with explicit args (COR-02 fix: no whitespace-splitting of paths).
func createTestArchive(t *testing.T, dir, bundleName, launcherName, archivePath string) {
	t.Helper()
	innerDir := filepath.Join(dir, bundleName, "bin")
	if err := os.MkdirAll(innerDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(innerDir, launcherName), []byte("#!/bin/sh\necho mock"), 0o755); err != nil {
		t.Fatal(err)
	}

	// COR-05 fix: use -czf to create a proper gzip archive (not uncompressed tar)
	cmd := exec.Command("tar", "-czf", archivePath, "-C", dir, bundleName)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		t.Fatalf("create test archive: %v", err)
	}
}

func TestEnsureBinary_DownloadAndVerify(t *testing.T) {
	dir := t.TempDir()
	cacheDir := t.TempDir()

	p, err := ResolvePlatform()
	if err != nil {
		t.Fatal(err)
	}
	bundleName, ext, _ := bundleBaseNameFor("1.0.0-test", p)
	archiveFile := bundleName + "." + ext

	launcherName := "mockserver"
	if runtime.GOOS == "windows" {
		launcherName = "mockserver.bat"
	}

	archivePath := filepath.Join(dir, archiveFile)
	createTestArchive(t, dir, bundleName, launcherName, archivePath)

	// Compute its SHA-256
	archiveData, err := os.ReadFile(archivePath)
	if err != nil {
		t.Fatal(err)
	}
	h := sha256.Sum256(archiveData)
	shaHex := hex.EncodeToString(h[:])

	// Serve via local HTTP server
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, ".sha256") {
			fmt.Fprintf(w, "%s  %s\n", shaHex, archiveFile)
			return
		}
		if strings.HasSuffix(r.URL.Path, "."+ext) || strings.HasSuffix(r.URL.Path, ".tar.gz") {
			w.Write(archiveData)
			return
		}
		w.WriteHeader(404)
	}))
	defer ts.Close()

	t.Setenv("MOCKSERVER_BINARY_CACHE", cacheDir)
	t.Setenv("MOCKSERVER_BINARY_BASE_URL", ts.URL)
	t.Setenv("MOCKSERVER_SKIP_BINARY_DOWNLOAD", "")

	launcher, err := EnsureBinary("1.0.0-test", &EnsureOptions{
		Log: func(format string, args ...interface{}) {
			t.Logf(format, args...)
		},
	})
	if err != nil {
		t.Fatalf("EnsureBinary failed: %v", err)
	}

	expectedLauncher := launcherPathForOS(
		filepath.Join(cacheDir, "1.0.0-test"),
		bundleName,
		runtime.GOOS,
	)
	if launcher != expectedLauncher {
		t.Errorf("expected launcher at %s, got %s", expectedLauncher, launcher)
	}

	// Verify the launcher file exists
	info, err := os.Stat(launcher)
	if err != nil {
		t.Fatalf("launcher stat failed: %v", err)
	}
	if info.Size() == 0 {
		t.Error("launcher file is empty")
	}
}

func TestEnsureBinary_ChecksumMismatch(t *testing.T) {
	cacheDir := t.TempDir()

	p, err := ResolvePlatform()
	if err != nil {
		t.Fatal(err)
	}
	bundleName, ext, _ := bundleBaseNameFor("1.0.0-bad", p)
	archiveFile := bundleName + "." + ext

	// Serve a fake archive with a wrong checksum
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, ".sha256") {
			fmt.Fprintf(w, "0000000000000000000000000000000000000000000000000000000000000000  %s\n", archiveFile)
			return
		}
		w.Write([]byte("fake archive content"))
	}))
	defer ts.Close()

	t.Setenv("MOCKSERVER_BINARY_CACHE", cacheDir)
	t.Setenv("MOCKSERVER_BINARY_BASE_URL", ts.URL)
	t.Setenv("MOCKSERVER_SKIP_BINARY_DOWNLOAD", "")

	_, err = EnsureBinary("1.0.0-bad", nil)
	if err == nil {
		t.Fatal("expected checksum mismatch error")
	}
	if !strings.Contains(err.Error(), "checksum mismatch") {
		t.Errorf("unexpected error: %v", err)
	}

	// Verify .part file was cleaned up
	partFile := filepath.Join(cacheDir, "1.0.0-bad", archiveFile+".part")
	if _, err := os.Stat(partFile); !os.IsNotExist(err) {
		t.Errorf("expected .part file to be deleted, but it exists: %s", partFile)
	}

	// H3: Verify .sha256 file was also cleaned up
	shaFile := filepath.Join(cacheDir, "1.0.0-bad", archiveFile+".sha256")
	if _, err := os.Stat(shaFile); !os.IsNotExist(err) {
		t.Errorf("expected .sha256 file to be deleted, but it exists: %s", shaFile)
	}
}

func TestEnsureBinary_EmptyChecksum(t *testing.T) {
	cacheDir := t.TempDir()

	// Serve a fake archive with an empty checksum file
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, ".sha256") {
			w.Write([]byte("   \n"))
			return
		}
		w.Write([]byte("fake archive content"))
	}))
	defer ts.Close()

	t.Setenv("MOCKSERVER_BINARY_CACHE", cacheDir)
	t.Setenv("MOCKSERVER_BINARY_BASE_URL", ts.URL)
	t.Setenv("MOCKSERVER_SKIP_BINARY_DOWNLOAD", "")

	_, err := EnsureBinary("1.0.0-empty", nil)
	if err == nil {
		t.Fatal("expected error for empty checksum")
	}
	if !strings.Contains(err.Error(), "empty or unparseable") {
		t.Errorf("unexpected error: %v", err)
	}
}

// --- H2: SkipChecksum is unexported (enforced by compiler) ---
// The SkipChecksum field was renamed to skipChecksum (unexported). External
// packages cannot set it, so production callers always verify SHA-256.
// This test ensures the production path always verifies checksums by attempting
// a download with a wrong checksum — it must fail.
func TestEnsureBinary_ChecksumAlwaysEnforced(t *testing.T) {
	cacheDir := t.TempDir()

	// Serve archive + bad checksum
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, ".sha256") {
			w.Write([]byte("badbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbadbad  file\n"))
			return
		}
		w.Write([]byte("some content"))
	}))
	defer ts.Close()

	t.Setenv("MOCKSERVER_BINARY_CACHE", cacheDir)
	t.Setenv("MOCKSERVER_BINARY_BASE_URL", ts.URL)
	t.Setenv("MOCKSERVER_SKIP_BINARY_DOWNLOAD", "")

	// Pass a vanilla EnsureOptions with no way to skip checksum (field is unexported)
	_, err := EnsureBinary("2.0.0", &EnsureOptions{})
	if err == nil {
		t.Fatal("expected checksum error; SHA-256 verification must not be bypassable")
	}
	if !strings.Contains(err.Error(), "checksum mismatch") {
		t.Errorf("expected checksum mismatch, got: %v", err)
	}
}

// TestEnsureBinary_NoBundle404 verifies that a 404 on the archive download
// produces a clear, actionable error (not an opaque HTTP error) naming the
// version, that no bundle exists, and the Docker / Maven Central alternatives.
func TestEnsureBinary_NoBundle404(t *testing.T) {
	cacheDir := t.TempDir()

	// Serve 404 for every asset — simulates a release tag with no bundle.
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(404)
	}))
	defer ts.Close()

	t.Setenv("MOCKSERVER_BINARY_CACHE", cacheDir)
	t.Setenv("MOCKSERVER_BINARY_BASE_URL", ts.URL)
	t.Setenv("MOCKSERVER_SKIP_BINARY_DOWNLOAD", "")

	_, err := EnsureBinary("9.9.9", &EnsureOptions{})
	if err == nil {
		t.Fatal("expected an error for a missing release bundle")
	}
	msg := err.Error()
	for _, want := range []string{
		"9.9.9",
		"no MockServer release bundle",
		"docker run mockserver/mockserver:mockserver-9.9.9",
		"org.mock-server:mockserver-netty:9.9.9",
	} {
		if !strings.Contains(msg, want) {
			t.Errorf("error message missing %q; got: %v", want, msg)
		}
	}
}

// --- Semver Comparison Tests (H7) ---

func TestCompareSemver(t *testing.T) {
	tests := []struct {
		a, b string
		want int
	}{
		{"1.0.0", "2.0.0", -1},
		{"2.0.0", "1.0.0", 1},
		{"1.0.0", "1.0.0", 0},
		{"1.0.0", "1.0.1", -1},
		{"1.0.10", "1.0.9", 1},     // numeric, not lexicographic
		{"1.0.2", "1.0.10", -1},    // numeric, not lexicographic
		{"2.0.0", "10.0.0", -1},    // numeric, not lexicographic
		{"10.0.0", "2.0.0", 1},     // numeric, not lexicographic
		{"1.0.0-beta", "1.0.0", -1},      // pre-release < release when numeric cores equal
		{"1.0.0", "1.0.0-beta", 1},       // release > pre-release
		{"7.0.0", "7.0.0-SNAPSHOT", 1},   // release > SNAPSHOT
		{"7.0.0-SNAPSHOT", "7.0.0", -1},  // SNAPSHOT < release
		{"1.0.0-alpha", "1.0.0-beta", -1}, // among pre-releases, compare lexicographically
		{"1.0.0-beta", "1.0.0-alpha", 1},  // among pre-releases, compare lexicographically
		{"1.0.0-rc.1", "1.0.0-rc.1", 0},  // identical pre-releases
		{"7.0.0", "6.1.0", 1},
		{"6.1.0", "7.0.0", -1},
	}
	for _, tt := range tests {
		got := compareSemver(tt.a, tt.b)
		if got != tt.want {
			t.Errorf("compareSemver(%q, %q) = %d, want %d", tt.a, tt.b, got, tt.want)
		}
	}
}

// --- Pruning Tests ---

func TestPruneOldVersions_RemovesOldDirs_SemverOrder(t *testing.T) {
	base := t.TempDir()

	// Create several version directories — H7: order by semver, not mtime/lexicographic
	versions := []string{"1.0.0", "2.0.0", "10.0.0", "3.0.0"}
	for _, v := range versions {
		if err := os.MkdirAll(filepath.Join(base, v), 0o755); err != nil {
			t.Fatal(err)
		}
	}

	// Prune, keeping "10.0.0" as current
	if err := PruneOldVersions(base, "10.0.0"); err != nil {
		t.Fatalf("PruneOldVersions: %v", err)
	}

	entries, err := os.ReadDir(base)
	if err != nil {
		t.Fatal(err)
	}

	remaining := make(map[string]bool)
	for _, e := range entries {
		remaining[e.Name()] = true
	}

	// Current version must always be kept
	if !remaining["10.0.0"] {
		t.Error("current version 10.0.0 was pruned")
	}

	// At most maxPreviousVersions (1) older version should remain
	otherCount := 0
	for name := range remaining {
		if name != "10.0.0" {
			otherCount++
		}
	}
	if otherCount > maxPreviousVersions {
		t.Errorf("expected at most %d previous versions, got %d: %v", maxPreviousVersions, otherCount, remaining)
	}

	// H7: The kept previous version must be the newest by semver (3.0.0), not
	// lexicographically largest ("3.0.0" < "2.0.0" lex but 3.0.0 > 2.0.0 semver)
	if otherCount == 1 && !remaining["3.0.0"] {
		t.Errorf("expected newest previous version 3.0.0 to be kept, got %v", remaining)
	}
}

func TestPruneOldVersions_ReleaseKeptOverPrerelease(t *testing.T) {
	base := t.TempDir()

	// Create versions: 6.0.0, 7.0.0-SNAPSHOT, 7.0.0 (release)
	// When current is "7.0.0", the pruner keeps current + 1 previous.
	// The kept previous must be "7.0.0-SNAPSHOT" (next newest by semver)
	// rather than "6.0.0" — but more importantly, the RELEASE "7.0.0"
	// must NEVER be pruned in favour of its SNAPSHOT.
	for _, v := range []string{"6.0.0", "7.0.0-SNAPSHOT", "7.0.0"} {
		if err := os.MkdirAll(filepath.Join(base, v), 0o755); err != nil {
			t.Fatal(err)
		}
	}

	if err := PruneOldVersions(base, "7.0.0"); err != nil {
		t.Fatalf("PruneOldVersions: %v", err)
	}

	entries, err := os.ReadDir(base)
	if err != nil {
		t.Fatal(err)
	}

	remaining := make(map[string]bool)
	for _, e := range entries {
		remaining[e.Name()] = true
	}

	// Current version (release) must always survive
	if !remaining["7.0.0"] {
		t.Error("current version 7.0.0 was pruned")
	}

	// With maxPreviousVersions=1, one previous should survive — the next
	// newest is 7.0.0-SNAPSHOT (which sorts just below 7.0.0 release)
	if !remaining["7.0.0-SNAPSHOT"] {
		t.Error("expected 7.0.0-SNAPSHOT to be kept as the newest previous version")
	}

	// 6.0.0 should be pruned (only 1 previous allowed)
	if remaining["6.0.0"] {
		t.Error("expected 6.0.0 to be pruned")
	}
}

func TestPruneOldVersions_KeepsCurrentOnly(t *testing.T) {
	base := t.TempDir()

	// Only the current version directory
	if err := os.MkdirAll(filepath.Join(base, "5.0.0"), 0o755); err != nil {
		t.Fatal(err)
	}

	if err := PruneOldVersions(base, "5.0.0"); err != nil {
		t.Fatalf("PruneOldVersions: %v", err)
	}

	entries, err := os.ReadDir(base)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != "5.0.0" {
		t.Errorf("expected only 5.0.0, got %v", entries)
	}
}

func TestPruneOldVersions_CleansPartFiles(t *testing.T) {
	base := t.TempDir()

	// Create current version dir and some .part files
	if err := os.MkdirAll(filepath.Join(base, "5.0.0"), 0o755); err != nil {
		t.Fatal(err)
	}
	partFile := filepath.Join(base, "leftover.tar.gz.part")
	if err := os.WriteFile(partFile, []byte("partial"), 0o644); err != nil {
		t.Fatal(err)
	}

	if err := PruneOldVersions(base, "5.0.0"); err != nil {
		t.Fatalf("PruneOldVersions: %v", err)
	}

	if _, err := os.Stat(partFile); !os.IsNotExist(err) {
		t.Errorf("expected .part file to be removed: %s", partFile)
	}
}

func TestPruneOldVersions_CleansSha256Files(t *testing.T) {
	base := t.TempDir()

	// Create current version dir and a leftover .sha256 file
	if err := os.MkdirAll(filepath.Join(base, "5.0.0"), 0o755); err != nil {
		t.Fatal(err)
	}
	shaFile := filepath.Join(base, "leftover.tar.gz.sha256")
	if err := os.WriteFile(shaFile, []byte("abcdef"), 0o644); err != nil {
		t.Fatal(err)
	}

	if err := PruneOldVersions(base, "5.0.0"); err != nil {
		t.Fatalf("PruneOldVersions: %v", err)
	}

	if _, err := os.Stat(shaFile); !os.IsNotExist(err) {
		t.Errorf("expected .sha256 file to be removed: %s", shaFile)
	}
}

func TestPruneOldVersions_EmptyDir(t *testing.T) {
	base := t.TempDir()
	// Should not error on an empty directory
	if err := PruneOldVersions(base, "1.0.0"); err != nil {
		t.Fatalf("PruneOldVersions on empty dir: %v", err)
	}
}

func TestPruneOldVersions_NonexistentDir(t *testing.T) {
	// Should not error on a nonexistent directory
	if err := PruneOldVersions("/nonexistent/path/abc123", "1.0.0"); err != nil {
		t.Fatalf("PruneOldVersions on nonexistent dir: %v", err)
	}
}

// COR-06: pruneOldVersions should handle cacheBase with redundant separators
func TestPruneOldVersions_RedundantSeparators(t *testing.T) {
	base := t.TempDir()

	// Create version directories
	for _, v := range []string{"1.0.0", "2.0.0", "3.0.0", "4.0.0"} {
		if err := os.MkdirAll(filepath.Join(base, v), 0o755); err != nil {
			t.Fatal(err)
		}
	}

	// Pass cacheBase with redundant separators
	redundantBase := base + string(filepath.Separator) + string(filepath.Separator)
	if err := PruneOldVersions(redundantBase, "4.0.0"); err != nil {
		t.Fatalf("PruneOldVersions with redundant separators: %v", err)
	}

	entries, err := os.ReadDir(base)
	if err != nil {
		t.Fatal(err)
	}

	remaining := make(map[string]bool)
	for _, e := range entries {
		remaining[e.Name()] = true
	}

	// Should have pruned down to current + 1 previous
	if !remaining["4.0.0"] {
		t.Error("current version 4.0.0 was pruned")
	}
	otherCount := 0
	for name := range remaining {
		if name != "4.0.0" {
			otherCount++
		}
	}
	if otherCount > maxPreviousVersions {
		t.Errorf("expected at most %d previous, got %d: %v", maxPreviousVersions, otherCount, remaining)
	}
}

// --- Version Constant Test ---

func TestVersionConstant(t *testing.T) {
	if Version == "" {
		t.Error("Version constant is empty")
	}
	// Should look like a version number
	if !strings.Contains(Version, ".") {
		t.Errorf("Version %q does not look like a version number", Version)
	}
	// Version constant must pass validation
	if err := validateVersion(Version); err != nil {
		t.Errorf("Version constant %q fails validation: %v", Version, err)
	}
}

// --- ServerHandle Tests ---

func TestServerHandle_Stop_NilCmd(t *testing.T) {
	h := &ServerHandle{}
	if err := h.Stop(); err != nil {
		t.Errorf("Stop on nil cmd should not error: %v", err)
	}
}

func TestServerHandle_Wait_NilCmd(t *testing.T) {
	h := &ServerHandle{}
	if err := h.Wait(); err != nil {
		t.Errorf("Wait on nil cmd should not error: %v", err)
	}
}

// --- Integration Test (skipped when no real binary is available) ---

func TestIntegration_EnsureBinary_RealDownload(t *testing.T) {
	if os.Getenv("MOCKSERVER_INTEGRATION_TEST") == "" {
		t.Skip("Set MOCKSERVER_INTEGRATION_TEST=1 to run real download integration test")
	}

	cacheDir := t.TempDir()
	t.Setenv("MOCKSERVER_BINARY_CACHE", cacheDir)
	t.Setenv("MOCKSERVER_BINARY_BASE_URL", "")
	t.Setenv("MOCKSERVER_SKIP_BINARY_DOWNLOAD", "")

	launcher, err := EnsureBinary(Version, &EnsureOptions{
		Log: func(format string, args ...interface{}) {
			t.Logf(format, args...)
		},
	})
	if err != nil {
		t.Fatalf("EnsureBinary failed: %v", err)
	}

	info, err := os.Stat(launcher)
	if err != nil {
		t.Fatalf("launcher not found: %v", err)
	}
	if info.Size() == 0 {
		t.Error("launcher is empty")
	}
	t.Logf("Downloaded launcher: %s (%d bytes)", launcher, info.Size())
}
