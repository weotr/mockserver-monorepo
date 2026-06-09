# Release Component: testcontainers-mockserver (Python)

## `scripts/release/components/testcontainers-python.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

COMPONENT_DIR="mockserver-testcontainers/python"
VERSION="${RELEASE_VERSION:?RELEASE_VERSION must be set}"

# Update version in pyproject.toml and __init__.py
sed -i.bak "s/^version = \".*\"/version = \"${VERSION}\"/" "${COMPONENT_DIR}/pyproject.toml"
sed -i.bak "s/^__version__ = \".*\"/__version__ = \"${VERSION}\"/" "${COMPONENT_DIR}/src/testcontainers_mockserver/__init__.py"
# Update the default Docker image tag
sed -i.bak "s/_DEFAULT_TAG = \"mockserver-.*\"/_DEFAULT_TAG = \"mockserver-${VERSION}\"/" "${COMPONENT_DIR}/src/testcontainers_mockserver/container.py"
rm -f "${COMPONENT_DIR}"/**/*.bak

# Build
(cd "${COMPONENT_DIR}" && python -m build)

# Publish
PYPI_TOKEN=$(aws secretsmanager get-secret-value \
  --profile mockserver-build \
  --secret-id mockserver-build/pypi \
  --query SecretString --output text)

twine upload "${COMPONENT_DIR}/dist/*" --username __token__ --password "${PYPI_TOKEN}"
```

## Liveness check for `scripts/release/components/verify.sh`

```bash
# testcontainers-mockserver (PyPI)
pip install --no-cache-dir "testcontainers-mockserver==${RELEASE_VERSION}" \
  && python -c "from testcontainers_mockserver import MockServerContainer; assert MockServerContainer().image.endswith('mockserver-${RELEASE_VERSION}')"
```
