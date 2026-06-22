#!/usr/bin/env ruby
# frozen_string_literal: true

# Demonstrates creating expectations from OpenAPI specifications.
#
# Prerequisites: MockServer running on localhost:1080
#   docker run -d -p 1080:1080 mockserver/mockserver

require 'mockserver-client'

include MockServer

PETSTORE_SPEC_URL = 'https://raw.githubusercontent.com/mock-server/mockserver-monorepo/' \
                    'master/mockserver/mockserver-integration-testing/src/main/resources/' \
                    'org/mockserver/openapi/openapi_petstore_example.json'

client = Client.new('localhost', 1080)

# ------------------------------------------------------------------
# 1. Create expectations from an OpenAPI spec URL (all operations)
# ------------------------------------------------------------------
client.open_api_expectation(
  OpenAPIExpectation.new(spec_url_or_payload: PETSTORE_SPEC_URL)
)
puts '1. Created expectations from Petstore OpenAPI spec (all operations)'

client.clear

# ------------------------------------------------------------------
# 2. Create expectations with specific operations and response codes
# ------------------------------------------------------------------
client.open_api_expectation(
  OpenAPIExpectation.new(
    spec_url_or_payload: PETSTORE_SPEC_URL,
    operations_and_responses: {
      'showPetById' => '200',
      'createPets'  => '500'
    }
  )
)
puts '2. Created expectations: showPetById->200, createPets->500'

# ------------------------------------------------------------------
# Clean up
# ------------------------------------------------------------------
client.reset
puts "\nAll expectations cleared."
