#!/usr/bin/env bash

# Derive the repository root from this script's location so the mTLS test
# certificate paths are portable across machines.
REPO_ROOT="$(git -C "$(cd "$(dirname "$0")" && pwd)" rev-parse --show-toplevel)"
CERTS="${REPO_ROOT}/mockserver/mockserver-core/src/test/resources/org/mockserver/authentication/mtls"

# MOCKSERVER_CERTIFICATE_AUTHORITY_PRIVATE_KEY=${CERTS}/ca-key.pem;MOCKSERVER_MAX_EXPECTATIONS=200;MOCKSERVER_CERTIFICATE_AUTHORITY_X509_CERTIFICATE=${CERTS}/ca.pem;MOCKSERVER_MAX_LOG_ENTRIES=1000;MOCKSERVER_SSL_CERTIFICATE_DOMAIN_NAME=isc-test-endpoint-westeurope-1.westeurope.azurecontainer.io;MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_REQUIRED=true

curl -v -s --key ${CERTS}/leaf-key.pem --cert ${CERTS}/leaf-cert.pem --cacert ${CERTS}/ca.pem -X PUT "https://localhost:1080/mockserver/reset"

MEM=$(jstat -gc "$(ps aux | grep java | grep 1080 | awk '{ print $2 }')" | awk '{ if (NR > 1) print $3","$4","$6","$8","$10 }')
printf "counter,logs,requests,expectations,S0U,S1U,EU,OU,MU\n"
printf "counter,logs,requests,expectations,S0U,S1U,EU,OU,MU\n" > memory_with_expectation.csv
for counter in $(seq 1 1 5000); do
  curl -s --key ${CERTS}/leaf-key.pem --cert ${CERTS}/leaf-cert.pem --cacert ${CERTS}/ca.pem -X PUT 'https://localhost:1080/mockserver/expectation' -d "{
                        'httpRequest' : {
                        },
                        'httpResponse' : {
                          'body': 'some body'
                        }
                      }" >/dev/null
  curl -s --key ${CERTS}/leaf-key.pem --cert ${CERTS}/leaf-cert.pem --cacert ${CERTS}/ca.pem -X PUT 'https://localhost:1080/some/path' >/dev/null
  # invalid client key and cert combination
  curl -s --key ${CERTS}/separateca/leaf-key.pem --cert ${CERTS}/leaf-cert.pem --cacert ${CERTS}/ca.pem -X PUT 'https://localhost:1080/some/path' >/dev/null
  # untrusted client cert
  curl -s --key ${CERTS}/separateca/leaf-key.pem --cert ${CERTS}/separateca/leaf-cert.pem --cacert ${CERTS}/ca.pem -X PUT 'https://localhost:1080/some/path' >/dev/null
  # untrusted server cert
  curl -s --key ${CERTS}/leaf-key.pem --cert ${CERTS}/leaf-cert.pem --cacert ${CERTS}/separateca/ca.pem -X PUT 'https://localhost:1080/some/path' >/dev/null
  # no client cert
  curl -s --cacert ${CERTS}/ca.pem -X PUT 'https://localhost:1080/some/path' >/dev/null

  COUNT_REQUESTS=$(curl -s --key ${CERTS}/leaf-key.pem --cert ${CERTS}/leaf-cert.pem --cacert ${CERTS}/ca.pem -X PUT "https://localhost:1080/mockserver/retrieve?type=REQUESTS" | grep "method" | wc -l)
  COUNT_LOGS=$(curl -s --key ${CERTS}/leaf-key.pem --cert ${CERTS}/leaf-cert.pem --cacert ${CERTS}/ca.pem -X PUT "https://localhost:1080/mockserver/retrieve?type=logs" | grep 2022 | wc -l)
  EXPECTATIONS=$(curl -s --key ${CERTS}/leaf-key.pem --cert ${CERTS}/leaf-cert.pem --cacert ${CERTS}/ca.pem -X PUT "https://localhost:1080/mockserver/retrieve?type=active_expectations" | grep "times" | wc -l)
  if [[ "$counter" == *00 ]]; then
    MEM=$(jstat -gc "$(ps aux | grep java | grep 1080 | awk '{ print $2 }')" | awk '{ if (NR > 1) print $3","$4","$6","$8","$10 }')
  fi
  printf "%d,%d,%d,%d,%s\n" "$counter" "$COUNT_LOGS" "$COUNT_REQUESTS" "$EXPECTATIONS" "$MEM"
  printf "%d,%d,%d,%d,%s\n" "$counter" "$COUNT_LOGS" "$COUNT_REQUESTS" "$EXPECTATIONS" "$MEM" >> memory_with_expectation.csv
done
