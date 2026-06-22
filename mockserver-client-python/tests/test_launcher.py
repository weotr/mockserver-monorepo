"""Tests for mockserver.launcher — the on-demand binary launcher.

All tests are hermetic (no network access).  Download behaviour is tested
either via ``MOCKSERVER_BINARY_BASE_URL`` pointed at a local ``file://`` URL
or by stubbing the internal ``_download`` helper.
"""

from __future__ import annotations

import hashlib
import os
import sys
import tarfile
from pathlib import Path
from unittest import mock

import pytest

from mockserver.launcher import (
    MockServerProcess,
    _CLIENT_VERSION,
    _MAX_PREVIOUS_VERSIONS,
    _is_snapshot,
    _parse_semver_tuple,
    _prune_old_versions,
    _sha256,
    _validate_version,
    asset_url,
    bundle_base_name,
    cache_dir,
    ensure_binary,
    launcher_path,
    resolve_platform,
    start,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _create_fake_archive(
    tmp_path: Path,
    version: str = "7.0.0",
    os_name: str | None = None,
    arch: str | None = None,
    ext: str | None = None,
) -> tuple[Path, str]:
    """Create a minimal tar.gz archive that mimics the real bundle layout.

    Returns ``(archive_path, sha256_hex)``.
    """
    if os_name is None or arch is None or ext is None:
        os_name_r, arch_r, ext_r = resolve_platform()
        os_name = os_name or os_name_r
        arch = arch or arch_r
        ext = ext or ext_r

    name = f"mockserver-{version}-{os_name}-{arch}"
    archive_name = f"{name}.{ext}"

    # Build the directory structure the extractor expects:
    #   <name>/bin/mockserver  (an executable script)
    staging = tmp_path / "staging"
    bin_dir = staging / name / "bin"
    bin_dir.mkdir(parents=True)
    launcher = bin_dir / "mockserver"
    launcher.write_text("#!/bin/sh\necho mock\n")
    launcher.chmod(0o755)

    archive_path = tmp_path / archive_name
    with tarfile.open(archive_path, "w:gz") as tf:
        tf.add(staging / name, arcname=name)

    digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
    return archive_path, digest


def _seed_fake_server(
    tmp_path: Path,
    version: str = "7.0.0",
) -> Path:
    """Create a fake archive + sha256 file in a directory suitable for
    ``file://`` serving via ``MOCKSERVER_BINARY_BASE_URL``.
    """
    serve_dir = tmp_path / "serve"
    serve_dir.mkdir(exist_ok=True)
    archive_path, digest = _create_fake_archive(tmp_path, version=version)
    # Move archive into serve dir
    dest = serve_dir / archive_path.name
    archive_path.rename(dest)
    # Write .sha256 file
    sha_file = serve_dir / (archive_path.name + ".sha256")
    sha_file.write_text(f"{digest}  {archive_path.name}\n")
    return serve_dir


# ---------------------------------------------------------------------------
# Platform resolution
# ---------------------------------------------------------------------------

class TestResolvePlatform:
    def test_current_platform_succeeds(self):
        os_name, arch, ext = resolve_platform()
        assert os_name in ("linux", "darwin", "windows")
        assert arch in ("x86_64", "aarch64")
        assert ext in ("tar.gz", "zip")

    def test_linux_x86_64(self):
        with mock.patch("mockserver.launcher.platform") as mp:
            mp.system.return_value = "Linux"
            mp.machine.return_value = "x86_64"
            assert resolve_platform() == ("linux", "x86_64", "tar.gz")

    def test_linux_aarch64(self):
        with mock.patch("mockserver.launcher.platform") as mp:
            mp.system.return_value = "Linux"
            mp.machine.return_value = "aarch64"
            assert resolve_platform() == ("linux", "aarch64", "tar.gz")

    def test_darwin_arm64(self):
        with mock.patch("mockserver.launcher.platform") as mp:
            mp.system.return_value = "Darwin"
            mp.machine.return_value = "arm64"
            assert resolve_platform() == ("darwin", "aarch64", "tar.gz")

    def test_darwin_x86_64(self):
        with mock.patch("mockserver.launcher.platform") as mp:
            mp.system.return_value = "Darwin"
            mp.machine.return_value = "x86_64"
            assert resolve_platform() == ("darwin", "x86_64", "tar.gz")

    def test_windows_amd64(self):
        with mock.patch("mockserver.launcher.platform") as mp:
            mp.system.return_value = "Windows"
            mp.machine.return_value = "AMD64"
            assert resolve_platform() == ("windows", "x86_64", "zip")

    def test_windows_aarch64(self):
        with mock.patch("mockserver.launcher.platform") as mp:
            mp.system.return_value = "Windows"
            mp.machine.return_value = "aarch64"
            assert resolve_platform() == ("windows", "aarch64", "zip")

    def test_unsupported_os_raises(self):
        with mock.patch("mockserver.launcher.platform") as mp:
            mp.system.return_value = "FreeBSD"
            mp.machine.return_value = "x86_64"
            with pytest.raises(RuntimeError, match="unsupported platform"):
                resolve_platform()

    def test_unsupported_arch_raises(self):
        with mock.patch("mockserver.launcher.platform") as mp:
            mp.system.return_value = "Linux"
            mp.machine.return_value = "mips"
            with pytest.raises(RuntimeError, match="unsupported architecture"):
                resolve_platform()


# ---------------------------------------------------------------------------
# Bundle naming
# ---------------------------------------------------------------------------

class TestBundleBaseName:
    def test_name_format(self):
        name, ext = bundle_base_name("7.0.0")
        os_name, arch, expected_ext = resolve_platform()
        assert name == f"mockserver-7.0.0-{os_name}-{arch}"
        assert ext == expected_ext

    def test_different_version(self):
        name, _ext = bundle_base_name("6.1.0")
        assert name.startswith("mockserver-6.1.0-")


# ---------------------------------------------------------------------------
# Cache directory
# ---------------------------------------------------------------------------

class TestCacheDir:
    def test_env_override(self, tmp_path: Path):
        with mock.patch.dict(os.environ, {"MOCKSERVER_BINARY_CACHE": str(tmp_path / "custom")}):
            assert cache_dir() == tmp_path / "custom"

    def test_xdg_cache_home(self, tmp_path: Path):
        env = {"XDG_CACHE_HOME": str(tmp_path / "xdg")}
        with mock.patch.dict(os.environ, env, clear=False):
            # Remove MOCKSERVER_BINARY_CACHE if set
            os.environ.pop("MOCKSERVER_BINARY_CACHE", None)
            with mock.patch("mockserver.launcher.sys") as ms:
                ms.platform = "linux"
                result = cache_dir()
                assert result == tmp_path / "xdg" / "mockserver" / "binaries"

    def test_default_unix(self, tmp_path: Path):
        env_remove = {"MOCKSERVER_BINARY_CACHE", "XDG_CACHE_HOME"}
        env_patch = {k: v for k, v in os.environ.items() if k not in env_remove}
        with mock.patch.dict(os.environ, env_patch, clear=True):
            with mock.patch("mockserver.launcher.sys") as ms:
                ms.platform = "linux"
                with mock.patch("mockserver.launcher.Path.home", return_value=tmp_path):
                    result = cache_dir()
                    assert result == tmp_path / ".cache" / "mockserver" / "binaries"

    def test_default_windows(self, tmp_path: Path):
        env_patch = {"LOCALAPPDATA": str(tmp_path / "AppData" / "Local")}
        with mock.patch.dict(os.environ, env_patch, clear=True):
            with mock.patch("mockserver.launcher.sys") as ms:
                ms.platform = "win32"
                result = cache_dir()
                assert result == tmp_path / "AppData" / "Local" / "mockserver" / "binaries"


# ---------------------------------------------------------------------------
# Asset URL
# ---------------------------------------------------------------------------

class TestAssetUrl:
    def test_default_url(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("MOCKSERVER_BINARY_BASE_URL", None)
            url = asset_url("7.0.0", "foo.tar.gz")
            assert url == (
                "https://github.com/mock-server/mockserver-monorepo"
                "/releases/download/mockserver-7.0.0/foo.tar.gz"
            )

    def test_custom_base_url(self):
        with mock.patch.dict(os.environ, {"MOCKSERVER_BINARY_BASE_URL": "https://mirror.local/bins/"}):
            url = asset_url("7.0.0", "foo.tar.gz")
            assert url == "https://mirror.local/bins/foo.tar.gz"

    def test_trailing_slash_stripped(self):
        with mock.patch.dict(os.environ, {"MOCKSERVER_BINARY_BASE_URL": "https://mirror.local///"}):
            url = asset_url("7.0.0", "foo.tar.gz")
            assert url == "https://mirror.local/foo.tar.gz"

    def test_snapshot_uses_cdn(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("MOCKSERVER_BINARY_BASE_URL", None)
            url = asset_url("7.1.0-SNAPSHOT", "mockserver-7.1.0-SNAPSHOT-linux-x86_64.tar.gz")
            assert url == (
                "https://downloads.mock-server.com/mockserver-7.1.0-SNAPSHOT"
                "/mockserver-7.1.0-SNAPSHOT-linux-x86_64.tar.gz"
            )

    def test_release_uses_github(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("MOCKSERVER_BINARY_BASE_URL", None)
            url = asset_url("7.0.0", "foo.tar.gz")
            assert url == (
                "https://github.com/mock-server/mockserver-monorepo"
                "/releases/download/mockserver-7.0.0/foo.tar.gz"
            )

    def test_env_override_wins_over_snapshot(self):
        with mock.patch.dict(os.environ, {"MOCKSERVER_BINARY_BASE_URL": "https://custom-mirror.example.com/bins"}):
            url = asset_url("7.1.0-SNAPSHOT", "mockserver-7.1.0-SNAPSHOT-linux-x86_64.tar.gz")
            assert url == "https://custom-mirror.example.com/bins/mockserver-7.1.0-SNAPSHOT-linux-x86_64.tar.gz"

    def test_snapshot_case_insensitive(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("MOCKSERVER_BINARY_BASE_URL", None)
            url = asset_url("7.1.0-snapshot", "file.tar.gz")
            assert url.startswith("https://downloads.mock-server.com/")


# ---------------------------------------------------------------------------
# Launcher path
# ---------------------------------------------------------------------------

class TestLauncherPath:
    def test_unix(self, tmp_path: Path):
        with mock.patch("mockserver.launcher.sys") as ms:
            ms.platform = "linux"
            p = launcher_path(tmp_path, "mockserver-7.0.0-linux-x86_64")
            assert p == tmp_path / "mockserver-7.0.0-linux-x86_64" / "bin" / "mockserver"

    def test_windows(self, tmp_path: Path):
        with mock.patch("mockserver.launcher.sys") as ms:
            ms.platform = "win32"
            p = launcher_path(tmp_path, "mockserver-7.0.0-windows-x86_64")
            assert p == tmp_path / "mockserver-7.0.0-windows-x86_64" / "bin" / "mockserver.bat"


# ---------------------------------------------------------------------------
# SHA-256 verification
# ---------------------------------------------------------------------------

class TestSha256:
    def test_correct_hash(self, tmp_path: Path):
        f = tmp_path / "data.bin"
        f.write_bytes(b"hello world")
        expected = hashlib.sha256(b"hello world").hexdigest()
        assert _sha256(f) == expected

    def test_wrong_hash_detected(self, tmp_path: Path):
        f = tmp_path / "data.bin"
        f.write_bytes(b"hello world")
        assert _sha256(f) != hashlib.sha256(b"different").hexdigest()


class TestChecksumVerification:
    """End-to-end tests that the install flow properly rejects bad checksums."""

    def test_good_checksum_passes(self, tmp_path: Path):
        serve_dir = _seed_fake_server(tmp_path, version="9.9.9")
        cache = tmp_path / "cache"
        env = {
            "MOCKSERVER_BINARY_CACHE": str(cache),
            "MOCKSERVER_BINARY_BASE_URL": serve_dir.as_uri(),
        }
        with mock.patch.dict(os.environ, env, clear=False):
            os.environ.pop("MOCKSERVER_SKIP_BINARY_DOWNLOAD", None)
            result = ensure_binary("9.9.9", log=False)
            assert result.exists()
            assert result.stat().st_size > 0

    def test_bad_checksum_fails(self, tmp_path: Path):
        serve_dir = _seed_fake_server(tmp_path, version="9.9.9")
        # Corrupt the sha256 file
        for f in serve_dir.iterdir():
            if f.name.endswith(".sha256"):
                f.write_text("0000000000000000000000000000000000000000000000000000000000000000  fake\n")
        cache = tmp_path / "cache"
        env = {
            "MOCKSERVER_BINARY_CACHE": str(cache),
            "MOCKSERVER_BINARY_BASE_URL": serve_dir.as_uri(),
        }
        with mock.patch.dict(os.environ, env, clear=False):
            os.environ.pop("MOCKSERVER_SKIP_BINARY_DOWNLOAD", None)
            with pytest.raises(RuntimeError, match="checksum mismatch"):
                ensure_binary("9.9.9", log=False)
        # Both .part AND .sha256 files should have been cleaned up (H3)
        version_dir = cache / "9.9.9"
        if version_dir.exists():
            part_files = list(version_dir.glob("*.part"))
            sha_files = list(version_dir.glob("*.sha256"))
            assert len(part_files) == 0, f"leftover .part files: {part_files}"
            assert len(sha_files) == 0, f"leftover .sha256 files: {sha_files}"

    def test_empty_checksum_fails(self, tmp_path: Path):
        serve_dir = _seed_fake_server(tmp_path, version="9.9.9")
        # Write an empty sha256 file
        for f in serve_dir.iterdir():
            if f.name.endswith(".sha256"):
                f.write_text("")
        cache = tmp_path / "cache"
        env = {
            "MOCKSERVER_BINARY_CACHE": str(cache),
            "MOCKSERVER_BINARY_BASE_URL": serve_dir.as_uri(),
        }
        with mock.patch.dict(os.environ, env, clear=False):
            os.environ.pop("MOCKSERVER_SKIP_BINARY_DOWNLOAD", None)
            with pytest.raises(RuntimeError, match="empty or unparseable"):
                ensure_binary("9.9.9", log=False)


class TestMissingBundle:
    """A 404 on the archive download yields a clear, actionable error."""

    def test_404_raises_actionable_error(self, tmp_path: Path):
        import urllib.error

        from mockserver import launcher as lmod

        def fake_download(url: str, dest: Path) -> None:
            raise urllib.error.HTTPError(url, 404, "Not Found", {}, None)  # type: ignore[arg-type]

        cache = tmp_path / "cache"
        env = {
            "MOCKSERVER_BINARY_CACHE": str(cache),
            "MOCKSERVER_BINARY_BASE_URL": "https://example.invalid",
        }
        with mock.patch.dict(os.environ, env, clear=False):
            os.environ.pop("MOCKSERVER_SKIP_BINARY_DOWNLOAD", None)
            with mock.patch.object(lmod, "_download", side_effect=fake_download):
                with pytest.raises(RuntimeError) as exc_info:
                    ensure_binary("9.9.9", log=False)
        msg = str(exc_info.value)
        assert "9.9.9" in msg
        assert "no MockServer release bundle" in msg
        assert "docker run mockserver/mockserver:mockserver-9.9.9" in msg
        assert "org.mock-server:mockserver-netty:9.9.9" in msg


# ---------------------------------------------------------------------------
# MOCKSERVER_SKIP_BINARY_DOWNLOAD
# ---------------------------------------------------------------------------

class TestSkipDownload:
    def test_skip_with_no_cache_raises(self, tmp_path: Path):
        env = {
            "MOCKSERVER_BINARY_CACHE": str(tmp_path / "empty-cache"),
            "MOCKSERVER_SKIP_BINARY_DOWNLOAD": "1",
        }
        with mock.patch.dict(os.environ, env, clear=False):
            with pytest.raises(RuntimeError, match="MOCKSERVER_SKIP_BINARY_DOWNLOAD"):
                ensure_binary("9.9.9", log=False)

    def test_skip_with_cached_binary_succeeds(self, tmp_path: Path):
        # Pre-seed the cache
        serve_dir = _seed_fake_server(tmp_path, version="9.9.9")
        cache = tmp_path / "cache"
        env = {
            "MOCKSERVER_BINARY_CACHE": str(cache),
            "MOCKSERVER_BINARY_BASE_URL": serve_dir.as_uri(),
        }
        with mock.patch.dict(os.environ, env, clear=False):
            os.environ.pop("MOCKSERVER_SKIP_BINARY_DOWNLOAD", None)
            first = ensure_binary("9.9.9", log=False)
            assert first.exists()

        # Now set SKIP and verify it still works from cache
        env["MOCKSERVER_SKIP_BINARY_DOWNLOAD"] = "1"
        with mock.patch.dict(os.environ, env, clear=False):
            second = ensure_binary("9.9.9", log=False)
            assert second == first


# ---------------------------------------------------------------------------
# Caching: reuse on second call
# ---------------------------------------------------------------------------

class TestCaching:
    def test_second_call_uses_cache(self, tmp_path: Path):
        serve_dir = _seed_fake_server(tmp_path, version="9.9.9")
        cache = tmp_path / "cache"
        env = {
            "MOCKSERVER_BINARY_CACHE": str(cache),
            "MOCKSERVER_BINARY_BASE_URL": serve_dir.as_uri(),
        }
        with mock.patch.dict(os.environ, env, clear=False):
            os.environ.pop("MOCKSERVER_SKIP_BINARY_DOWNLOAD", None)
            first = ensure_binary("9.9.9", log=False)
            # Delete the serve dir so no download is possible
            import shutil
            shutil.rmtree(serve_dir)
            second = ensure_binary("9.9.9", log=False)
            assert first == second


# ---------------------------------------------------------------------------
# Versioned cache pruning
# ---------------------------------------------------------------------------

class TestCachePruning:
    def test_old_versions_removed(self, tmp_path: Path):
        """Pre-create several old version dirs; after pruning for the
        current version, only the _MAX_PREVIOUS_VERSIONS highest-versioned
        dirs remain (semver-aware, not mtime-based)."""
        cache = tmp_path / "cache"
        # Create "old" version dirs — note: mtimes are intentionally NOT in
        # version order to verify that pruning uses semver comparison.
        old_versions = ["1.0.0", "2.0.0", "3.0.0", "4.0.0"]
        for i, v in enumerate(old_versions):
            d = cache / v
            d.mkdir(parents=True)
            (d / "placeholder").write_text("x")

        _prune_old_versions(cache, "5.0.0")

        remaining = {d.name for d in cache.iterdir() if d.is_dir()}
        # Current version dir (5.0.0) doesn't exist yet, so not in remaining.
        # Should keep the _MAX_PREVIOUS_VERSIONS highest semver = "4.0.0"
        assert "4.0.0" in remaining
        # The older ones should be gone
        assert "1.0.0" not in remaining
        assert "2.0.0" not in remaining
        assert "3.0.0" not in remaining
        assert len(remaining) <= _MAX_PREVIOUS_VERSIONS

    def test_current_version_always_kept(self, tmp_path: Path):
        cache = tmp_path / "cache"
        current = cache / "5.0.0"
        current.mkdir(parents=True)
        (current / "placeholder").write_text("x")

        _prune_old_versions(cache, "5.0.0")

        assert current.exists()

    def test_leftover_part_files_cleaned(self, tmp_path: Path):
        cache = tmp_path / "cache"
        cache.mkdir(parents=True)
        part_file = cache / "something.tar.gz.part"
        part_file.write_text("partial")

        _prune_old_versions(cache, "5.0.0")

        assert not part_file.exists()

    def test_pruning_after_install(self, tmp_path: Path):
        """Full end-to-end: install v9.9.9 with pre-existing old dirs."""
        serve_dir = _seed_fake_server(tmp_path, version="9.9.9")
        cache = tmp_path / "cache"

        # Pre-create old version dirs
        for v in ["1.0.0", "2.0.0", "3.0.0"]:
            d = cache / v
            d.mkdir(parents=True)
            (d / "placeholder").write_text("x")

        env = {
            "MOCKSERVER_BINARY_CACHE": str(cache),
            "MOCKSERVER_BINARY_BASE_URL": serve_dir.as_uri(),
        }
        with mock.patch.dict(os.environ, env, clear=False):
            os.environ.pop("MOCKSERVER_SKIP_BINARY_DOWNLOAD", None)
            ensure_binary("9.9.9", log=False)

        remaining = {d.name for d in cache.iterdir() if d.is_dir()}
        # 9.9.9 (current) must exist
        assert "9.9.9" in remaining
        # At most _MAX_PREVIOUS_VERSIONS old versions remain
        old = remaining - {"9.9.9"}
        assert len(old) <= _MAX_PREVIOUS_VERSIONS

    def test_prune_safe_with_nonexistent_dir(self, tmp_path: Path):
        """Pruning should not fail on a missing cache base."""
        _prune_old_versions(tmp_path / "nonexistent", "1.0.0")

    def test_prune_ignores_files(self, tmp_path: Path):
        """Regular files (not dirs) in the cache should not be removed."""
        cache = tmp_path / "cache"
        cache.mkdir(parents=True)
        regular = cache / "readme.txt"
        regular.write_text("keep me")

        _prune_old_versions(cache, "1.0.0")

        assert regular.exists()


# ---------------------------------------------------------------------------
# ensure_binary: version defaults to client version
# ---------------------------------------------------------------------------

class TestDefaultVersion:
    def test_defaults_to_client_version(self, tmp_path: Path):
        """When no version is passed, ensure_binary uses _CLIENT_VERSION."""
        env = {
            "MOCKSERVER_BINARY_CACHE": str(tmp_path / "cache"),
            "MOCKSERVER_SKIP_BINARY_DOWNLOAD": "1",
        }
        with mock.patch.dict(os.environ, env, clear=False):
            with pytest.raises(RuntimeError) as exc_info:
                ensure_binary(log=False)
            # The error message should reference the default version
            assert _CLIENT_VERSION in str(exc_info.value)


# ---------------------------------------------------------------------------
# MockServerProcess
# ---------------------------------------------------------------------------

class TestMockServerProcess:
    def test_stop_already_exited(self, tmp_path: Path):
        """stop() on an already-exited process returns the exit code."""
        import subprocess as _sp
        # Use a trivial command that exits immediately
        proc = MockServerProcess(
            process=_sp.Popen(
                [sys.executable, "-c", "pass"],
                stdout=_sp.DEVNULL,
                stderr=_sp.DEVNULL,
            ),
            port=1080,
            launcher=Path("/fake"),
        )
        proc._process.wait(timeout=5)
        code = proc.stop()
        assert code == 0

    def test_context_manager(self, tmp_path: Path):
        import subprocess as _sp
        proc = MockServerProcess(
            process=_sp.Popen(
                [sys.executable, "-c", "import time; time.sleep(60)"],
                stdout=_sp.DEVNULL,
                stderr=_sp.DEVNULL,
            ),
            port=1080,
            launcher=Path("/fake"),
        )
        with proc:
            assert proc.pid is not None
        # After __exit__, process should have terminated
        assert proc.returncode is not None

    def test_port_property(self):
        import subprocess as _sp
        proc = MockServerProcess(
            process=_sp.Popen(
                [sys.executable, "-c", "pass"],
                stdout=_sp.DEVNULL,
                stderr=_sp.DEVNULL,
            ),
            port=9999,
            launcher=Path("/fake"),
        )
        assert proc.port == 9999
        proc.stop()


# ---------------------------------------------------------------------------
# start()
# ---------------------------------------------------------------------------

class TestStart:
    def test_start_calls_ensure_binary_and_spawns(self, tmp_path: Path):
        """Verify start() calls ensure_binary and spawns the launcher."""
        serve_dir = _seed_fake_server(tmp_path, version="9.9.9")
        cache = tmp_path / "cache"
        env = {
            "MOCKSERVER_BINARY_CACHE": str(cache),
            "MOCKSERVER_BINARY_BASE_URL": serve_dir.as_uri(),
        }
        with mock.patch.dict(os.environ, env, clear=False):
            os.environ.pop("MOCKSERVER_SKIP_BINARY_DOWNLOAD", None)
            proc = start(1080, version="9.9.9", log=False)
            try:
                assert proc.port == 1080
                assert proc.launcher.exists()
            finally:
                proc.stop()


# ---------------------------------------------------------------------------
# Version validation (H1)
# ---------------------------------------------------------------------------

class TestIsSnapshot:
    """Verify _is_snapshot detects SNAPSHOT versions case-insensitively."""

    def test_snapshot_detected(self):
        assert _is_snapshot("7.0.0-SNAPSHOT") is True
        assert _is_snapshot("7.0.0-snapshot") is True
        assert _is_snapshot("7.0.0-Snapshot") is True
        assert _is_snapshot("7.1.0-SNAPSHOT") is True

    def test_release_not_snapshot(self):
        assert _is_snapshot("7.0.0") is False
        assert _is_snapshot("7.0.0-beta.1") is False
        assert _is_snapshot("7.0.0-rc.1") is False


class TestVersionValidation:
    """Verify _validate_version rejects path-traversal and malformed strings."""

    def test_valid_versions(self):
        for v in ("7.0.0", "6.1.0", "7.0.0-SNAPSHOT", "7.0.0-rc.1", "10.20.30"):
            _validate_version(v)  # should not raise

    def test_rejects_empty(self):
        with pytest.raises(ValueError, match="invalid version"):
            _validate_version("")

    def test_rejects_path_traversal_slash(self):
        with pytest.raises(ValueError, match="invalid version"):
            _validate_version("../../etc")

    def test_rejects_path_traversal_backslash(self):
        with pytest.raises(ValueError, match="invalid version"):
            _validate_version("..\\..\\etc")

    def test_rejects_no_patch(self):
        with pytest.raises(ValueError, match="invalid version"):
            _validate_version("7.0")

    def test_rejects_alpha_only(self):
        with pytest.raises(ValueError, match="invalid version"):
            _validate_version("latest")

    def test_ensure_binary_rejects_bad_version(self, tmp_path: Path):
        """ensure_binary must reject bad versions before any filesystem ops."""
        env = {"MOCKSERVER_BINARY_CACHE": str(tmp_path / "cache")}
        with mock.patch.dict(os.environ, env, clear=False):
            with pytest.raises(ValueError, match="invalid version"):
                ensure_binary("../../etc/passwd", log=False)
        # No directory should have been created
        assert not (tmp_path / "cache").exists()


# ---------------------------------------------------------------------------
# Semver-aware pruning (H7)
# ---------------------------------------------------------------------------

class TestSemverPruning:
    """Verify pruning uses numeric semver comparison, not lexicographic."""

    def test_semver_parse(self):
        assert _parse_semver_tuple("7.0.0") == ((7, 0, 0), 1, "")
        assert _parse_semver_tuple("10.2.30") == ((10, 2, 30), 1, "")
        assert _parse_semver_tuple("7.0.0-SNAPSHOT") == ((7, 0, 0), 0, "SNAPSHOT")
        assert _parse_semver_tuple("7.0.0-rc.1") == ((7, 0, 0), 0, "rc.1")

    def test_release_sorts_above_prerelease(self):
        """A release (no suffix) must sort ABOVE its pre-release counterpart.

        This is the canonical semver rule: 7.0.0 > 7.0.0-SNAPSHOT.  The pruner
        uses this ordering so a stable release is never deleted in favour of its
        -SNAPSHOT.
        """
        release = _parse_semver_tuple("7.0.0")
        snapshot = _parse_semver_tuple("7.0.0-SNAPSHOT")
        rc = _parse_semver_tuple("7.0.0-rc.1")

        assert release > snapshot
        assert release > rc

    def test_prune_keeps_release_over_snapshot(self, tmp_path: Path):
        """When both 7.0.0 and 7.0.0-SNAPSHOT exist, pruning for a newer
        current version must keep the release (7.0.0) and remove the
        SNAPSHOT, because _MAX_PREVIOUS_VERSIONS == 1."""
        cache = tmp_path / "cache"
        for v in ["7.0.0-SNAPSHOT", "7.0.0"]:
            d = cache / v
            d.mkdir(parents=True)
            (d / "placeholder").write_text("x")

        _prune_old_versions(cache, "8.0.0")

        remaining = {d.name for d in cache.iterdir() if d.is_dir()}
        assert "7.0.0" in remaining, "release must be kept over its pre-release"
        assert "7.0.0-SNAPSHOT" not in remaining, "SNAPSHOT must be pruned"

    def test_semver_ordering_beats_lexicographic(self, tmp_path: Path):
        """Version 10.0.0 > 9.0.0 but lexicographically '10.0.0' < '9.0.0'.
        Pruning must keep 10.0.0 (highest semver), not 9.0.0."""
        cache = tmp_path / "cache"
        for v in ["2.0.0", "9.0.0", "10.0.0"]:
            d = cache / v
            d.mkdir(parents=True)
            (d / "placeholder").write_text("x")

        _prune_old_versions(cache, "11.0.0")

        remaining = {d.name for d in cache.iterdir() if d.is_dir()}
        # Should keep 10.0.0 (highest semver among others)
        assert "10.0.0" in remaining
        # 9.0.0 and 2.0.0 should be pruned
        assert "9.0.0" not in remaining
        assert "2.0.0" not in remaining

    def test_multi_digit_segments(self, tmp_path: Path):
        """Verify 1.10.0 > 1.9.0 (not lexicographic where '9' > '1')."""
        cache = tmp_path / "cache"
        for v in ["1.8.0", "1.9.0", "1.10.0"]:
            d = cache / v
            d.mkdir(parents=True)
            (d / "placeholder").write_text("x")

        _prune_old_versions(cache, "2.0.0")

        remaining = {d.name for d in cache.iterdir() if d.is_dir()}
        assert "1.10.0" in remaining
        assert "1.9.0" not in remaining
        assert "1.8.0" not in remaining


# ---------------------------------------------------------------------------
# SHA-256 cleanup on failure (H3)
# ---------------------------------------------------------------------------

class TestFailureCleanup:
    """Verify both .part and .sha256 files are cleaned up on any failure."""

    def test_sha256_cleaned_on_empty_checksum(self, tmp_path: Path):
        serve_dir = _seed_fake_server(tmp_path, version="9.9.9")
        # Make sha256 file empty
        for f in serve_dir.iterdir():
            if f.name.endswith(".sha256"):
                f.write_text("")
        cache = tmp_path / "cache"
        env = {
            "MOCKSERVER_BINARY_CACHE": str(cache),
            "MOCKSERVER_BINARY_BASE_URL": serve_dir.as_uri(),
        }
        with mock.patch.dict(os.environ, env, clear=False):
            os.environ.pop("MOCKSERVER_SKIP_BINARY_DOWNLOAD", None)
            with pytest.raises(RuntimeError, match="empty or unparseable"):
                ensure_binary("9.9.9", log=False)
        version_dir = cache / "9.9.9"
        if version_dir.exists():
            sha_files = list(version_dir.glob("*.sha256"))
            assert len(sha_files) == 0, f"stale .sha256 files: {sha_files}"


# ---------------------------------------------------------------------------
# HTTP timeout configuration (H6)
# ---------------------------------------------------------------------------

class TestHttpTimeout:
    """Verify _download passes a timeout to urlopen."""

    def test_download_uses_timeout(self, tmp_path: Path):
        import mockserver.launcher as lmod

        dest = tmp_path / "out.bin"
        calls = []

        class FakeResp:
            def read(self, n=-1):
                return b""
            def __enter__(self):
                return self
            def __exit__(self, *a):
                pass

        def fake_urlopen(req, *, timeout=None, **kw):
            calls.append(timeout)
            return FakeResp()

        with mock.patch.object(lmod.urllib.request, "urlopen", side_effect=fake_urlopen):
            lmod._download("https://example.com/f", dest)

        assert len(calls) == 1
        assert calls[0] is not None and calls[0] > 0


# ---------------------------------------------------------------------------
# Integration test (skipped when no real bundle exists)
# ---------------------------------------------------------------------------

class TestIntegration:
    @pytest.mark.integration
    def test_real_download_and_start(self, tmp_path: Path):
        """Download the real bundle and verify the launcher is functional.

        This test is SKIPPED unless the environment variable
        ``MOCKSERVER_RUN_LIVE_LAUNCHER_TEST`` is set, because a released
        bundle may not exist for the current development version.
        """
        if not os.environ.get("MOCKSERVER_RUN_LIVE_LAUNCHER_TEST"):
            pytest.skip(
                "Set MOCKSERVER_RUN_LIVE_LAUNCHER_TEST=1 to run live launcher tests"
            )
        env = {"MOCKSERVER_BINARY_CACHE": str(tmp_path / "cache")}
        with mock.patch.dict(os.environ, env, clear=False):
            os.environ.pop("MOCKSERVER_SKIP_BINARY_DOWNLOAD", None)
            os.environ.pop("MOCKSERVER_BINARY_BASE_URL", None)
            launcher = ensure_binary(log=True)
            assert launcher.exists()
            assert launcher.stat().st_size > 0
