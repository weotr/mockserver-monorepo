# Gaps & Recommendations

## Review Summary

This document identifies missing documentation, undocumented areas, and recommendations for improving the project's documentation and operational practices.

## Critical Gaps

### 1. ~~No Infrastructure as Code~~ (Resolved)

**Status:** ~~The AWS infrastructure is managed manually or via a CloudFormation stack whose template is not stored in this repository.~~

**Resolution:** Buildkite agent infrastructure is now managed by Terraform in `terraform/buildkite-agents/`, using the official [Buildkite Elastic CI Stack for AWS](https://github.com/buildkite/terraform-buildkite-elastic-ci-stack-for-aws) module. State is stored remotely in S3 with native file locking. See [AWS Infrastructure](infrastructure/aws-infrastructure.md) for details.

**Remaining:** Website infrastructure (S3, CloudFront, Route53) is still manually provisioned. Consider adding Terraform definitions for these resources.

### ~~1b. DynamoDB Locking Reference~~ (Resolved)

**Status:** ~~Documentation referenced DynamoDB for Terraform state locking.~~

**Resolution:** `backend.tf` uses `use_lockfile = true` (S3-native locking), not DynamoDB. Docs updated to reflect this.

### ~~2. No Automated Release Pipeline~~ (Resolved)

**Status:** ~~The release process is a manual 13-step checklist (`scripts/release_steps.md`) spanning 7 artifact registries and multiple AWS services.~~

**Resolution:** An end-to-end Buildkite release pipeline now exists (`.buildkite/release-pipeline.yml`, `.buildkite/release-preflight-pipeline.yml`) backed by per-component scripts under `scripts/release/` (`release.sh` orchestrator, `preflight.sh`, `prepare.sh`, `finalize.sh`, `test-all.sh`, and component scripts in `scripts/release/components/`). The legacy `scripts/release_steps.md` is retained for reference but is no longer the primary path. See [Release Process](operations/release-process.md) and [Release Principles](operations/release-principles.md).

**Remaining:** Consider deleting or clearly archiving `scripts/release_steps.md` so contributors are not tempted to follow the manual sequence.

### 3. Missing API Documentation

**Status:** The OpenAPI spec (`mock-server-openapi-embedded-model.yaml`) exists in `mockserver-core` resources but is only published to SwaggerHub manually. There is no auto-generated API documentation in the repository or website.

**Recommendation:**
- Auto-generate API docs from the OpenAPI spec during the build
- Include API documentation in the Jekyll website
- Automate SwaggerHub publishing

### 4. No Runbook for Operational Issues

**Status:** AWS infrastructure debugging commands exist in `.buildkite/buildkite.md` and AGENTS.md, but there is no structured runbook for common operational scenarios.

**Recommendation:** Create `docs/runbook.md` covering:
- Buildkite agents not starting (ASG/Lambda troubleshooting)
- CI builds failing with OOM (memory tuning)
- Website not updating after deploy (CloudFront cache)
- Docker Hub push failures
- Maven Central release failures (staging repo cleanup)

## Moderate Gaps

### ~~5. Inconsistent Version References~~ (Resolved)

**Resolution:** Every version-bearing file in the repo is now enumerated in [docs/operations/release-process.md → "Version-bearing files updated by the release pipeline"](operations/release-process.md#version-bearing-files-updated-by-the-release-pipeline). The release pipeline (`scripts/release/prepare.sh` + `finalize.sh`) writes all of them; contributors should not maintain version literals by hand. The table doubles as a sanity check — `git diff` after a dry-run should touch every file listed and no others.

### ~~6. No Contributor Architecture Guide~~ (Resolved)

**Resolution:** `CONTRIBUTING.md` now links to [docs/code/overview.md](code/overview.md) and [docs/architecture.md](architecture.md) and includes a "Where to make changes" section mapping common change types (new matcher, new action, Netty pipeline, TLS, configuration property, Dashboard UI, client, Helm, CI, release) to the right module and reference doc.

### ~~7. No Testing Documentation~~ (Resolved)

**Status:** ~~The test infrastructure is complex (unit tests, integration tests, container integration tests, performance tests) but undocumented.~~

**Resolution:** Created `docs/testing.md` covering unit tests, integration tests, container integration tests, performance tests, test naming conventions, helper scripts, environment variable controls, and CI execution.

### ~~8. No Security Documentation~~ (Resolved)

**Status:** ~~`SECURITY.md` exists but only covers reporting policy. There is no documentation of security features from an architectural perspective.~~

**Resolution:** `docs/code/tls-and-security.md` now comprehensively covers BouncyCastle CA, SNI, mTLS, JWT auth (with all 15 supported JWS algorithms), control plane security, and authentication classes (8 classes documented).

### 9. Helm Chart Repo Hosting (Partial)

**Status:** Helm charts are hosted on the same S3 bucket as the website. `index.yaml` regeneration is now automated by the release pipeline — `scripts/release/components/helm.sh` rebuilds and uploads the index — but the choice of hosting is unchanged.

**Recommendation:**
- Optional: consider publishing to an OCI registry as a parallel channel (the S3 channel remains the primary published location).

### 10. ~~Build Image Staleness~~ (Resolved)

**Status:** ~~The `mockserver/mockserver:maven` CI build image pre-fetches dependencies by cloning and building the repo. This image is not automatically rebuilt when dependencies change, potentially causing CI cache misses.~~

**Resolution:** The Maven CI image is built and pushed by the Buildkite pipeline `.buildkite/docker-push-maven.yml` (manual trigger). The image has been modernised from Ubuntu 22.10 + JDK 8 to Ubuntu 24.04 + JDK 21 + Maven 3.9.15. Docker Hub credentials are managed via AWS Secrets Manager (`mockserver-build/dockerhub`), with access granted to Buildkite agents via IAM policy.

**Remaining:** Consider tagging build images with a dependency hash for better cache invalidation when `pom.xml` changes.

## Minor Gaps

### ~~11. Docker Compose Examples Not Cross-Referenced~~ (Resolved)

**Resolution:** The two trees now share a single source of truth. Each `mockserver/mockserver-examples/docker_compose_examples/<case>/docker-compose.yml` is the canonical, user-runnable example (public `mockserver/mockserver:latest` image, no CI sidecar). Each `container_integration_tests/<case>/` carries only an `integration_test.sh` and a tiny `docker-compose.override.yml` that adds the `client:` test sidecar and swaps in the locally-built `:integration_testing` image. The harness invokes `docker-compose -f <base> -f <overlay>`, so any change to a configuration is visible to both audiences on the same line. The previously example-only `docker_compose_with_mtls` is now exercised end-to-end in CI.

### ~~12. Deprecated Packaging Formats~~ (Resolved)

**Resolution:** `dput.sh` and Upstart artefacts have been removed from the repo.

### ~~13. Missing `.gitignore` Entries~~ (Resolved)

**Resolution:** `docs/` is tracked; `.gitignore` does not exclude it.

### ~~14. No Dependency Update Automation~~ (Resolved)

**Resolution:** `.github/dependabot.yml` covers the Maven ecosystem (both `/mockserver` and `/mockserver/mockserver-maven-plugin`) with conservative Java 11 compatibility guards. Snyk is wired up — see [Snyk Security](operations/snyk-security.md). Triage workflow for both is documented in [Security](operations/security.md), with skills `dependabot-snyk-pr-management` and `pr-monitor` automating review and auto-merge of green builds.

## Documentation Coverage Matrix

| Area | Status | Document |
|------|--------|----------|
| Code architecture | Documented | [code/overview.md](code/overview.md) |
| Maven build system | Documented | [operations/build-system.md](operations/build-system.md) |
| CI/CD pipelines | Documented | [infrastructure/ci-cd.md](infrastructure/ci-cd.md) |
| AWS infrastructure | Documented | [infrastructure/aws-infrastructure.md](infrastructure/aws-infrastructure.md) |
| Docker images | Documented | [infrastructure/docker.md](infrastructure/docker.md) |
| Helm charts | Documented | [infrastructure/helm.md](infrastructure/helm.md) |
| Website structure | Documented | [operations/website.md](operations/website.md) |
| Dependencies | Covered in [Security](operations/security.md) and [AGENTS.md](../AGENTS.md#java-compatibility-policy) | Version ceilings and Java 11 constraints |
| Release process | Documented | [operations/release-process.md](operations/release-process.md) |
| Testing strategy | Documented | [testing.md](testing.md) |
| Security architecture | Documented | [code/tls-and-security.md](code/tls-and-security.md) |
| Metrics & monitoring | Documented | [code/metrics.md](code/metrics.md) |
| Agent starvation fix | **Resolved** | Separate `trigger` queue on cheap t3 instances; see [ci-cd.md](infrastructure/ci-cd.md#agent-starvation-from-script-based-triggers-resolved) |
| Operational runbook | **Open** | Symptom-first triage guide for agents/CI/website/registries — deferred until there's a recurring incident pattern worth codifying |
| Infrastructure as Code | **Partial** | `terraform/buildkite-agents/` (website IaC still missing) |
| API documentation | **Open** | OpenAPI spec exists but only published to SwaggerHub manually — no auto-generated rendering on the website |
| Performance tuning | Documented | [operations/performance-tuning.md](operations/performance-tuning.md) |
| Configuration reference | Documented | [code/configuration-reference.md](code/configuration-reference.md) (mechanism + how-to-add) and `mockserver.example.properties` (authoritative property list) |
