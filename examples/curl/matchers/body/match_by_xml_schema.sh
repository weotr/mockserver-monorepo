#!/usr/bin/env bash
# Match requests by body validated against an XML Schema.
# Assumes MOCKSERVER_URL is set (defaults to http://localhost:1080).

curl -X PUT "${MOCKSERVER_URL:-http://localhost:1080}/mockserver/expectation" \
-d '{
  "httpRequest": {
    "body": {
      "type": "XML_SCHEMA",
      "xmlSchema": "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<xs:schema xmlns:xs=\"https://www.w3.org/2001/XMLSchema\" elementFormDefault=\"qualified\" attributeFormDefault=\"unqualified\"><xs:element name=\"notes\"><xs:complexType><xs:sequence><xs:element name=\"note\" maxOccurs=\"unbounded\"><xs:complexType><xs:sequence><xs:element name=\"to\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element><xs:element name=\"from\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element><xs:element name=\"heading\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element><xs:element name=\"body\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element></xs:sequence></xs:complexType></xs:element></xs:sequence></xs:complexType></xs:element>\n</xs:schema>"
    }
  },
  "httpResponse": {
    "body": "some_response_body"
  }
}'
