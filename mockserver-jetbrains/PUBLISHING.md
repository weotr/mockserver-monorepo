# Publishing the MockServer JetBrains Plugin

## Registry

JetBrains Marketplace: https://plugins.jetbrains.com/

## Plugin ID

`com.mock-server.mockserver`

## Non-interactive publish command

```bash
cd mockserver-jetbrains
JETBRAINS_TOKEN="$JETBRAINS_TOKEN" ./gradlew publishPlugin
```

## Secret

| Secret path | JSON key | Purpose |
|---|---|---|
| `mockserver-release/jetbrains` | `token` | JetBrains Marketplace upload token (exported to the build as the `JETBRAINS_TOKEN` env var) |

> Store the secret as `{"token":"..."}` — `jetbrains.sh` reads it with
> `load_secret "mockserver-release/jetbrains" "token"` and exports it as `JETBRAINS_TOKEN` for
> `./gradlew publishPlugin`.

The token is stored in AWS Secrets Manager under the `mockserver-build` account. Retrieve with:

```bash
aws secretsmanager get-secret-value \
  --secret-id mockserver-release/jetbrains \
  --profile mockserver-build \
  --query SecretString --output text
```

## Marketplace listing page & screenshots

The JetBrains Marketplace listing has TWO parts, neither of which updates automatically from the repo:

1. **Description text** — rendered from the `<description>` HTML in
   [`src/main/resources/META-INF/plugin.xml`](src/main/resources/META-INF/plugin.xml) (NOT the README — the
   README is only the GitHub page). `publishPlugin` uploads it with the build, so description changes ship on
   release. Edit the `<description>` (allowed tags: `<p> <ul> <li> <b> <em> <a> <br>`) to change the listing copy.
2. **Screenshots / gallery** — uploaded **manually** in the Marketplace UI; they are NOT taken from the repo.
   Ready-to-upload, pre-framed images (rounded corners + equal padding baked in, since the gallery has no CSS)
   are committed at **[`docs/screenshots/marketplace/`](docs/screenshots/marketplace/)**:
   - `intellij_dashboard_in_ide.png` — the live dashboard embedded in the IDE
   - `intellij_dashboard_code_export.png` — expectation builder with multi-language code export
   - `intellij_tool_window.png` — the MockServer tool window (status line + grouped buttons + Port field)
   - `intellij_tools_menu.png` — the Tools ▸ MockServer menu

   The raw (unframed) sources live at [`docs/screenshots/`](docs/screenshots/) and also render in the GitHub
   README. Regenerate the framed copies after recapturing with:
   ```bash
   python3 docs/make-marketplace-screenshots.py <raw-src-dir> docs/screenshots/marketplace
   ```

**On each release**, to keep the listing current:
1. Update the `<description>` / `<change-notes>` in `plugin.xml` if the feature set changed.
2. Recapture + re-frame any screenshots whose UI changed, then at plugins.jetbrains.com → the MockServer plugin
   → **Edit Plugin Page → Media**, upload the files from `docs/screenshots/marketplace/` (replace the old ones).

## Plugin signing (optional, recommended)

For signed plugin distribution, set these additional environment variables:

- `CERTIFICATE_CHAIN` — the full certificate chain (PEM)
- `PRIVATE_KEY` — the private key (PEM)
- `PRIVATE_KEY_PASSWORD` — passphrase for the private key

## Versioning

The plugin version is derived from the MockServer project version (currently `7.0.1`). It is set in `gradle.properties` as `pluginVersion` and should be updated in lockstep with the MockServer release version.

## Eventual split note

If the plugin is ever extracted to a separate repository, the natural home would be `github.com/mock-server/mockserver-jetbrains`.
