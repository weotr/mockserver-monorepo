#!/usr/bin/env ruby
# frozen_string_literal: true

# Demonstrates request matching features in MockServer.
#
# Prerequisites: MockServer running on localhost:1080
#   docker run -d -p 1080:1080 mockserver/mockserver

require 'mockserver-client'
require 'json'

include MockServer

client = Client.new('localhost', 1080)

# ------------------------------------------------------------------
# 1. Match by exact path
# ------------------------------------------------------------------
client.when(
  HttpRequest.request(path: '/some/path')
).respond(
  HttpResponse.response(body: 'matched by exact path')
)
puts '1. Match by exact path: /some/path'

# ------------------------------------------------------------------
# 2. Match by path with regex
# ------------------------------------------------------------------
client.when(
  HttpRequest.request(path: '/some.*')
).respond(
  HttpResponse.response(body: 'matched by regex path')
)
puts '2. Match by regex path: /some.*'

# ------------------------------------------------------------------
# 3. Match by method
# ------------------------------------------------------------------
client.when(
  HttpRequest.new(method: 'POST', path: '/submit')
).respond(
  HttpResponse.response(body: 'matched POST')
)
puts '3. Match by method: POST /submit'

# ------------------------------------------------------------------
# 4. Match by query string parameters
# ------------------------------------------------------------------
client.when(
  HttpRequest.new(
    method: 'GET',
    path: '/search',
    query_string_parameters: [
      KeyToMultiValue.new(name: 'q', values: ['mockserver']),
      KeyToMultiValue.new(name: 'page', values: ['1'])
    ]
  )
).respond(
  HttpResponse.response(body: 'matched by query params')
)
puts '4. Match by query params: GET /search?q=mockserver&page=1'

# ------------------------------------------------------------------
# 5. Match by headers
# ------------------------------------------------------------------
client.when(
  HttpRequest.new(
    method: 'GET',
    path: '/api/data',
    headers: [
      KeyToMultiValue.new(name: 'Accept', values: ['application/json']),
      KeyToMultiValue.new(name: 'Accept-Encoding', values: ['gzip, deflate, br'])
    ]
  )
).respond(
  HttpResponse.response(body: '{"data": "value"}')
)
puts '5. Match by headers: GET /api/data with Accept and Accept-Encoding'

# ------------------------------------------------------------------
# 6. Match by cookies
# ------------------------------------------------------------------
client.when(
  HttpRequest.new(
    method: 'GET',
    path: '/view/cart',
    query_string_parameters: [
      KeyToMultiValue.new(name: 'cartId', values: ['055CA455-1DF7-45BB-8535-4F83E7266092'])
    ],
    cookies: [
      KeyToMultiValue.new(name: 'session', values: ['4930456C-C718-476F-971F-CB8E047AB349'])
    ]
  )
).respond(
  HttpResponse.response(body: 'matched by cookies and query params')
)
puts '6. Match by cookies and query params: GET /view/cart'

# ------------------------------------------------------------------
# 7. Match by JSON body
# ------------------------------------------------------------------
client.when(
  HttpRequest.new(
    method: 'POST',
    path: '/api/items',
    body: Body.json('{"id": 1, "name": "widget"}')
  )
).respond(
  HttpResponse.response(body: 'matched JSON body', status_code: 202)
)
puts '7. Match by JSON body: POST /api/items'

# ------------------------------------------------------------------
# 8. Match by regex body
# ------------------------------------------------------------------
client.when(
  HttpRequest.new(
    path: '/log',
    body: Body.regex('starts_with_.*')
  )
).respond(
  HttpResponse.response(body: 'matched regex body')
)
puts '8. Match by regex body: /log with body matching starts_with_.*'

# ------------------------------------------------------------------
# 9. Match with limited times and TTL
# ------------------------------------------------------------------
client.when(
  HttpRequest.request(path: '/ephemeral'),
  times: Times.exactly(2),
  time_to_live: TimeToLive.exactly(60, 'SECONDS')
).respond(
  HttpResponse.response(body: 'ephemeral response')
)
puts '9. Match with limits: /ephemeral (max 2 times within 60s)'

# ------------------------------------------------------------------
# 10. Match using fluent builder
# ------------------------------------------------------------------
client.when(
  HttpRequest.new
    .with_method('PUT')
    .with_path('/api/resource')
    .with_header('Authorization', 'Bearer token123')
    .with_query_param('version', '2')
).respond(
  HttpResponse.response(body: 'matched fluent builder')
)
puts '10. Match with fluent builder: PUT /api/resource with auth header'

# ------------------------------------------------------------------
# Clean up
# ------------------------------------------------------------------
client.reset
puts "\nAll expectations cleared."
