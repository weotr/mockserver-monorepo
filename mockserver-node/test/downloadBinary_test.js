/*
 * Hermetic unit tests for the on-demand binary launcher (downloadBinary.js).
 *
 * All download/extract tests use local filesystem fixtures (file:// URLs via
 * MOCKSERVER_BINARY_BASE_URL) — no network access required.
 *
 * ONE integration test is conditionally skipped: it runs only when
 * MOCKSERVER_INTEGRATION_TEST=1 is set (and a real release bundle is available).
 * It validates the full download-verify-extract-launch path.
 */
'use strict';

var test = require('node:test');
var assert = require('node:assert');
var fs = require('fs');
var path = require('path');
var os = require('os');
var crypto = require('crypto');
var child_process = require('child_process');
var binary = require('../downloadBinary');

// ---------- helpers ----------

/**
 * Create a temporary directory for test isolation.
 * Returns { base, cleanup } where cleanup removes the directory.
 */
function makeTempDir(prefix) {
  var dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix || 'ms-test-'));
  return {
    base: dir,
    cleanup: function () {
      try { fs.rmSync(dir, { recursive: true, force: true }); } catch (e) { /* ignore */ }
    }
  };
}

/**
 * Create a tiny tar.gz fixture archive that contains a mockserver launcher stub.
 * The archive has the structure: mockserver-<version>-<os>-<arch>/bin/mockserver
 *
 * NOTE: Always creates a gzip-compressed tar archive regardless of platform.
 * On Windows the bundle extension is .zip but the content is a tar.gz stream.
 * This works because bsdtar/GNU tar auto-detects format by content signature
 * (not file extension), and the production code uses `tar -xf`. If a future
 * test validates archive type explicitly, this helper must be updated to create
 * a real ZIP on Windows.
 *
 * Returns { archivePath, sha256hex } where archivePath is the path to the archive
 * and sha256hex is its SHA-256 hex digest.
 */
function createFixtureArchive(fixtureDir, version) {
  var plat = binary.resolvePlatform();
  var bundleName = 'mockserver-' + version + '-' + plat.osName + '-' + plat.arch;
  var ext = plat.ext;
  var archiveName = bundleName + '.' + ext;

  // Create the directory structure the archive will contain
  var contentDir = path.join(fixtureDir, '_content');
  var binDir = path.join(contentDir, bundleName, 'bin');
  fs.mkdirSync(binDir, { recursive: true });

  var launcherName = process.platform === 'win32' ? 'mockserver.bat' : 'mockserver';
  var launcherFile = path.join(binDir, launcherName);
  if (process.platform === 'win32') {
    fs.writeFileSync(launcherFile, '@echo off\r\necho mockserver-stub\r\n');
  } else {
    fs.writeFileSync(launcherFile, '#!/bin/sh\necho mockserver-stub\n');
    fs.chmodSync(launcherFile, 0o755);
  }

  // Create the tar.gz archive
  var archivePath = path.join(fixtureDir, archiveName);
  var r = child_process.spawnSync('tar', ['-czf', archivePath, '-C', contentDir, bundleName], {
    stdio: 'pipe'
  });
  if (r.status !== 0) {
    throw new Error('failed to create fixture archive: ' + (r.stderr ? r.stderr.toString() : 'unknown'));
  }

  // Compute SHA-256
  var hash = crypto.createHash('sha256');
  hash.update(fs.readFileSync(archivePath));
  var sha256hex = hash.digest('hex');

  // Write the .sha256 sidecar
  fs.writeFileSync(archivePath + '.sha256', sha256hex + '  ' + archiveName + '\n');

  // Clean up the content directory (only the archive matters now)
  fs.rmSync(contentDir, { recursive: true, force: true });

  return { archivePath: archivePath, sha256hex: sha256hex, archiveName: archiveName };
}

// ================================================================
// 1. Platform / architecture mapping
// ================================================================

test('resolvePlatform maps to a supported os/arch and archive type', function () {
  var p = binary.resolvePlatform();
  assert.ok(['linux', 'darwin', 'windows'].indexOf(p.osName) !== -1, 'osName');
  assert.ok(['x86_64', 'aarch64'].indexOf(p.arch) !== -1, 'arch');
  assert.strictEqual(p.ext, p.osName === 'windows' ? 'zip' : 'tar.gz');
});

test('bundleBaseName follows mockserver-<version>-<os>-<arch>', function () {
  var meta = binary.bundleBaseName('1.2.3');
  assert.match(meta.name, /^mockserver-1\.2\.3-(linux|darwin|windows)-(x86_64|aarch64)$/);
  assert.ok(meta.ext === 'tar.gz' || meta.ext === 'zip');
});

// ================================================================
// 2. Asset URL
// ================================================================

test('assetUrl honours the MOCKSERVER_BINARY_BASE_URL mirror', function () {
  var prev = process.env.MOCKSERVER_BINARY_BASE_URL;
  process.env.MOCKSERVER_BINARY_BASE_URL = 'https://mirror.example.com/ms/';
  try {
    assert.strictEqual(
      binary.assetUrl('1.2.3', 'file.tar.gz'),
      'https://mirror.example.com/ms/file.tar.gz');
  } finally {
    if (prev === undefined) { delete process.env.MOCKSERVER_BINARY_BASE_URL; }
    else { process.env.MOCKSERVER_BINARY_BASE_URL = prev; }
  }
});

