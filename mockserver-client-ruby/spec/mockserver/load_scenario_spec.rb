# frozen_string_literal: true

RSpec.describe MockServer::Client do
  let(:host) { 'localhost' }
  let(:port) { 1080 }
  let(:base_url) { "http://#{host}:#{port}" }
  let(:client) { MockServer::Client.new(host, port) }

  after { client.close }

  def sample_scenario
    MockServer::LoadScenario.new(
      name: 'checkout-spike',
      template_type: 'VELOCITY',
      labels: { 'team' => 'payments' },
      max_requests: 1000,
      profile: MockServer::LoadProfile.new(
        stages: [
          MockServer::LoadStage.vu(60_000, start_vus: 1, end_vus: 50, curve: 'LINEAR'),
          MockServer::LoadStage.rate(30_000, rate: 50.0, max_vus: 20),
          MockServer::LoadStage.pause(5_000)
        ]
      ),
      steps: [
        MockServer::LoadStep.new(
          name: 'place-order',
          labels: { 'critical' => 'true' },
          think_time: MockServer::Delay.new(time_unit: 'MILLISECONDS', value: 500),
          request: MockServer::HttpRequest.new(
            method: 'POST',
            path: '/orders',
            socket_address: MockServer::SocketAddress.new(host: 'shop.svc', port: 8080, scheme: 'HTTP')
          )
        )
      ]
    )
  end

  # -------------------------------------------------------------------
  # load_scenario (PUT /loadScenario) — register/load (no run)
  # -------------------------------------------------------------------
  describe '#load_scenario' do
    it 'sends PUT to /mockserver/loadScenario with the scenario body and returns {name,state}' do
      stub_request(:put, "#{base_url}/mockserver/loadScenario")
        .to_return(status: 200, body: JSON.generate({ 'name' => 'checkout-spike', 'state' => 'LOADED' }))

      result = client.load_scenario(sample_scenario)

      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/loadScenario")
        .with { |r|
          parsed = JSON.parse(r.body)
          stages = parsed['profile']['stages']
          parsed['name'] == 'checkout-spike' &&
            parsed['templateType'] == 'VELOCITY' &&
            parsed['maxRequests'] == 1000 &&
            stages.length == 3 &&
            stages[0]['type'] == 'VU' &&
            stages[0]['startVus'] == 1 &&
            stages[0]['endVus'] == 50 &&
            stages[0]['durationMillis'] == 60_000 &&
            stages[0]['curve'] == 'LINEAR' &&
            !stages[0].key?('vus') &&
            stages[1]['type'] == 'RATE' &&
            stages[1]['rate'] == 50.0 &&
            stages[1]['maxVus'] == 20 &&
            stages[2]['type'] == 'PAUSE' &&
            stages[2]['durationMillis'] == 5_000 &&
            !stages[2].key?('vus') &&
            !stages[2].key?('curve') &&
            parsed['steps'][0]['name'] == 'place-order' &&
            parsed['steps'][0]['thinkTime']['value'] == 500 &&
            parsed['steps'][0]['request']['method'] == 'POST' &&
            parsed['steps'][0]['request']['path'] == '/orders' &&
            parsed['steps'][0]['request']['socketAddress']['host'] == 'shop.svc' &&
            parsed['steps'][0]['request']['socketAddress']['port'] == 8080
        }
      expect(result['name']).to eq('checkout-spike')
      expect(result['state']).to eq('LOADED')
    end

    it 'accepts a plain hash scenario' do
      stub_request(:put, "#{base_url}/mockserver/loadScenario")
        .to_return(status: 200, body: JSON.generate({ 'name' => 'raw', 'state' => 'LOADED' }))

      client.load_scenario({ 'name' => 'raw', 'profile' => { 'stages' => [{ 'type' => 'VU', 'vus' => 5, 'durationMillis' => 1000 }] } })

      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/loadScenario")
        .with { |r| JSON.parse(r.body)['profile']['stages'][0]['vus'] == 5 }
    end

    it 'is allowed even when load generation is disabled (registration does not run)' do
      stub_request(:put, "#{base_url}/mockserver/loadScenario")
        .to_return(status: 200, body: JSON.generate({ 'name' => 'checkout-spike', 'state' => 'LOADED' }))

      expect { client.load_scenario(sample_scenario) }.not_to raise_error
    end

    it 'raises Error on failures' do
      stub_request(:put, "#{base_url}/mockserver/loadScenario")
        .to_return(status: 400, body: '{"error":"bad"}')

      expect { client.load_scenario(sample_scenario) }
        .to raise_error(MockServer::Error, /Failed to register load scenario/)
    end
  end

  # -------------------------------------------------------------------
  # load_scenarios (GET /loadScenario) — list all
  # -------------------------------------------------------------------
  describe '#load_scenarios' do
    it 'sends GET to /mockserver/loadScenario and returns the scenarios list' do
      stub_request(:get, "#{base_url}/mockserver/loadScenario")
        .to_return(status: 200, body: JSON.generate({
                                                       'scenarios' => [
                                                         { 'name' => 'a', 'state' => 'RUNNING', 'definition' => { 'name' => 'a' } },
                                                         { 'name' => 'b', 'state' => 'LOADED', 'definition' => { 'name' => 'b' } }
                                                       ]
                                                     }))

      result = client.load_scenarios

      expect(WebMock).to have_requested(:get, "#{base_url}/mockserver/loadScenario")
      expect(result['scenarios'].length).to eq(2)
      expect(result['scenarios'][0]['name']).to eq('a')
      expect(result['scenarios'][0]['state']).to eq('RUNNING')
    end

    it 'raises Error on failures' do
      stub_request(:get, "#{base_url}/mockserver/loadScenario")
        .to_return(status: 500, body: '{"error":"boom"}')

      expect { client.load_scenarios }
        .to raise_error(MockServer::Error, /Failed to list load scenarios/)
    end
  end

  # -------------------------------------------------------------------
  # get_load_scenario (GET /loadScenario/{name})
  # -------------------------------------------------------------------
  describe '#get_load_scenario' do
    it 'sends GET to /mockserver/loadScenario/{name}' do
      stub_request(:get, "#{base_url}/mockserver/loadScenario/checkout-spike")
        .to_return(status: 200, body: JSON.generate({ 'name' => 'checkout-spike', 'state' => 'RUNNING' }))

      result = client.get_load_scenario('checkout-spike')

      expect(WebMock).to have_requested(:get, "#{base_url}/mockserver/loadScenario/checkout-spike")
      expect(result['name']).to eq('checkout-spike')
      expect(result['state']).to eq('RUNNING')
    end

    it 'percent-encodes the name in the path' do
      stub_request(:get, "#{base_url}/mockserver/loadScenario/my%20scenario")
        .to_return(status: 200, body: JSON.generate({ 'name' => 'my scenario', 'state' => 'LOADED' }))

      client.get_load_scenario('my scenario')

      expect(WebMock).to have_requested(:get, "#{base_url}/mockserver/loadScenario/my%20scenario")
    end

    it 'raises a clear error on 404 (unknown name)' do
      stub_request(:get, "#{base_url}/mockserver/loadScenario/missing")
        .to_return(status: 404, body: '')

      expect { client.get_load_scenario('missing') }
        .to raise_error(MockServer::Error, /Load scenario not found/)
    end
  end

  # -------------------------------------------------------------------
  # delete_load_scenario (DELETE /loadScenario/{name})
  # -------------------------------------------------------------------
  describe '#delete_load_scenario' do
    it 'sends DELETE to /mockserver/loadScenario/{name}' do
      stub_request(:delete, "#{base_url}/mockserver/loadScenario/checkout-spike")
        .to_return(status: 200, body: '')

      client.delete_load_scenario('checkout-spike')

      expect(WebMock).to have_requested(:delete, "#{base_url}/mockserver/loadScenario/checkout-spike")
    end

    it 'raises Error on failures' do
      stub_request(:delete, "#{base_url}/mockserver/loadScenario/checkout-spike")
        .to_return(status: 500, body: '{"error":"boom"}')

      expect { client.delete_load_scenario('checkout-spike') }
        .to raise_error(MockServer::Error, /Failed to delete load scenario/)
    end
  end

  # -------------------------------------------------------------------
  # clear_load_scenarios (DELETE /loadScenario)
  # -------------------------------------------------------------------
  describe '#clear_load_scenarios' do
    it 'sends DELETE to /mockserver/loadScenario' do
      stub_request(:delete, "#{base_url}/mockserver/loadScenario")
        .to_return(status: 200, body: '')

      client.clear_load_scenarios

      expect(WebMock).to have_requested(:delete, "#{base_url}/mockserver/loadScenario")
    end

    it 'raises Error on failures' do
      stub_request(:delete, "#{base_url}/mockserver/loadScenario")
        .to_return(status: 500, body: '{"error":"boom"}')

      expect { client.clear_load_scenarios }
        .to raise_error(MockServer::Error, /Failed to clear load scenarios/)
    end
  end

  # -------------------------------------------------------------------
  # start_load_scenarios (PUT /loadScenario/start)
  # -------------------------------------------------------------------
  describe '#start_load_scenarios' do
    it 'wraps a single String name as {"names":[...]}' do
      stub_request(:put, "#{base_url}/mockserver/loadScenario/start")
        .to_return(status: 200, body: JSON.generate({ 'started' => [{ 'name' => 'a', 'state' => 'PENDING' }], 'status' => 'OK' }))

      result = client.start_load_scenarios('a')

      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/loadScenario/start")
        .with { |r| JSON.parse(r.body) == { 'names' => ['a'] } }
      expect(result['started'][0]['name']).to eq('a')
    end

    it 'passes an Array of names through as {"names":[...]}' do
      stub_request(:put, "#{base_url}/mockserver/loadScenario/start")
        .to_return(status: 200, body: JSON.generate({ 'started' => [], 'status' => 'OK' }))

      client.start_load_scenarios(%w[a b])

      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/loadScenario/start")
        .with { |r| JSON.parse(r.body) == { 'names' => %w[a b] } }
    end

    it 'raises a clear error on 403 (load generation disabled)' do
      stub_request(:put, "#{base_url}/mockserver/loadScenario/start")
        .to_return(status: 403, body: '')

      expect { client.start_load_scenarios('a') }
        .to raise_error(MockServer::Error, /load generation is disabled/)
    end

    it 'raises a clear error on 404 (unknown name)' do
      stub_request(:put, "#{base_url}/mockserver/loadScenario/start")
        .to_return(status: 404, body: 'unknown: a')

      expect { client.start_load_scenarios('a') }
        .to raise_error(MockServer::Error, /Load scenario not found/)
    end

    it 'raises Error on other failures' do
      stub_request(:put, "#{base_url}/mockserver/loadScenario/start")
        .to_return(status: 400, body: '{"error":"bad"}')

      expect { client.start_load_scenarios('a') }
        .to raise_error(MockServer::Error, /Failed to start load scenarios/)
    end
  end

  # -------------------------------------------------------------------
  # stop_load_scenarios (PUT /loadScenario/stop)
  # -------------------------------------------------------------------
  describe '#stop_load_scenarios' do
    it 'sends an empty body to stop all when called with no arguments' do
      stub_request(:put, "#{base_url}/mockserver/loadScenario/stop")
        .to_return(status: 200, body: JSON.generate({ 'stopped' => ['a', 'b'], 'status' => 'OK' }))

      result = client.stop_load_scenarios

      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/loadScenario/stop")
        .with { |r| r.body.nil? || r.body.empty? }
      expect(result['stopped']).to eq(%w[a b])
    end

    it 'wraps a single String name as {"names":[...]}' do
      stub_request(:put, "#{base_url}/mockserver/loadScenario/stop")
        .to_return(status: 200, body: JSON.generate({ 'stopped' => ['a'] }))

      client.stop_load_scenarios('a')

      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/loadScenario/stop")
        .with { |r| JSON.parse(r.body) == { 'names' => ['a'] } }
    end

    it 'passes an Array of names through as {"names":[...]}' do
      stub_request(:put, "#{base_url}/mockserver/loadScenario/stop")
        .to_return(status: 200, body: JSON.generate({ 'stopped' => %w[a b] }))

      client.stop_load_scenarios(%w[a b])

      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/loadScenario/stop")
        .with { |r| JSON.parse(r.body) == { 'names' => %w[a b] } }
    end

    it 'raises Error on failures' do
      stub_request(:put, "#{base_url}/mockserver/loadScenario/stop")
        .to_return(status: 500, body: '{"error":"boom"}')

      expect { client.stop_load_scenarios }
        .to raise_error(MockServer::Error, /Failed to stop load scenarios/)
    end
  end

  # -------------------------------------------------------------------
  # run_load_scenario (register then start)
  # -------------------------------------------------------------------
  describe '#run_load_scenario' do
    it 'registers the scenario then starts it by name' do
      stub_request(:put, "#{base_url}/mockserver/loadScenario")
        .to_return(status: 200, body: JSON.generate({ 'name' => 'checkout-spike', 'state' => 'LOADED' }))
      stub_request(:put, "#{base_url}/mockserver/loadScenario/start")
        .to_return(status: 200, body: JSON.generate({ 'started' => [{ 'name' => 'checkout-spike', 'state' => 'PENDING' }], 'status' => 'OK' }))

      result = client.run_load_scenario(sample_scenario)

      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/loadScenario")
      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/loadScenario/start")
        .with { |r| JSON.parse(r.body) == { 'names' => ['checkout-spike'] } }
      expect(result['started'][0]['name']).to eq('checkout-spike')
    end

    it 'raises ArgumentError when the scenario hash has no name' do
      expect { client.run_load_scenario({ 'profile' => { 'stages' => [] } }) }
        .to raise_error(ArgumentError, /non-empty name/)
    end
  end

  # -------------------------------------------------------------------
  # model serialisation
  # -------------------------------------------------------------------
  describe 'LoadScenario models' do
    it 'serialises to the LoadScenario JSON contract' do
      hash = sample_scenario.to_h
      expect(hash['name']).to eq('checkout-spike')
      expect(hash['profile']['stages'][0]['type']).to eq('VU')
      expect(hash['profile']['stages'][0]['curve']).to eq('LINEAR')
      expect(hash['steps'][0]['request']['path']).to eq('/orders')
      expect(hash['steps'][0]['thinkTime']).to eq({ 'timeUnit' => 'MILLISECONDS', 'value' => 500 })
    end

    it 'round-trips through from_hash' do
      original = sample_scenario
      roundtrip = MockServer::LoadScenario.from_hash(original.to_h)
      expect(roundtrip.to_h).to eq(original.to_h)
    end

    it 'omits nil fields' do
      scenario = MockServer::LoadScenario.new(
        name: 'minimal',
        profile: MockServer::LoadProfile.new(stages: [MockServer::LoadStage.vu(1000, vus: 3)]),
        steps: [MockServer::LoadStep.new(request: MockServer::HttpRequest.new(method: 'GET', path: '/'))]
      )
      hash = scenario.to_h
      expect(hash).not_to have_key('templateType')
      expect(hash).not_to have_key('labels')
      expect(hash).not_to have_key('maxRequests')
      expect(hash).not_to have_key('startDelayMillis')
      expect(hash['profile']['stages'][0]).not_to have_key('startVus')
      expect(hash['profile']['stages'][0]).not_to have_key('curve')
      expect(hash['steps'][0]).not_to have_key('thinkTime')
    end

    it 'serialises start_delay_millis to startDelayMillis and round-trips it' do
      scenario = MockServer::LoadScenario.new(
        name: 'delayed',
        start_delay_millis: 2_500,
        profile: MockServer::LoadProfile.new(stages: [MockServer::LoadStage.vu(1000, vus: 3)]),
        steps: [MockServer::LoadStep.new(request: MockServer::HttpRequest.new(method: 'GET', path: '/'))]
      )
      hash = scenario.to_h
      expect(hash['startDelayMillis']).to eq(2_500)

      roundtrip = MockServer::LoadScenario.from_hash(hash)
      expect(roundtrip.start_delay_millis).to eq(2_500)
      expect(roundtrip.to_h).to eq(hash)
    end
  end
end
