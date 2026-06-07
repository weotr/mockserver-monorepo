#!/usr/bin/env bash
# Match requests by body validated against a JSON Schema.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "body": {
      "type": "JSON_SCHEMA",
      "jsonSchema": {
        "$schema": "https://json-schema.org/draft-04/schema#",
        "title": "Product",
        "description": "A product from Acme'\''s catalog",
        "type": "object",
        "properties": {
          "id": {
            "description": "The unique identifier for a product",
            "type": "integer"
          },
          "name": {
            "description": "Name of the product",
            "type": "string"
          },
          "price": {
            "type": "number",
            "minimum": 0,
            "exclusiveMinimum": true
          },
          "tags": {
            "type": "array",
            "items": {
              "type": "string"
            },
            "minItems": 1,
            "uniqueItems": true
          }
        },
        "required": [
          "id",
          "name",
          "price"
        ]
      }
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