test('assetUrl defaults to the GitHub Release for the version tag', function () {
  var prev = process.env.MOCKSERVER_BINARY_BASE_URL;
  delete process.env.MOCKSERVER_BINARY_BASE_URL;
  try {
    assert.strictEqual(
      binary.assetUrl('1.2.3', 'x.tar.gz'),
      'https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-1.2.3/x.tar.gz');
  } finally {
    if (prev !== undefined) { process.env.MOCKSERVER_BINARY_BASE_URL = prev; }
  }
});

test('isSnapshot detects SNAPSHOT versions case-insensitively', function () {
  var isSnapshot = binary._internal.isSnapshot;
  assert.strictEqual(isSnapshot('7.0.0-SNAPSHOT'), true);
  assert.strictEqual(isSnapshot('7.0.0-snapshot'), true);
  assert.strictEqual(isSnapshot('7.0.0-Snapshot'), true);
  assert.strictEqual(isSnapshot('7.1.0-SNAPSHOT'), true);
  assert.strictEqual(isSnapshot('7.0.0'), false);
  assert.strictEqual(isSnapshot('7.0.0-beta.1'), false);
  assert.strictEqual(isSnapshot('7.0.0-rc.1'), false);
});

test('assetUrl uses CDN for SNAPSHOT versions', function () {
  var prev = process.env.MOCKSERVER_BINARY_BASE_URL;
  delete process.env.MOCKSERVER_BINARY_BASE_URL;
  try {
    assert.strictEqual(
      binary.assetUrl('7.1.0-SNAPSHOT', 'mockserver-7.1.0-SNAPSHOT-linux-x86_64.tar.gz'),
      'https://downloads.mock-server.com/mockserver-7.1.0-SNAPSHOT/mockserver-7.1.0-SNAPSHOT-linux-x86_64.tar.gz');
  } finally {
    if (prev !== undefined) { process.env.MOCKSERVER_BINARY_BASE_URL = prev; }
    else { delete process.env.MOCKSERVER_BINARY_BASE_URL; }
  }
});

test('assetUrl uses GitHub for release versions', function () {
  var prev = process.env.MOCKSERVER_BINARY_BASE_URL;
  delete process.env.MOCKSERVER_BINARY_BASE_URL;
  try {
    assert.strictEqual(
      binary.assetUrl('7.0.0', 'mockserver-7.0.0-darwin-aarch64.tar.gz'),
      'https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-7.0.0/mockserver-7.0.0-darwin-aarch64.tar.gz');
  } finally {
    if (prev !== undefined) { process.env.MOCKSERVER_BINARY_BASE_URL = prev; }
    else { delete process.env.MOCKSERVER_BINARY_BASE_URL; }
  }
});

test('assetUrl env override wins over SNAPSHOT detection', function () {
  var prev = process.env.MOCKSERVER_BINARY_BASE_URL;
  process.env.MOCKSERVER_BINARY_BASE_URL = 'https://custom-mirror.example.com/bins';
  try {
    assert.strictEqual(
      binary.assetUrl('7.1.0-SNAPSHOT', 'mockserver-7.1.0-SNAPSHOT-linux-x86_64.tar.gz'),
      'https://custom-mirror.example.com/bins/mockserver-7.1.0-SNAPSHOT-linux-x86_64.tar.gz');
  } finally {
    if (prev === undefined) { delete process.env.MOCKSERVER_BINARY_BASE_URL; }
    else { process.env.MOCKSERVER_BINARY_BASE_URL = prev; }
  }
});

// ================================================================
// 3. Cache directory resolution
// ================================================================

test('cacheDir uses MOCKSERVER_BINARY_CACHE when set', function () {
  var prev = process.env.MOCKSERVER_BINARY_CACHE;
  process.env.MOCKSERVER_BINARY_CACHE = '/custom/cache/path';
  try {
    assert.strictEqual(binary.cacheDir(), '/custom/cache/path');
  } finally {
    if (prev === undefined) { delete process.env.MOCKSERVER_BINARY_CACHE; }
    else { process.env.MOCKSERVER_BINARY_CACHE = prev; }
  }
});

test('cacheDir uses XDG_CACHE_HOME on non-Windows', function () {
  if (process.platform === 'win32') { return; } // skip on Windows
  var prevCache = process.env.MOCKSERVER_BINARY_CACHE;
  var prevXdg = process.env.XDG_CACHE_HOME;
  delete process.env.MOCKSERVER_BINARY_CACHE;
  process.env.XDG_CACHE_HOME = '/tmp/xdg-test';
  try {
    assert.strictEqual(binary.cacheDir(), '/tmp/xdg-test/mockserver/binaries');
  } finally {
    if (prevCache === undefined) { delete process.env.MOCKSERVER_BINARY_CACHE; }
    else { process.env.MOCKSERVER_BINARY_CACHE = prevCache; }
    if (prevXdg === undefined) { delete process.env.XDG_CACHE_HOME; }
    else { process.env.XDG_CACHE_HOME = prevXdg; }
  }
});

test('cacheDir falls back to ~/.cache/mockserver/binaries on non-Windows', function () {
  if (process.platform === 'win32') { return; }
  var prevCache = process.env.MOCKSERVER_BINARY_CACHE;
  var prevXdg = process.env.XDG_CACHE_HOME;
  delete process.env.MOCKSERVER_BINARY_CACHE;
  delete process.env.XDG_CACHE_HOME;
  try {
    assert.strictEqual(binary.cacheDir(), path.join(os.homedir(), '.cache', 'mockserver', 'binaries'));
  } finally {
    if (prevCache === undefined) { delete process.env.MOCKSERVER_BINARY_CACHE; }
    else { process.env.MOCKSERVER_BINARY_CACHE = prevCache; }
    if (prevXdg === undefined) { delete process.env.XDG_CACHE_HOME; }
    else { process.env.XDG_CACHE_HOME = prevXdg; }
  }
});

