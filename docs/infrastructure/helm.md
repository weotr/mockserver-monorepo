# Helm & Kubernetes

## Charts Overview

MockServer provides two Helm charts. The main `mockserver` chart can optionally create its own ConfigMap via inline configuration, or mount an externally-created ConfigMap:

```mermaid
graph LR
    subgraph "mockserver chart"
        DEP[Deployment]
        SVC[Service]
        ING[Ingress]
        CM_INLINE["ConfigMap
inline, optional"]
        CM_REF[ConfigMap volume mount]
    end

    subgraph "mockserver-config chart (legacy)"
        CM_EXT["ConfigMap
mockserver.properties
initializerJson.json"]
    end

    CM_INLINE -->|when app.config.enabled| CM_REF
    CM_EXT -->|external| CM_REF
    CM_REF -->|mounts /config| DEP
```

| Chart | Path | Version | Purpose |
|-------|------|---------|---------|
| `mockserver` | `helm/mockserver/` | 6.1.0 | Main deployment chart (includes optional ConfigMap) |
| `mockserver-config` | `helm/mockserver-config/` | 6.1.0 | Example external ConfigMap chart (for reference) |

## mockserver Chart

### Templates

| Template | Purpose |
|----------|---------|
| `deployment.yaml` | Single-replica Deployment with ConfigMap volume mount |
| `service.yaml` | Service (NodePort/LoadBalancer/ClusterIP) |
| `ingress.yaml` | Optional Ingress resource |
| `configmap.yaml` | Optional ConfigMap for inline configuration (when `app.config.enabled`) |
| `pvc.yaml` | Optional PersistentVolumeClaim (when `app.persistence.enabled` and no `existingClaimName`) |
| `service-test.yaml` | Helm test pod (curl readiness check) |
| `_helpers.tpl` | Template helper functions |
| `NOTES.txt` | Post-install instructions |

### Default Values

```yaml
replicaCount: 1
app:
  logLevel: "INFO"
  serverPort: "1080"
  mountedConfigMapName: "mockserver-config"
  mountedLibsConfigMapName: "mockserver-config"
  propertiesFileName: "mockserver.properties"
  readOnlyRootFilesystem: false
  serviceAccountName: default
  runAsUser: 65534
  config:
    enabled: false
    properties: ""
    initializerJson: ""
    extraFiles: {}
  persistence:
    enabled: false
    existingClaimName: ""
    storageClass: ""
    accessModes:
      - ReadWriteOnce
    size: 256Mi
    mountPath: /persistence
    annotations: {}
podSecurityContext: {}   # pod-level securityContext, e.g. {fsGroup: 2000}
image:
  repository: mockserver
  snapshot: false
  pullPolicy: IfNotPresent
service:
  type: NodePort
  port: 1080
  annotations: {}
  clusterIP: ""
  externalIPs: []
  loadBalancerIP: ""
  loadBalancerSourceRanges: []
  nodePort: ""
  test:
    image: radial/busyboxplus:curl
ingress:
  enabled: false
  className: ""
  annotations: {}
  hosts:
    - host: mockserver.local
      paths:
        - path: /
          pathType: ImplementationSpecific
  tls: []
podAnnotations: {}
podLabels: {}
resources: {}
nodeSelector: {}
tolerations: []
affinity: {}
imagePullSecrets: []
releasenameOverride: ""
```

### Deployment Architecture

```mermaid
graph TB
    ING["Ingress
optional"] --> SVC["Service
NodePort :1080"]
    SVC --> POD[Pod]

    subgraph POD
        CONT["MockServer Container
Port 1080"]
        VOL_PROPS["/config/
mockserver.properties"]
        VOL_LIBS["/libs/
additional JARs"]
        VOL_PERSIST["/persistence/
persistedExpectations.json
optional"]
    end

    CM["ConfigMap
mockserver-config"] -->|volume mount| VOL_PROPS
    CM -->|volume mount| VOL_LIBS
    PVC["PersistentVolumeClaim
optional"] -->|volume mount| VOL_PERSIST
    CONT --> VOL_PROPS
    CONT --> VOL_LIBS
    CONT --> VOL_PERSIST
```

### Persistence

When `app.persistence.enabled=true`, the chart:

1. Creates a PersistentVolumeClaim (unless `app.persistence.existingClaimName` references an existing one)
2. Mounts the PVC at `app.persistence.mountPath` (default `/persistence`)
3. Injects environment variables to enable MockServer's file-based persistence:
   - `MOCKSERVER_PERSIST_EXPECTATIONS=true`
   - `MOCKSERVER_PERSISTED_EXPECTATIONS_PATH=/persistence/persistedExpectations.json`
   - `MOCKSERVER_INITIALIZATION_JSON_PATH=/persistence/persistedExpectations.json`

