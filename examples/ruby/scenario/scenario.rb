#!/usr/bin/env ruby
# frozen_string_literal: true

# Demonstrates MockServer's stateful-scenario features with the Ruby client.
#
# Runs all 5 canonical scenarios in sequence against a running MockServer,
# exercises the data plane with Net::HTTP, and asserts each outcome. Prints
# "PASS: <scenario>" per scenario and exits non-zero on any failure.
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

# ------------------------------------------------------------------
# Data-plane helpers (Net::HTTP)
# ------------------------------------------------------------------

# Perform a data-plane request and return [status_code, parsed_or_raw_body].
def call(method, path, body: nil)
  uri = URI("#{BASE_URL}#{path}")
  http = Net::HTTP.new(uri.host, uri.port)
  req =
    case method
    when :get  then Net::HTTP::Get.new(uri.request_uri)
    when :post then Net::HTTP::Post.new(uri.request_uri)
    else raise ArgumentError, "unsupported method #{method}"
    end
  if body
    req['Content-Type'] = 'application/json'
    req.body = body
  end
  resp = http.request(req)
  parsed =
    begin
      resp.body && !resp.body.empty? ? JSON.parse(resp.body) : nil
    rescue JSON::ParserError
      resp.body
    end
  [resp.code.to_i, parsed]
end

def assert(condition, message)
  raise "ASSERTION FAILED: #{message}" unless condition
end

# Run a single named scenario block: reset the server first so each scenario is
# self-contained and order-independent, then run + assert. Each control-plane
# request opens its own short-lived connection, so one client per scenario is fine.
def scenario(name)
  client = Client.new(HOST, PORT)
  client.reset
  yield client
  puts "PASS: #{name}"
ensure
  client&.close
end

# ------------------------------------------------------------------
# 1. state_machine -- login flow (scenarioState transitions)
# ------------------------------------------------------------------
scenario('state_machine') do |client|
  client.upsert(
    Expectation.new(
      http_request: HttpRequest.new(method: 'POST', path: '/login'),
      http_response: HttpResponse.new(status_code: 200, body: '{"token":"abc123"}'),
      times: Times.exactly(1),
      scenario_name: 'LoginFlow',
      scenario_state: 'Started',
      new_scenario_state: 'LoggedIn'
    ),
    Expectation.new(
      http_request: HttpRequest.new(method: 'GET', path: '/profile'),
      http_response: HttpResponse.new(status_code: 200, body: '{"name":"Alice"}'),
      scenario_name: 'LoginFlow',
      scenario_state: 'LoggedIn'
    ),
    Expectation.new(
      http_request: HttpRequest.new(method: 'GET', path: '/profile'),
      http_response: HttpResponse.new(status_code: 401, body: '{"error":"Not authenticated"}'),
      scenario_name: 'LoginFlow',
      scenario_state: 'Started'
    )
  )

  status, = call(:get, '/profile')
  assert(status == 401, "expected 401 before login, got #{status}")

  status, body = call(:post, '/login')
  assert(status == 200, "expected 200 from login, got #{status}")
  assert(body['token'] == 'abc123', "expected token abc123, got #{body.inspect}")

  status, body = call(:get, '/profile')
  assert(status == 200, "expected 200 after login, got #{status}")
  assert(body['name'] == 'Alice', "expected name Alice, got #{body.inspect}")
end

# ------------------------------------------------------------------
# 2. sequential_cycling -- multiple responses on one expectation
# ------------------------------------------------------------------
scenario('sequential_cycling') do |client|
  client.upsert(
    Expectation.new(
      http_request: HttpRequest.new(method: 'GET', path: '/api/status'),
      http_responses: [
        HttpResponse.new(status_code: 200, body: '{"status":"ok"}'),
        HttpResponse.new(status_code: 503, body: '{"status":"degraded"}'),
        HttpResponse.new(status_code: 200, body: '{"status":"ok"}')
      ],
      response_mode: ResponseMode::SEQUENTIAL
    )
  )

  codes = 4.times.map { call(:get, '/api/status').first }
  assert(codes == [200, 503, 200, 200],
         "expected [200, 503, 200, 200] (4th cycles back), got #{codes.inspect}")
