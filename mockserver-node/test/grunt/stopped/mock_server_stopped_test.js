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
var http = require('http');
var mockserver = require(__dirname + '/../../..');

function sendRequest(method, host, port, path, jsonBody) {
    return new Promise(function (resolve, reject) {
        var body = (typeof jsonBody === "string" ? jsonBody : JSON.stringify(jsonBody || ""));
        var options = {
            method: method,
            host: host,
            path: path,
            port: port
        };

        var req = http.request(options);

        req.once('response', function (response) {
            var data = '';

            if (response.statusCode === 400 || response.statusCode === 404) {
                reject(response.statusCode);
            }

            response.on('data', function (chunk) {
                data += chunk;
            });

            response.on('end', function () {
                resolve({
                    statusCode: response.statusCode,
                    body: data
                });
            });
        });

        req.once('error', function (error) {
            reject(error);
        });

        req.write(body);
        req.end();
    });
}

var port = 1085;

test('mock server has stopped - should fail when attempting to setup expectation', async function () {
    await mockserver.start_mockserver({serverPort: port, mockServerVersion: "6.0.0"});
    await mockserver.stop_mockserver({serverPort: port});

    // wait for the server to fully shut down
    await new Promise(function (resolve) { setTimeout(resolve, 500); });

    await assert.rejects(
        sendRequest("PUT", "localhost", port, "/expectation", {
            'httpRequest': {
                'path': '/somePath'
            },
            'httpResponse': {
                'statusCode': 201,
                'body': JSON.stringify({name: 'first_body'})
            }
        }),
        function () {
            // Any rejection (connection refused) means the server is stopped - this is expected
            return true;
        },
        "did not allow expectation to be setup"
    );
});
