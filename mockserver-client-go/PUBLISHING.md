# Publishing the Go Client

## Registry

The Go client is published to [pkg.go.dev](https://pkg.go.dev) via the Go module proxy.
pkg.go.dev indexes a module automatically the first time anyone fetches it after a git tag is pushed.

## Module Path

Current (monorepo): `github.com/mock-server/mockserver-monorepo/mockserver-client-go`

Eventual split target: `github.com/mock-server/mockserver-client-go`

## Publish Command (non-interactive)

Go modules are published by pushing a git tag. For a subdirectory module in a monorepo, the tag
format is `<subdir>/v<VERSION>`.

```bash
# Example for version 7.0.1:
VERSION="7.0.1"
git tag "mockserver-client-go/v${VERSION}"
git push origin "mockserver-client-go/v${VERSION}"
```

After the tag is pushed, pkg.go.dev will index the module within minutes. You can trigger
immediate indexing by fetching the module:

```bash
GOPROXY=https://proxy.golang.org GO111MODULE=on \
  go get "github.com/mock-server/mockserver-monorepo/mockserver-client-go@v${VERSION}"
```

## Secret

None required. The Go module proxy indexes public repositories automatically.

## Verification

Check that the module page exists:

```bash
curl -sf "https://pkg.go.dev/github.com/mock-server/mockserver-monorepo/mockserver-client-go@v${VERSION}" \
  | grep -q "mockserver-client-go" && echo "OK" || echo "NOT FOUND"
```

## Pre-publish Checklist

1. All tests pass: `cd mockserver-client-go && go test ./... && go vet ./...`
2. `go.mod` has the correct module path
3. No `replace` directives in `go.mod`
4. Version in tag matches the MockServer release version
