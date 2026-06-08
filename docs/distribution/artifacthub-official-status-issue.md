# Artifact Hub — Official Status Request Issue

## ✅ STATUS: SUBMITTED 2026-06-08 → https://github.com/artifacthub/hub/issues/4818
Awaiting an Artifact Hub maintainer to review and grant the `official` badge (it's granted by an AH
maintainer, not by a repo deploy/annotation). MockServer qualifies cleanly: the publisher (James D
Bloom / the `mock-server` org) owns the MockServer software the chart installs — the publisher-owns-
the-software case the status is for. Field values submitted: repository name `mockserver`, official
packages = empty (the repo holds only the official `mockserver` chart), project https://www.mock-server.com,
not a CNCF project, source https://github.com/mock-server/mockserver-monorepo. No further action
needed unless the maintainer asks a follow-up on the issue.

## TL;DR

File an issue on `artifacthub/hub` using their official-status template once the **Verified
Publisher** badge is visible on artifacthub.io for the MockServer chart. The Verified Publisher
badge is granted automatically when Artifact Hub re-processes the `artifacthub-repo.yml` ownership
metadata already published to `oci://ghcr.io/mock-server/charts/mockserver`. A new chart deploy
triggers that scan; it does not require a separate action.

---

## Prerequisites (confirm before filing)

| Check | Where to verify |
|-------|----------------|
| Chart appears on Artifact Hub | `https://artifacthub.io/packages/search?repo=mockserver` |
| **Verified Publisher** badge is shown on the listing | Same URL — look for the green "Verified Publisher" label next to the publisher name |
| Repository ID in `helm/artifacthub-repo.yml` matches the AH control panel | `repositoryID: a6ca1874-16c1-43c8-9924-9bf9c3a5a9ea` |
| You are logged in to artifacthub.io as the registered owner | Control Panel → Repositories → the `mockserver` entry should show as yours |

---

## Where to file

Open an issue at: **`https://github.com/artifacthub/hub/issues/new`**

Select the **"Official status request"** template if one is available; otherwise open a blank
issue with the title and body below.

---

## Ready-to-post issue body

**Title:**

```
Request Official status: MockServer (oci://ghcr.io/mock-server/charts/mockserver)
```

**Body:**

```
## Official Status Request

Requesting **Official** status for the MockServer Helm chart on Artifact Hub.

### Repository details

| Field | Value |
|-------|-------|
| Repository URL | `oci://ghcr.io/mock-server/charts/mockserver` |
| Repository ID | `a6ca1874-16c1-43c8-9924-9bf9c3a5a9ea` |
| Verified Publisher | Yes (ownership metadata published via `artifacthub-repo.yml`) |
| Project homepage | https://www.mock-server.com |
| Source repository | https://github.com/mock-server/mockserver-monorepo |
| License | Apache-2.0 |

### Why this qualifies for Official status

The Artifact Hub [official status criteria](https://artifacthub.io/docs/topics/official/) state
that Official status is granted when the publisher is the official project maintainer or a
vendor publishing their own software.

- I am the sole maintainer of MockServer and the author of the Helm chart. This is the
  canonical, project-maintained chart — there is no community chart or third-party fork.
- The chart ships a `README.md`, a `values.schema.json`, and is cosign-signed.
- The `maintainers` field in `Chart.yaml` references the same email address (`jamesdbloom+mockserver@gmail.com`)
  as the registered Artifact Hub publisher.

### Versions published

The chart has been published to `oci://ghcr.io/mock-server/charts/mockserver` for every
MockServer release since 5.3.0, automated via the release pipeline. The current version is 7.0.0.

Thank you for considering this request.
```

---

## After the issue is filed

Artifact Hub maintainers grant Official status manually and will respond on the issue. There is no
automated approval. Typical turnaround is days to weeks. No further action is required on your
part until they respond — they will either grant the badge directly or ask for additional
information.

Once granted, the orange "Official" badge appears automatically on the chart listing at
`https://artifacthub.io/packages/helm/mockserver/mockserver`.
