#!/usr/bin/env ruby
# frozen_string_literal: true

# Demonstrates forwarding requests to upstream services with MockServer.
#
# Prerequisites: MockServer running on localhost:1080
#   docker run -d -p 1080:1080 mockserver/mockserver

require 'mockserver-client'

include MockServer

client = Client.new('localhost', 1080)

# ------------------------------------------------------------------
# 1. Simple forward to an HTTP host
# ------------------------------------------------------------------
client.when(
  HttpRequest.request(path: '/api/users')
).forward(
  HttpForward.new(host: 'backend.example.com', port: 80, scheme: 'HTTP')
)
puts '1. Created expectation: /api/users -> forward to backend.example.com:80 (HTTP)'

# ------------------------------------------------------------------
# 2. Forward to an HTTPS host
# ------------------------------------------------------------------
client.when(
  HttpRequest.request(path: '/api/secure')
).forward(
  HttpForward.new(host: 'secure.example.com', port: 443, scheme: 'HTTPS')
)
puts '2. Created expectation: /api/secure -> forward to secure.example.com:443 (HTTPS)'

# ------------------------------------------------------------------
# 3. Override the forwarded request (change path and add Host header)
# ------------------------------------------------------------------
client.when(
  HttpRequest.request(path: '/some/path')
).forward(
  HttpOverrideForwardedRequest.new(
    http_request: HttpRequest.new(
      path: '/some/other/path',
      headers: [
        KeyToMultiValue.new(name: 'Host', values: ['target.host.com'])
      ]
    )
  )
)
puts '3. Created expectation: /some/path -> forward with overridden path and Host header'

# ------------------------------------------------------------------
# 4. Override both forwarded request and response
# ------------------------------------------------------------------
client.when(
  HttpRequest.request(path: '/override/both')
).forward(
  HttpOverrideForwardedRequest.new(
    http_request: HttpRequest.new(
      path: '/real/endpoint',
      headers: [
        KeyToMultiValue.new(name: 'Host', values: ['target.host.com'])
      ]
    ),
    http_response: HttpResponse.new(body: 'overridden_response_body')
  )
)
puts '4. Created expectation: /override/both -> forward with overridden request and response'

# ------------------------------------------------------------------
# 5. Override forwarded request with custom socket address
# ------------------------------------------------------------------
client.when(
  HttpRequest.request(path: '/custom/host')
).forward(
  HttpOverrideForwardedRequest.new(
    http_request: HttpRequest.new(
      path: '/target/path',
      headers: [
        KeyToMultiValue.new(name: 'Host', values: ['any.host.com'])
      ],
      socket_address: SocketAddress.new(
        host: 'target.host.com',
        port: 1234,
        scheme: 'HTTPS'
      )
    )
  )
)
puts '5. Created expectation: /custom/host -> forward to target.host.com:1234 (HTTPS)'

# ------------------------------------------------------------------
# Clean up
# ------------------------------------------------------------------
client.reset
puts "\nAll expectations cleared."