end

# ------------------------------------------------------------------
# 3. timed_transition -- scenario REST helper, timed auto-transition
# ------------------------------------------------------------------
scenario('timed_transition') do |client|
  client.upsert(
    Expectation.new(
      http_request: HttpRequest.new(method: 'GET', path: '/status'),
      http_response: HttpResponse.new(status_code: 200, body: '{"status":"deploying"}'),
      scenario_name: 'DeployFlow',
      scenario_state: 'Deploying'
    ),
    Expectation.new(
      http_request: HttpRequest.new(method: 'GET', path: '/status'),
      http_response: HttpResponse.new(status_code: 200, body: '{"status":"complete"}'),
      scenario_name: 'DeployFlow',
      scenario_state: 'Deployed'
    )
  )

  # Set the scenario to Deploying, auto-transitioning to Deployed after 1s.
  client.scenario('DeployFlow').set('Deploying', transition_after_ms: 1000, next_state: 'Deployed')

  _, body = call(:get, '/status')
  assert(body['status'] == 'deploying', "expected deploying, got #{body.inspect}")

  sleep(1.3)

  _, body = call(:get, '/status')
  assert(body['status'] == 'complete', "expected complete after transition, got #{body.inspect}")
end

# ------------------------------------------------------------------
# 4. external_trigger -- scenario REST helper, external trigger
# ------------------------------------------------------------------
scenario('external_trigger') do |client|
  client.upsert(
    Expectation.new(
      http_request: HttpRequest.new(method: 'GET', path: '/health'),
      http_response: HttpResponse.new(status_code: 200, body: '{"status":"healthy"}'),
      scenario_name: 'HealthFlow',
      scenario_state: 'Started'
    ),
    Expectation.new(
      http_request: HttpRequest.new(method: 'GET', path: '/health'),
      http_response: HttpResponse.new(status_code: 503, body: '{"status":"down"}'),
      scenario_name: 'HealthFlow',
      scenario_state: 'Down'
    )
  )

  status, body = call(:get, '/health')
  assert(status == 200, "expected 200 healthy, got #{status}")
  assert(body['status'] == 'healthy', "expected healthy, got #{body.inspect}")

  # Externally trigger the scenario into the Down state.
  client.scenario('HealthFlow').trigger('Down')

  status, body = call(:get, '/health')
  assert(status == 503, "expected 503 down, got #{status}")
  assert(body['status'] == 'down', "expected down, got #{body.inspect}")
end

# ------------------------------------------------------------------
# 5. cross_protocol -- crossProtocolScenarios (HTTP_REQUEST trigger)
# ------------------------------------------------------------------
# A GET /events advances the ConnFlow scenario to Connected, which unlocks
# GET /api/conn-status. The same mechanism advances scenarios from
# DNS_QUERY / WEBSOCKET_CONNECT / GRPC_REQUEST events.
scenario('cross_protocol') do |client|
  client.upsert(
    Expectation.new(
      http_request: HttpRequest.new(method: 'GET', path: '/events'),
      http_response: HttpResponse.new(status_code: 200),
      cross_protocol_scenarios: [
        CrossProtocolScenario.new(
          trigger: CrossProtocolTrigger::HTTP_REQUEST,
          match_pattern: '/events',
          scenario_name: 'ConnFlow',
          target_state: 'Connected'
        )
      ]
    ),
    Expectation.new(
      http_request: HttpRequest.new(method: 'GET', path: '/api/conn-status'),
      http_response: HttpResponse.new(status_code: 200, body: '{"status":"connected"}'),
      scenario_name: 'ConnFlow',
      scenario_state: 'Connected'
    )
  )

  # Before the trigger, /api/conn-status is unmatched.
  status, = call(:get, '/api/conn-status')
  assert(status == 404, "expected 404 before trigger, got #{status}")

  status, = call(:get, '/events')
  assert(status == 200, "expected 200 from /events, got #{status}")

  status, body = call(:get, '/api/conn-status')
  assert(status == 200, "expected 200 connected after trigger, got #{status}")
  assert(body['status'] == 'connected', "expected connected, got #{body.inspect}")
end

puts "\nAll scenarios passed."
