#!/usr/bin/env node
/*
 * `mockserver` CLI shim — downloads the self-contained MockServer binary bundle
 * for this platform on first use (no JVM, no Docker) and runs it, forwarding all
 * arguments to the bundled `mockserver` launcher.
 *
 *   npx -p mockserver-node mockserver run -p 1080
 *   npx -p mockserver-node mockserver --openapi ./petstore.yaml -p 1080
 *
 * Licensed under the Apache License, Version 2.0
 */
'use strict';

const { runBinary } = require('../downloadBinary');
const version = require('../package.json').version;

runBinary(version, process.argv.slice(2), { log: function (m) { console.error('[mockserver] ' + m); } })
  .then(function (child) {
    child.on('exit', function (code, signal) { process.exit(signal ? 1 : (code === null ? 1 : code)); });
    ['SIGINT', 'SIGTERM'].forEach(function (sig) { process.on(sig, function () { child.kill(sig); }); });
  })
  .catch(function (err) {
    console.error('[mockserver] ' + (err && err.message ? err.message : err));
    process.exit(1);
  });
