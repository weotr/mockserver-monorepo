/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

'use strict';

var test = require('node:test');
var assert = require('node:assert');
var path = require('path');
var exec = require('child_process').exec;
var execOptions = {
    cwd: path.join(__dirname)
};

test('mock server fails to start - should fail start if configuration missing', function (t, done) {
    exec('../../../node_modules/.bin/grunt start_mockserver:missing_ports', execOptions, function (error, stdout, stderr) {
        stderr = stderr.replace(/\(node:\d*\) ExperimentalWarning: queueMicrotask\(\) is experimental\.\n/, '');
        assert.strictEqual(
            stderr,
            "Please specify \"serverPort\", for example: \"start_mockserver({ serverPort: 1080 })\"\n" +
            "\n" +
            "mockserver-node - you must at least specify serverPort, for example:\n" +
            "start_mockserver: {\n" +
            "    options: {\n" +
            "        serverPort: 1080\n" +
            "    }\n" +
            "}\n" +
            "\n"
        );
        done();
    });
});

test('mock server fails to stop - should fail stop if configuration missing', function (t, done) {
    exec('../../../node_modules/.bin/grunt stop_mockserver:missing_ports', execOptions, function (error, stdout, stderr) {
        stderr = stderr.replace(/\(node:\d*\) ExperimentalWarning: queueMicrotask\(\) is experimental\.\n/, '');
        assert.strictEqual(
            stderr,
            "Please specify \"serverPort\", for example: \"stop_mockserver({ serverPort: 1080 })\"\n" +
            "\n" +
            "mockserver-node - you must at least specify serverPort, for example:\n" +
            "stop_mockserver: {\n" +
            "    options: {\n" +
            "        serverPort: 1080\n" +
            "    }\n" +
            "}\n" +
            "\n"
        );
        done();
    });
});
