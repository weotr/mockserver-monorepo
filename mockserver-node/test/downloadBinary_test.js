'use strict';

// Network-free unit tests for the on-demand binary launcher (downloadBinary.js).
// The download/extract/launch path is covered by the release-time integration
// test; here we assert the platform mapping and URL/mirror logic.

const test = require('node:test');
const assert = require('node:assert');
const binary = require('../downloadBinary');

test('resolvePlatform maps to a supported os/arch and archive type', function () {
  const p = binary.resolvePlatform();
  assert.ok(['linux', 'darwin', 'windows'].indexOf(p.osName) !== -1, 'osName');
  assert.ok(['x86_64', 'aarch64'].indexOf(p.arch) !== -1, 'arch');
  assert.strictEqual(p.ext, p.osName === 'windows' ? 'zip' : 'tar.gz');
});

test('bundleBaseName follows mockserver-<version>-<os>-<arch>', function () {
  const meta = binary.bundleBaseName('1.2.3');
  assert.match(meta.name, /^mockserver-1\.2\.3-(linux|darwin|windows)-(x86_64|aarch64)$/);
  assert.ok(meta.ext === 'tar.gz' || meta.ext === 'zip');
});

test('assetUrl honours the MOCKSERVER_BINARY_BASE_URL mirror', function () {
  const prev = process.env.MOCKSERVER_BINARY_BASE_URL;
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
  const prev = process.env.MOCKSERVER_BINARY_BASE_URL;
  delete process.env.MOCKSERVER_BINARY_BASE_URL;
  try {
    assert.strictEqual(
      binary.assetUrl('1.2.3', 'x.tar.gz'),
      'https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-1.2.3/x.tar.gz');
  } finally {
    if (prev !== undefined) { process.env.MOCKSERVER_BINARY_BASE_URL = prev; }
  }
});
