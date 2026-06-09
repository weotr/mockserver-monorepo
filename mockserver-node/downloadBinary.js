/*
 * mockserver-node — on-demand binary launcher
 * http://mock-server.com
 *
 * Downloads the self-contained, JVM-less MockServer bundle (a jlink runtime +
 * the server + a `mockserver` launcher) for the current platform from the
 * GitHub Release, verifies its SHA-256, caches it per-user, and launches it.
 * No Java installation and no Docker required.
 *
 * This is the reference implementation of the on-demand-binary pattern (à la
 * esbuild / Playwright) for the MockServer client libraries.
 *
 * Environment overrides:
 *   MOCKSERVER_BINARY_BASE_URL    mirror host for the release assets (corporate / air-gapped)
 *   MOCKSERVER_BINARY_CACHE       cache directory (default: per-OS user cache)
 *   MOCKSERVER_SKIP_BINARY_DOWNLOAD  fail instead of downloading (air-gapped CI with a pre-seeded cache)
 *   NODE_EXTRA_CA_CERTS           extra CA bundle for TLS-inspecting proxies (honoured natively by Node)
 *   HTTPS_PROXY / HTTP_PROXY      used if an https/http proxy agent module is installed (else use a mirror)
 *
 * Licensed under the Apache License, Version 2.0
 */
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { spawn, spawnSync } = require('child_process');
const followRedirects = require('follow-redirects');

const REPO = 'mock-server/mockserver-monorepo';

// Map Node's platform/arch to the bundle's {os}-{arch} naming + archive type.
function resolvePlatform() {
  const p = process.platform;
  const a = process.arch;
  let osName, ext;
  if (p === 'linux') { osName = 'linux'; ext = 'tar.gz'; }
  else if (p === 'darwin') { osName = 'darwin'; ext = 'tar.gz'; }
  else if (p === 'win32') { osName = 'windows'; ext = 'zip'; }
  else { throw new Error('unsupported platform: ' + p); }
  let arch;
  if (a === 'x64') { arch = 'x86_64'; }
  else if (a === 'arm64') { arch = 'aarch64'; }
  else { throw new Error('unsupported architecture: ' + a); }
  return { osName, arch, ext };
}

function bundleBaseName(version) {
  const { osName, arch, ext } = resolvePlatform();
  return { name: 'mockserver-' + version + '-' + osName + '-' + arch, ext };
}

