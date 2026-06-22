#!/usr/bin/env bash
# Mock an A2A (Agent-to-Agent) agent card.
# A2A clients discover an agent by fetching its card from
# /.well-known/agent.json before sending tasks. This expectation serves a
# static card describing the agent's name, version, capabilities and skills.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "method": "GET",
    "path": "/.well-known/agent.json"
  },
  "httpResponse": {
    "statusCode": 200,
    "headers": {
      "Content-Type": [
        "application/json"
      ]
    },
    "body": {
      "name": "TranslationAgent",
      "description": "Translates text between languages",
      "version": "1.0.0",
      "url": "http://localhost:1080/a2a",
      "capabilities": {
        "streaming": false,
        "pushNotifications": false,
        "stateTransitionHistory": false
      },
      "skills": [
        {
          "id": "translate",
          "name": "Translation",
          "description": "Translates text between languages",
          "tags": [
            "nlp",
            "i18n"
          ],
          "examples": [
            "Translate hello to Spanish"
          ]
        }
      ]
    }
  }
}'