// ================================================================
// 4. Version validation (H1)
// ================================================================

test('validateVersion accepts valid semver versions', function () {
  var valid = ['1.2.3', '7.0.0', '1.2.3-beta.1', '10.20.30-SNAPSHOT', '1.0.0-rc.2'];
  valid.forEach(function (v) {
    assert.doesNotThrow(function () { binary._internal.validateVersion(v); }, 'should accept: ' + v);
  });
});

test('validateVersion rejects invalid versions', function () {
  var invalid = [
    '', null, undefined, 123,
    '../evil', '1.2.3/../../etc/passwd', '1.2.3\\..\\evil',
    'not-a-version', 'v1.2.3', '1.2', '1', 'latest',
    '1.2.3; rm -rf /', '1.2.3 && whoami'
  ];
  invalid.forEach(function (v) {
    assert.throws(function () { binary._internal.validateVersion(v); }, 'should reject: ' + v);
  });
});

// ================================================================
// 5. Path traversal guard (H1)
// ================================================================

test('assertWithinBase allows child paths within base', function () {
  assert.doesNotThrow(function () {
    binary._internal.assertWithinBase('/cache/base/1.2.3', '/cache/base');
  });
  assert.doesNotThrow(function () {
    binary._internal.assertWithinBase('/cache/base/1.2.3/sub', '/cache/base');
  });
});

test('assertWithinBase blocks paths outside base', function () {
  assert.throws(function () {
    binary._internal.assertWithinBase('/cache/other/1.2.3', '/cache/base');
  }, /path traversal blocked/);
  assert.throws(function () {
    binary._internal.assertWithinBase('/different', '/cache/base');
  }, /path traversal blocked/);
});

// ================================================================
// 6. Semver-aware version comparison (H7)
// ================================================================

test('compareVersions sorts semver versions correctly', function () {
  var compare = binary._internal.compareVersions;
  assert.ok(compare('1.0.0', '2.0.0') < 0, '1.0.0 < 2.0.0');
  assert.ok(compare('1.2.0', '1.10.0') < 0, '1.2.0 < 1.10.0 (numeric, not lexicographic)');
  assert.ok(compare('1.9.0', '1.10.0') < 0, '1.9.0 < 1.10.0');
  assert.ok(compare('7.0.0', '7.0.0') === 0, '7.0.0 == 7.0.0');
  assert.ok(compare('2.0.0', '1.0.0') > 0, '2.0.0 > 1.0.0');
  assert.ok(compare('1.0.0', '1.0.1') < 0, '1.0.0 < 1.0.1');
});

test('compareVersions handles pre-release versions', function () {
  var compare = binary._internal.compareVersions;
  // Pre-release labels are compared lexicographically per semver, but our
  // implementation does string compare for non-numeric segments
  assert.ok(compare('1.0.0-alpha', '1.0.0-beta') < 0, 'alpha < beta');
  assert.ok(compare('1.0.0-beta.1', '1.0.0-beta.2') < 0, 'beta.1 < beta.2');
});

// ================================================================
// 7. SHA-256 verification (H2) — correct checksum passes
// ================================================================

test('ensureBinary succeeds with correct SHA-256 checksum', async function () {
  var tmp = makeTempDir('ms-sha-ok-');
  var fixtureDir = path.join(tmp.base, 'fixture');
  var cacheBase = path.join(tmp.base, 'cache');
  fs.mkdirSync(fixtureDir, { recursive: true });

  var prevBase = process.env.MOCKSERVER_BINARY_BASE_URL;
  var prevCache = process.env.MOCKSERVER_BINARY_CACHE;
  var prevSkip = process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD;
  try {
    var fixture = createFixtureArchive(fixtureDir, '9.8.7');
    process.env.MOCKSERVER_BINARY_BASE_URL = 'file://' + fixtureDir;
    process.env.MOCKSERVER_BINARY_CACHE = cacheBase;
    delete process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD;

    var launcher = await binary.ensureBinary('9.8.7');
    assert.ok(fs.existsSync(launcher), 'launcher exists at: ' + launcher);
    assert.ok(fs.statSync(launcher).size > 0, 'launcher is non-empty');
  } finally {
    if (prevBase === undefined) { delete process.env.MOCKSERVER_BINARY_BASE_URL; }
    else { process.env.MOCKSERVER_BINARY_BASE_URL = prevBase; }
    if (prevCache === undefined) { delete process.env.MOCKSERVER_BINARY_CACHE; }
    else { process.env.MOCKSERVER_BINARY_CACHE = prevCache; }
    if (prevSkip === undefined) { delete process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD; }
    else { process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD = prevSkip; }
    tmp.cleanup();
  }
});

// ================================================================
// 7b. Missing release bundle (HTTP 404) — clear, actionable error
// ================================================================

