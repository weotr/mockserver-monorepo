# Awesome-list PRs — MockServer

## TL;DR

MockServer is being added to / refreshed across the community "awesome" lists. **4 PRs are open**
and **1 was skipped as out of scope**.

| List | Outcome | Link |
|------|---------|------|
| `TheJambo/awesome-testing` | ✅ PR open (new entry) | [#150](https://github.com/TheJambo/awesome-testing/pull/150) |
| `mfornos/awesome-microservices` | ✅ PR open (new entry) | [#314](https://github.com/mfornos/awesome-microservices/pull/314) |
| `Kikobeats/awesome-api` | ✅ PR open (new entry) | [#98](https://github.com/Kikobeats/awesome-api/pull/98) |
| `atinfo/awesome-test-automation` | ✅ PR open (refresh existing entry) | [#551](https://github.com/atinfo/awesome-test-automation/pull/551) |
| `awesome-selfhosted/awesome-selfhosted` | ⏭️ skipped (out of scope) | see below |

All PRs are OPEN. The three new-entry PRs were raised from forks `jamesdbloom/<repo>` on branch
`add-mockserver`; the atinfo refresh is on branch `update-mockserver-description`. None of the
target repos runs `awesome-lint`, though TheJambo/awesome-testing runs a dead-link checker + Copilot
review and mfornos/awesome-microservices runs a link linter (the added URLs are live, so these
should pass); Kikobeats/awesome-api and atinfo have no blocking workflows. Every entry positions
MockServer as a **mocking, debugging proxy and chaos engineering** tool for **multiple protocols
(HTTP, HTTPS, gRPC, TCP and more)**.

---

## ✅ Target 1 — `TheJambo/awesome-testing` → PR #150

Section **`### Service Virtualization`** in `README.md`, inserted after `mockd` and before
`WireMock` (the section isn't strictly alphabetical, but this matches the existing ordering). Entry
(matches the section's GitHub-repo link style):

```markdown
- [MockServer](https://github.com/mock-server/mockserver-monorepo) - Mocking, debugging proxy and chaos engineering tool for multiple protocols (HTTP, HTTPS, gRPC, TCP and more); mock any dependency, record/replay and inspect traffic, verify requests, and inject faults. Docker, JAR, Helm, multi-language clients.
```

## ✅ Target 2 — `mfornos/awesome-microservices` → PR #314

Section **`### Testing`** in `README.md`, alphabetically between `Mitmproxy` and `Mountebank`.
Entry (homepage link, matching neighbours like Mountebank/WireMock):

```markdown
- [MockServer](https://www.mock-server.com) - Mocking, debugging proxy and chaos engineering for multiple protocols (HTTP, HTTPS, gRPC, TCP and more); mock dependencies, record/replay traffic, verify requests, and inject faults for integration and resilience testing.
```

## ✅ Target 3 — `Kikobeats/awesome-api` → PR #98

Section **`### Mocking`** in `README.md` (uses `*` bullets), placed among the mock-server tools
after `json-server`:

```markdown
* [MockServer](https://www.mock-server.com) - Mocking, debugging proxy and chaos engineering for HTTP, HTTPS, gRPC, TCP and more; mock APIs, record/replay and inspect traffic, verify requests, and inject chaos. Docker, JAR, Helm.
```

## ✅ Target 4 — `atinfo/awesome-test-automation` → PR #551 (refresh existing entry)

MockServer was already listed in `java-test-automation.md` ("Useful libs") with a stale HTTP-only
description. PR [#551](https://github.com/atinfo/awesome-test-automation/pull/551) updates it to the
current positioning (mocking, debugging proxy and chaos engineering across HTTP/HTTPS/gRPC/TCP) and
switches the link to HTTPS. No new entry — only improved wording:

```markdown
* [MockServer](https://www.mock-server.com/) is a mocking, debugging proxy and chaos engineering tool for multiple protocols (HTTP, HTTPS, gRPC, TCP and more) - mock any system you integrate with, record/replay and verify traffic, and inject faults for resilience testing.
```

## ⏭️ Target 5 — `awesome-selfhosted/awesome-selfhosted` (skipped — out of scope)

**Skipped.** Despite MockServer being Apache-2.0 and Docker/Helm self-hostable, awesome-selfhosted
curates **self-hosted end-user network services/applications** (the kind you host instead of a SaaS),
**not developer testing tools/libraries**. A mock-server/test tool falls outside the list's scope
and would very likely be closed. (This corrects the earlier draft's "it qualifies" note.) Also note
the list is now maintained as data in `awesome-selfhosted/awesome-selfhosted-data` (one YAML file per
entry), not by editing the README. Revisit only if their scope changes.

---

## Shared notes

- All PRs are to external repos under the maintainer's GitHub account (`jamesdbloom`); merging is up
  to each list's maintainer.
- Entries follow `awesome-lint` style (sentence-case, trailing period) where it fits the house style.
  Note Kikobeats/awesome-api uses `*` bullets (not `-`), so that entry matches `*` to fit the list.
- The bullets only claim channels that are live (Docker Hub, Helm OCI, multi-language clients).
- On merge each list regenerates its README automatically. If a PR is instead closed (e.g. the
  maintainer requests changes or deems it out of scope), record the reason and update the table above.
