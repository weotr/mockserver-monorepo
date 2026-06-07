#!/usr/bin/env bash
# Forward requests using a JavaScript template.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path"
  },
  "httpForwardTemplate": {
    "template": "return {'\''path'\'' : \"/somePath\", '\''queryStringParameters'\'' : {'\''userId'\'' : request.queryStringParameters && request.queryStringParameters['\''userId'\'']},'\''headers'\'' : {'\''Host'\'' : [ \"localhost:1081\" ]}, '\''body'\'': {'\''name'\'': '\''value'\''}};",
    "templateType": "JAVASCRIPT"
  }
}'
