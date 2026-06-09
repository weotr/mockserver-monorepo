/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

'use strict';

module.exports = function (grunt) {

    grunt.initConfig({
        exec: {
            stop_existing_mockservers: './stop_MockServer.sh',
            typecheck: 'npx tsc',
            node_test: 'node --test --test-force-exit --test-concurrency=1 test/no_proxy/mock_server_node_client_test.js test/with_proxy/proxy_client_node_test.js'
        },
        jshint: {
            options: {
                jshintrc: '.jshintrc'
            },
            user_defaults: [
                'Gruntfile.js',
                'js/**/*.js',
                '!js/lib/**/*.js'
            ]
        },
        start_mockserver: {
            options: {
                serverPort: parseInt(process.env.MOCKSERVER_PORT, 10) || 1080,
                jvmOptions: [
                    '-Dmockserver.enableCORSForAllResponses=true',
                    '-Dmockserver.corsAllowMethods="CONNECT, DELETE, GET, HEAD, OPTIONS, POST, PUT, PATCH, TRACE"',
                    '-Dmockserver.corsAllowHeaders="Allow, Content-Encoding, Content-Length, Content-Type, ETag, Expires, Last-Modified, Location, Server, Vary, Authorization"',
                    '-Dmockserver.corsAllowCredentials=true -Dmockserver.corsMaxAgeInSeconds=300'
                ],
                mockServerVersion: process.env.MOCKSERVER_VERSION || require('./package.json').version,
                verbose: false
            }
        },
        stop_mockserver: {
            options: {
                serverPort: parseInt(process.env.MOCKSERVER_PORT, 10) || 1080
            }
        },
    });

    grunt.loadNpmTasks('grunt-exec');
    grunt.loadNpmTasks('mockserver-node');
    grunt.loadNpmTasks('grunt-contrib-jshint');
    grunt.registerTask('ts', ['exec:typecheck']);
    grunt.registerTask('test_node', ['ts', 'start_mockserver', 'exec:node_test', 'stop_mockserver']);
    grunt.registerTask('test_node_external', ['exec:node_test']);
    grunt.registerTask('test', ['start_mockserver', 'exec:node_test', 'stop_mockserver']);

    grunt.registerTask('default', ['exec:stop_existing_mockservers', 'jshint', 'test_node']);
    grunt.registerTask('headless', ['exec:stop_existing_mockservers', 'jshint', 'test_node']);
};
