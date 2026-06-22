#!/usr/bin/env ruby
# frozen_string_literal: true

# Demonstrates MockServer CLASS callbacks with the Ruby client.
#
# A class callback references a server-side Java class that implements a
# callback interface (org.mockserver.mock.action.ExpectationResponseCallback or
# ExpectationForwardCallback). It is a pure, declarative, REST-only action -- no
# WebSocket is involved -- so the wire shape can be validated without the
# referenced class existing on the server: the server stores the expectation
# exactly as sent.
#
# This example registers class-callback expectations three ways and asserts the
# server ACCEPTS each one (HTTP 200/201 on upsert) and stores the expected wire
# shape (httpResponseClassCallback.callbackClass / httpForwardClassCallback).
#
# It also briefly demonstrates an object/closure callback (the response is
# written in Ruby over a WebSocket) when one is available, but the required,
# always-run deliverable is the class-callback assertion.
#
# Prerequisites: MockServer running (default localhost:1080)
#   docker run -d -p 1080:1080 mockserver/mockserver
#
# The server location is read from the environment:
#   MOCKSERVER_HOST (default "localhost")
#   MOCKSERVER_PORT (default 1080)

require 'mockserver-client'
require 'net/http'
require 'json'

include MockServer

HOST = ENV.fetch('MOCKSERVER_HOST', 'localhost')
PORT = Integer(ENV.fetch('MOCKSERVER_PORT', '1080'))
BASE_URL = "http://#{HOST}:#{PORT}"

def assert(condition, message)
  raise "ASSERTION FAILED: #{message}" unless condition
end

# Upsert an expectation via the raw REST API so we can assert on the actual HTTP
# status code the server returns (the high-level client raises on >= 400 but
# hides 200 vs 201). Returns [status_code, parsed_response_body].
def upsert_raw(expectation)
  uri = URI("#{BASE_URL}/mockserver/expectation")
  http = Net::HTTP.new(uri.host, uri.port)
  req = Net::HTTP::Put.new(uri.request_uri)
  req['Content-Type'] = 'application/json'
  req.body = JSON.generate([expectation.to_h])
  resp = http.request(req)
  parsed =
    begin
      resp.body && !resp.body.empty? ? JSON.parse(resp.body) : nil
    rescue JSON::ParserError
      resp.body
    end
  [resp.code.to_i, parsed]
end

client = Client.new(HOST, PORT)
client.reset

# ------------------------------------------------------------------
# 1. Response class callback from a fully-qualified class-name String
# ------------------------------------------------------------------
# A String is accepted and wrapped into an HttpClassCallback, so to_h emits
# httpResponseClassCallback.callbackClass.
exp1 = Expectation.new(
  http_request: HttpRequest.new(method: 'GET', path: '/class-callback/string'),
  http_response_class_callback: 'com.example.MyResponseCallback'
)
assert(
  exp1.to_h['httpResponseClassCallback'] == { 'callbackClass' => 'com.example.MyResponseCallback' },
  "String form should serialize to httpResponseClassCallback.callbackClass, got #{exp1.to_h['httpResponseClassCallback'].inspect}"
)
status, = upsert_raw(exp1)
assert([200, 201].include?(status),
       "expected 200/201 upserting a response class callback (String), got #{status}")
puts "PASS: response class callback (String) accepted (status=#{status})"

# ------------------------------------------------------------------
# 2. Response class callback from an HttpClassCallback (with delay + primary)
# ------------------------------------------------------------------
exp2 = Expectation.new(
  http_request: HttpRequest.new(method: 'GET', path: '/class-callback/object'),
  http_response_class_callback: HttpClassCallback.new(
    callback_class: 'com.example.MyResponseCallback',
    delay: Delay.new(time_unit: 'SECONDS', value: 1),
    primary: true
  )
)
h2 = exp2.to_h['httpResponseClassCallback']
assert(h2['callbackClass'] == 'com.example.MyResponseCallback',
       "expected callbackClass, got #{h2.inspect}")