test('ensureBinary gives an actionable error when no bundle exists (HTTP 404)', async function () {
  var http = require('http');
  var tmp = makeTempDir('ms-404-');
  var cacheBase = path.join(tmp.base, 'cache');

  // Local server that 404s every asset (simulates a release tag with no bundle).
  var server = http.createServer(function (req, res) { res.statusCode = 404; res.end('Not Found'); });
  await new Promise(function (resolve) { server.listen(0, '127.0.0.1', resolve); });
  var addr = server.address();

  var prevBase = process.env.MOCKSERVER_BINARY_BASE_URL;
  var prevCache = process.env.MOCKSERVER_BINARY_CACHE;
  var prevSkip = process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD;
  try {
    process.env.MOCKSERVER_BINARY_BASE_URL = 'http://127.0.0.1:' + addr.port + '/mockserver-9.9.9';
    process.env.MOCKSERVER_BINARY_CACHE = cacheBase;
    delete process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD;

    await assert.rejects(
      binary.ensureBinary('9.9.9'),
      function (err) {
        var m = err.message;
        return m.indexOf('9.9.9') !== -1 &&
          m.indexOf('no MockServer release bundle') !== -1 &&
          m.indexOf('docker run mockserver/mockserver:mockserver-9.9.9') !== -1 &&
          m.indexOf('org.mock-server:mockserver-netty:9.9.9') !== -1;
      }
    );
  } finally {
    await new Promise(function (resolve) { server.close(resolve); });
    if (prevBase === undefined) { delete process.env.MOCKSERVER_BINARY_BASE_URL; }
    else { process.env.MOCKSERVER_BINARY_BASE_URL = prevBase; }
    if (prevCache === undefined) { delete process.env.MOCKSERVER_BINARY_CACHE; }
    else { process.env.MOCKSERVER_BINARY_CACHE = prevCache; }
    if (prevSkip === undefined) { delete process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD; }
    else { process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD = prevSkip; }
    tmp.cleanup();
  }
});

// ================================================================
// 8. SHA-256 verification — checksum mismatch fails (H2)
// ================================================================

test('ensureBinary fails on SHA-256 mismatch and cleans up .part', async function () {
  var tmp = makeTempDir('ms-sha-bad-');
  var fixtureDir = path.join(tmp.base, 'fixture');
  var cacheBase = path.join(tmp.base, 'cache');
  fs.mkdirSync(fixtureDir, { recursive: true });

  var prevBase = process.env.MOCKSERVER_BINARY_BASE_URL;
  var prevCache = process.env.MOCKSERVER_BINARY_CACHE;
  var prevSkip = process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD;
  try {
    var fixture = createFixtureArchive(fixtureDir, '9.8.7');
    // Corrupt the checksum file
    var shaPath = fixture.archivePath + '.sha256';
    fs.writeFileSync(shaPath, 'deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef  file.tar.gz\n');
    process.env.MOCKSERVER_BINARY_BASE_URL = 'file://' + fixtureDir;
    process.env.MOCKSERVER_BINARY_CACHE = cacheBase;
    delete process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD;

    await assert.rejects(
      binary.ensureBinary('9.8.7'),
      function (err) { return err.message.indexOf('checksum mismatch') !== -1; }
    );

    // Verify .part AND .sha256 files were cleaned up (H3)
    var verDir = path.join(cacheBase, '9.8.7');
    if (fs.existsSync(verDir)) {
      var files = fs.readdirSync(verDir);
      var partFiles = files.filter(function (f) { return f.endsWith('.part'); });
      assert.strictEqual(partFiles.length, 0, 'no .part files should remain after mismatch');
      var shaFiles = files.filter(function (f) { return f.endsWith('.sha256'); });
      assert.strictEqual(shaFiles.length, 0, 'no .sha256 files should remain after mismatch');
    }
  } finally {
    if (prevBase === undefined) { delete process.env.MOCKSERVER_BINARY_BASE_URL; }
    else { process.env.MOCKSERVER_BINARY_BASE_URL = prevBase; }
    if (prevCache === undefined) { delete process.env.MOCKSERVER_BINARY_CACHE; }
    else { process.env.MOCKSERVER_BINARY_CACHE = prevCache; }
    if (prevSkip === undefined) { delete process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD; }
    else { process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD = prevSkip; }
    tmp.cleanup();
  }
});

// ================================================================
// 9. SHA-256 verification — empty checksum file fails closed (H2)
// ================================================================

test('ensureBinary fails on empty SHA-256 checksum file (fail-closed)', async function () {
  var tmp = makeTempDir('ms-sha-empty-');
  var fixtureDir = path.join(tmp.base, 'fixture');
  var cacheBase = path.join(tmp.base, 'cache');
  fs.mkdirSync(fixtureDir, { recursive: true });

  var prevBase = process.env.MOCKSERVER_BINARY_BASE_URL;
  var prevCache = process.env.MOCKSERVER_BINARY_CACHE;
  var prevSkip = process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD;
  try {
    var fixture = createFixtureArchive(fixtureDir, '9.8.7');
    // Write an empty checksum file
    fs.writeFileSync(fixture.archivePath + '.sha256', '   \n');
    process.env.MOCKSERVER_BINARY_BASE_URL = 'file://' + fixtureDir;
    process.env.MOCKSERVER_BINARY_CACHE = cacheBase;
    delete process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD;

    await assert.rejects(
      binary.ensureBinary('9.8.7'),
      function (err) { return err.message.indexOf('empty or unparseable') !== -1; }
    );
  } finally {
    if (prevBase === undefined) { delete process.env.MOCKSERVER_BINARY_BASE_URL; }
    else { process.env.MOCKSERVER_BINARY_BASE_URL = prevBase; }
    if (prevCache === undefined) { delete process.env.MOCKSERVER_BINARY_CACHE; }
    else { process.env.MOCKSERVER_BINARY_CACHE = prevCache; }
    if (prevSkip === undefined) { delete process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD; }
    else { process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD = prevSkip; }
    tmp.cleanup();
  }
});

