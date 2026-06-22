# frozen_string_literal: true

require 'mockserver/rspec'

RSpec.describe 'MockServer::RSpec shared context' do
  it 'defines a named shared context' do
    expect(MockServer::RSpec::SHARED_CONTEXT_NAME).to eq('mockserver client')
  end

  describe 'using the :mockserver tag', :mockserver do
    let(:mockserver_host) { 'localhost' }
    let(:mockserver_port) { 1080 }

    # prepend_before so the stub is registered before the shared context's own
    # before hook (which calls reset) fires.
    prepend_before do
      stub_request(:put, 'http://localhost:1080/mockserver/reset')
        .to_return(status: 200, body: '')
    end

    it 'exposes a memoised mockserver client' do
      expect(mockserver).to be_a(MockServer::Client)
      expect(mockserver).to equal(mockserver)
    end

    it 'resets the server before the example runs' do
      expect(a_request(:put, 'http://localhost:1080/mockserver/reset'))
        .to have_been_made.at_least_once
    end
  end
end