**Property precedence:** These environment variables are safe defaults. MockServer's property resolution order is: system property > property file > environment variable > hardcoded default. So any matching property in the user's `mockserver.properties` file overrides the chart-injected env vars.

| Value | Type | Default | Description |
|-------|------|---------|-------------|
| `app.persistence.enabled` | bool | `false` | Enable persistent storage |
| `app.persistence.existingClaimName` | string | `""` | Use existing PVC (skip PVC creation) |
| `app.persistence.storageClass` | string | `""` | StorageClass (empty = cluster default) |
| `app.persistence.accessModes` | list | `[ReadWriteOnce]` | PVC access modes |
| `app.persistence.size` | string | `256Mi` | PVC size |
| `app.persistence.mountPath` | string | `/persistence` | Container mount path |
| `app.persistence.annotations` | map | `{}` | PVC annotations |
| `podSecurityContext` | map | `{}` | Pod-level securityContext, rendered verbatim into `spec.template.spec.securityContext`. Accepts any pod-level field (`fsGroup`, `fsGroupChangePolicy`, `runAsGroup`, `seccompProfile`, …). Empty ⇒ nothing emitted. |

**Backward compatibility:** Disabled by default. When disabled, no PVC, volumes, volumeMounts, or env vars are added — the chart behaves identically to before this feature was added. `podSecurityContext` likewise defaults to `{}`, so no pod-level `securityContext` is emitted unless set.

**Pod securityContext / PVC permissions:** on clusters with restrictive defaults the pod may be unable to write to the mounted volume, so persistence silently fails. Set a pod-level `fsGroup` so the volume is group-owned and writable, e.g. `--set podSecurityContext.fsGroup=2000`. `podSecurityContext` is the general-purpose hook for any pod-level securityContext field (the container-level `securityContext` continues to carry `runAsUser` / `readOnlyRootFilesystem` / `allowPrivilegeEscalation`).

**PVC retention:** Chart-managed PVCs are NOT deleted by `helm uninstall`. Delete the PVC manually if you want to remove persisted data: `kubectl delete pvc <release-name> -n <namespace>`.

### Health Checks

- **Readiness probe:** TCP socket check on port 1080
- **Liveness probe:** TCP socket check on port 1080

### Installation

```bash
# --- Option A: OCI (recommended, Helm 3.8+) ------------------------------
helm install mockserver oci://ghcr.io/mock-server/charts/mockserver

# Pin a version
helm install mockserver oci://ghcr.io/mock-server/charts/mockserver --version 6.1.0

# --- Option B: Legacy HTTP repo ------------------------------------------
helm repo add mockserver https://www.mock-server.com
helm repo update

# Install with defaults (no configuration)
helm install mockserver mockserver/mockserver

# Install with custom values
helm install mockserver mockserver/mockserver \
  --set app.serverPort=1080 \
  --set service.type=ClusterIP

# Install with inline configuration (single chart — recommended)
# Use --set-string for JSON values — escape commas as \, since --set treats commas as separators
helm install mockserver mockserver/mockserver \
  --set app.config.enabled=true \
  --set app.config.properties="mockserver.initializationJsonPath=/config/initializerJson.json" \
  --set-string 'app.config.initializerJson=[{"httpRequest":{"path":"/example"}\,"httpResponse":{"body":"response"}}]'

# Or using a values.yaml file for complex config
helm install mockserver mockserver/mockserver -f my-values.yaml

# Legacy: external config chart + main chart. The mockserver-config chart is
# NOT published in the Helm repo (see "mockserver-config Chart" section
# below) — to use it, clone the source repo and install from the local path.
git clone https://github.com/mock-server/mockserver-monorepo
helm install mockserver-config ./mockserver-monorepo/helm/mockserver-config
helm install mockserver mockserver/mockserver
```

## mockserver-config Chart (Legacy / Example)

The separate `mockserver-config` chart is retained as a **reference example only**. It is not published to the Helm repository at `https://www.mock-server.com/index.yaml` — consumers wanting to use it must copy the chart from `helm/mockserver-config/` in the source repo. For new deployments, use the inline `app.config` values in the main chart instead (see above).

