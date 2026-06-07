# MockServer Postman Collection

[`MockServer.postman_collection.json`](MockServer.postman_collection.json) is a Postman v2.1.0
collection for MockServer's REST **control plane** — create expectations, verify requests, inspect
recorded traffic, and manage server state.

## Use it

1. Start MockServer: `docker run -d --rm -p 1080:1080 mockserver/mockserver`
2. In Postman: **Import** → drop in `MockServer.postman_collection.json` (or use the raw GitHub URL).
3. The `baseUrl` collection variable defaults to `http://localhost:1080`; change it to point at any
   MockServer instance.
4. Run the requests top to bottom: create the `/hello` expectation, call it, verify it, inspect
   recorded traffic, then clear/reset.

## What's included

| Folder | Requests |
|--------|----------|
| Expectations | Create expectation, call the mocked endpoint, retrieve active expectations |
| Verify | Verify a request was received, verify a sequence |
| Traffic | Retrieve recorded requests, retrieve logs |
| Manage | Status, clear (by matcher), reset (everything) |

The full control-plane API is documented at <https://www.mock-server.com> and as an OpenAPI spec on
[SwaggerHub](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi).

## Publishing to the Postman Public API Network (maintainer, one-time)

To make the collection discoverable to the millions of developers browsing Postman:

1. Import this collection into a Postman **public workspace** owned by the MockServer team.
2. Keep it in sync on release (the source of truth is this file in the repo).
3. Link the published workspace URL from the website's *Where* section once live.

This step is manual (needs a Postman account); the importable collection above is the source of truth.