// ================================================================
// 10. MOCKSERVER_SKIP_BINARY_DOWNLOAD behaviour
// ================================================================

test('ensureBinary fails when MOCKSERVER_SKIP_BINARY_DOWNLOAD is set and no cached binary', async function () {
  var tmp = makeTempDir('ms-skip-');
  var prevCache = process.env.MOCKSERVER_BINARY_CACHE;
  var prevSkip = process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD;
  try {
    process.env.MOCKSERVER_BINARY_CACHE = path.join(tmp.base, 'empty-cache');
    process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD = '1';

    await assert.rejects(
      binary.ensureBinary('9.8.7'),
      function (err) { return err.message.indexOf('MOCKSERVER_SKIP_BINARY_DOWNLOAD') !== -1; }
    );
  } finally {
    if (prevCache === undefined) { delete process.env.MOCKSERVER_BINARY_CACHE; }
    else { process.env.MOCKSERVER_BINARY_CACHE = prevCache; }
    if (prevSkip === undefined) { delete process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD; }
    else { process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD = prevSkip; }
    tmp.cleanup();
  }
});

// ================================================================
// 11. .sha256 file cleanup on failure (H3)
// ================================================================

test('ensureBinary cleans up .sha256 temp file on failure', async function () {
  var tmp = makeTempDir('ms-sha-cleanup-');
  var fixtureDir = path.join(tmp.base, 'fixture');
  var cacheBase = path.join(tmp.base, 'cache');
  fs.mkdirSync(fixtureDir, { recursive: true });

  var prevBase = process.env.MOCKSERVER_BINARY_BASE_URL;
  var prevCache = process.env.MOCKSERVER_BINARY_CACHE;
  var prevSkip = process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD;
  try {
    var fixture = createFixtureArchive(fixtureDir, '9.8.7');
    // Corrupt the checksum to trigger failure after .sha256 is downloaded
    fs.writeFileSync(fixture.archivePath + '.sha256', '0000000000000000000000000000000000000000000000000000000000000000  file\n');
    process.env.MOCKSERVER_BINARY_BASE_URL = 'file://' + fixtureDir;
    process.env.MOCKSERVER_BINARY_CACHE = cacheBase;
    delete process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD;

    try {
      await binary.ensureBinary('9.8.7');
    } catch (e) {
      // expected
    }

    // Check that .sha256 temp file was cleaned up
    var verDir = path.join(cacheBase, '9.8.7');
    if (fs.existsSync(verDir)) {
      var files = fs.readdirSync(verDir);
      var shaFiles = files.filter(function (f) { return f.endsWith('.sha256'); });
      assert.strictEqual(shaFiles.length, 0, 'no .sha256 files should remain after checksum failure');
      var partFiles = files.filter(function (f) { return f.endsWith('.part'); });
      assert.strictEqual(partFiles.length, 0, 'no .part files should remain after checksum failure');
    }
  } finally {
    if (prevBase === undefined) { delete process.env.MOCKSERVER_BINARY_BASE_URL; }
    else { process.env.MOCKSERVER_BINARY_BASE_URL = prevBase; }
    if (prevCache === undefined) { delete process.env.MOCKSERVER_BINARY_CACHE; }
    else { process.env.MOCKSERVER_BINARY_CACHE = prevCache; }
    if (prevSkip === undefined) { delete process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD; }
    else { process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD = prevSkip; }
    tmp.cleanup();
  }
});

// ================================================================
// 12. Pruning — removes old versions, keeps current (Contract #7)
// ================================================================

test('pruneOldVersions removes old version directories and keeps current', function () {
  var tmp = makeTempDir('ms-prune-');
  try {
    var base = tmp.base;
    // Create version directories
    fs.mkdirSync(path.join(base, '1.0.0'));
    fs.writeFileSync(path.join(base, '1.0.0', 'file.txt'), 'data');
    fs.mkdirSync(path.join(base, '2.0.0'));
    fs.writeFileSync(path.join(base, '2.0.0', 'file.txt'), 'data');
    fs.mkdirSync(path.join(base, '3.0.0'));
    fs.writeFileSync(path.join(base, '3.0.0', 'file.txt'), 'data');

    binary.pruneOldVersions(base, '3.0.0', 0);

    assert.ok(fs.existsSync(path.join(base, '3.0.0')), 'current version preserved');
    assert.ok(!fs.existsSync(path.join(base, '1.0.0')), '1.0.0 removed');
    assert.ok(!fs.existsSync(path.join(base, '2.0.0')), '2.0.0 removed');
  } finally {
    tmp.cleanup();
  }
});

// ================================================================
// 13. Pruning — maxPrevious retains extra versions
// ================================================================

test('pruneOldVersions keeps maxPrevious older versions', function () {
  var tmp = makeTempDir('ms-prune-keep-');
  try {
    var base = tmp.base;
    fs.mkdirSync(path.join(base, '1.0.0'));
    fs.mkdirSync(path.join(base, '2.0.0'));
    fs.mkdirSync(path.join(base, '3.0.0'));
    fs.mkdirSync(path.join(base, '4.0.0'));

    binary.pruneOldVersions(base, '4.0.0', 1);

    assert.ok(fs.existsSync(path.join(base, '4.0.0')), 'current version preserved');
    assert.ok(fs.existsSync(path.join(base, '3.0.0')), 'previous version preserved (maxPrevious=1)');
    assert.ok(!fs.existsSync(path.join(base, '2.0.0')), '2.0.0 removed');
    assert.ok(!fs.existsSync(path.join(base, '1.0.0')), '1.0.0 removed');
  } finally {
    tmp.cleanup();
  }
});

