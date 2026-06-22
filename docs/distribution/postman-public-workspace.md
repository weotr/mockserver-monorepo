# Postman Public Workspace — Go-Live Runbook

## TL;DR

**Status: LIVE + automated.** The collection is published to the public workspace
`official-mockserver` (workspace `1739eeee-…`, collection `3256712-63a2d67a-…`) and now covers
**all 68 control-plane endpoints**. It is **generated from the OpenAPI spec**
(`jekyll-www.mock-server.com/mockserver-openapi.yaml`) by
`scripts/collections/generate_collections.py`, and the release pipeline's `postman-collection`
component re-generates and **republishes it via the Postman API on every release** (key in Secrets
Manager at `mockserver-build/postman-api-key`). The steps below are the original one-time go-live
runbook, kept for reference / disaster recovery.

The collection is committed at `examples/postman/MockServer.postman_collection.json`.
Publishing it to the Postman Public API Network takes about 10 minutes and needs a Postman account.
Once live, link the workspace URL from the website and README.

**Cost: free.** Publishing a public workspace / public collection to the Postman API Network is
available on every tier, including the **Free** plan — no paid subscription is required. The
March 2026 pricing change restricts *multi-user team collaboration* (the Free plan is now
single-user), **not** public publishing; a single-user Free account can publish this collection.
The only practical blocker has been network access — `postman.com` is blocked on the company
laptop, so publish from another machine.

---

## Pre-flight

| Item | Status |
|------|--------|
| Collection file | `examples/postman/MockServer.postman_collection.json` — valid Postman v2.1.0 JSON |
| Collection name | "MockServer Control Plane" |
| `baseUrl` variable | pre-set to `http://localhost:1080` |
| Coverage | Expectations, Verify, Traffic (retrieve requests/logs), Manage (status/clear/reset) |
| Source of truth | The JSON file in the repo — update it there first, then re-import to Postman on each release |

---

## Step-by-step: publish the collection

### 1. Create a public workspace

1. Log in to [postman.com](https://www.postman.com) with the MockServer maintainer account.
2. Click **Workspaces → Create Workspace**.
3. Name: `MockServer`
4. Summary: `Official Postman collection for MockServer's REST control plane — create expectations, verify requests, inspect traffic, and manage server state.`
5. Visibility: **Public**
6. Click **Create Workspace**.

### 2. Import the collection

1. Inside the new workspace, click **Import**.
2. Choose **File** and upload `examples/postman/MockServer.postman_collection.json`.
3. Postman imports the collection with all folders, requests, and the `baseUrl` variable intact.

### 3. Verify the collection

Run through the requests manually against a local MockServer instance to confirm they work:

```bash
docker run -d --rm -p 1080:1080 mockserver/mockserver
```

Open Postman, select the `MockServer Control Plane` collection, set `baseUrl` = `http://localhost:1080`,
and run the **Expectations → Create expectation** request. Confirm a `201` response. Then run
**Verify → Verify request received** and confirm a `202`.

### 4. Set the collection description

Click the collection root → **Edit** → paste this description:

```
Drive MockServer's REST control plane: create expectations, verify requests, inspect recorded
traffic, and manage server state.

**Quick start:**
1. Start MockServer: `docker run -d --rm -p 1080:1080 mockserver/mockserver`
2. Set the `baseUrl` collection variable to your MockServer instance (default: http://localhost:1080).
3. Run requests top to bottom: create a /hello expectation, call it, verify it, inspect traffic,
   then clear/reset.

Full API documentation: https://www.mock-server.com
OpenAPI spec: https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi
Source repository: https://github.com/mock-server/mockserver-monorepo
```

### 5. Get the public link

1. Click **Share** on the collection.
2. Copy the **Public link** (format: `https://www.postman.com/mock-server-<id>/mockserver/collection/<id>`).
3. Record this URL — it goes into the README and website page.

### 6. Add the Postman Run button to the README

After publishing, add this badge to `README.md` in the **Quick Start** section (replace
`<collection-id>` and `<workspace-id>` with the real IDs from the public link):

```markdown
[![Run in Postman](https://run.pstmn.io/button.svg)](https://app.getpostman.com/run-collection/<collection-id>)
```

---

## Keeping it in sync

The **OpenAPI spec** (`jekyll-www.mock-server.com/mockserver-openapi.yaml`) is the source of truth —
**not** the collection JSON, which is generated. When the control-plane API changes:

1. Edit the spec (add/adjust the endpoint and its `requestBody` example).
2. Regenerate: `python3 scripts/collections/generate_collections.py` (rewrites both the Postman
   JSON and the Bruno collection) and commit the result.
3. Validate: `python3 scripts/collections/test_collections.py`.

The release pipeline's `postman-collection` component then **republishes automatically** via the
Postman API on each release (the workspace and collection IDs stay stable, so no URL changes). If you
ever need to publish by hand, `PUT https://api.getpostman.com/collections/<uid>` with
`{"collection": <json>}` and an `X-Api-Key` header does the same thing.

---

## After go-live: update the website

Once the workspace is public, update:
1. `jekyll-www.mock-server.com/where/postman.html` — the new consumer doc page (see below).
2. `README.md` — add the Run in Postman button and workspace link.
3. `jekyll-www.mock-server.com/mock_server/running_mock_server.html` (or the relevant include) —
   add a line referencing the Postman collection under the "Using MockServer" section.
