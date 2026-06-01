#!/usr/bin/env ruby
# frozen_string_literal: true

# Demonstrates returning canned HTTP responses with MockServer.
#
# Prerequisites: MockServer running on localhost:1080
#   docker run -d -p 1080:1080 mockserver/mockserver

require 'mockserver-client'
require 'net/http'
require 'json'

include MockServer

client = Client.new('localhost', 1080)

# ------------------------------------------------------------------
# 1. Simple response with body only
# ------------------------------------------------------------------
client.when(
  HttpRequest.request(path: '/hello')
).respond(
  HttpResponse.response(body: 'Hello, World!')
)
puts '1. Created expectation: GET /hello -> 200 "Hello, World!"'

# ------------------------------------------------------------------
# 2. Response with status code, headers, and cookies
# ------------------------------------------------------------------
client.when(
  HttpRequest.new(method: 'POST', path: '/login')
).respond(
  HttpResponse.new(
    status_code: 201,
    reason_phrase: 'Created',
    headers: [
      KeyToMultiValue.new(name: 'Content-Type', values: ['application/json']),
      KeyToMultiValue.new(name: 'X-Custom', values: ['example'])
    ],
    cookies: [
      KeyToMultiValue.new(name: 'session', values: ['abc-123'])
    ],
    body: '{"status": "logged_in"}'
  )
)
puts '2. Created expectation: POST /login -> 201 with headers and cookies'

# ------------------------------------------------------------------
# 3. Response with a delay
# ------------------------------------------------------------------
client.when(
  HttpRequest.request(path: '/slow')
).respond(
  HttpResponse.new(
    status_code: 200,
    body: 'delayed response',
    delay: Delay.new(time_unit: 'SECONDS', value: 2)
  )
)
puts '3. Created expectation: GET /slow -> 200 after 2s delay'

# ------------------------------------------------------------------
# 4. Different responses for the same path (first match wins)
# ------------------------------------------------------------------
client.when(
  HttpRequest.request(path: '/counter'),
  times: Times.exactly(1)
).respond(
  HttpResponse.response(body: 'first', status_code: 200)
)

client.when(
  HttpRequest.request(path: '/counter'),
  times: Times.exactly(1)
).respond(
  HttpResponse.response(body: 'second', status_code: 200)
)
puts '4. Created expectations: GET /counter -> "first" then "second"'

# ------------------------------------------------------------------
# 5. Custom status code and reason phrase
# ------------------------------------------------------------------
client.when(
  HttpRequest.new(method: 'POST', path: '/teapot')
).respond(
  HttpResponse.new(status_code: 418, reason_phrase: "I'm a teapot")
)
puts "5. Created expectation: POST /teapot -> 418 I'm a teapot"

# ------------------------------------------------------------------
# Verify by sending a real request
# ------------------------------------------------------------------
puts "\n--- Sending test request to GET /hello ---"
uri = URI('http://localhost:1080/hello')
response = Net::HTTP.get_response(uri)
puts "Status: #{response.code}"
puts "Body:   #{response.body}"

# ------------------------------------------------------------------
# Clean up
# ------------------------------------------------------------------
client.reset
puts "\nAll expectations cleared."
