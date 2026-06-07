#!/usr/bin/env ruby
# frozen_string_literal: true

# Demonstrates retrieving requests that MockServer has recorded.
#
# Prerequisites: MockServer running on localhost:1080
#   docker run -d -p 1080:1080 mockserver/mockserver

require 'mockserver-client'
require 'net/http'
require 'json'

include MockServer

client = Client.new('localhost', 1080)

# ------------------------------------------------------------------
# Set up expectations so requests are accepted
# ------------------------------------------------------------------
client.when(
  HttpRequest.request(path: '/api/users')
).respond(
  HttpResponse.response(body: '{"users": []}')
)

client.when(
  HttpRequest.new(method: 'POST', path: '/api/users')
).respond(
  HttpResponse.response(body: '{"id": 1}', status_code: 201)
)

# ------------------------------------------------------------------
# Send some requests to generate recorded data
# ------------------------------------------------------------------
Net::HTTP.get(URI('http://localhost:1080/api/users'))

uri = URI('http://localhost:1080/api/users')
req = Net::HTTP::Post.new(uri, 'Content-Type' => 'application/json')
req.body = '{"name": "Alice"}'
Net::HTTP.start(uri.host, uri.port) { |http| http.request(req) }

Net::HTTP.get(URI('http://localhost:1080/api/users'))

puts 'Sent 3 requests (2x GET, 1x POST to /api/users)'

# ------------------------------------------------------------------
# 1. Retrieve all recorded requests
# ------------------------------------------------------------------
all_requests = client.retrieve_recorded_requests
puts "\n1. All recorded requests: #{all_requests.length} total"
all_requests.each_with_index do |r, i|
  puts "   [#{i}] #{r.method || 'GET'} #{r.path}"
end

# ------------------------------------------------------------------
# 2. Retrieve recorded requests matching a filter
# ------------------------------------------------------------------
post_requests = client.retrieve_recorded_requests(
  request: HttpRequest.new(method: 'POST', path: '/api/users')
)
puts "\n2. POST /api/users requests: #{post_requests.length}"
post_requests.each_with_index do |r, i|
  puts "   [#{i}] #{r.method} #{r.path}"
end

# ------------------------------------------------------------------
# 3. Retrieve active expectations
# ------------------------------------------------------------------
active = client.retrieve_active_expectations
puts "\n3. Active expectations: #{active.length}"
active.each_with_index do |exp, i|
  req = exp.http_request
  puts "   [#{i}] #{req&.method || '*'} #{req&.path || '*'}"
end

# ------------------------------------------------------------------
# Clean up
# ------------------------------------------------------------------
client.reset
puts "\nAll expectations cleared."