// ================================================================
// 14. Pruning — semver sort order (not lexicographic) (H7)
// ================================================================

test('pruneOldVersions uses semver-aware sort (1.9.0 < 1.10.0)', function () {
  var tmp = makeTempDir('ms-prune-semver-');
  try {
    var base = tmp.base;
    // With lexicographic sort, "1.9.0" > "1.10.0" — wrong!
    fs.mkdirSync(path.join(base, '1.2.0'));
    fs.mkdirSync(path.join(base, '1.9.0'));
    fs.mkdirSync(path.join(base, '1.10.0'));
    fs.mkdirSync(path.join(base, '1.11.0'));

    binary.pruneOldVersions(base, '1.11.0', 1);

    assert.ok(fs.existsSync(path.join(base, '1.11.0')), 'current preserved');
    assert.ok(fs.existsSync(path.join(base, '1.10.0')), '1.10.0 kept (is second-newest numerically)');
    assert.ok(!fs.existsSync(path.join(base, '1.9.0')), '1.9.0 removed');
    assert.ok(!fs.existsSync(path.join(base, '1.2.0')), '1.2.0 removed');
  } finally {
    tmp.cleanup();
  }
});

// ================================================================
// 15. Pruning — cleans leftover .part and .sha256 temp files
// ================================================================

test('pruneOldVersions removes leftover .part and .sha256 files', function () {
  var tmp = makeTempDir('ms-prune-temp-');
  try {
    var base = tmp.base;
    fs.mkdirSync(path.join(base, '1.0.0'));
    fs.writeFileSync(path.join(base, 'archive.tar.gz.part'), 'partial');
    fs.writeFileSync(path.join(base, 'archive.tar.gz.sha256'), 'hash');
    fs.writeFileSync(path.join(base, 'other.part'), 'leftover');

    binary.pruneOldVersions(base, '1.0.0', 0);

    assert.ok(fs.existsSync(path.join(base, '1.0.0')), 'current version preserved');
    assert.ok(!fs.existsSync(path.join(base, 'archive.tar.gz.part')), '.part cleaned');
    assert.ok(!fs.existsSync(path.join(base, 'archive.tar.gz.sha256')), '.sha256 cleaned');
    assert.ok(!fs.existsSync(path.join(base, 'other.part')), 'other .part cleaned');
  } finally {
    tmp.cleanup();
  }
});

// ================================================================
// 16. Pruning — non-version entries left untouched
// ================================================================

test('pruneOldVersions leaves non-version entries untouched', function () {
  var tmp = makeTempDir('ms-prune-nonver-');
  try {
    var base = tmp.base;
    fs.mkdirSync(path.join(base, '1.0.0'));
    fs.mkdirSync(path.join(base, 'not-a-version'));
    fs.writeFileSync(path.join(base, 'README.txt'), 'info');

    binary.pruneOldVersions(base, '1.0.0', 0);

    assert.ok(fs.existsSync(path.join(base, 'not-a-version')), 'non-version dir preserved');
    assert.ok(fs.existsSync(path.join(base, 'README.txt')), 'non-version file preserved');
  } finally {
    tmp.cleanup();
  }
});

// ================================================================
// 17. Pruning — safe with empty or nonexistent base directory
// ================================================================

test('pruneOldVersions handles nonexistent base gracefully', function () {
  assert.doesNotThrow(function () {
    binary.pruneOldVersions('/nonexistent/path/that/does/not/exist', '1.0.0', 0);
  });
});

test('pruneOldVersions handles empty base directory', function () {
  var tmp = makeTempDir('ms-prune-empty-');
  try {
    assert.doesNotThrow(function () {
      binary.pruneOldVersions(tmp.base, '1.0.0', 0);
    });
  } finally {
    tmp.cleanup();
  }
});

// ================================================================
// 18. Pruning — deeply nested content removed correctly
// ================================================================

test('pruneOldVersions removes deeply nested content in old versions', function () {
  var tmp = makeTempDir('ms-prune-nested-');
  try {
    var base = tmp.base;
    var deep = path.join(base, '1.0.0', 'a', 'b', 'c');
    fs.mkdirSync(deep, { recursive: true });
    fs.writeFileSync(path.join(deep, 'file.txt'), 'deep content');
    fs.mkdirSync(path.join(base, '2.0.0'));

    binary.pruneOldVersions(base, '2.0.0', 0);

    assert.ok(!fs.existsSync(path.join(base, '1.0.0')), '1.0.0 and all its contents removed');
    assert.ok(fs.existsSync(path.join(base, '2.0.0')), 'current preserved');
  } finally {
    tmp.cleanup();
  }
});

// ================================================================
// 19. ensureBinary triggers pruning after successful download
// ================================================================