Its `Chart.yaml` is bumped automatically by the release scripts (`finalize.sh`'s find-and-replace pass) to keep it in lock-step with the main `mockserver` chart, but only the `mockserver` chart is packaged and pushed to S3 by `helm.sh`.

This chart provides a ConfigMap containing:

- `mockserver.properties` — server configuration
- `initializerJson.json` — pre-loaded expectations

### Template

The ConfigMap template loads default files from `static/`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ .Chart.Name }}
data:
  mockserver.properties: |-
    {{ printf "%s" (.Files.Get "static/mockserver.properties") | indent 4 }}
  initializerJson.json: |-
    {{ printf "%s" (.Files.Get "static/initializerJson.json") | indent 4 }}
```

Static defaults are in `helm/mockserver-config/static/`:
- `mockserver.properties` — default MockServer properties
- `initializerJson.json` — default expectation initialiser (empty array)

## Versioning Policy

All MockServer components — Java modules, client libraries, Docker images, and Helm charts — share a single version number. This keeps things simple and transparent for users.

The Helm chart `version` and `appVersion` in `Chart.yaml` **MUST always match the MockServer application version**. Both charts (`mockserver` and `mockserver-config`) follow this rule, but the enforcement path differs:

- **`mockserver` (published)**: `scripts/release/components/helm.sh` sets both fields to `$RELEASE_VERSION` via an explicit `sed` pass before packaging, then publishes the `.tgz` to S3.
- **`mockserver-config` (reference-only, NOT published)**: bumped passively by `finalize.sh`'s general find-and-replace across `*.yaml` files. Not packaged or pushed anywhere.

Rules:

- **NEVER** bump the chart version independently of the MockServer version
- **NEVER** change `version` without also changing `appVersion` to the same value
- Both charts must be kept at the same version (the release scripts handle this automatically)

Helm chart changes made between releases are published as part of the next MockServer release, not independently.

## Chart Distribution

The chart is published to two locations on every release. Both are kept in lock-step by `scripts/release/components/helm.sh`.

| Channel | URL | Underlying storage | Why both |
|---------|-----|-------------------|----------|
| **OCI (recommended)** | `oci://ghcr.io/mock-server/charts/mockserver` | GitHub Container Registry, public | Native format for modern toolchains (Argo CD, Flux, Renovate); immutable digests; cosign-signable; same auth path consumers already use for `ghcr.io` container images |
| **Legacy HTTP** | `https://www.mock-server.com` (index.yaml) | S3 bucket fronting the website | Back-compat for `helm repo add` users and direct `.tgz` downloads — kept indefinitely |

### OCI registry (GHCR)

- **Registry:** `ghcr.io`
- **Namespace:** `mock-server/charts`
- **Package:** `mockserver` (chart name from `Chart.yaml`)
- **Pull URL:** `oci://ghcr.io/mock-server/charts/mockserver`
- **Visibility:** public (no auth required for `helm pull` / `helm install`)
- **Push auth:** Fine-scoped GitHub PAT with `write:packages` scope, stored in AWS Secrets Manager as `mockserver-release/ghcr-token` (`{username, token}`)

### Legacy HTTP repo

- **Bucket:** Main website S3 bucket (see `~/mockserver-aws-ids.md`)
- **Index:** `helm/charts/index.yaml`
- **Charts:** `helm/charts/mockserver-*.tgz` (every released version)

### Artifact Hub

