# frozen_string_literal: true

RSpec.describe MockServer::ForwardChainExpectation do
  let(:mock_client) { instance_double(MockServer::Client) }
  let(:expectation) { MockServer::Expectation.new(http_request: MockServer::HttpRequest.new(path: '/test')) }
  let(:chain) { MockServer::ForwardChainExpectation.new(mock_client, expectation) }

  before do
    allow(mock_client).to receive(:upsert).and_return([expectation])
  end

  # -------------------------------------------------------------------
  # with_id
  # -------------------------------------------------------------------
  describe '#with_id' do
    it 'sets the expectation id and returns self' do
      result = chain.with_id('test-id')
      expect(result).to be(chain)
      expect(expectation.id).to eq('test-id')
    end
  end

  # -------------------------------------------------------------------
  # with_priority
  # -------------------------------------------------------------------
  describe '#with_priority' do
    it 'sets the expectation priority and returns self' do
      result = chain.with_priority(10)
      expect(result).to be(chain)
      expect(expectation.priority).to eq(10)
    end
  end

  # -------------------------------------------------------------------
  # respond
  # -------------------------------------------------------------------
  describe '#respond' do
    it 'sets http_response for HttpResponse' do
      resp = MockServer::HttpResponse.new(status_code: 200)
      chain.respond(resp)

      expect(expectation.http_response).to eq(resp)
      expect(mock_client).to have_received(:upsert).with(expectation)
    end

    it 'sets http_response_template for HttpTemplate' do
      tpl = MockServer::HttpTemplate.new(template: 'return {};')
      chain.respond(tpl)

      expect(expectation.http_response_template).to eq(tpl)
      expect(mock_client).to have_received(:upsert).with(expectation)
    end

    it 'raises TypeError for invalid types' do
      expect { chain.respond('invalid') }.to raise_error(TypeError, /Expected HttpResponse/)
      expect { chain.respond(123) }.to raise_error(TypeError, /Expected HttpResponse/)
      expect { chain.respond(nil) }.to raise_error(TypeError, /Expected HttpResponse/)
    end

    it 'handles callable (registers WebSocket callback)' do
      callback = ->(req) { MockServer::HttpResponse.new(status_code: 200) }
      allow(mock_client).to receive(:send).with(:register_websocket_callback, 'response', callback).and_return('ws-client-id')

      chain.respond(callback)

      expect(expectation.http_response_object_callback).to be_a(MockServer::HttpObjectCallback)
      expect(expectation.http_response_object_callback.client_id).to eq('ws-client-id')
    end
  end

  # -------------------------------------------------------------------
  # respond_with_delay
  # -------------------------------------------------------------------
  describe '#respond_with_delay' do
    it 'sets delay on response and upserts' do
      resp = MockServer::HttpResponse.new(status_code: 200)
      delay = MockServer::Delay.new(time_unit: 'SECONDS', value: 1)
      chain.respond_with_delay(resp, delay)

      expect(resp.delay).to eq(delay)
      expect(expectation.http_response).to eq(resp)
      expect(mock_client).to have_received(:upsert).with(expectation)
    end
  end

  # -------------------------------------------------------------------
  # forward
  # -------------------------------------------------------------------
  describe '#forward' do
    it 'sets http_forward for HttpForward' do
      fwd = MockServer::HttpForward.new(host: 'backend')
      chain.forward(fwd)

      expect(expectation.http_forward).to eq(fwd)
      expect(mock_client).to have_received(:upsert).with(expectation)
    end

    it 'sets http_override_forwarded_request for HttpOverrideForwardedRequest' do
      override = MockServer::HttpOverrideForwardedRequest.new(
        http_request: MockServer::HttpRequest.new(path: '/new')
      )
      chain.forward(override)

      expect(expectation.http_override_forwarded_request).to eq(override)
    end

    it 'sets http_forward_template for HttpTemplate' do
      tpl = MockServer::HttpTemplate.new(template: 'return {};')
      chain.forward(tpl)

      expect(expectation.http_forward_template).to eq(tpl)
    end

    it 'raises TypeError for invalid types' do
      expect { chain.forward('invalid') }.to raise_error(TypeError, /Expected HttpForward/)
      expect { chain.forward(123) }.to raise_error(TypeError, /Expected HttpForward/)
    end

    it 'handles callable (registers WebSocket callback)' do
      forward_fn = ->(req) { req }
      allow(mock_client).to receive(:send)
        .with(:register_websocket_callback, 'forward', forward_fn, nil)
        .and_return('ws-fwd-id')

      chain.forward(forward_fn)

      expect(expectation.http_forward_object_callback).to be_a(MockServer::HttpObjectCallback)
      expect(expectation.http_forward_object_callback.client_id).to eq('ws-fwd-id')
    end

    it 'sets response_callback=true when response_callback provided' do
      forward_fn = ->(req) { req }
      response_fn = ->(req, resp) { resp }
      allow(mock_client).to receive(:send)
        .with(:register_websocket_callback, 'forward', forward_fn, response_fn)
        .and_return('ws-fwd-id')

      chain.forward(forward_fn, response_fn)

      expect(expectation.http_forward_object_callback.response_callback).to eq(true)
    end
  end

  # -------------------------------------------------------------------
  # forward_with_delay
  # -------------------------------------------------------------------
  describe '#forward_with_delay' do
    it 'sets delay on forward and upserts' do
      fwd = MockServer::HttpForward.new(host: 'backend')
      delay = MockServer::Delay.new(time_unit: 'SECONDS', value: 2)
      chain.forward_with_delay(fwd, delay)

      expect(fwd.delay).to eq(delay)
      expect(expectation.http_forward).to eq(fwd)
      expect(mock_client).to have_received(:upsert).with(expectation)
    end
  end

  # -------------------------------------------------------------------
  # error
  # -------------------------------------------------------------------
  describe '#error' do
    it 'sets http_error and upserts' do
      err = MockServer::HttpError.new(drop_connection: true)
      chain.error(err)

      expect(expectation.http_error).to eq(err)
      expect(mock_client).to have_received(:upsert).with(expectation)
    end
  end

  # -------------------------------------------------------------------
  # respond_with_grpc_stream
  # -------------------------------------------------------------------
  describe '#respond_with_grpc_stream' do
    it 'sets grpc_stream_response and upserts' do
      grpc_resp = MockServer::GrpcStreamResponse.new(
        status_name: 'OK',
        messages: [MockServer::GrpcStreamMessage.new(json: '{"id": 1}')]
      )
      chain.respond_with_grpc_stream(grpc_resp)

      expect(expectation.grpc_stream_response).to eq(grpc_resp)
      expect(mock_client).to have_received(:upsert).with(expectation)
    end

    it 'raises TypeError for invalid types' do
      expect { chain.respond_with_grpc_stream('invalid') }.to raise_error(TypeError, /Expected GrpcStreamResponse/)
      expect { chain.respond_with_grpc_stream(123) }.to raise_error(TypeError, /Expected GrpcStreamResponse/)
    end
  end

  # -------------------------------------------------------------------
  # respond_with_grpc_bidi
  # -------------------------------------------------------------------
  describe '#respond_with_grpc_bidi' do
    it 'sets grpc_bidi_response and upserts' do
      bidi_resp = MockServer::GrpcBidiResponse.new(
        status_name: 'OK',
        messages: [MockServer::GrpcStreamMessage.new(json: '{"id": 1}')],
        rules: [MockServer::GrpcBidiRule.new(
          match_json: '{"name": "test"}',
          responses: [MockServer::GrpcStreamMessage.new(json: '{"reply": "ok"}')]
        )]
      )
      chain.respond_with_grpc_bidi(bidi_resp)

      expect(expectation.grpc_bidi_response).to eq(bidi_resp)
      expect(mock_client).to have_received(:upsert).with(expectation)
    end

    it 'raises TypeError for invalid types' do
      expect { chain.respond_with_grpc_bidi('invalid') }.to raise_error(TypeError, /Expected GrpcBidiResponse/)
      expect { chain.respond_with_grpc_bidi(123) }.to raise_error(TypeError, /Expected GrpcBidiResponse/)
    end
  end

  # -------------------------------------------------------------------
  # respond_with_binary
  # -------------------------------------------------------------------
  describe '#respond_with_binary' do
    it 'sets binary_response and upserts' do
      bin_resp = MockServer::BinaryResponse.new(binary_data: 'AQID')
      chain.respond_with_binary(bin_resp)

      expect(expectation.binary_response).to eq(bin_resp)
      expect(mock_client).to have_received(:upsert).with(expectation)
    end

    it 'raises TypeError for invalid types' do
      expect { chain.respond_with_binary('invalid') }.to raise_error(TypeError, /Expected BinaryResponse/)
    end
  end

  # -------------------------------------------------------------------
  # respond_with_dns
  # -------------------------------------------------------------------
  describe '#respond_with_dns' do
    it 'sets dns_response and upserts' do
      dns_resp = MockServer::DnsResponse.new(
        response_code: 'NOERROR',
        answer_records: [MockServer::DnsRecord.a_record('example.com', '1.2.3.4')]
      )
      chain.respond_with_dns(dns_resp)

      expect(expectation.dns_response).to eq(dns_resp)
      expect(mock_client).to have_received(:upsert).with(expectation)
    end

    it 'raises TypeError for invalid types' do
      expect { chain.respond_with_dns('invalid') }.to raise_error(TypeError, /Expected DnsResponse/)
    end
  end

  # -------------------------------------------------------------------
  # forward_with_template
  # -------------------------------------------------------------------
  describe '#forward_with_template' do
    it 'sets http_forward_template and upserts' do
      tpl = MockServer::HttpTemplate.new(template_type: 'VELOCITY', template: '$req.path')
      chain.forward_with_template(tpl)

      expect(expectation.http_forward_template).to eq(tpl)
      expect(mock_client).to have_received(:upsert).with(expectation)
    end

    it 'raises TypeError for invalid types' do
      expect { chain.forward_with_template('invalid') }.to raise_error(TypeError, /Expected HttpTemplate/)
    end
  end

  # -------------------------------------------------------------------
  # forward_with_class_callback
  # -------------------------------------------------------------------
  describe '#forward_with_class_callback' do
    it 'sets http_forward_class_callback and upserts' do
      callback = MockServer::HttpClassCallback.new(callback_class: 'com.example.MyForwardCallback')
      chain.forward_with_class_callback(callback)

      expect(expectation.http_forward_class_callback).to eq(callback)
      expect(mock_client).to have_received(:upsert).with(expectation)
    end

    it 'raises TypeError for invalid types' do
      expect { chain.forward_with_class_callback('invalid') }.to raise_error(TypeError, /Expected HttpClassCallback/)
    end
  end

  # -------------------------------------------------------------------
  # Chaining with_id and with_priority
  # -------------------------------------------------------------------
  describe 'chaining' do
    it 'supports with_id then with_priority then respond' do
      resp = MockServer::HttpResponse.new(status_code: 200)
      chain.with_id('my-id').with_priority(5).respond(resp)

      expect(expectation.id).to eq('my-id')
      expect(expectation.priority).to eq(5)
      expect(expectation.http_response).to eq(resp)
      expect(mock_client).to have_received(:upsert)
    end
  end
end
