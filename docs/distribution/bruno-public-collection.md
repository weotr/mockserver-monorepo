# Bruno Public Collection — Go-Live Runbook

## TL;DR

The collection is committed at `examples/bruno/` as plain `.bru` files. Because
[Bruno](https://www.usebruno.com/) is **git-native**, it is already "published" —
users clone the repo and **Open Collection**. Go-live is therefore mostly about
**discoverability**: link it from the website and README, and (optionally) list it
on the Bruno hub. ~10 minutes, **no account and no cost** (unlike Postman, there is
no external workspace to create — the repo *is* the published location).

**Spec-driven + in parity by construction.** The Bruno collection is **generated from the
OpenAPI spec** (`jekyll-www.mock-server.com/mockserver-openapi.yaml`) by the same generator as
Postman (`scripts/collections/generate_collections.py`), so both cover all **68 control-plane
endpoints** and can never diverge. To change anything, edit the spec and regenerate — never edit
the `.bru` files by hand. Validate with `python3 scripts/collections/test_collections.py`.

---

## Pre-flight

| Item | Status |
|------|--------|
| Collection | `examples/bruno/` (`bruno.json` + folders) |
| Collection name | "MockServer Control Plane" |
| Environment | `environments/Local.bru`, `baseUrl` = `http://localhost:1080` |
| Coverage | Expectations, Verify, Traffic (retrieve requests/logs), Manage (status/clear/reset) |
| Source of truth | The `.bru` files in the repo — update them first, on each release |
| Parity | Mirrors `examples/postman/MockServer.postman_collection.json` |

---

## Step-by-step

### 1. Verify the collection

```bash
docker run -d --rm -p 1080:1080 mockserver/mockserver
```

Open Bruno → **Open Collection** → select `examples/bruno`. Choose the **Local**
environment. Run **Expectations → Create expectation** and confirm a `201`. Then
run **Verify → Verify request received** and confirm a `202`.

### 2. Make it discoverable on the website

Add a consumer page mirroring `where/postman.html`:

1. Create `jekyll-www.mock-server.com/where/bruno.html` describing the collection
   and how to open it (clone the repo → Bruno → Open Collection → `examples/bruno`).
2. Link it from the website's *Where* / download section and from the running /
   getting-started pages alongside the Postman link.

### 3. Add a link/badge to the README

In `README.md`, alongside the "Run in Postman" button, add a line such as:

```markdown
**Bruno:** open `examples/bruno` with [Bruno](https://www.usebruno.com/) (Open Collection).
```

(There is no official "Run in Bruno" web button — Bruno opens local/cloned
collections — so a short instruction is the right form.)

### 4. (Optional) List on the Bruno hub

If a public Bruno hub/marketplace listing is desired, submit the collection per
the current usebruno.com guidance. This is optional and not required for users to
use the collection from the repo.

---

## Keeping it in sync

The **OpenAPI spec** (`jekyll-www.mock-server.com/mockserver-openapi.yaml`) is the
source of truth — the `.bru` files are **generated**, not hand-edited. When the
control-plane API changes:

1. Edit the spec, then regenerate:
   `python3 scripts/collections/generate_collections.py` (rewrites both the Bruno
   and Postman collections from the same source, so they can never diverge).
2. Commit the regenerated `.bru` files — that is the publish step (git-native).
   Anyone who pulls gets the update; no re-import, no external workspace to refresh.

The release pipeline's `postman-collection` component regenerates both collections
and **fails its drift guard** if the committed files are out of sync with the spec,
so they stay in lockstep automatically.

---

## After go-live

1. `jekyll-www.mock-server.com/where/bruno.html` — new consumer page (step 2).
2. `README.md` — add the Bruno link (step 3).
3. Confirm the Postman and Bruno links sit together wherever collections are
   referenced.
