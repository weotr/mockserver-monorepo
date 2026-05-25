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

### 5. Inconsistent Version References

**Status:** Version numbers are still hardcoded in multiple locations (current values as of 2026-05-25):

| Location | Current value |
|----------|---------------|
| `mockserver/pom.xml` | `6.0.1-SNAPSHOT` |
| `jekyll-www.mock-server.com/_config.yml` | `6.0.0` (released) / `6.0.1-SNAPSHOT` |
| `helm/mockserver/Chart.yaml` | `6.0.0` (must match app version per Helm chart policy) |
| `helm/mockserver/values.yaml` | image tag defaults |
| Dockerfiles | `VERSION=RELEASE` or `6.0.1-SNAPSHOT` |
| `mockserver-client-python/pyproject.toml` | `6.0.0` |
| `mockserver-client-ruby/lib/mockserver/version.rb` | `6.0.0` |

**Recommendation:**
- The new release pipeline (`scripts/release/release.sh --version`) updates all of these as a unit; no standalone `bump_version.sh` is needed if releases always run through it. Verify the pipeline covers every location above and add any missing ones to `scripts/release/components/`.
- Add a release-process doc section enumerating every file the pipeline touches, so a human can sanity-check before promotion.

### 6. No Contributor Architecture Guide

**Status:** `CONTRIBUTING.md` exists but does not explain the codebase architecture, module relationships, or where to make changes for different types of contributions.

**Recommendation:**
- Link to `docs/architecture.md` from `CONTRIBUTING.md`
- Add a "Where to make changes" section mapping feature types to modules

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

### 11. Docker Compose Examples Not Cross-Referenced

The 10 Docker Compose examples in `mockserver-examples/docker_compose_examples/` duplicate the integration test configurations in `container_integration_tests/`. Changes in one are not automatically reflected in the other.

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
| Operational runbook | **Missing** | Recommended: `docs/runbook.md` |
| Infrastructure as Code | **Partial** | `terraform/buildkite-agents/` (website IaC still missing) |
| API documentation | **Partial** | OpenAPI spec exists, not integrated |
| Performance tuning | **Partial** | Website covers it, no internal docs |
| Configuration reference | **Partial** | `mockserver.example.properties` exists |
