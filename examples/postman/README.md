# MockServer Postman Collection

[`MockServer.postman_collection.json`](MockServer.postman_collection.json) is a Postman v2.1.0
collection for MockServer's REST **control plane**, covering **every endpoint** — create
expectations, verify requests, inspect recorded traffic, inject chaos, drive scenarios, set
breakpoints, and manage server state.

> **Generated — do not edit by hand.** This collection is generated from MockServer's OpenAPI
> spec ([`jekyll-www.mock-server.com/mockserver-openapi.yaml`](../../jekyll-www.mock-server.com/mockserver-openapi.yaml)),
> the single source of truth. To change a request or its example body, edit the spec, then run:
>
> ```bash
> python3 scripts/collections/generate_collections.py
> ```
>
> This regenerates both this collection and the git-native [Bruno collection](../bruno) so they
> never diverge.

## Use it

1. Start MockServer: `docker run -d --rm -p 1080:1080 mockserver/mockserver`
2. **Run in Postman** from the [public workspace](https://www.mock-server.com/where/postman.html),
   or **Import** this `MockServer.postman_collection.json` file.
3. The `baseUrl` collection variable defaults to `http://localhost:1080`; change it to point at any
   MockServer instance.
4. Each request carries a worked example body and documentation; run them top to bottom or pick
   the area you need.

## Authentication

The collection sets collection-level auth so it works against an authenticated MockServer without
per-request setup. By default it uses a **JWT bearer token** — MockServer's control plane supports
JWT auth via `controlPlaneJWTAuthenticationRequired`. Set the `bearerToken` collection variable to
your token. Left blank, the bearer header is empty and requests still work against an
unauthenticated MockServer. If the OpenAPI spec later declares `securitySchemes` (bearer, API key,
or basic), the generator emits that scheme instead.

## Validate the examples

Every example body is tested against a live MockServer:

```bash
python3 scripts/collections/test_collections.py
```

## Publishing

On each release the collection is regenerated from the spec and republished to the public Postman
workspace automatically (see `scripts/release/components/postman-collection.sh`). The full API is
also documented as an OpenAPI spec on
[SwaggerHub](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi).
