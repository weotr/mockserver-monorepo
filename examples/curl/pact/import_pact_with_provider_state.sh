#!/usr/bin/env bash
# Import a Pact v3 contract whose interaction is gated by a PROVIDER STATE.
# The interaction declares a provider state ("a user with id 1 exists"), so the
# generated expectation is gated on a scenario named "pact-provider-state" and
# only matches once that provider state has been ACTIVATED.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

MOCKSERVER_URL="${MOCKSERVER_URL:-http://localhost:1080}"

echo "1) Import the Pact contract (one interaction, gated by a provider state):"
curl -X PUT "${MOCKSERVER_URL}/mockserver/pact/import" \
-H "Content-Type: application/json" \
-d '{
  "consumer": {"name": "frontend"},
  "provider": {"name": "users-service"},
  "interactions": [
    {
      "description": "get user 1 when it exists",
      "providerStates": [
        {"name": "a user with id 1 exists", "params": {"id": 1}}
      ],
      "request": {"method": "GET", "path": "/api/users/1"},
      "response": {
        "status": 200,
        "headers": {"content-type": ["application/json"]},
        "body": {"id": 1, "name": "Alice"}
      }
    }
  ],
  "metadata": {"pactSpecification": {"version": "3.0.0"}}
}'

echo
echo "2) Before activation the gated interaction does NOT match (no expectation):"
curl -s -o /dev/null -w "   GET /api/users/1 -> HTTP %{http_code}\n" \
  "${MOCKSERVER_URL}/api/users/1"

echo
echo "3) Activate the provider state (set the pact-provider-state scenario):"
curl -X PUT "${MOCKSERVER_URL}/mockserver/scenario/pact-provider-state" \
-H "Content-Type: application/json" \
-d '{"state": "a user with id 1 exists"}'

echo
echo "4) Now the gated interaction matches and returns its mocked response:"
curl -s -w "\n   GET /api/users/1 -> HTTP %{http_code}\n" \
  "${MOCKSERVER_URL}/api/users/1"
