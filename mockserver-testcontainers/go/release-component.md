# Release Component: testcontainers-go

## `scripts/release/components/testcontainers-go.sh`

```bash
#!/bin/bash
set -euo pipefail

# Release the Go Testcontainers module by creating and pushing a git tag.
# The Go module proxy indexes the module automatically on first fetch.

RELEASE_VERSION="${RELEASE_VERSION:?RELEASE_VERSION must be set}"
MODULE_TAG="mockserver-testcontainers/go/v${RELEASE_VERSION}"

# Update the DefaultImage constant to the release version
sed -i.bak "s|mockserver/mockserver:mockserver-[0-9.]*|mockserver/mockserver:mockserver-${RELEASE_VERSION}|g" \
  mockserver-testcontainers/go/mockserver.go
rm -f mockserver-testcontainers/go/mockserver.go.bak

# Verify the module builds
(cd mockserver-testcontainers/go && go vet ./... && go test -run 'TestURL|TestDefault' ./...)

# Tag and push
git add mockserver-testcontainers/go/mockserver.go
git commit -m "release(testcontainers-go): bump DefaultImage to ${RELEASE_VERSION}"
git tag "${MODULE_TAG}"
git push origin "${MODULE_TAG}"

# Trigger proxy indexing
GOPROXY=https://proxy.golang.org GO111MODULE=on \
  go list -m "github.com/mock-server/mockserver-monorepo/mockserver-testcontainers/go@v${RELEASE_VERSION}" || true

echo "Published: ${MODULE_TAG}"
```

## Liveness Check (`scripts/release/components/verify.sh` entry)

```bash
# testcontainers-go: verify the module is accessible via the Go proxy
go list -m -json "github.com/mock-server/mockserver-monorepo/mockserver-testcontainers/go@v${RELEASE_VERSION}"
```
