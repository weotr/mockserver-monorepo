# frozen_string_literal: true

require_relative 'client'

module MockServer
  # RSpec integration helpers for MockServer.
  #
  # Require this file from your `spec_helper.rb` to get a shared context that
  # provides a fresh {MockServer::Client} per example and automatically resets
  # the server between examples, so recorded requests, expectations and logs
  # never leak from one example to the next:
  #
  #     require 'mockserver/rspec'
  #
  #     RSpec.describe 'my integration', :mockserver do
  #       it 'records the request' do
  #         # `mockserver` is the shared, reset client
  #         mockserver.when(MockServer::HttpRequest.request(path: '/hello'))
  #                   .respond(MockServer::HttpResponse.response(body: 'world'))
  #       end
  #     end
  #
  # The host and port default to `127.0.0.1:1080` and can be overridden with the
  # `MOCKSERVER_HOST` / `MOCKSERVER_PORT` environment variables, or by defining a
  # `mockserver_host` / `mockserver_port` `let` in your example group.
  module RSpec
    SHARED_CONTEXT_NAME = 'mockserver client'

    if defined?(::RSpec)
      ::RSpec.shared_context SHARED_CONTEXT_NAME do
        let(:mockserver_host) { ENV.fetch('MOCKSERVER_HOST', '127.0.0.1') }
        let(:mockserver_port) { Integer(ENV.fetch('MOCKSERVER_PORT', '1080')) }

        # A fresh client per example. Memoised so the same instance is returned
        # within a single example, but rebuilt for the next one.
        let(:mockserver) { MockServer::Client.new(mockserver_host, mockserver_port) }

        # Start each example from a clean server and tear down again afterwards,
        # closing any websocket connections opened by the client.
        before do
          mockserver.reset
        end

        after do
          mockserver.reset
        end
      end

      # Make the shared context available to any example group tagged
      # `:mockserver` without an explicit `include_context`.
      ::RSpec.configure do |config|
        config.include_context SHARED_CONTEXT_NAME, :mockserver
      end
    end
  end
end
