# frozen_string_literal: true

RSpec.describe 'Stateful scenarios' do
  let(:host) { 'localhost' }
  let(:port) { 1080 }
  let(:base_url) { "http://#{host}:#{port}" }
  let(:client) { MockServer::Client.new(host, port) }

  after { client.close }

  # -------------------------------------------------------------------
  # Expectation model serialisation (new typed scenario fields)
  # -------------------------------------------------------------------
  describe 'Expectation scenario fields' do
    def scenario_expectation
      MockServer::Expectation.new(
        http_request: MockServer::HttpRequest.new(method: 'GET', path: '/deploy'),
        http_responses: [
          MockServer::HttpResponse.response(body: 'a'),
          MockServer::HttpResponse.response(body: 'b')
        ],
        response_mode: MockServer::ResponseMode::WEIGHTED,
        response_weights: [3, 1],
        switch_after: 5,
        scenario_name: 'Deploy',
        scenario_state: 'Deploying',
        new_scenario_state: 'Deployed',
        cross_protocol_scenarios: [
          MockServer::CrossProtocolScenario.new(
            trigger: MockServer::CrossProtocolTrigger::DNS_QUERY,
            match_pattern: 'api.example.com',
            scenario_name: 'Deploy',
            target_state: 'DnsObserved'
          )
        ]
      )
    end

    it 'emits the new fields in camelCase' do
      hash = scenario_expectation.to_h

      expect(hash['responseMode']).to eq('WEIGHTED')
      expect(hash['responseWeights']).to eq([3, 1])
      expect(hash['switchAfter']).to eq(5)
      expect(hash['scenarioName']).to eq('Deploy')
      expect(hash['scenarioState']).to eq('Deploying')
      expect(hash['newScenarioState']).to eq('Deployed')
      expect(hash['httpResponses'].length).to eq(2)
    end

    it 'emits crossProtocolScenarios with nested camelCase keys' do
      hash = scenario_expectation.to_h
      cps = hash['crossProtocolScenarios']

      expect(cps).to be_an(Array)
      expect(cps.length).to eq(1)
      expect(cps[0]).to eq(
        'trigger'      => 'DNS_QUERY',
        'matchPattern' => 'api.example.com',
        'scenarioName' => 'Deploy',
        'targetState'  => 'DnsObserved'
      )
    end

    it 'omits the new fields when unset' do
      hash = MockServer::Expectation.new(
        http_request: MockServer::HttpRequest.new(path: '/x'),
        http_response: MockServer::HttpResponse.response(body: 'y')
      ).to_h

      expect(hash).not_to have_key('responseWeights')
      expect(hash).not_to have_key('switchAfter')
      expect(hash).not_to have_key('crossProtocolScenarios')
      expect(hash).not_to have_key('responseMode')
      expect(hash).not_to have_key('scenarioName')
    end

    it 'omits optional matchPattern on a CrossProtocolScenario when unset' do
      hash = MockServer::CrossProtocolScenario.new(
        trigger: MockServer::CrossProtocolTrigger::WEBSOCKET_CONNECT,
        scenario_name: 'Deploy',
        target_state: 'Connected'
      ).to_h

      expect(hash).to eq(
        'trigger'      => 'WEBSOCKET_CONNECT',
        'scenarioName' => 'Deploy',
        'targetState'  => 'Connected'
      )
      expect(hash).not_to have_key('matchPattern')
    end

    it 'round-trips through from_hash' do
      original = scenario_expectation
      roundtrip = MockServer::Expectation.from_hash(original.to_h)

      expect(roundtrip.response_mode).to eq('WEIGHTED')
      expect(roundtrip.response_weights).to eq([3, 1])
      expect(roundtrip.switch_after).to eq(5)
      expect(roundtrip.cross_protocol_scenarios.length).to eq(1)
      expect(roundtrip.cross_protocol_scenarios[0].trigger).to eq('DNS_QUERY')
      expect(roundtrip.cross_protocol_scenarios[0].match_pattern).to eq('api.example.com')
      expect(roundtrip.to_h).to eq(original.to_h)
    end
  end

  # -------------------------------------------------------------------
  # scenario(name).state (GET)
  # -------------------------------------------------------------------
  describe '#scenario state' do
    it 'sends GET to /mockserver/scenario/{name} and returns currentState' do
      stub_request(:get, "#{base_url}/mockserver/scenario/Deploy")
        .to_return(status: 200, body: JSON.generate({ 'scenarioName' => 'Deploy', 'currentState' => 'Deploying' }))

      result = client.scenario('Deploy').state

      expect(WebMock).to have_requested(:get, "#{base_url}/mockserver/scenario/Deploy")
      expect(result).to eq('Deploying')
    end

    it 'URL-encodes the scenario name' do
      stub_request(:get, "#{base_url}/mockserver/scenario/My%20Deploy")
        .to_return(status: 200, body: JSON.generate({ 'scenarioName' => 'My Deploy', 'currentState' => 'X' }))

      client.scenario('My Deploy').state

      expect(WebMock).to have_requested(:get, "#{base_url}/mockserver/scenario/My%20Deploy")
    end

    it 'raises Error on failure' do
      stub_request(:get, "#{base_url}/mockserver/scenario/Deploy")
        .to_return(status: 500, body: 'boom')

      expect { client.scenario('Deploy').state }
        .to raise_error(MockServer::Error, /Scenario request failed/)
    end
  end

  # -------------------------------------------------------------------
  # scenario(name).set (PUT)
  # -------------------------------------------------------------------
  describe '#scenario set' do
    it 'sends PUT to /mockserver/scenario/{name} with the state body' do
      stub_request(:put, "#{base_url}/mockserver/scenario/Deploy")
        .to_return(status: 200, body: JSON.generate({ 'scenarioName' => 'Deploy', 'currentState' => 'Deploying' }))

      result = client.scenario('Deploy').set('Deploying')

      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/scenario/Deploy")
        .with { |r| JSON.parse(r.body) == { 'state' => 'Deploying' } }
      expect(result.scenario_name).to eq('Deploy')
      expect(result.current_state).to eq('Deploying')
    end

    it 'includes transitionAfterMs and nextState when given' do
      stub_request(:put, "#{base_url}/mockserver/scenario/Deploy")
        .to_return(status: 200, body: JSON.generate({ 'scenarioName' => 'Deploy', 'currentState' => 'Deploying', 'nextState' => 'Deployed', 'transitionAfterMs' => 5000 }))

      result = client.scenario('Deploy').set('Deploying', transition_after_ms: 5000, next_state: 'Deployed')

      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/scenario/Deploy")
        .with { |r|
          body = JSON.parse(r.body)
          body['state'] == 'Deploying' &&
            body['transitionAfterMs'] == 5000 &&
            body['nextState'] == 'Deployed'
        }
      expect(result.next_state).to eq('Deployed')
      expect(result.transition_after_ms).to eq(5000)
    end

    it 'omits transitionAfterMs and nextState when not given' do
      stub_request(:put, "#{base_url}/mockserver/scenario/Deploy")
        .to_return(status: 200, body: JSON.generate({ 'scenarioName' => 'Deploy', 'currentState' => 'Deploying' }))

      client.scenario('Deploy').set('Deploying')

      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/scenario/Deploy")
        .with { |r|
          body = JSON.parse(r.body)
          !body.key?('transitionAfterMs') && !body.key?('nextState')
        }
    end
  end

  # -------------------------------------------------------------------
  # scenario(name).trigger (PUT .../trigger)
  # -------------------------------------------------------------------
  describe '#scenario trigger' do
    it 'sends PUT to /mockserver/scenario/{name}/trigger with the newState body' do
      stub_request(:put, "#{base_url}/mockserver/scenario/Deploy/trigger")
        .to_return(status: 200, body: JSON.generate({ 'scenarioName' => 'Deploy', 'currentState' => 'Failed' }))

      result = client.scenario('Deploy').trigger('Failed')

      expect(WebMock).to have_requested(:put, "#{base_url}/mockserver/scenario/Deploy/trigger")
        .with { |r| JSON.parse(r.body) == { 'newState' => 'Failed' } }
      expect(result.current_state).to eq('Failed')
    end
  end

  # -------------------------------------------------------------------
  # scenarios (GET list)
  # -------------------------------------------------------------------
  describe '#scenarios' do
    it 'sends GET to /mockserver/scenario and returns the scenario states' do
      stub_request(:get, "#{base_url}/mockserver/scenario")
        .to_return(status: 200, body: JSON.generate({
          'scenarios' => [
            { 'scenarioName' => 'Deploy', 'currentState' => 'Deploying' },
            { 'scenarioName' => 'Login', 'currentState' => 'LoggedOut' }
          ]
        }))

      result = client.scenarios

      expect(WebMock).to have_requested(:get, "#{base_url}/mockserver/scenario")
      expect(result.length).to eq(2)
      expect(result[0].scenario_name).to eq('Deploy')
      expect(result[0].current_state).to eq('Deploying')
      expect(result[1].scenario_name).to eq('Login')
    end

    it 'returns an empty list when there are no scenarios' do
      stub_request(:get, "#{base_url}/mockserver/scenario")
        .to_return(status: 200, body: JSON.generate({ 'scenarios' => [] }))

      expect(client.scenarios).to eq([])
    end
  end
end