[Artifact Hub](https://artifacthub.io) is the de-facto discovery site for Helm charts. Publishing
the OCI chart there makes it findable without users knowing the `oci://` path in advance. The
listing reads `Chart.yaml` natively (`name`, `description`, `keywords`, `home`, `sources`,
`maintainers`, `icon`) plus the `annotations` block (`artifacthub.io/license`, `artifacthub.io/links`).

Repository metadata lives in [`helm/artifacthub-repo.yml`](../../helm/artifacthub-repo.yml). One-time
bootstrap (manual — needs an Artifact Hub account):

1. Artifact Hub → Control Panel → Repositories → Add → kind **Helm charts**, OCI based, URL
   `oci://ghcr.io/mock-server/charts`.
2. Copy the generated **Repository ID** into `repositoryID` in `helm/artifacthub-repo.yml`.
3. Publish the metadata file to the registry root so Artifact Hub can verify ownership:
   ```bash
   oras push ghcr.io/mock-server/charts:artifacthub.io \
     helm/artifacthub-repo.yml:application/vnd.cncf.artifacthub.repository-metadata.layer.v1.yaml
   ```

After that, Artifact Hub auto-indexes each new chart version pushed to GHCR by the release pipeline —
no per-release step. (Optional follow-up: add the `oras push` of the metadata file to
`scripts/release/components/helm.sh` once `oras` is available on the `release` agents.)

### Signing the chart (Artifact Hub "Signed" badge)

Artifact Hub shows a **Signed** badge when the OCI chart carries a [cosign](https://docs.sigstore.dev/)
signature (it detects them automatically for OCI repositories — no annotation needed). The release
pipeline has a **guarded, opt-in** signing step in `scripts/release/components/helm.sh`: it is a
**no-op until a signing key exists**, so it never affects unsigned releases.

To enable signing:

1. **Generate a cosign key pair** (once): `cosign generate-key-pair` → `cosign.key` (encrypted with a
   password) + `cosign.pub`. Publish `cosign.pub` somewhere users can verify against (e.g. the repo
   or the website).
2. **Store it** in the build account's Secrets Manager as `mockserver-release/cosign-key`, a JSON
   secret with keys `key` (the PEM contents of `cosign.key`) and `password` (the key password).
3. **Validate with the test harness** — run `scripts/release/test-cosign-signing.sh`. It sources the
   release library and calls the same `load_secret` + `in_docker` helpers, downloading a pinned cosign
   binary into the `alpine/helm` image, `cosign login ghcr.io` with the existing GHCR token, and
   `cosign sign --key <key> ghcr.io/mock-server/charts/mockserver:<version>` — the identical code path
   to the release step. Default run is a non-mutating preflight (loads the secret, proves the
   key+password decrypt, logs in to GHCR); `--sign` then signs the real published tags and verifies
   them. A `--dry-run` *release* does **not** test this — the release skips signing in dry-run — so use
   the harness. Signing is non-fatal regardless, so a real release won't break if it fails (it just
   publishes unsigned).

Once a signed version is on GHCR, Artifact Hub shows the Signed badge on its next scan. The public
key is published in the repo at `helm/mockserver/cosign.pub`, so users can verify a chart with:

```bash
cosign verify \
  --key https://raw.githubusercontent.com/mock-server/mockserver-monorepo/master/helm/mockserver/cosign.pub \
  ghcr.io/mock-server/charts/mockserver:<version>
```

(The private half lives only in the `mockserver-release/cosign-key` Secrets Manager secret.)

> Hardening notes: the step fetches a SHA256-pinned cosign binary (v2.4.3) at release time and the
> private key is mounted as a `0600` file rather than passed via the container's environment, so it
> never appears in the host process table. Keyless (OIDC) signing is a further alternative if
> Buildkite OIDC is set up, avoiding a stored key altogether.

### Requesting "Official" status (Artifact Hub)

The Artifact Hub **Official** badge marks a package as published by the project that owns the
software. It is a curated status granted by the Artifact Hub maintainers (not a file you add), and
requires the repository to already be a **Verified Publisher** (it is). Request it by opening an
issue on [`github.com/artifacthub/hub`](https://github.com/artifacthub/hub/issues) using the
*"Request official status"* template, e.g.:

> **Title:** Request official status for the MockServer Helm chart
>
> **Repository:** `mockserver` (kind: Helm) — `oci://ghcr.io/mock-server/charts/mockserver`
> **Artifact Hub URL:** _<the chart's artifacthub.io URL>_
>
> MockServer is an open-source HTTP(S) mock server & proxy (https://www.mock-server.com, source at
> https://github.com/mock-server/mockserver-monorepo). This repository is the official publisher of
> MockServer — the chart is built and released from the same monorepo. It is already a Verified
> Publisher (repository ID `a6ca1874-16c1-43c8-9924-9bf9c3a5a9ea`). Please grant Official status.

Once granted, the Official badge appears alongside Verified Publisher.

### Release pipeline (automated)

`scripts/release/components/helm.sh` runs on the `release` agent queue and:

1. Bumps `Chart.yaml` `version` + `appVersion` to `$RELEASE_VERSION`
2. `helm lint` + `helm package`
3. `helm registry login ghcr.io` + `helm push <tgz> oci://ghcr.io/mock-server/charts`
4. Sync historical `.tgz` + `index.yaml` from S3, regenerate index, upload back
5. Commit + push `Chart.yaml`, the new `.tgz`, and the rebuilt `index.yaml`

GHCR is pushed first so a registry outage aborts the step before any S3 mutation. Re-running the step after a mid-publish failure is safe — `helm push` overwrites the existing OCI tag with identical bytes, and the S3 sync rebuilds the index from scratch. **Caveat:** this assumes the GHCR package is *not* configured with immutable tags. If you ever turn on immutability in the GHCR package settings, re-publishing the same version will fail with `403`; either bump the version or temporarily disable immutability before retry.

### Backfilling the OCI registry

To populate GHCR with historical chart versions (one-time, after first turning the OCI publishing flow on):

```bash
# Authenticate to AWS to load the PAT from Secrets Manager
aws sso login --profile mockserver-build

# Dry-run to see which versions will push
AWS_PROFILE=mockserver-build scripts/release/backfill-helm-oci.sh --dry-run

# Push every .tgz under helm/charts/ to ghcr.io/mock-server/charts
AWS_PROFILE=mockserver-build scripts/release/backfill-helm-oci.sh --execute
```

The script enumerates `helm/charts/mockserver-*.tgz` in version order and pushes each one. Safe to re-run — `helm push` against an existing OCI tag overwrites it with the same bytes.

### Publishing a chart manually (rare)

Almost never needed — the release pipeline does this. Useful for emergency republishes:

```bash
# Package
cd helm && helm package ./mockserver/ --destination charts/ && cd ..

# OCI (GHCR) — printf avoids the trailing newline that echo would append
printf "%s" "$GHCR_TOKEN" | helm registry login ghcr.io --username "$GHCR_USERNAME" --password-stdin
helm push helm/charts/mockserver-X.Y.Z.tgz oci://ghcr.io/mock-server/charts

# Legacy HTTP (S3)
aws s3 sync s3://<website-bucket>/ helm/charts/ --exclude '*' --include 'mockserver-*.tgz' --include 'index.yaml'
helm repo index helm/charts/ --url https://www.mock-server.com
aws s3 cp helm/charts/mockserver-X.Y.Z.tgz s3://<website-bucket>/
aws s3 cp helm/charts/index.yaml s3://<website-bucket>/
```

## Testing

### Static Validation (CI)

The infra pipeline runs `helm lint` and `helm template` against both charts on every change to `helm/`. This catches syntax errors, rendering issues, and invalid template logic without needing a Kubernetes cluster.

- **Pipeline step:** `.buildkite/scripts/steps/helm-validate.sh`
- **Validates:** default values, inline config enabled, ingress enabled, persistence enabled (chart-managed and existing PVC)

### k3d-Based Integration Testing

The container integration tests use k3d (k3s in Docker) for Helm testing. k3d was chosen over Kind for faster cluster startup (~10-15s vs ~30-40s) and simpler port mapping.

```mermaid
sequenceDiagram
    participant Script as integration_tests.sh
    participant K3d as k3d Cluster
    participant Helm as Helm
    participant MS as MockServer Pod
    participant Test as helm test Pod

    Script->>K3d: Create cluster (port 1080 mapped)
    Script->>K3d: Import MockServer image
    Script->>Helm: helm install mockserver
    Helm->>MS: Deploy pod
    Script->>Helm: helm test (curl -f /mockserver/status)
    Helm->>Test: Run test pod
    Script->>MS: Create expectations (curl PUT)
    Script->>MS: Validate responses (curl PUT)
    Script->>Helm: helm uninstall
    Script->>K3d: Delete cluster
```

**k3d config** (`container_integration_tests/k3d-config.yaml`):

```yaml
apiVersion: k3d.io/v1alpha5
kind: Simple
metadata:
  name: mockserver
servers: 1
ports:
  - port: 1080:1080
    nodeFilters:
      - loadbalancer
```

### Test Cases

| Test | What It Tests |
|------|--------------|
| `helm_default_config` | Default chart deployment (no overrides) |
| `helm_local_docker_container` | Custom local Docker image |
| `helm_custom_server_port` | Custom server port (`app.serverPort=1081`) |
| `helm_remote_host_and_port` | Proxy remote host/port (two MockServer instances) |
| `helm_inline_config` | Inline ConfigMap with pre-loaded expectations (`app.config.enabled=true`) |

Each test also invokes `helm test` to verify the service-test pod can reach MockServer's `/status` endpoint.

### Running Locally

```bash
# Prerequisites: docker, k3d, helm, kubectl
# Build the test image first
SKIP_HELM_TESTS=true container_integration_tests/integration_tests.sh

# Run helm tests only
SKIP_JAVA_BUILD=true SKIP_DOCKER_TESTS=true container_integration_tests/integration_tests.sh

# Clean up cluster afterward
DELETE_CLUSTER=true SKIP_JAVA_BUILD=true SKIP_DOCKER_TESTS=true container_integration_tests/integration_tests.sh
```
