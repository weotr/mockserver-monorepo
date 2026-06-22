#!/usr/bin/env ruby
# frozen_string_literal: true

# Demonstrates MockServer's Load Scenario registry with the Ruby client.
#
# A "load scenario" is a named, server-side traffic generator: you register it
# once (its profile of ramp/hold/pause stages and the request steps it drives),
# then start/stop it by name. While running it generates synthetic traffic
# against the data plane and reports live throughput/latency status. This is the
# registry workflow exercised with the typed Ruby client:
#
#   client.load_scenario(scenario)        register/upsert (PUT /mockserver/loadScenario)
#   client.start_load_scenarios(names)    start one/many (PUT .../start)
#   client.load_scenarios                 list all (GET /mockserver/loadScenario)
#   client.get_load_scenario(name)        one scenario + live status (GET .../{name})
#   client.stop_load_scenarios(names)     stop one/many; nil = stop all (PUT .../stop)
#   client.run_load_scenario(scenario)    register + start in one call
#   client.delete_load_scenario(name)     delete one (DELETE .../{name})
#   client.clear_load_scenarios           clear the registry (DELETE /mockserver/loadScenario)
#
# IMPORTANT: the server must be started with load generation enabled, otherwise
# starting returns HTTP 403:
#   java -Dmockserver.loadGenerationEnabled=true -jar mockserver-netty-...-jar-with-dependencies.jar -serverPort 1080
#   (or env MOCKSERVER_LOAD_GENERATION_ENABLED=true). Registering is always allowed.
#
# Prints "PASS" and exits 0 on success; exits non-zero on the first failure.
#
# The server location is read from the environment:
#   MOCKSERVER_HOST (default "localhost")
#   MOCKSERVER_PORT (default 1080)

require 'mockserver-client'

include MockServer

HOST = ENV.fetch('MOCKSERVER_HOST', 'localhost')
PORT = Integer(ENV.fetch('MOCKSERVER_PORT', '1080'))

def assert(condition, message)
  raise "ASSERTION FAILED: #{message}" unless condition
end

# A realistic multi-stage scenario built from the typed model: a linear RATE
# ramp (5 -> 50 req/s, capped at 50 VUs), then a 25-VU hold, then a PAUSE. Two
# Velocity-templated steps drive each iteration ($!iteration.index varies the
# request). start_delay_millis defers load for half a second after start. Stage
# VUs are kept within the default safety cap of 50 (loadGenerationMaxVirtualUsers).
def build_scenario
  LoadScenario.new(
    name: 'checkout-load',
    template_type: 'VELOCITY',
    max_requests: 100_000,
    start_delay_millis: 500,
    labels: { 'team' => 'payments', 'env' => 'staging' },
    profile: LoadProfile.new(stages: [
      LoadStage.rate(30_000, start_rate: 5, end_rate: 50, max_vus: 50, curve: 'LINEAR'),
      LoadStage.vu(60_000, vus: 25),
      LoadStage.pause(10_000)
    ]),
    steps: [
      LoadStep.new(
        name: 'browse',
        request: HttpRequest.new(method: 'GET', path: '/products/$!iteration.index'),
        think_time: Delay.new(time_unit: 'MILLISECONDS', value: 500)
      ),
      LoadStep.new(
        name: 'checkout',
        labels: { 'critical' => 'true' },
        request: HttpRequest.new(method: 'POST', path: '/cart/checkout',
                                 body: '{"item":"$!iteration.index","qty":1}')
      )
    ]
  )
end

client = Client.new(HOST, PORT)
scenario = build_scenario

begin
  # A target expectation so generated traffic gets a 200 to measure.
  client.upsert(
    Expectation.new(
      http_request: HttpRequest.new,
      http_response: HttpResponse.new(status_code: 200, body: 'ok')
    )
  )

  # 1. Register (does NOT start it yet).
  client.load_scenario(scenario)
  puts 'registered "checkout-load"'

  # 2. Start it (accepts a single name or an Array of names).
  client.start_load_scenarios('checkout-load')
  puts 'started "checkout-load"'
  sleep(1.5)

  # 3. List all registered scenarios -> { "scenarios" => [ <status node>, ... ] }.
  scenarios = client.load_scenarios['scenarios'] || []
  running = scenarios.select { |s| s['state'] == 'RUNNING' }
  assert(running.any? { |s| s['name'] == 'checkout-load' },
         'checkout-load is not RUNNING in the list (is loadGenerationEnabled=true?)')
  puts "listed: #{scenarios.map { |s| "#{s['name']}=#{s['state']}" }.join(', ')}"

  # One scenario's live status (throughput/latency, current stage, ...).
  status = client.get_load_scenario('checkout-load')
  puts "status: state=#{status['state']} stageType=#{status['stageType']} " \
       "currentTarget=#{status['currentTarget']} requestsSent=#{status['requestsSent']}"

  # 4. Stop it (pass nil to stop ALL running scenarios).
  client.stop_load_scenarios('checkout-load')
  puts 'stopped "checkout-load"'

  # Tidy up the registry.
  client.clear_load_scenarios

  puts 'PASS'
rescue StandardError => e
  warn "FAIL: #{e.message}"
  exit 1
ensure
  client.close
end
