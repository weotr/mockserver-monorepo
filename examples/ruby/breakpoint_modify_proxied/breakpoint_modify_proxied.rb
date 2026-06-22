#!/usr/bin/env ruby
# frozen_string_literal: true

# Demonstrates using a RESPONSE-phase interactive breakpoint to modify a
# proxied (forwarded) response before it reaches the caller.
#
# The example is fully self-contained using a loopback forward (MockServer
# forwards to itself via socketAddress), so no external upstream is needed.
#
# Flow:
#   1. Create a mock "upstream" endpoint:  GET /upstream/greeting -> 200 JSON
#   2. Create a forward expectation:       GET /service/greeting  -> forwards
#      to /upstream/greeting on the SAME MockServer (socketAddress loopback)
#   3. Register a RESPONSE-phase breakpoint on /service/greeting whose handler
#      modifies the upstream response before it reaches the caller.
#   4. Issue GET /service/greeting and print the modified response.
#
# Prerequisites:
#   - MockServer running on localhost:1080 with breakpoint support
#   - gem install mockserver-client

require 'mockserver-client'
require 'net/http'
require 'json'

include MockServer

MOCK_HOST = 'localhost'
MOCK_PORT = 1080

client = Client.new(MOCK_HOST, MOCK_PORT)
client.reset

# ------------------------------------------------------------------
# Step 1: Create a mock "upstream" endpoint
# ------------------------------------------------------------------
client.when(
  HttpRequest.new(method: 'GET', path: '/upstream/greeting')
).respond(
  HttpResponse.new(
    status_code: 200,
    headers: [
      KeyToMultiValue.new(name: 'Content-Type', values: ['application/json'])
    ],
    body: JSON.generate({
      message: 'Hello from upstream',
      source: 'original'
    })
  )
)
puts '1. Created upstream mock: GET /upstream/greeting -> 200 JSON'

# ------------------------------------------------------------------
# Step 2: Create a loopback forward expectation
# ------------------------------------------------------------------
client.when(
  HttpRequest.new(method: 'GET', path: '/service/greeting')
).forward(
  HttpOverrideForwardedRequest.new(
    http_request: HttpRequest.new(
      path: '/upstream/greeting',
      socket_address: SocketAddress.new(
        host: MOCK_HOST,
        port: MOCK_PORT,
        scheme: 'HTTP'
      )
    )
  )
)
puts '2. Created forward expectation: GET /service/greeting -> loopback to /upstream/greeting'

# ------------------------------------------------------------------
# Step 3: Register a RESPONSE-phase breakpoint
# ------------------------------------------------------------------
response_handler = lambda { |request, response|
  # The handler receives the request and response as HttpRequest / HttpResponse
  # objects and must return an HttpResponse (return nil to auto-continue).
  raw_body = response.body
  raw_body = raw_body.json || raw_body.string if raw_body.is_a?(MockServer::Body)
  puts "3. Breakpoint fired! Original response body: #{raw_body}"

  # The forwarded JSON body may arrive already parsed (a Hash) or as a String.
  original_body =
    case raw_body
    when Hash then raw_body
    when String
      begin
        JSON.parse(raw_body)
      rescue JSON::ParserError
        {}
      end
    else {}
    end

  # Modify the response
  original_body['source'] = 'modified-by-breakpoint'
  original_body['injectedField'] = 'this was added by the breakpoint handler'

  HttpResponse.new(
    status_code: response.status_code || 200,
    headers: response.headers,
    body: JSON.generate(original_body)
  )
}

bp_id = client.add_breakpoint(
  HttpRequest.new(method: 'GET', path: '/service/greeting'),
  %w[RESPONSE],
  response_handler: response_handler
)
puts "   Breakpoint registered with id: #{bp_id}"

# ------------------------------------------------------------------
# Step 4: Send a request through the forwarded path
# ------------------------------------------------------------------
puts '4. Sending GET /service/greeting ...'
uri = URI("http://#{MOCK_HOST}:#{MOCK_PORT}/service/greeting")
resp = Net::HTTP.get_response(uri)

puts
puts '--- Response received ---'
puts "Status: #{resp.code}"
puts "Body:   #{resp.body}"

begin
  parsed = JSON.parse(resp.body)
  if parsed['source'] == 'modified-by-breakpoint'
    puts "\nBreakpoint successfully modified the proxied response!"
  end
rescue JSON::ParserError
  # ignore
end

# ------------------------------------------------------------------
# Clean up
# ------------------------------------------------------------------
client.clear_breakpoint_matchers
puts "\nBreakpoint matchers cleared."
client.reset
puts 'MockServer reset.'
