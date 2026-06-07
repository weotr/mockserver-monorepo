#!/usr/bin/env ruby
# frozen_string_literal: true

# Demonstrates using MockServer as a reverse proxy.
#
# This sets up MockServer to:
#   1. Forward REST API requests to a local backend (e.g. localhost:8080)
#   2. Forward all other requests to a remote environment
#
# Prerequisites: MockServer running on localhost:1080
#   docker run -d -p 1080:1080 mockserver/mockserver

require 'mockserver-client'

include MockServer

client = Client.new('localhost', 1080)

# ------------------------------------------------------------------
# 1. Forward REST API requests to a local backend
# ------------------------------------------------------------------
client.when(
  HttpRequest.request(path: '/rest.*'),
  times: Times.unlimited
).forward(
  HttpForward.new(host: '127.0.0.1', port: 8080, scheme: 'HTTP')
)
puts '1. Forwarding /rest.* -> 127.0.0.1:8080 (HTTP)'

# ------------------------------------------------------------------
# 2. Forward all other requests to a remote environment
# ------------------------------------------------------------------
client.when(
  HttpRequest.request(path: '/.*'),
  times: Times.unlimited
).forward(
  HttpForward.new(host: '192.168.50.10', port: 443, scheme: 'HTTPS')
)
puts '2. Forwarding /.* -> 192.168.50.10:443 (HTTPS)'

puts "\nReverse proxy configured."
puts 'REST API requests go to the local backend; everything else goes to the remote environment.'
puts 'Press Ctrl-C to stop.'

# ------------------------------------------------------------------
# Wait for Ctrl-C
# ------------------------------------------------------------------
begin
  sleep
rescue Interrupt
  puts "\nShutting down..."
  client.reset
  puts 'All expectations cleared.'
end
