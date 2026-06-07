#!/usr/bin/env ruby
# frozen_string_literal: true

# Demonstrates verifying that MockServer received specific requests.
#
# Prerequisites: MockServer running on localhost:1080
#   docker run -d -p 1080:1080 mockserver/mockserver

require 'mockserver-client'
require 'net/http'

include MockServer

client = Client.new('localhost', 1080)

# ------------------------------------------------------------------
# Set up an expectation so requests are accepted
# ------------------------------------------------------------------
client.when(
  HttpRequest.request(path: '/some/path')
).respond(
  HttpResponse.response(body: 'ok')
)

client.when(
  HttpRequest.request(path: '/other/path')
).respond(
  HttpResponse.response(body: 'ok')
)

client.when(
  HttpRequest.request(path: '/third/path')
).respond(
  HttpResponse.response(body: 'ok')
)

# ------------------------------------------------------------------
# Send some real requests so we have something to verify
# ------------------------------------------------------------------
Net::HTTP.get(URI('http://localhost:1080/some/path'))
Net::HTTP.get(URI('http://localhost:1080/some/path'))
Net::HTTP.get(URI('http://localhost:1080/other/path'))
Net::HTTP.get(URI('http://localhost:1080/third/path'))
puts 'Sent 4 requests (2x /some/path, 1x /other/path, 1x /third/path)'

# ------------------------------------------------------------------
# 1. Verify a request was received at least once
# ------------------------------------------------------------------
client.verify(
  HttpRequest.request(path: '/some/path'),
  times: VerificationTimes.at_least(1)
)
puts '1. Verified: /some/path received at least once'

# ------------------------------------------------------------------
# 2. Verify a request was received exactly twice
# ------------------------------------------------------------------
client.verify(
  HttpRequest.request(path: '/some/path'),
  times: VerificationTimes.exactly(2)
)
puts '2. Verified: /some/path received exactly 2 times'

# ------------------------------------------------------------------
# 3. Verify a request was received at most once
# ------------------------------------------------------------------
client.verify(
  HttpRequest.request(path: '/other/path'),
  times: VerificationTimes.at_most(1)
)
puts '3. Verified: /other/path received at most once'

# ------------------------------------------------------------------
# 4. Verify a request was received between 1 and 3 times
# ------------------------------------------------------------------
client.verify(
  HttpRequest.request(path: '/some/path'),
  times: VerificationTimes.between(1, 3)
)
puts '4. Verified: /some/path received between 1 and 3 times'

# ------------------------------------------------------------------
# 5. Verify requests were received in a specific sequence
# ------------------------------------------------------------------
client.verify_sequence(
  HttpRequest.request(path: '/some/path'),
  HttpRequest.request(path: '/other/path'),
  HttpRequest.request(path: '/third/path')
)
puts '5. Verified sequence: /some/path -> /other/path -> /third/path'

# ------------------------------------------------------------------
# 6. Verify zero interactions for an unvisited path
# ------------------------------------------------------------------
client.verify(
  HttpRequest.request(path: '/never/called'),
  times: VerificationTimes.exactly(0)
)
puts '6. Verified: /never/called received exactly 0 times'

# ------------------------------------------------------------------
# 7. Demonstrate a failed verification
# ------------------------------------------------------------------
begin
  client.verify(
    HttpRequest.request(path: '/some/path'),
    times: VerificationTimes.exactly(99)
  )
rescue MockServer::VerificationError => e
  puts "7. Expected verification failure: #{e.message[0..60]}..."
end

# ------------------------------------------------------------------
# Clean up
# ------------------------------------------------------------------
client.reset
puts "\nAll expectations cleared."
