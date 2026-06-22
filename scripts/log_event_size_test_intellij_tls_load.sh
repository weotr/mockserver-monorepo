#!/usr/bin/env bash

# Derive the repository root from this script's location so the mTLS test
# certificate paths are portable across machines.
REPO_ROOT="$(git -C "$(cd "$(dirname "$0")" && pwd)" rev-parse --show-toplevel)"
CERTS="${REPO_ROOT}/mockserver/mockserver-core/src/test/resources/org/mockserver/authentication/mtls"

for counter in $(seq 1 1 5000); do
  echo "count: ${counter}"
  # valid client key and cert combination
  curl -s --key ${CERTS}/leaf-key.pem --cert ${CERTS}/leaf-cert.pem --cacert ${CERTS}/ca.pem -X PUT 'https://localhost:1080/some/path' >/dev/null &
  # invalid client key and cert combination
  curl -s --key ${CERTS}/separateca/leaf-key.pem --cert ${CERTS}/leaf-cert.pem --cacert ${CERTS}/ca.pem -X PUT 'https://localhost:1080/some/path' >/dev/null &
  # untrusted client cert
  curl -s --key ${CERTS}/separateca/leaf-key.pem --cert ${CERTS}/separateca/leaf-cert.pem --cacert ${CERTS}/ca.pem -X PUT 'https://localhost:1080/some/path' >/dev/null &
  # untrusted server cert
  curl -s --key ${CERTS}/leaf-key.pem --cert ${CERTS}/leaf-cert.pem --cacert ${CERTS}/separateca/ca.pem -X PUT 'https://localhost:1080/some/path' >/dev/null &
  # no client cert
  curl -s --cacert ${CERTS}/ca.pem -X PUT 'https://localhost:1080/some/path' >/dev/null
done