test('ensureBinary triggers pruning after successful install (maxPrevious=1)', async function () {
  var tmp = makeTempDir('ms-prune-e2e-');
  var fixtureDir = path.join(tmp.base, 'fixture');
  var cacheBase = path.join(tmp.base, 'cache');
  fs.mkdirSync(fixtureDir, { recursive: true });
  fs.mkdirSync(cacheBase, { recursive: true });

  var prevBase = process.env.MOCKSERVER_BINARY_BASE_URL;
  var prevCache = process.env.MOCKSERVER_BINARY_CACHE;
  var prevSkip = process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD;
  try {
    // Pre-seed TWO old versions in the cache; with maxPrevious=1 the newest
    // previous (2.0.0) should be kept and the older one (1.0.0) pruned
    var oldDir1 = path.join(cacheBase, '1.0.0');
    fs.mkdirSync(oldDir1, { recursive: true });
    fs.writeFileSync(path.join(oldDir1, 'marker.txt'), 'old1');
    var oldDir2 = path.join(cacheBase, '2.0.0');
    fs.mkdirSync(oldDir2, { recursive: true });
    fs.writeFileSync(path.join(oldDir2, 'marker.txt'), 'old2');

    var fixture = createFixtureArchive(fixtureDir, '9.8.7');
    process.env.MOCKSERVER_BINARY_BASE_URL = 'file://' + fixtureDir;
    process.env.MOCKSERVER_BINARY_CACHE = cacheBase;
    delete process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD;

    await binary.ensureBinary('9.8.7');

    // maxPrevious=1: keep current (9.8.7) + 1 previous (2.0.0), prune the rest (1.0.0)
    assert.ok(fs.existsSync(path.join(cacheBase, '9.8.7')), 'new version present');
    assert.ok(fs.existsSync(oldDir2), '2.0.0 kept (maxPrevious=1, newest previous)');
    assert.ok(!fs.existsSync(oldDir1), '1.0.0 pruned (beyond maxPrevious=1)');
  } finally {
    if (prevBase === undefined) { delete process.env.MOCKSERVER_BINARY_BASE_URL; }
    else { process.env.MOCKSERVER_BINARY_BASE_URL = prevBase; }
    if (prevCache === undefined) { delete process.env.MOCKSERVER_BINARY_CACHE; }
    else { process.env.MOCKSERVER_BINARY_CACHE = prevCache; }
    if (prevSkip === undefined) { delete process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD; }
    else { process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD = prevSkip; }
    tmp.cleanup();
  }
});

// ================================================================
// 20. ensureBinary uses cached binary without re-downloading
// ================================================================

test('ensureBinary uses cached binary without downloading', async function () {
  var tmp = makeTempDir('ms-cached-');
  var cacheBase = path.join(tmp.base, 'cache');

  var prevBase = process.env.MOCKSERVER_BINARY_BASE_URL;
  var prevCache = process.env.MOCKSERVER_BINARY_CACHE;
  var prevSkip = process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD;
  try {
    process.env.MOCKSERVER_BINARY_CACHE = cacheBase;
    // MOCKSERVER_BINARY_BASE_URL points nowhere — download would fail if attempted
    process.env.MOCKSERVER_BINARY_BASE_URL = 'file:///nonexistent';
    delete process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD;

    // Pre-seed a cached launcher
    var meta = binary.bundleBaseName('9.8.7');
    var verDir = path.join(cacheBase, '9.8.7');
    var binDir = path.join(verDir, meta.name, 'bin');
    fs.mkdirSync(binDir, { recursive: true });
    var launcherName = process.platform === 'win32' ? 'mockserver.bat' : 'mockserver';
    var launcherFile = path.join(binDir, launcherName);
    fs.writeFileSync(launcherFile, '#!/bin/sh\necho stub\n');

    var launcher = await binary.ensureBinary('9.8.7');
    assert.strictEqual(launcher, launcherFile, 'returns cached launcher path');
  } finally {
    if (prevBase === undefined) { delete process.env.MOCKSERVER_BINARY_BASE_URL; }
    else { process.env.MOCKSERVER_BINARY_BASE_URL = prevBase; }
    if (prevCache === undefined) { delete process.env.MOCKSERVER_BINARY_CACHE; }
    else { process.env.MOCKSERVER_BINARY_CACHE = prevCache; }
    if (prevSkip === undefined) { delete process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD; }
    else { process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD = prevSkip; }
    tmp.cleanup();
  }
});

// ================================================================
// 21. ensureBinary rejects path-traversal version (H1)
// ================================================================

test('ensureBinary rejects path-traversal version', async function () {
  await assert.rejects(
    binary.ensureBinary('../../etc/passwd'),
    function (err) { return err.message.indexOf('invalid version') !== -1; }
  );
});

// ================================================================
// 22. Pre-release sorts LOWER than release in compareVersions (H7)
// ================================================================

test('compareVersions: pre-release versions sort lower than release (H7)', function () {
  var compare = binary._internal.compareVersions;
  // Per semver, 1.0.0-SNAPSHOT < 1.0.0 (release outranks pre-release)
  assert.ok(compare('1.0.0-SNAPSHOT', '1.0.0') < 0, '1.0.0-SNAPSHOT < 1.0.0');
  assert.ok(compare('1.0.0', '1.0.0-SNAPSHOT') > 0, '1.0.0 > 1.0.0-SNAPSHOT');
  assert.ok(compare('7.0.0-rc.1', '7.0.0') < 0, '7.0.0-rc.1 < 7.0.0');
  assert.ok(compare('7.0.0', '7.0.0-rc.1') > 0, '7.0.0 > 7.0.0-rc.1');
  assert.ok(compare('2.0.0-alpha', '2.0.0-beta') < 0, 'alpha < beta (both pre-release)');
  assert.ok(compare('1.0.0-SNAPSHOT', '1.0.1-SNAPSHOT') < 0, '1.0.0-SNAPSHOT < 1.0.1-SNAPSHOT');
});

// ================================================================
// 23. Pruning prefers release over pre-release (H7)
// ================================================================

