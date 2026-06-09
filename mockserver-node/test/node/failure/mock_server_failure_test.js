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
var mockserver = require(__dirname + '/../../..');

test('mock server fails to start - if configuration missing', async function () {
    await assert.rejects(
        mockserver.start_mockserver(),
        function (error) {
            assert.strictEqual(error, 'Please specify "serverPort", for example: "start_mockserver({ serverPort: 1080 })"');
            return true;
        }
    );
});

test('mock server fails to start - if port is missing', async function () {
    var options = {};
    await assert.rejects(
        mockserver.start_mockserver(options),
        function (error) {
            assert.strictEqual(error, 'Please specify "serverPort", for example: "start_mockserver({ serverPort: 1080 })"');
            return true;
        }
    );
});

test('mock server fails to start - if deprecated option "systemProperties" is given', async function () {
    await assert.rejects(
        mockserver.start_mockserver({
            serverPort: 1080,
            systemProperties: '--foo'
        }),
        function (error) {
            assert.strictEqual(error, 'The option "systemProperties" was renamed to "jvmOptions" in 5.4.1. Please migrate to the new option name');
            return true;
        }
    );
});

test('mock server fails to stop - if configuration is missing', async function () {
    await assert.rejects(
        mockserver.stop_mockserver(),
        function (error) {
            assert.strictEqual(error, 'Please specify "serverPort", for example: "stop_mockserver({ serverPort: 1080 })"');
            return true;
        }
    );
});

test('mock server fails to stop - if port is missing', async function () {
    var options = {};
    await assert.rejects(
        mockserver.stop_mockserver(options),
        function (error) {
            assert.strictEqual(error, 'Please specify "serverPort", for example: "stop_mockserver({ serverPort: 1080 })"');
            return true;
        }
    );
});
