# CNCF Landscape Entry — MockServer

## TL;DR

MockServer is cross-listed in the CNCF Landscape under **two** subcategories: **App Definition and
Development → Continuous Integration & Delivery** (holds testing tools such as k6, Keploy, Fortio)
and **Observability and Analysis → Chaos Engineering** (it has fault/chaos injection). The entry
adds an identical YAML block in each subcategory of `landscape.yml`, plus a monochrome SVG to
`hosted_logos/`, and references the live Crunchbase organisation
[`mockserver`](https://www.crunchbase.com/organization/mockserver).

**Current status (2026-06):** PR submitted —
[`cncf/landscape#4868`](https://github.com/cncf/landscape/pull/4868) (OPEN, not draft; DCO green).
Raised from fork `jamesdbloom/landscape`, branch `add-mockserver` (3 commits, tip `99443a20`,
DCO-signed). Awaiting CNCF maintainer review/merge.

---

## What was submitted

| Item | Value |
|------|-------|
| Categories → Subcategories | `App Definition and Development` → `Continuous Integration & Delivery`; **and** `Observability and Analysis` → `Chaos Engineering` |
| Placement | CI&D: between **Mergify** and **Northflank**. Chaos Engineering: between **Litmus** and **PowerfulSeal** |
| `homepage_url` | `https://www.mock-server.com` |
| `repo_url` | `https://github.com/mock-server/mockserver-monorepo` |
| `crunchbase` | `https://www.crunchbase.com/organization/mockserver` (live) |
| `logo` | `mockserver.svg` in `hosted_logos/` (see [Logo](#logo) below) |

> **Why CI&D (not "Testing"):** the CNCF Landscape has no standalone *Testing* subcategory.
> Mock/test/load tools (k6, Keploy, Fortio) live under *App Definition and Development →
> Continuous Integration & Delivery*, so that is the primary home.
>
> **Why also Chaos Engineering:** MockServer ships fault/chaos injection (latency, error, connection
> and rate-limit faults, plus fault profiles), so it is cross-listed there too. The landscape
> supports the same item in multiple subcategories — the **same** item block is used in both.

### Exact `landscape.yml` block

```yaml
          - item:
            name: MockServer
            description: Mocking, debugging proxy and chaos engineering for HTTP, HTTPS, gRPC, TCP and more.
            homepage_url: https://www.mock-server.com
            repo_url: https://github.com/mock-server/mockserver-monorepo
            logo: mockserver.svg
            crunchbase: https://www.crunchbase.com/organization/mockserver
```

This identical block is inserted in **both** subcategories (CI&D and Chaos Engineering).
Indentation is 10 spaces for `- item:` and 12 for the fields, matching the surrounding entries.

---

## Logo

`hosted_logos/mockserver.svg` is the MockServer **wordmark**: the brand "M" mark stacked directly
above the word "MockServer", both rendered in the **Permanent Marker** typeface to match the
website titles and favicons (see [website.md](../operations/website.md)). It satisfies the CNCF
logo rules:

- SVG only, **no embedded raster** (`<image>`/base64) and **no `<text>`** — the lettering is
  outlined to vector paths, so it renders identically on the Landscape build servers.
- Single colour `#333333` on a transparent background; includes the project name in English
  (CNCF requires the name in the logo, so the icon-only "M" is *not* sufficient).
- ~26 KB after rounding path coordinates to 2 decimal places.

**How to regenerate:** the lettering is outlined from the Google font *Permanent Marker* using
`fonttools` (`SVGPathPen` + `TransformPen`), then path coordinates are rounded to 2 dp. The same
recipe produces the website/dashboard "M" icons (`favicon.svg`, `favicon.ico`, `apple-touch-icon.png`,
`images/mockserver-icon.png`). Keep the full wordmark (the "M" mark above "MockServer"), not the bare "M" icon, for the Landscape.

---

## Crunchbase organisation

- **URL (permalink):** <https://www.crunchbase.com/organization/mockserver>
- This is the value of the `crunchbase:` field above; the Landscape build pulls org metadata from
  the Crunchbase API using this permalink, so it must resolve to a real, live organisation.
- It is a free Crunchbase organisation profile for the MockServer open-source project (there is no
  controlling company — MockServer is the org). If it ever needs editing, sign in to Crunchbase and
  edit the profile directly; changes go through Crunchbase moderation before they appear.

---

## PR

The PR is open: **[cncf/landscape#4868](https://github.com/cncf/landscape/pull/4868)** — *Add
MockServer to Continuous Integration & Delivery* (from `jamesdbloom/landscape:add-mockserver`).
The branch now also cross-lists MockServer under Chaos Engineering (PR title unchanged). DCO checks
are green.

**Remaining:** respond to CNCF maintainer / bot feedback (typical asks: logo size, description
length, or repo activity thresholds) until merged. Any follow-up commits must also be DCO-signed
(`git commit -s`).

How it was raised, for reference / next time:

1. From the landscape fork checkout: `git push -u origin add-mockserver`.
2. Open a PR from `jamesdbloom/landscape:add-mockserver` against `cncf/landscape:master`.
3. **DCO is required** — every commit must be `Signed-off-by` an identity matching the author
   (the noreply GitHub email is fine).

**Who must do this:** a human with a GitHub account — the PR is against an external repo and the
fork push is outward-facing.

---

## Related

- Manual distribution playbook: `docs/plans/distribution-manual-channels.local.md` (local-only)
- Other external listings: [Artifact Hub](artifacthub-official-status-issue.md),
  [Postman](postman-public-workspace.md)
- Brand assets / site fonts: [operations/website.md](../operations/website.md)
