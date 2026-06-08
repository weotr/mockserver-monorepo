# Awesome-list PRs — MockServer

## TL;DR

Five PRs into five community awesome-lists. Each PR adds one bullet in alphabetical order within
the appropriate section. The exact file path, section heading, and bullet text for each target
list are specified below. MockServer's Apache 2.0 licence satisfies every list's criteria,
including awesome-selfhosted.

---

## License check: awesome-selfhosted

`awesome-selfhosted/awesome-selfhosted` requires software to be self-hostable and to carry an
[OSI-approved licence](https://opensource.org/licenses). MockServer is licensed under
**Apache License 2.0** (see `LICENSE.md`), which is OSI-approved. It is self-hostable via Docker
(`docker run mockserver/mockserver`) and Helm. It qualifies.

---

## Target 1 — `TheJambo/awesome-testing`

**Repo:** `https://github.com/TheJambo/awesome-testing`

**File:** `README.md`

**Section:** look for a heading along the lines of `## Mock Servers / Service Virtualisation` or
`## API Mocking`. If no such heading exists, use the most relevant existing section (e.g.
`## Tools`). Insert **alphabetically by tool name** within that section.

**Bullet to add:**

```markdown
- [MockServer](https://github.com/mock-server/mockserver-monorepo) - Mock HTTP(S), gRPC, WebSocket and LLM/AI services; verify requests; record and replay traffic; inject chaos. Docker, Helm, Java, and multi-language clients (JS, Python, Ruby, Go, .NET, Rust). Apache 2.0.
```

**PR title:** `Add MockServer — HTTP/gRPC/LLM mock server and proxy`

---

## Target 2 — `atinfo/awesome-test-automation`

**Repo:** `https://github.com/atinfo/awesome-test-automation`

**File:** `java-test-automation.md` (and optionally `python-test-automation.md` / `javascript-test-automation.md` for the respective clients)

**Section (Java file):** `## Mock frameworks`

**Bullet to add (Java file):**

```markdown
- [MockServer](https://github.com/mock-server/mockserver-monorepo) - Mock and proxy any system over HTTP(S), gRPC, WebSockets; verify requests; record traffic. Includes JUnit 4/5 extensions, Spring test listener, Maven plugin, and clients for Java, JS, Python, Ruby, Go, .NET, Rust.
```

Insert alphabetically by tool name within the section.

**PR title:** `Add MockServer to Mock frameworks`

---

## Target 3 — `mfornos/awesome-microservices`

**Repo:** `https://github.com/mfornos/awesome-microservices`

**File:** `README.md`

**Section:** `## Testing` (or the closest equivalent — search for `WireMock` or `Hoverfly` to find
where mock/service-virtualisation tools sit).

**Bullet to add:**

```markdown
- [MockServer](https://www.mock-server.com) - Mock and proxy HTTP(S), gRPC, WebSocket and AI/LLM services for microservice integration tests and chaos testing. [Apache 2.0](https://github.com/mock-server/mockserver-monorepo/blob/master/LICENSE.md)
```

Insert alphabetically by tool name within the section.

**PR title:** `Add MockServer to Testing section`

---

## Target 4 — `Kikobeats/awesome-api`

**Repo:** `https://github.com/Kikobeats/awesome-api`

**File:** `README.md`

**Section:** Look for `## Mock` or `## Testing` or `## Tools`. Search the file for `WireMock`,
`Mockoon`, or `Hoverfly` to find the right heading.

**Bullet to add (adapt indentation/icon style to match the list's house style):**

```markdown
- [MockServer](https://www.mock-server.com) — Mock & proxy HTTP(S), gRPC, WebSockets and LLM/AI APIs; verify requests; inject chaos. Docker, Helm, multi-language clients.
```

**PR title:** `Add MockServer — HTTP/gRPC/LLM mock server`

**Note:** this list uses varying formats (sometimes with an emoji or leading dash). Match exactly
whatever style the adjacent entries use.

---

## Target 5 — `awesome-selfhosted/awesome-selfhosted`

**Repo:** `https://github.com/awesome-selfhosted/awesome-selfhosted`

**File:** `README.md`

**Section:** `## Software Development - Testing` (search for the exact heading — it changes
between list versions; alternatively search for `WireMock` to locate the section).

**Bullet to add (follow the list's strict format: `[Name](URL) - Description. ([Language](lang-url), [License](license-url))`):**

```markdown
- [MockServer](https://www.mock-server.com) - Mock and proxy HTTP(S), gRPC, WebSocket and AI/LLM services; verify requests; record traffic; inject chaos for resilience testing. ([Java](https://www.java.com), [Apache-2.0](https://github.com/mock-server/mockserver-monorepo/blob/master/LICENSE.md))
```

Insert **alphabetically by name** — `MockServer` sorts between `Mockoon` and `N` entries.

**PR title:** `Add MockServer to Software Development - Testing`

**Notes specific to awesome-selfhosted:**
- The list requires a working demo or Docker-runnable image. MockServer satisfies this:
  `docker run -d --rm -p 1080:1080 mockserver/mockserver`
- They require an active project. MockServer 7.0.0 was released June 2026.
- They may ask for a `dependent` tag if the tool requires Java. Add `Java 17+` to the description
  if a reviewer requests it.
- Read their `CONTRIBUTING.md` before opening the PR — they enforce strict review criteria and
  close PRs that do not follow the template.

---

## Shared notes

- All PRs go to external repos. A human with a GitHub account must open each one.
- Check each list's `CONTRIBUTING.md` before submitting — many have a PR template.
- All five lists auto-deploy their README on merge; no further action is needed once merged.
- Do not open these PRs until MockServer is actually published in each channel the bullet
  references (currently: Docker Hub confirmed live, Helm OCI confirmed live, clients confirmed
  live — all valid).