function cacheDir() {
  if (process.env.MOCKSERVER_BINARY_CACHE) { return process.env.MOCKSERVER_BINARY_CACHE; }
  const base = process.platform === 'win32' ?
    (process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local')) :
    (process.env.XDG_CACHE_HOME || path.join(os.homedir(), '.cache'));
  return path.join(base, 'mockserver', 'binaries');
}

function assetUrl(version, file) {
  const base = process.env.MOCKSERVER_BINARY_BASE_URL ||
    ('https://github.com/' + REPO + '/releases/download/mockserver-' + version);
  return base.replace(/\/+$/, '') + '/' + file;
}

function launcherPath(dir, name) {
  return path.join(dir, name, 'bin', process.platform === 'win32' ? 'mockserver.bat' : 'mockserver');
}

// Optional proxy agent — only if the user has installed one; otherwise a mirror
// (MOCKSERVER_BINARY_BASE_URL) is the recommended corporate path.
function proxyAgent(targetUrl) {
  const proxy = targetUrl.indexOf('https:') === 0 ?
    (process.env.HTTPS_PROXY || process.env.https_proxy) :
    (process.env.HTTP_PROXY || process.env.http_proxy);
  if (!proxy) { return undefined; }
  try {
    const { HttpsProxyAgent } = require('https-proxy-agent');
    return new HttpsProxyAgent(proxy);
  } catch (e) {
    return undefined; // not installed — fall through (works if no proxy is actually required)
  }
}

function download(url, dest) {
  return new Promise(function (resolve, reject) {
    const lib = url.indexOf('https:') === 0 ? followRedirects.https : followRedirects.http;
    const req = lib.get(url, { agent: proxyAgent(url) }, function (res) {
      if (res.statusCode < 200 || res.statusCode >= 300) {
        res.resume();
        reject(new Error('download ' + url + ' failed: HTTP ' + res.statusCode));
        return;
      }
      const out = fs.createWriteStream(dest);
      res.on('error', reject); // pipe() does not forward readable errors to the writable
      out.on('error', reject);
      res.pipe(out);
      out.on('finish', function () { out.close(function () { resolve(); }); });
    });
    req.on('error', reject);
  });
}

function sha256(file) {
  return new Promise(function (resolve, reject) {
    const hash = crypto.createHash('sha256');
    fs.createReadStream(file)
      .on('data', function (d) { hash.update(d); })
      .on('error', reject)
      .on('end', function () { resolve(hash.digest('hex')); });
  });
}

// Ensure the platform bundle is present and return the launcher path,
// downloading + verifying + extracting + caching on first use.
async function ensureBinary(version, opts) {
  opts = opts || {};
  const log = opts.log || function () {};
  const meta = bundleBaseName(version);
  const dir = path.join(cacheDir(), version);
  const launcher = launcherPath(dir, meta.name);

  if (fs.existsSync(launcher) && fs.statSync(launcher).size > 0) {
    log('Using cached binary: ' + launcher);
    return launcher;
  }
  if (process.env.MOCKSERVER_SKIP_BINARY_DOWNLOAD) {
    throw new Error('MOCKSERVER_SKIP_BINARY_DOWNLOAD is set but no cached binary at ' + launcher);
  }

  fs.mkdirSync(dir, { recursive: true });
  const archive = path.join(dir, meta.name + '.' + meta.ext);
  const partial = archive + '.part';
  try {
    // Download to a temp file and rename only after the checksum passes, so an
    // interrupted download never leaves a truncated archive that looks complete.
    log('Downloading ' + assetUrl(version, meta.name + '.' + meta.ext));
    await download(assetUrl(version, meta.name + '.' + meta.ext), partial);

    // Verify the published SHA-256 (skip only if explicitly disabled). Fail
    // closed on a missing/empty/unparseable checksum, not only on a mismatch.
    if (opts.requireChecksum !== false) {
      const shaFile = archive + '.sha256';
      await download(assetUrl(version, meta.name + '.' + meta.ext + '.sha256'), shaFile);
      const expected = fs.readFileSync(shaFile, 'utf8').trim().split(/\s+/)[0];
      if (!expected) { throw new Error('checksum file for ' + meta.name + ' is empty or unparseable'); }
      const actual = await sha256(partial);
      if (expected !== actual) {
        throw new Error('checksum mismatch for ' + meta.name + ': expected ' + expected + ', got ' + actual);
      }
      log('Checksum verified');
    }
    fs.renameSync(partial, archive);
  } catch (e) {
    try { fs.unlinkSync(partial); } catch (ignore) { /* best effort cleanup */ }
    throw e;
  }

  // Extract with the system tar: GNU tar auto-detects gzip; bsdtar (macOS,
  // Windows 10+) also handles .zip — so `tar -xf` covers every bundle type.
  log('Extracting ' + archive);
  const r = spawnSync('tar', ['-xf', archive, '-C', dir], { stdio: 'inherit' });
  if (r.error) { throw new Error('could not run tar (is it installed?): ' + r.error.message); }
  if (r.status !== 0) { throw new Error('extraction failed (tar exit ' + r.status + ')'); }
  if (!(fs.existsSync(launcher) && fs.statSync(launcher).size > 0)) {
    throw new Error('launcher missing or empty after extract: ' + launcher);
  }
  if (process.platform !== 'win32') { fs.chmodSync(launcher, 0o755); }
  return launcher;
}

// Download (if needed) and spawn the binary with the given args; returns the child process.
function runBinary(version, args, opts) {
  opts = opts || {};
  return ensureBinary(version, opts).then(function (launcher) {
    // shell:true on Windows so the `.bat` launcher can be executed (spawn can't
    // run .bat/.cmd directly).
    return spawn(launcher, args || [], Object.assign(
      { stdio: 'inherit', shell: process.platform === 'win32' },
      opts.spawnOptions || {}));
  });
}

module.exports = {
  resolvePlatform: resolvePlatform,
  bundleBaseName: bundleBaseName,
  cacheDir: cacheDir,
  assetUrl: assetUrl,
  ensureBinary: ensureBinary,
  runBinary: runBinary
};
