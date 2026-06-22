# frozen_string_literal: true

require 'securerandom'

module MockServer
  # Idiomatic Ruby LLM-mocking builder API for MockServer.
  #
  # This module mirrors the Java/Node/Python client LLM builders
  # (+LlmMockBuilder+, +LlmConversationBuilder+, +TurnBuilder+,
  # +LlmFailoverBuilder+) and the underlying server-side model classes
  # (+Completion+, +ToolUse+, +Usage+, +StreamingPhysics+, +EmbeddingResponse+).
  #
  # The builders produce plain Ruby Hashes with camelCase string keys that
  # serialise to exactly the same expectation wire JSON the other clients emit.
  # The expectation action is carried in the +httpLlmResponse+ field (a sibling
  # of +httpRequest+, +scenarioName+, +scenarioState+, +newScenarioState+,
  # +times+, +timeToLive+, +httpResponse+). Nil fields are omitted (NON_NULL).
  #
  # @example A single completion mock
  #   MockServer::LLM.llm_mock('/v1/chat/completions')
  #     .with_provider(MockServer::LLM::Provider::OPENAI)
  #     .with_model('gpt-4o')
  #     .responding_with(MockServer::LLM.completion.with_text('Hello!'))
  #     .apply_to(client)
  module LLM
    # LLM provider names. Serialized on the wire as the upper-case enum name.
    module Provider
      ANTHROPIC = 'ANTHROPIC'
      OPENAI = 'OPENAI'
      OPENAI_RESPONSES = 'OPENAI_RESPONSES'
      GEMINI = 'GEMINI'
      BEDROCK = 'BEDROCK'
      AZURE_OPENAI = 'AZURE_OPENAI'
      OLLAMA = 'OLLAMA'
    end

    # Parsed-message roles (mirrors org.mockserver.llm.ParsedMessage.Role).
    module Role
      USER = 'USER'
      ASSISTANT = 'ASSISTANT'
      TOOL = 'TOOL'
      SYSTEM = 'SYSTEM'
    end

    # @api private
    # Build a Hash from the given pairs, omitting nil values.
    def self.omit_nil(hash)
      result = {}
      hash.each { |k, v| result[k] = v unless v.nil? }
      result
    end

    # @api private
    # Convert a value to its wire form: call +to_h+ on builder objects, leave
    # Hashes/scalars untouched.
    def self.wire(value)
      return nil if value.nil?
      return value.map { |v| wire(v) } if value.is_a?(Array)
      return value.to_h if value.respond_to?(:to_h) && !value.is_a?(Hash)

      value
    end

    # =====================================================================
    # ToolUse
    # =====================================================================
    class ToolUse
      def initialize(name = nil)
        @name = name
        @id = nil
        @arguments = nil
      end

      # @return [self]
      def with_id(id)
        @id = id
        self
      end

      # @return [self]
      def with_name(name)
        @name = name
        self
      end

      # Accepts a JSON string (matching the Java API) or any value, which is
      # serialised to a JSON string.
      # @return [self]
      def with_arguments(args)
        @arguments = args.is_a?(String) ? args : JSON.generate(args)
        self
      end

      # @return [Hash]
      def to_h
        LLM.omit_nil('id' => @id, 'name' => @name, 'arguments' => @arguments)
      end
    end

    # @return [ToolUse]
    def self.tool_use(name = nil)
      ToolUse.new(name)
    end

    # =====================================================================
    # Usage
    # =====================================================================
    class Usage
      def initialize
        @input_tokens = nil
        @output_tokens = nil
      end

      # @return [self]
      def with_input_tokens(input_tokens)
        if !input_tokens.nil? && input_tokens.negative?
          raise ArgumentError, 'inputTokens must be >= 0'
        end

        @input_tokens = input_tokens
        self
      end

      # @return [self]
      def with_output_tokens(output_tokens)
        if !output_tokens.nil? && output_tokens.negative?
          raise ArgumentError, 'outputTokens must be >= 0'
        end

        @output_tokens = output_tokens
        self
      end

      # @return [Hash]
      def to_h
        LLM.omit_nil('inputTokens' => @input_tokens, 'outputTokens' => @output_tokens)
      end
    end

    # @return [Usage]
    def self.usage
      Usage.new
    end

    # @return [Usage]
    def self.input_tokens(count)
      Usage.new.with_input_tokens(count)
    end

    # @return [Usage]
    def self.output_tokens(count)
      Usage.new.with_output_tokens(count)
    end

    # =====================================================================
    # StreamingPhysics
    #   timeToFirstToken serialises as a Delay: { timeUnit, value }
    # =====================================================================
    class StreamingPhysics
      def initialize
        @time_to_first_token = nil
        @tokens_per_second = nil
        @jitter = nil
        @seed = nil
      end

      # Accepts a Delay-shaped Hash (+{ 'timeUnit' => .., 'value' => .. }+) or a
      # (value, time_unit) pair.
      # @return [self]
      def with_time_to_first_token(value, time_unit = 'MILLISECONDS')
        @time_to_first_token =
          if value.is_a?(Hash)
            { 'timeUnit' => value['timeUnit'] || value[:timeUnit],
              'value' => value['value'] || value[:value] }
          else
            { 'timeUnit' => time_unit, 'value' => value }
          end
        self
      end

      # @return [self]
      def with_tokens_per_second(tokens_per_second)
        if !tokens_per_second.nil? && (tokens_per_second < 1 || tokens_per_second > 10_000)
          raise ArgumentError, 'tokensPerSecond must be between 1 and 10000'
        end

        @tokens_per_second = tokens_per_second
        self
      end

      # @return [self]
      def with_jitter(jitter)
        if !jitter.nil? && (jitter < 0.0 || jitter > 1.0)
          raise ArgumentError, 'jitter must be between 0.0 and 1.0'
        end

        @jitter = jitter
        self
      end

      # @return [self]
      def with_seed(seed)
        @seed = seed
        self
      end

      # @return [Hash]
      def to_h
        LLM.omit_nil(
          'timeToFirstToken' => @time_to_first_token,
          'tokensPerSecond' => @tokens_per_second,
          'jitter' => @jitter,
          'seed' => @seed
        )
      end
    end

    # @return [StreamingPhysics]
    def self.streaming_physics
      StreamingPhysics.new
    end

    # @return [StreamingPhysics]
    def self.tokens_per_second(count)
      StreamingPhysics.new.with_tokens_per_second(count)
    end

    # @return [StreamingPhysics]
    def self.jitter(amount)
      StreamingPhysics.new.with_jitter(amount)
    end

    # Delay representing time-to-first-token: +{ 'timeUnit' => .., 'value' => .. }+.
    # @return [Hash]
    def self.time_to_first_token(value, time_unit = 'MILLISECONDS')
      { 'timeUnit' => time_unit, 'value' => value }
    end

    # =====================================================================
    # Completion
    # =====================================================================
    class Completion
      def initialize
        @text = nil
        @tool_calls = nil
        @stop_reason = nil
        @usage = nil
        @streaming = nil
        @streaming_physics = nil
        @output_schema = nil
        @model = nil
      end

      # @return [self]
      def with_text(text)
        @text = text
        self
      end

      # @return [self]
      def with_tool_call(tool_call)
        @tool_calls ||= []
        @tool_calls << tool_call
        self
      end

      # @return [self]
      def with_tool_calls(*tool_calls)
        flattened = tool_calls.length == 1 && tool_calls.first.is_a?(Array) ? tool_calls.first : tool_calls
        @tool_calls = flattened.dup
        self
      end

      # @return [self]
      def with_stop_reason(stop_reason)
        @stop_reason = stop_reason
        self
      end

      # @return [self]
      def with_usage(usage)
        @usage = usage
        self
      end

      # @return [self]
      def with_streaming(streaming = true)
        @streaming = streaming
        self
      end

      # Enable streaming. Mirror of +completion.streaming()+.
      # @return [self]
      def streaming
        with_streaming(true)
      end

      # Note: does NOT touch +streaming+ (matching the Java/Python builder).
      # @return [self]
      def with_streaming_physics(physics)
        @streaming_physics = physics
        self
      end

      # Accepts a JSON string (matching Java) or any value (serialised to JSON).
      # @return [self]
      def with_output_schema(output_schema)
        @output_schema = output_schema.is_a?(String) ? output_schema : JSON.generate(output_schema)
        self
      end

      # @return [self]
      def with_model(model)
        @model = model
        self
      end

      # @return [Hash]
      def to_h
        LLM.omit_nil(
          'text' => @text,
          'toolCalls' => LLM.wire(@tool_calls),
          'stopReason' => @stop_reason,
          'usage' => LLM.wire(@usage),
          'streaming' => @streaming,
          'streamingPhysics' => LLM.wire(@streaming_physics),
          'outputSchema' => @output_schema,
          'model' => @model
        )
      end
    end

    # @return [Completion]
    def self.completion
      Completion.new
    end

    # =====================================================================
    # EmbeddingResponse
    # =====================================================================
    class EmbeddingResponse
      def initialize
        @dimensions = nil
        @deterministic_from_input = nil
        @seed = nil
      end

      # @return [self]
      def with_dimensions(dimensions)
        @dimensions = dimensions
        self
      end

      # @return [self]
      def with_deterministic_from_input(deterministic)
        @deterministic_from_input = deterministic
        self
      end

      # @return [self]
      def with_seed(seed)
        @seed = seed
        self
      end

      # @return [Hash]
      def to_h
        LLM.omit_nil(
          'dimensions' => @dimensions,
          'deterministicFromInput' => @deterministic_from_input,
          'seed' => @seed
        )
      end
    end

    # @return [EmbeddingResponse]
    def self.embedding
      EmbeddingResponse.new
    end

    # =====================================================================
    # IsolationSource — encodes as "kind:name" (e.g. "header:x-session-id")
    # =====================================================================
    class IsolationSource
      attr_reader :kind, :name

      def initialize(kind, name)
        raise ArgumentError, 'name must not be nil or empty' if name.nil? || name.to_s.empty?

        @kind = kind
        @name = name
      end

      # @return [String]
      def encode
        "#{@kind}:#{@name}"
      end
    end

    # @return [IsolationSource]
    def self.header(name)
      IsolationSource.new('header', name)
    end

    # @return [IsolationSource]
    def self.query_parameter(name)
      IsolationSource.new('query_parameter', name)
    end

    # @return [IsolationSource]
    def self.cookie(name)
      IsolationSource.new('cookie', name)
    end

    # @api private
    def self.post_matcher(path)
      { 'method' => 'POST', 'path' => path }
    end

    # @api private
    def self.build_llm_response(provider, model, completion, embedding, conversation_predicates, chaos)
      omit_nil(
        'provider' => provider,
        'model' => model,
        'completion' => wire(completion),
        'embedding' => wire(embedding),
        'conversationPredicates' => wire(conversation_predicates),
        'chaos' => wire(chaos)
      )
    end

    # @api private
    # Wraps a Hash so it responds to +to_h+, allowing it to be passed to
    # +Client#upsert+ (which serialises via +to_h+).
    class RawExpectation
      def initialize(hash)
        @hash = hash
      end

      def to_h
        @hash
      end
    end

    # =====================================================================
    # LlmMockBuilder — a single completion or embedding mock.
    # =====================================================================
    class LlmMockBuilder
      def initialize(path)
        @path = path
        @provider = nil
        @model = nil
        @completion = nil
        @embedding = nil
      end

      # @return [self]
      def with_provider(provider)
        @provider = provider
        self
      end

      # @return [self]
      def with_model(model)
        @model = model
        self
      end

      # @param response [Completion, EmbeddingResponse]
      # @return [self]
      def responding_with(response)
        if response.is_a?(EmbeddingResponse)
          @embedding = response
          @completion = nil
        else
          @completion = response
          @embedding = nil
        end
        self
      end

      # @return [Hash] a single expectation
      def build
        {
          'httpRequest' => LLM.post_matcher(@path),
          'httpLlmResponse' => LLM.build_llm_response(@provider, @model, @completion, @embedding, nil, nil)
        }
      end

      # Build and register the expectation via +client.upsert+.
      # @return [Array<Expectation>]
      def apply_to(client)
        client.upsert(RawExpectation.new(build))
      end
    end

    # Entry point mirroring +LlmMockBuilder.llmMock(path)+.
    # @return [LlmMockBuilder]
    def self.llm_mock(path)
      LlmMockBuilder.new(path)
    end

    # =====================================================================
    # TurnBuilder — one turn within a conversation.
    # =====================================================================
    class TurnBuilder
      attr_reader :chaos, :completion

      def initialize(parent)
        @parent = parent
        @turn_index = nil
        @latest_message_contains = nil
        @latest_message_matches = nil
        @latest_message_role = nil
        @contains_tool_result_for = nil
        @semantic_match_against = nil
        @normalization = nil
        @chaos = nil
        @completion = nil
      end

      # @return [self]
      def when_turn_index(index)
        @turn_index = index
        self
      end

      # @return [self]
      def when_latest_message_contains(text)
        @latest_message_contains = text
        self
      end

      # @return [self]
      def when_latest_message_matches(regex)
        raise ArgumentError, 'regex must not be nil' if regex.nil?

        @latest_message_matches = regex.is_a?(Regexp) ? regex.source : regex
        self
      end

      # @return [self]
      def when_latest_message_role(role)
        @latest_message_role = role
        self
      end

      # @return [self]
      def when_contains_tool_result_for(tool_name)
        @contains_tool_result_for = tool_name
        self
      end

      # @return [self]
      def when_semantic_match(expected_meaning)
        @semantic_match_against = expected_meaning
        self
      end

      # @return [self]
      def with_normalization(normalization)
        @normalization = normalization
        self
      end

      # @return [self]
      def with_chaos(chaos)
        @chaos = chaos
        self
      end

      # @return [self]
      def responding_with(completion)
        @completion = completion
        self
      end

      # Start a new turn on the parent conversation builder.
      # @return [TurnBuilder]
      def turn
        @parent.turn
      end

      # Return to the parent conversation builder.
      # @return [LlmConversationBuilder]
      def and_then
        @parent
      end

      # @return [Array<Hash>]
      def build
        @parent.build
      end

      # @return [Array<Expectation>]
      def apply_to(client)
        @parent.apply_to(client)
      end

      # @api private
      # @return [Hash, nil] the conversationPredicates Hash, or nil if none set.
      def predicates
        return nil unless any_predicate?

        LLM.omit_nil(
          'turnIndex' => @turn_index,
          'latestMessageContains' => @latest_message_contains,
          'latestMessageMatches' => @latest_message_matches,
          'latestMessageRole' => @latest_message_role,
          'containsToolResultFor' => @contains_tool_result_for,
          'semanticMatchAgainst' => @semantic_match_against,
          'normalization' => LLM.wire(@normalization)
        )
      end

      # @api private
      # +normalization+ is intentionally excluded (a modifier, not a predicate).
      def any_predicate?
        !@turn_index.nil? ||
          !@latest_message_contains.nil? ||
          !@latest_message_matches.nil? ||
          !@latest_message_role.nil? ||
          !@contains_tool_result_for.nil? ||
          !@semantic_match_against.nil?
      end
    end

    # =====================================================================
    # LlmConversationBuilder — multi-turn conversation with scenario state.
    # =====================================================================
    SCENARIO_PREFIX = '__llm_conv_'
    ISOLATION_MARKER = '__iso='
    DONE_STATE = '__done'

    class LlmConversationBuilder
      def initialize
        @path = nil
        @provider = nil
        @model = nil
        @isolation_source = nil
        @turns = []
      end

      # @return [self]
      def with_path(path)
        @path = path
        self
      end

      # @return [self]
      def with_provider(provider)
        @provider = provider
        self
      end

      # @return [self]
      def with_model(model)
        @model = model
        self
      end

      # @return [self]
      def isolate_by(source)
        @isolation_source = source
        self
      end

      # @return [TurnBuilder]
      def turn
        turn_builder = TurnBuilder.new(self)
        @turns << turn_builder
        turn_builder
      end

      # @return [Array<Hash>] a list of expectations
      def build
        raise ArgumentError, 'At least one turn must be defined' if @turns.empty?
        raise ArgumentError, 'Path must be set' if @path.nil?
        raise ArgumentError, 'Provider must be set' if @provider.nil?

        conversation_id = SCENARIO_PREFIX + SecureRandom.uuid
        scenario_name = conversation_id
        if @isolation_source
          scenario_name = conversation_id + ISOLATION_MARKER + @isolation_source.encode
        end

        n = @turns.length
        @turns.each_with_index.map do |turn, i|
          next_state = i < n - 1 ? "turn_#{i + 1}" : DONE_STATE
          llm_response = LLM.build_llm_response(
            @provider, @model, turn.completion, nil, turn.predicates, turn.chaos
          )

          {
            'httpRequest' => LLM.post_matcher(@path),
            'scenarioName' => scenario_name,
            'scenarioState' => i.zero? ? 'Started' : "turn_#{i}",
            'newScenarioState' => next_state,
            'httpLlmResponse' => llm_response
          }
        end
      end

      # @return [Array<Expectation>]
      def apply_to(client)
        client.upsert(*build.map { |h| RawExpectation.new(h) })
      end
    end

    # Entry point mirroring +LlmConversationBuilder.conversation()+.
    # @return [LlmConversationBuilder]
    def self.conversation
      LlmConversationBuilder.new
    end

    # =====================================================================
    # LlmFailoverBuilder — N failures then a success completion.
    # =====================================================================
    def self.default_error_body(status_code)
      type, message =
        case status_code
        when 429
          ['rate_limit_error', 'Rate limit exceeded. Please retry after a brief wait.']
        when 500
          ['internal_server_error', 'An internal error occurred. Please retry your request.']
        when 502
          ['bad_gateway', 'Bad gateway. The upstream server returned an invalid response.']
        when 503
          ['service_unavailable', 'The service is temporarily overloaded. Please retry later.']
        else
          ['error', "Request failed with status #{status_code}"]
        end
      JSON.generate('error' => { 'type' => type, 'message' => message })
    end

    # @api private
    def self.validate_status_code(status_code)
      if status_code < 100 || status_code > 599
        raise ArgumentError, "statusCode must be between 100 and 599, got #{status_code}"
      end
    end

    class LlmFailoverBuilder
      def initialize
        @path = nil
        @provider = nil
        @model = nil
        @failures = []
        @success_completion = nil
      end

      # @return [self]
      def with_path(path)
        @path = path
        self
      end

      # @return [self]
      def with_provider(provider)
        @provider = provider
        self
      end

      # @return [self]
      def with_model(model)
        @model = model
        self
      end

      # Add one (or +count+) failure attempt(s) with the given status.
      #
      # Mirrors the three overloads: +fail_with(status)+,
      # +fail_with(status, error_body_string)+ and +fail_with(status, count_int)+.
      # @return [self]
      def fail_with(status_code, second = nil)
        LLM.validate_status_code(status_code)
        if second.is_a?(Integer)
          raise ArgumentError, "count must be >= 1, got #{second}" if second < 1

          second.times { @failures << { status_code: status_code, error_body: nil } }
        else
          @failures << { status_code: status_code, error_body: second.is_a?(String) ? second : nil }
        end
        self
      end

      # @return [self]
      def then_respond_with(completion)
        @success_completion = completion
        self
      end

      # @return [Integer]
      def failure_count
        @failures.length
      end

      # @api private
      def coalesce_failures
        result = []
        @failures.each do |spec|
          last = result.last
          if last && last[:status_code] == spec[:status_code] && last[:error_body] == spec[:error_body]
            last[:count] += 1
          else
            result << { status_code: spec[:status_code], error_body: spec[:error_body], count: 1 }
          end
        end
        result
      end

      # @return [Array<Hash>] a list of expectations
      def build
        raise ArgumentError, 'Path must be set' if @path.nil?
        raise ArgumentError, 'Provider must be set' if @provider.nil?
        raise ArgumentError, 'At least one failure must be defined' if @failures.empty?
        raise ArgumentError, 'Success completion must be set via then_respond_with()' if @success_completion.nil?

        expectations = coalesce_failures.map do |cf|
          body = cf[:error_body] || LLM.default_error_body(cf[:status_code])
          {
            'httpRequest' => LLM.post_matcher(@path),
            'times' => { 'remainingTimes' => cf[:count], 'unlimited' => false },
            'timeToLive' => { 'unlimited' => true },
            'httpResponse' => {
              'statusCode' => cf[:status_code],
              'headers' => [{ 'name' => 'Content-Type', 'values' => ['application/json'] }],
              'body' => body
            }
          }
        end

        expectations << {
          'httpRequest' => LLM.post_matcher(@path),
          'times' => { 'remainingTimes' => 0, 'unlimited' => true },
          'timeToLive' => { 'unlimited' => true },
          'httpLlmResponse' => LLM.build_llm_response(@provider, @model, @success_completion, nil, nil, nil)
        }

        expectations
      end

      # @return [Array<Expectation>]
      def apply_to(client)
        client.upsert(*build.map { |h| RawExpectation.new(h) })
      end
    end

    # Entry point mirroring +LlmFailoverBuilder.llmFailover()+.
    # @return [LlmFailoverBuilder]
    def self.llm_failover
      LlmFailoverBuilder.new
    end
  end
end
