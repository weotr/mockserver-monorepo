# frozen_string_literal: true

RSpec.describe MockServer::LLM do
  describe '.llm_mock' do
    it 'builds a single completion expectation with uppercase provider and httpLlmResponse' do
      expectation = described_class.llm_mock('/v1/chat/completions')
                                   .with_provider(MockServer::LLM::Provider::OPENAI)
                                   .with_model('gpt-4o')
                                   .responding_with(described_class.completion.with_text('Hello!'))
                                   .build

      expect(expectation['httpRequest']).to eq('method' => 'POST', 'path' => '/v1/chat/completions')
      llm = expectation['httpLlmResponse']
      expect(llm).not_to be_nil
      expect(llm['provider']).to eq('OPENAI')
      expect(llm['model']).to eq('gpt-4o')
      expect(llm['completion']).to eq('text' => 'Hello!')
      expect(llm).not_to have_key('embedding')
    end

    it 'omits nil fields (NON_NULL) and serialises usage and tool calls' do
      expectation = described_class.llm_mock('/chat')
                                   .with_provider(MockServer::LLM::Provider::ANTHROPIC)
                                   .responding_with(
                                     described_class.completion
                                                    .with_text('done')
                                                    .with_stop_reason('tool_use')
                                                    .with_usage(described_class.usage.with_input_tokens(10).with_output_tokens(5))
                                                    .with_tool_call(
                                                      described_class.tool_use('get_weather')
                                                                     .with_id('call_1')
                                                                     .with_arguments('{"city":"London"}')
                                                    )
                                   )
                                   .build

      llm = expectation['httpLlmResponse']
      expect(llm).not_to have_key('model')
      completion = llm['completion']
      expect(completion['usage']).to eq('inputTokens' => 10, 'outputTokens' => 5)
      expect(completion['stopReason']).to eq('tool_use')
      expect(completion['toolCalls']).to eq(
        [{ 'id' => 'call_1', 'name' => 'get_weather', 'arguments' => '{"city":"London"}' }]
      )
    end

    it 'clears completion when responding with an embedding and vice-versa' do
      builder = described_class.llm_mock('/embeddings')
                               .with_provider(MockServer::LLM::Provider::OPENAI)
                               .responding_with(described_class.completion.with_text('x'))
                               .responding_with(described_class.embedding.with_dimensions(1536))

      llm = builder.build['httpLlmResponse']
      expect(llm).not_to have_key('completion')
      expect(llm['embedding']).to eq('dimensions' => 1536)
    end

    it 'serialises streamingPhysics' do
      expectation = described_class.llm_mock('/chat')
                                   .with_provider(MockServer::LLM::Provider::OPENAI)
                                   .responding_with(
                                     described_class.completion
                                                    .with_text('hi')
                                                    .streaming
                                                    .with_streaming_physics(
                                                      described_class.streaming_physics
                                                                     .with_tokens_per_second(50)
                                                                     .with_jitter(0.2)
                                                                     .with_time_to_first_token(100)
                                                    )
                                   )
                                   .build

      completion = expectation['httpLlmResponse']['completion']
      expect(completion['streaming']).to be(true)
      expect(completion['streamingPhysics']).to eq(
        'tokensPerSecond' => 50,
        'jitter' => 0.2,
        'timeToFirstToken' => { 'timeUnit' => 'MILLISECONDS', 'value' => 100 }
      )
    end

    it 'validates usage and streaming physics bounds' do
      expect { described_class.usage.with_input_tokens(-1) }.to raise_error(ArgumentError)
      expect { described_class.streaming_physics.with_tokens_per_second(0) }.to raise_error(ArgumentError)
      expect { described_class.streaming_physics.with_jitter(1.5) }.to raise_error(ArgumentError)
    end
  end

  describe '.conversation' do
    it 'advances scenario states Started/turn_1/.../__done' do
      expectations = described_class.conversation
                                    .with_path('/chat')
                                    .with_provider(MockServer::LLM::Provider::OPENAI)
                                    .turn.when_latest_message_contains('hello').responding_with(described_class.completion.with_text('hi'))
                                    .turn.when_latest_message_contains('bye').responding_with(described_class.completion.with_text('cya'))
                                    .build

      expect(expectations.length).to eq(2)

      scenario_name = expectations.first['scenarioName']
      expect(scenario_name).to start_with('__llm_conv_')
      expect(expectations.all? { |e| e['scenarioName'] == scenario_name }).to be(true)

      expect(expectations[0]['scenarioState']).to eq('Started')
      expect(expectations[0]['newScenarioState']).to eq('turn_1')
      expect(expectations[1]['scenarioState']).to eq('turn_1')
      expect(expectations[1]['newScenarioState']).to eq('__done')

      preds = expectations[0]['httpLlmResponse']['conversationPredicates']
      expect(preds).to eq('latestMessageContains' => 'hello')
    end

    it 'appends an isolation marker when isolate_by is set' do
      expectations = described_class.conversation
                                    .with_path('/chat')
                                    .with_provider(MockServer::LLM::Provider::OPENAI)
                                    .isolate_by(described_class.header('x-session-id'))
                                    .turn.responding_with(described_class.completion.with_text('hi'))
                                    .build

      expect(expectations.first['scenarioName']).to match(/\A__llm_conv_.+__iso=header:x-session-id\z/)
    end

    it 'omits conversationPredicates when a turn has no predicate (normalization alone does not count)' do
      expectations = described_class.conversation
                                    .with_path('/chat')
                                    .with_provider(MockServer::LLM::Provider::OPENAI)
                                    .turn.responding_with(described_class.completion.with_text('hi'))
                                    .build

      expect(expectations.first['httpLlmResponse']).not_to have_key('conversationPredicates')
    end

    it 'validates required fields' do
      expect { described_class.conversation.with_provider('OPENAI').turn.build }.to raise_error(ArgumentError)
      expect do
        described_class.conversation.with_path('/c').with_provider('OPENAI').build
      end.to raise_error(ArgumentError, /turn/)
    end
  end

  describe '.llm_failover' do
    it 'coalesces consecutive identical failures into one expectation with remainingTimes' do
      expectations = described_class.llm_failover
                                    .with_path('/chat')
                                    .with_provider(MockServer::LLM::Provider::OPENAI)
                                    .fail_with(429, 3)
                                    .then_respond_with(described_class.completion.with_text('ok'))
                                    .build

      expect(expectations.length).to eq(2)
      failure = expectations[0]
      expect(failure['times']).to eq('remainingTimes' => 3, 'unlimited' => false)
      expect(failure['timeToLive']).to eq('unlimited' => true)
      expect(failure['httpResponse']['statusCode']).to eq(429)
      expect(failure['httpResponse']['headers']).to eq(
        [{ 'name' => 'Content-Type', 'values' => ['application/json'] }]
      )

      success = expectations[1]
      expect(success['times']).to eq('remainingTimes' => 0, 'unlimited' => true)
      expect(success['httpLlmResponse']['completion']).to eq('text' => 'ok')
    end

    it 'uses default error bodies keyed by status code' do
      expect(described_class.default_error_body(429)).to eq(
        '{"error":{"type":"rate_limit_error","message":"Rate limit exceeded. Please retry after a brief wait."}}'
      )
      expect(described_class.default_error_body(500)).to eq(
        '{"error":{"type":"internal_server_error","message":"An internal error occurred. Please retry your request."}}'
      )
      expect(described_class.default_error_body(502)).to eq(
        '{"error":{"type":"bad_gateway","message":"Bad gateway. The upstream server returned an invalid response."}}'
      )
      expect(described_class.default_error_body(503)).to eq(
        '{"error":{"type":"service_unavailable","message":"The service is temporarily overloaded. Please retry later."}}'
      )
      expect(described_class.default_error_body(418)).to eq(
        '{"error":{"type":"error","message":"Request failed with status 418"}}'
      )
    end

    it 'injects the default error body into a coalesced failure expectation' do
      expectations = described_class.llm_failover
                                    .with_path('/chat')
                                    .with_provider(MockServer::LLM::Provider::OPENAI)
                                    .fail_with(503)
                                    .then_respond_with(described_class.completion.with_text('ok'))
                                    .build

      expect(expectations[0]['httpResponse']['body']).to eq(described_class.default_error_body(503))
    end

    it 'does not coalesce failures with different status codes or bodies' do
      expectations = described_class.llm_failover
                                    .with_path('/chat')
                                    .with_provider(MockServer::LLM::Provider::OPENAI)
                                    .fail_with(429)
                                    .fail_with(500)
                                    .fail_with(500, 'custom')
                                    .then_respond_with(described_class.completion.with_text('ok'))
                                    .build

      # three distinct failure expectations + one success
      expect(expectations.length).to eq(4)
      expect(expectations[0]['httpResponse']['statusCode']).to eq(429)
      expect(expectations[1]['httpResponse']['statusCode']).to eq(500)
      expect(expectations[2]['httpResponse']['body']).to eq('custom')
    end

    it 'validates status code and count' do
      expect { described_class.llm_failover.fail_with(99) }.to raise_error(ArgumentError)
      expect { described_class.llm_failover.fail_with(429, 0) }.to raise_error(ArgumentError)
    end
  end

  describe 'apply_to' do
    it 'registers built expectations via client.upsert' do
      client = instance_double(MockServer::Client)
      allow(client).to receive(:upsert)

      described_class.llm_mock('/chat')
                     .with_provider(MockServer::LLM::Provider::OPENAI)
                     .responding_with(described_class.completion.with_text('hi'))
                     .apply_to(client)

      expect(client).to have_received(:upsert) do |*args|
        expect(args.length).to eq(1)
        expect(args.first.to_h['httpLlmResponse']['provider']).to eq('OPENAI')
      end
    end
  end
end
