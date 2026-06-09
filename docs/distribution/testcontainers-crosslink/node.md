# Node — Testcontainers Cross-Link Draft

## Publish Gate

**Do not post until `@mockserver/testcontainers` is live on npm.**

Verify:
```bash
npm info @mockserver/testcontainers version
```

---

## Target

**Repository:** https://github.com/testcontainers/testcontainers-node

File to update (or PR against): `packages/testcontainers/src/modules/` or the modules
documentation page at https://node.testcontainers.org/modules/.

The upstream Node library has a `MockServer` stub in its `@testcontainers/mockserver`
scoped package (if one exists). If no upstream module exists, the PR is a **new docs
entry** pointing at the official module. If a stub module exists and is unmaintained, the
PR deprecates it in favour of `@mockserver/testcontainers`.

**Fallback:** open a GitHub issue under
https://github.com/testcontainers/testcontainers-node/issues with the title below if the
maintainers prefer issue-driven additions over direct PRs.

---

## PR Title

```
docs: add official MockServer module — @mockserver/testcontainers
```

---

## PR / Issue Body

```markdown
## Summary

MockServer now ships an officially maintained Testcontainers module for Node/TypeScript:
[`@mockserver/testcontainers`](https://www.npmjs.com/package/@mockserver/testcontainers).

The module is maintained by the MockServer project (not a third-party community port) and
tracks each MockServer release.

## Install

```bash
npm install --save-dev @mockserver/testcontainers
```

## Quick start

```typescript
import { MockServerContainer } from "@mockserver/testcontainers";

const container = await MockServerContainer.start();
const url = container.getUrl(); // e.g. http://localhost:32789

await fetch(`${url}/mockserver/expectation`, {
  method: "PUT",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    httpRequest: { method: "GET", path: "/hello" },
    httpResponse: { statusCode: 200, body: "world" },
  }),
});

const response = await fetch(`${url}/hello`);
console.log(await response.text()); // "world"

await container.stop();
```

## Links

- npm: https://www.npmjs.com/package/@mockserver/testcontainers
- Source: https://github.com/mock-server/mockserver/tree/master/mockserver-testcontainers/node
- MockServer docs: https://www.mock-server.com
```

---

## Notes for the Submitter

- The package name is `@mockserver/testcontainers` (scoped under the `@mockserver` org).
- Requires Node >= 18 and `testcontainers` >= 12.
- If testcontainers-node already ships `@testcontainers/mockserver`, open the PR against
  that package's README or deprecation notice to redirect to `@mockserver/testcontainers`.
- File source: `mockserver-testcontainers/node/package.json` (name, version, deps verified).
