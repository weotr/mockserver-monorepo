# MockServer Bruno Collection

This directory is a [Bruno](https://www.usebruno.com/) collection for MockServer's REST
**control plane**, covering **every endpoint** — create expectations, verify requests, inspect
recorded traffic, inject chaos, drive scenarios, set breakpoints, and manage server state. Bruno is
open-source and **git-native**: the collection lives here as plain `.bru` files, so it versions
alongside the code.

> **Generated — do not edit by hand.** This collection is generated from MockServer's OpenAPI
> spec ([`jekyll-www.mock-server.com/mockserver-openapi.yaml`](../../jekyll-www.mock-server.com/mockserver-openapi.yaml)),
> the single source of truth. To change a request or its example body, edit the spec, then run:
>
> ```bash
> python3 scripts/collections/generate_collections.py
> ```
>
> This regenerates both this collection and the [Postman collection](../postman) so they never
> diverge.

## Use it

1. Start MockServer: `docker run -d --rm -p 1080:1080 mockserver/mockserver`
2. In Bruno: **Open Collection** → select this `examples/bruno` folder.
3. Pick the **Local** environment (top-right); its `baseUrl` defaults to `http://localhost:1080`.
   Change it to point at any MockServer instance.
4. Each request carries a worked example body and documentation; run them top to bottom or pick the
   area you need.

## Authentication

The collection ships with collection-level auth so it works against an authenticated MockServer
without per-request setup. By default it uses a **JWT bearer token** — MockServer's control plane
supports JWT auth via `controlPlaneJWTAuthenticationRequired`, and every request inherits the
collection auth. Set the `bearerToken` variable in the **Local** environment to your token. Left
blank, the bearer header is empty and requests still work against an unauthenticated MockServer. If
the OpenAPI spec later declares `securitySchemes` (bearer, API key, or basic), the generator emits
that scheme instead.

## Validate the examples

Every example body is tested against a live MockServer:

```bash
python3 scripts/collections/test_collections.py
```

Because Bruno is git-native, committing the regenerated `.bru` files **is** the publish step — anyone
who pulls the repo gets the update. The full API is also documented as an OpenAPI spec on
[SwaggerHub](https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi).