test('pruneOldVersions keeps release over SNAPSHOT when maxPrevious=1 (H7)', function () {
  var tmp = makeTempDir('ms-prune-prerelease-');
  try {
    var base = tmp.base;
    // Both 1.0.0-SNAPSHOT and 1.0.0 present; current is 2.0.0
    fs.mkdirSync(path.join(base, '1.0.0-SNAPSHOT'));
    fs.mkdirSync(path.join(base, '1.0.0'));
    fs.mkdirSync(path.join(base, '2.0.0'));

    binary.pruneOldVersions(base, '2.0.0', 1);

    assert.ok(fs.existsSync(path.join(base, '2.0.0')), 'current preserved');
    // 1.0.0 (release) is newer than 1.0.0-SNAPSHOT per semver, so it should be kept
    assert.ok(fs.existsSync(path.join(base, '1.0.0')), '1.0.0 kept (release outranks SNAPSHOT)');
    assert.ok(!fs.existsSync(path.join(base, '1.0.0-SNAPSHOT')), '1.0.0-SNAPSHOT pruned');
  } finally {
    tmp.cleanup();
  }
});

// ================================================================
// 23b. Pruning — release never deleted in favour of its SNAPSHOT (default maxPrevious)
// ================================================================

test('pruneOldVersions with default maxPrevious=1 keeps release, prunes SNAPSHOT', function () {
  var tmp = makeTempDir('ms-prune-default-');
  try {
    var base = tmp.base;
    // Simulate: current=3.0.0, two previous: 2.0.0 (release) and 2.0.0-SNAPSHOT
    // With maxPrevious=1 (the production default), the pruner should keep 2.0.0
    // (release outranks SNAPSHOT) and prune 2.0.0-SNAPSHOT
    fs.mkdirSync(path.join(base, '2.0.0-SNAPSHOT'));
    fs.mkdirSync(path.join(base, '2.0.0'));
    fs.mkdirSync(path.join(base, '3.0.0'));

    // Call without explicit maxPrevious to test the default
    binary.pruneOldVersions(base, '3.0.0');

    assert.ok(fs.existsSync(path.join(base, '3.0.0')), 'current preserved');
    assert.ok(fs.existsSync(path.join(base, '2.0.0')), '2.0.0 release kept (default maxPrevious=1)');
    assert.ok(!fs.existsSync(path.join(base, '2.0.0-SNAPSHOT')), '2.0.0-SNAPSHOT pruned (release outranks)');
  } finally {
    tmp.cleanup();
  }
});

// ================================================================
// 24. escapeCmdArg uses cmd.exe-correct quoting (H4)
// ================================================================

test('escapeCmdArg uses doubled quotes and handles trailing backslash (H4)', function () {
  var escape = binary._internal.escapeCmdArg;
  // Simple args pass through without quoting
  assert.strictEqual(escape('--serverPort'), '--serverPort');
  assert.strictEqual(escape('1080'), '1080');
  // Args with spaces get quoted
  assert.strictEqual(escape('hello world'), '"hello world"');
  // Internal double quotes are doubled (not backslash-escaped)
  assert.strictEqual(escape('say "hi"'), '"say ""hi"""');
  // Trailing backslash is doubled to prevent escaping the closing quote
  assert.strictEqual(escape('C:\\Users\\test\\'), '"C:\\Users\\test\\\\"');
  // Multiple trailing backslashes are all doubled
  assert.strictEqual(escape('path\\\\'), '"path\\\\\\\\"');
});

// ================================================================
// 25. VERSION_PATTERN rejects four-segment versions (observation fix)
// ================================================================

test('VERSION_PATTERN rejects four-segment versions like 1.2.3.4', function () {
  var pattern = binary._internal.VERSION_PATTERN;
  assert.ok(!pattern.test('1.2.3.4'), '1.2.3.4 should be rejected');
  assert.ok(!pattern.test('1.2.3.4.5'), '1.2.3.4.5 should be rejected');
  // Valid versions still pass
  assert.ok(pattern.test('1.2.3'), '1.2.3 should be accepted');
  assert.ok(pattern.test('1.2.3-beta.1'), '1.2.3-beta.1 should be accepted');
  assert.ok(pattern.test('1.2.3-SNAPSHOT'), '1.2.3-SNAPSHOT should be accepted');
  assert.ok(pattern.test('7.0.0-rc.2'), '7.0.0-rc.2 should be accepted');
});

// ================================================================
// 26. Integration test — SKIPPED when no real bundle is available
// ================================================================

test('integration: full download-verify-extract cycle (requires real bundle)', { skip: !process.env.MOCKSERVER_INTEGRATION_TEST ? 'set MOCKSERVER_INTEGRATION_TEST=1 and ensure a real release bundle is available' : false }, async function () {
  // This test is skipped by default. To run it:
  //   MOCKSERVER_INTEGRATION_TEST=1 npm test
  // It requires the real MockServer release bundle to be downloadable.
  var version = require('../package.json').version;
  var tmp = makeTempDir('ms-integration-');
  var prevCache = process.env.MOCKSERVER_BINARY_CACHE;
  try {
    process.env.MOCKSERVER_BINARY_CACHE = path.join(tmp.base, 'cache');
    var launcher = await binary.ensureBinary(version, { log: console.error.bind(console) });
    assert.ok(fs.existsSync(launcher), 'launcher exists');
    assert.ok(fs.statSync(launcher).size > 0, 'launcher is non-empty');
  } finally {
    if (prevCache === undefined) { delete process.env.MOCKSERVER_BINARY_CACHE; }
    else { process.env.MOCKSERVER_BINARY_CACHE = prevCache; }
    tmp.cleanup();
  }
});
