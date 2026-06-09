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

var port = 1080;

test('mock server should have started - should allow expectation to be setup', async function () {
    await mockserver.start_mockserver({
        serverPort: port,
        jvmOptions: [
            '-Dmockserver.enableCORSForAllResponses=true',
            '-Dmockserver.corsAllowMethods="CONNECT, DELETE, GET, HEAD, OPTIONS, POST, PUT, PATCH, TRACE"',
            '-Dmockserver.corsAllowHeaders="Allow, Content-Encoding, Content-Length, Content-Type, ETag, Expires, Last-Modified, Location, Server, Vary, Authorization"',
            '-Dmockserver.corsAllowCredentials=true -Dmockserver.corsMaxAgeInSeconds=300'
        ],
        mockServerVersion: "6.0.0"
    });
    try {
        var response = await sendRequest("PUT", "localhost", port, "/expectation", {
            'httpRequest': {
                'path': '/somePath'
            },
            'httpResponse': {
                'statusCode': 202,
                'body': JSON.stringify({name: 'first_body'})
            }
        });
        assert.strictEqual(response.statusCode, 201, "allows expectation to be setup");

        var matchResponse = await sendRequest("GET", "localhost", port, "/somePath");
        assert.strictEqual(matchResponse.statusCode, 202, "expectation matched successfully");
    } finally {
        await mockserver.stop_mockserver({serverPort: port});
    }
});
