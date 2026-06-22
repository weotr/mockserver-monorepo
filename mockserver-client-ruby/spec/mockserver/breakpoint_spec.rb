# frozen_string_literal: true

require 'base64'
require 'json'

RSpec.describe 'Breakpoint support' do
  # -------------------------------------------------------------------
  # Constants
  # -------------------------------------------------------------------
  describe 'breakpoint constants' do
    it 'defines BREAKPOINT_ID_HEADER_NAME' do
      expect(MockServer::BREAKPOINT_ID_HEADER_NAME).to eq('X-MockServer-BreakpointId')
    end

    it 'defines TYPE_PAUSED_STREAM_FRAME_DTO' do
      expect(MockServer::TYPE_PAUSED_STREAM_FRAME_DTO).to eq('org.mockserver.serialization.model.PausedStreamFrameDTO')
    end

    it 'defines TYPE_STREAM_FRAME_DECISION_DTO' do
      expect(MockServer::TYPE_STREAM_FRAME_DECISION_DTO).to eq('org.mockserver.serialization.model.StreamFrameDecisionDTO')
    end
  end

  # -------------------------------------------------------------------
  # extract_breakpoint_id
  # -------------------------------------------------------------------
  describe 'MockServer.extract_breakpoint_id' do
    it 'returns nil when headers are nil' do
      req = MockServer::HttpRequest.new
      expect(MockServer.extract_breakpoint_id(req)).to be_nil
    end

    it 'returns nil when no matching header' do
      req = MockServer::HttpRequest.new
      req.with_header('Content-Type', 'application/json')
      expect(MockServer.extract_breakpoint_id(req)).to be_nil
    end

    it 'returns breakpoint ID from header' do
      req = MockServer::HttpRequest.new
      req.headers = [
        MockServer::KeyToMultiValue.new(name: 'X-MockServer-BreakpointId', values: ['bp-1'])
      ]
      expect(MockServer.extract_breakpoint_id(req)).to eq('bp-1')
    end

    it 'returns nil when values are empty' do
      req = MockServer::HttpRequest.new
      req.headers = [
        MockServer::KeyToMultiValue.new(name: 'X-MockServer-BreakpointId', values: [])
      ]
      expect(MockServer.extract_breakpoint_id(req)).to be_nil
    end
  end

  # -------------------------------------------------------------------
  # WebSocketClient breakpoint handler registration
  # -------------------------------------------------------------------
  describe MockServer::WebSocketClient do
    let(:ws_client) { MockServer::WebSocketClient.new }

    describe 'handler registration' do
      it 'registers and removes request handler' do
        handler = ->(req) { req }
        ws_client.set_breakpoint_request_handler('bp-1', handler)
        expect(ws_client.instance_variable_get(:@breakpoint_request_handlers)['bp-1']).to eq(handler)

        ws_client.remove_breakpoint_handlers('bp-1')
        expect(ws_client.instance_variable_get(:@breakpoint_request_handlers)['bp-1']).to be_nil
      end

      it 'registers and removes response handler' do
        handler = ->(req, resp) { resp }
        ws_client.set_breakpoint_response_handler('bp-1', handler)
        expect(ws_client.instance_variable_get(:@breakpoint_response_handlers)['bp-1']).to eq(handler)

        ws_client.remove_breakpoint_handlers('bp-1')
        expect(ws_client.instance_variable_get(:@breakpoint_response_handlers)['bp-1']).to be_nil
      end

      it 'registers and removes stream frame handler' do
        handler = ->(frame) { { 'action' => 'CONTINUE' } }
        ws_client.set_breakpoint_stream_frame_handler('bp-1', handler)
        expect(ws_client.instance_variable_get(:@breakpoint_stream_frame_handlers)['bp-1']).to eq(handler)

        ws_client.remove_breakpoint_handlers('bp-1')
        expect(ws_client.instance_variable_get(:@breakpoint_stream_frame_handlers)['bp-1']).to be_nil
      end

      it 'clears all breakpoint handlers' do
        ws_client.set_breakpoint_request_handler('bp-1', ->(r) { r })
        ws_client.set_breakpoint_response_handler('bp-2', ->(r, s) { s })
        ws_client.set_breakpoint_stream_frame_handler('bp-3', ->(f) { f })

        ws_client.clear_breakpoint_handlers

        expect(ws_client.instance_variable_get(:@breakpoint_request_handlers)).to be_empty
        expect(ws_client.instance_variable_get(:@breakpoint_response_handlers)).to be_empty
        expect(ws_client.instance_variable_get(:@breakpoint_stream_frame_handlers)).to be_empty
      end

      it 'ignores nil breakpoint id' do
        ws_client.set_breakpoint_request_handler(nil, ->(r) { r })
        expect(ws_client.instance_variable_get(:@breakpoint_request_handlers)).to be_empty
      end

      it 'ignores nil handler' do
        ws_client.set_breakpoint_request_handler('bp-1', nil)
        expect(ws_client.instance_variable_get(:@breakpoint_request_handlers)).to be_empty
      end
    end

    describe 'breakpoint message routing' do
      let(:fake_ws) { double('ws') }

      before do
        allow(fake_ws).to receive(:send)
        ws_client.instance_variable_set(:@ws, fake_ws)
      end

      # --- REQUEST phase ---

      it 'routes HttpRequest to breakpoint request handler' do
        modified = MockServer::HttpRequest.new(method: 'PUT', path: '/modified')
        ws_client.set_breakpoint_request_handler('bp-1', ->(req) { modified })

        msg = JSON.generate({
          'type' => MockServer::TYPE_HTTP_REQUEST,
          'value' => JSON.generate({
            'method' => 'GET',
            'path' => '/api/test',
            'headers' => [
              { 'name' => 'WebSocketCorrelationId', 'values' => ['corr-1'] },
              { 'name' => 'X-MockServer-BreakpointId', 'values' => ['bp-1'] }
            ]
          })
        })

        ws_client.send(:handle_raw_message, msg)

        expect(fake_ws).to have_received(:send) do |sent_msg|
          parsed = JSON.parse(sent_msg)
          expect(parsed['type']).to eq(MockServer::TYPE_HTTP_REQUEST)
          inner = JSON.parse(parsed['value'])
          expect(inner['method']).to eq('PUT')
          expect(inner['path']).to eq('/modified')
          # Correlation id should be echoed
          corr_header = inner['headers']&.find { |h| h['name'] == 'WebSocketCorrelationId' }
          expect(corr_header).not_to be_nil
          expect(corr_header['values']).to eq(['corr-1'])
        end
      end

      it 'handler returning HttpResponse sends abort' do
        ws_client.set_breakpoint_request_handler('bp-1', ->(_req) {
          MockServer::HttpResponse.new(status_code: 403)
        })

        msg = JSON.generate({
          'type' => MockServer::TYPE_HTTP_REQUEST,
          'value' => JSON.generate({
            'headers' => [
              { 'name' => 'WebSocketCorrelationId', 'values' => ['corr-2'] },
              { 'name' => 'X-MockServer-BreakpointId', 'values' => ['bp-1'] }
            ]
          })
        })

        ws_client.send(:handle_raw_message, msg)

        expect(fake_ws).to have_received(:send) do |sent_msg|
          parsed = JSON.parse(sent_msg)
          expect(parsed['type']).to eq(MockServer::TYPE_HTTP_RESPONSE)
          inner = JSON.parse(parsed['value'])
          expect(inner['statusCode']).to eq(403)
        end
      end

      it 'auto-continues on request handler error' do
        ws_client.set_breakpoint_request_handler('bp-1', ->(_req) {
          raise 'oops'
        })

        msg = JSON.generate({
          'type' => MockServer::TYPE_HTTP_REQUEST,
          'value' => JSON.generate({
            'method' => 'GET',
            'path' => '/original',
            'headers' => [
              { 'name' => 'WebSocketCorrelationId', 'values' => ['corr-3'] },
              { 'name' => 'X-MockServer-BreakpointId', 'values' => ['bp-1'] }
            ]
          })
        })

        ws_client.send(:handle_raw_message, msg)

        expect(fake_ws).to have_received(:send) do |sent_msg|
          parsed = JSON.parse(sent_msg)
          expect(parsed['type']).to eq(MockServer::TYPE_HTTP_REQUEST)
          inner = JSON.parse(parsed['value'])
          expect(inner['path']).to eq('/original')
        end
      end

      # --- RESPONSE phase ---

      it 'routes HttpRequestAndHttpResponse to breakpoint response handler' do
        ws_client.set_breakpoint_response_handler('bp-2', ->(_req, _resp) {
          MockServer::HttpResponse.new(status_code: 202)
        })

        msg = JSON.generate({
          'type' => MockServer::TYPE_HTTP_REQUEST_AND_RESPONSE,
          'value' => JSON.generate({
            'httpRequest' => {
              'method' => 'GET',
              'path' => '/test',
              'headers' => [
                { 'name' => 'WebSocketCorrelationId', 'values' => ['corr-5'] },
                { 'name' => 'X-MockServer-BreakpointId', 'values' => ['bp-2'] }
              ]
            },
            'httpResponse' => { 'statusCode' => 200 }
          })
        })

        ws_client.send(:handle_raw_message, msg)

        expect(fake_ws).to have_received(:send) do |sent_msg|
          parsed = JSON.parse(sent_msg)
          expect(parsed['type']).to eq(MockServer::TYPE_HTTP_RESPONSE)
          inner = JSON.parse(parsed['value'])
          expect(inner['statusCode']).to eq(202)
        end
      end

      it 'auto-continues on response handler error' do
        ws_client.set_breakpoint_response_handler('bp-2', ->(_req, _resp) {
          raise 'boom'
        })

        msg = JSON.generate({
          'type' => MockServer::TYPE_HTTP_REQUEST_AND_RESPONSE,
          'value' => JSON.generate({
            'httpRequest' => {
              'headers' => [
                { 'name' => 'WebSocketCorrelationId', 'values' => ['corr-6'] },
                { 'name' => 'X-MockServer-BreakpointId', 'values' => ['bp-2'] }
              ]
            },
            'httpResponse' => { 'statusCode' => 200 }
          })
        })

        ws_client.send(:handle_raw_message, msg)

        expect(fake_ws).to have_received(:send) do |sent_msg|
          parsed = JSON.parse(sent_msg)
          expect(parsed['type']).to eq(MockServer::TYPE_HTTP_RESPONSE)
          inner = JSON.parse(parsed['value'])
          expect(inner['statusCode']).to eq(200)
        end
      end

      # --- STREAM FRAME phase ---

      it 'routes PausedStreamFrameDTO to stream frame handler' do
        modified_body = Base64.strict_encode64('modified-data')
        ws_client.set_breakpoint_stream_frame_handler('bp-3', ->(frame) {
          { 'action' => 'MODIFY', 'body' => modified_body }
        })

        paused_frame = {
          'correlationId' => 'frame-corr-1',
          'streamId' => 'stream-1',
          'sequenceNumber' => 0,
          'direction' => 'OUTBOUND',
          'phase' => 'RESPONSE_STREAM',
          'body' => Base64.strict_encode64('original-data'),
          'requestMethod' => 'GET',
          'requestPath' => '/sse',
          'breakpointId' => 'bp-3'
        }

        msg = JSON.generate({
          'type' => MockServer::TYPE_PAUSED_STREAM_FRAME_DTO,
          'value' => JSON.generate(paused_frame)
        })

        ws_client.send(:handle_raw_message, msg)

        expect(fake_ws).to have_received(:send) do |sent_msg|
          parsed = JSON.parse(sent_msg)
          expect(parsed['type']).to eq(MockServer::TYPE_STREAM_FRAME_DECISION_DTO)
          inner = JSON.parse(parsed['value'])
          expect(inner['correlationId']).to eq('frame-corr-1')
          expect(inner['action']).to eq('MODIFY')
          expect(Base64.strict_decode64(inner['body'])).to eq('modified-data')
        end
      end

      it 'auto-continues for unknown stream frame breakpoint id' do
        paused_frame = {
          'correlationId' => 'frame-corr-2',
          'breakpointId' => 'unknown-bp'
        }

        msg = JSON.generate({
          'type' => MockServer::TYPE_PAUSED_STREAM_FRAME_DTO,
          'value' => JSON.generate(paused_frame)
        })

        ws_client.send(:handle_raw_message, msg)

        expect(fake_ws).to have_received(:send) do |sent_msg|
          parsed = JSON.parse(sent_msg)
          inner = JSON.parse(parsed['value'])
          expect(inner['action']).to eq('CONTINUE')
          expect(inner['correlationId']).to eq('frame-corr-2')
        end
      end

      it 'auto-continues on stream frame handler error' do
        ws_client.set_breakpoint_stream_frame_handler('bp-3', ->(_frame) {
          raise 'handler error'
        })

        paused_frame = {
          'correlationId' => 'frame-corr-3',
          'breakpointId' => 'bp-3'
        }

        msg = JSON.generate({
          'type' => MockServer::TYPE_PAUSED_STREAM_FRAME_DTO,
          'value' => JSON.generate(paused_frame)
        })

        ws_client.send(:handle_raw_message, msg)

        expect(fake_ws).to have_received(:send) do |sent_msg|
          parsed = JSON.parse(sent_msg)
          inner = JSON.parse(parsed['value'])
          expect(inner['action']).to eq('CONTINUE')
          expect(inner['correlationId']).to eq('frame-corr-3')
        end
      end

      it 'auto-continues for stream frame with no breakpoint id' do
        paused_frame = {
          'correlationId' => 'frame-corr-4'
        }

        msg = JSON.generate({
          'type' => MockServer::TYPE_PAUSED_STREAM_FRAME_DTO,
          'value' => JSON.generate(paused_frame)
        })

        ws_client.send(:handle_raw_message, msg)

        expect(fake_ws).to have_received(:send) do |sent_msg|
          parsed = JSON.parse(sent_msg)
          inner = JSON.parse(parsed['value'])
          expect(inner['action']).to eq('CONTINUE')
        end
      end

      # --- Per-breakpoint-id routing ---

      it 'routes two breakpoints independently' do
        calls = { 'bp-A' => [], 'bp-B' => [] }
        sent_messages = []

        ws_client.set_breakpoint_request_handler('bp-A', ->(req) {
          calls['bp-A'] << req.path
          MockServer::HttpRequest.new(method: 'GET', path: '/from-A')
        })
        ws_client.set_breakpoint_request_handler('bp-B', ->(req) {
          calls['bp-B'] << req.path
          MockServer::HttpRequest.new(method: 'GET', path: '/from-B')
        })

        allow(fake_ws).to receive(:send) { |m| sent_messages << m }

        [['bp-A', '/from-A'], ['bp-B', '/from-B']].each do |bp_id, expected_path|
          msg = JSON.generate({
            'type' => MockServer::TYPE_HTTP_REQUEST,
            'value' => JSON.generate({
              'method' => 'GET',
              'path' => "/test-#{bp_id}",
              'headers' => [
                { 'name' => 'WebSocketCorrelationId', 'values' => ['c'] },
                { 'name' => 'X-MockServer-BreakpointId', 'values' => [bp_id] }
              ]
            })
          })

          ws_client.send(:handle_raw_message, msg)
        end

        expect(sent_messages.length).to eq(2)

        # First message should be from bp-A
        parsed_a = JSON.parse(sent_messages[0])
        inner_a = JSON.parse(parsed_a['value'])
        expect(inner_a['path']).to eq('/from-A')

        # Second message should be from bp-B
        parsed_b = JSON.parse(sent_messages[1])
        inner_b = JSON.parse(parsed_b['value'])
        expect(inner_b['path']).to eq('/from-B')

        expect(calls['bp-A']).to eq(['/test-bp-A'])
        expect(calls['bp-B']).to eq(['/test-bp-B'])
      end
    end
  end
end
