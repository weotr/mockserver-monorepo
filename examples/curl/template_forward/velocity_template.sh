#!/usr/bin/env bash
# Forward requests using a Velocity template.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "path": "/some/path"
  },
  "httpForwardTemplate": {
    "template": "{'\''path'\'' : \"/somePath\", '\''queryStringParameters'\'' : { '\''userId'\'' : [ \"$!request.queryStringParameters['\''userId'\''][0]\" ]},'\''cookies'\'' : {'\''SessionId'\'' : \"$!request.cookies['\''SessionId'\'']\"},'\''headers'\'' : {'\''Host'\'' : [ \"localhost:1081\" ]}, '\''body'\'': \"{'\''name'\'': '\''value'\''}\"}",
    "templateType": "VELOCITY"
  }
}'
