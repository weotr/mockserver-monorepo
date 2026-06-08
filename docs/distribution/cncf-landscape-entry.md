# CNCF Landscape Entry — MockServer

## TL;DR

Add MockServer to the CNCF Landscape under **Observability and Analysis → Testing** (or the closest
equivalent testing subcategory) by opening a PR against `cncf/landscape` that adds one YAML block
to `landscape.yml` and contributes a monochrome SVG logo to `hosted_logos/`.

---

## Exact `landscape.yml` block

Find the section for testing tools in `landscape.yml`. The categories vary over time — check the
current file for the right parent. At the time of writing the closest match is
`Observability and Analysis` with a subcategory that contains other testing/quality tools
(e.g. WireMock, Pact). Insert **alphabetically by `name`** within that subcategory:

```yaml
- item:
    name: MockServer
    homepage_url: https://www.mock-server.com
    repo_url: https://github.com/mock-server/mockserver-monorepo
    logo: mockserver.svg
    description: >-
      HTTP(S), gRPC, WebSocket and LLM/AI mock server and proxy for integration
      testing and chaos engineering. Supports OpenAPI-driven expectations,
      request verification, traffic recording, and multi-protocol mocking
      (Kafka, MQTT, HTTP/3). Runs as a Docker image, Helm chart, or embedded
      Java library. Apache 2.0.
    crunchbase: https://www.crunchbase.com/organization/mockserver
```

**Notes on each field:**

| Field | Value / instruction |
|-------|---------------------|
| `name` | `MockServer` — exact capitalisation used in all public channels |
| `homepage_url` | `https://www.mock-server.com` — the canonical docs site |
| `repo_url` | `https://github.com/mock-server/mockserver-monorepo` — the monorepo |
| `logo` | `mockserver.svg` — file you must add to `hosted_logos/` (see below) |
| `description` | Keep to ≤150 characters if the Landscape YAML enforces it; the draft above is ~235 chars — trim as needed |
| `crunchbase` | Optional but CNCF reviewers often ask for it. Create a free Crunchbase org at `crunchbase.com/organization/mockserver` if one does not already exist |

---

## SVG logo requirement

The CNCF Landscape requires an SVG file in the `hosted_logos/` directory of the `cncf/landscape`
repo. Rules the CNCF enforces:

- File format: `.svg` only — no embedded raster images (no `<image>` tags referencing a PNG/JPEG).
- Colour: the logo renders on a white card; a clean single-colour or limited-palette version works
  best. The Landscape CI will reject SVGs with embedded raster data.
- File name: must match the `logo:` field exactly — `mockserver.svg`.
- Source: the MockServer site uses a logo at `https://www.mock-server.com/images/mockserver-icon.png`.
  Convert it to a clean SVG (use Inkscape, Figma, or an online SVG tracer). A monochrome `#000000`
  version on a transparent background is the safest choice.

---

## Exact PR steps

1. Fork `https://github.com/cncf/landscape`.
2. Add `hosted_logos/mockserver.svg` (the clean SVG, ≤50 KB).
3. Open `landscape.yml`. Locate the correct subcategory (search for `WireMock` or `Pact` to find
   where testing tools sit). Insert the YAML block above **in alphabetical order by `name`**.
4. Run the local lint check the repo provides (usually `npm run check` or the CI instructions in
   their `CONTRIBUTING.md`) to catch schema or logo errors before submitting.
5. Open the PR with title: `Add MockServer to Testing subcategory`.
6. In the PR body link the MockServer homepage, repo, and Apache 2.0 licence.
7. Respond to CNCF bot feedback (usually: logo size, Crunchbase, description length). The bot
   runs automatically and posts a preview card.

**Who must do this:** a human with a GitHub account — the PR is against an external repo.