assert(h2['delay'] == { 'timeUnit' => 'SECONDS', 'value' => 1 },
       "expected delay carried through, got #{h2.inspect}")
assert(h2['primary'] == true, "expected primary=true, got #{h2.inspect}")
status, = upsert_raw(exp2)
assert([200, 201].include?(status),
       "expected 200/201 upserting a response class callback (HttpClassCallback), got #{status}")
puts "PASS: response class callback (HttpClassCallback, delay+primary) accepted (status=#{status})"

# ------------------------------------------------------------------
# 3. Forward class callback from a String
# ------------------------------------------------------------------
exp3 = Expectation.new(
  http_request: HttpRequest.new(method: 'GET', path: '/class-callback/forward'),
  http_forward_class_callback: 'com.example.MyForwardCallback'
)
assert(
  exp3.to_h['httpForwardClassCallback'] == { 'callbackClass' => 'com.example.MyForwardCallback' },
  "expected httpForwardClassCallback.callbackClass, got #{exp3.to_h['httpForwardClassCallback'].inspect}"
)
status, = upsert_raw(exp3)
assert([200, 201].include?(status),
       "expected 200/201 upserting a forward class callback (String), got #{status}")
puts "PASS: forward class callback (String) accepted (status=#{status})"

# ------------------------------------------------------------------
# 4. Fluent builder: respond_with_class_callback / forward_with_class_callback
# ------------------------------------------------------------------
# The fluent when(...).respond_with_class_callback(...) builder upserts directly;
# it raises on a >= 400 status, so reaching the next line proves acceptance.
client.when(
  HttpRequest.request(path: '/class-callback/fluent-respond')
).respond_with_class_callback('com.example.MyResponseCallback')
puts 'PASS: fluent respond_with_class_callback accepted'

client.when(
  HttpRequest.request(path: '/class-callback/fluent-forward')
).forward_with_class_callback(
  HttpClassCallback.new(callback_class: 'com.example.MyForwardCallback')
)
puts 'PASS: fluent forward_with_class_callback accepted'

# Confirm the server actually stored the class-callback expectations by reading
# the active expectations back and checking their wire shape.
active = client.retrieve_active_expectations
response_cbs = active.map(&:http_response_class_callback).compact
forward_cbs = active.map(&:http_forward_class_callback).compact
assert(response_cbs.any? { |cb| cb.callback_class == 'com.example.MyResponseCallback' },
       'expected a stored httpResponseClassCallback referencing com.example.MyResponseCallback')
assert(forward_cbs.any? { |cb| cb.callback_class == 'com.example.MyForwardCallback' },
       'expected a stored httpForwardClassCallback referencing com.example.MyForwardCallback')
puts 'PASS: class-callback expectations round-trip from the server with the expected wire shape'

# ------------------------------------------------------------------
# 5. (Optional) Object/closure callback -- the response is written in Ruby
# ------------------------------------------------------------------
# Unlike class callbacks, object callbacks open a WebSocket and run your Ruby
# closure on each match. This is best-effort here: it needs a working callback
# WebSocket, so any failure is reported but does not fail the example (the
# class-callback assertions above are the required deliverable).
begin
  client.mock_with_callback(
    HttpRequest.request(path: '/object-callback'),
    ->(request) {
      HttpResponse.response(body: "handled #{request.path} in Ruby", status_code: 200)
    }
  )
  uri = URI("#{BASE_URL}/object-callback")
  resp = Net::HTTP.get_response(uri)
  if resp.code.to_i == 200 && resp.body.to_s.include?('handled /object-callback in Ruby')
    puts 'PASS: object/closure callback produced a Ruby-written response'
  else
    puts "SKIP: object callback returned status=#{resp.code} body=#{resp.body.inspect}"
  end
rescue StandardError => e
  puts "SKIP: object/closure callback not demonstrated (#{e.class}: #{e.message})"
end

client.reset
client.close

puts "\nAll class-callback assertions passed."
