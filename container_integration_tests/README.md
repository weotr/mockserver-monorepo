# install prerequisites on mac

```bash
# 1. install docker
open https://docs.docker.com/desktop/mac/install/
# 2. install microk8s (see: https://microk8s.io/)
brew install ubuntu/microk8s/microk8s
microk8s install
microk8s status --wait-ready
microk8s enable dns helm3 ingress
# 3. kubectl (merge output from microk8s config into ~/.kube)
# it is potentially possible to script this using `yq` installed via `brew install python-yq`
microk8s config
# 4. confirm ns list works via kubectl
kubectl --context microk8s get ns
```

# start and stop microk8s

```bash
# start
microk8s start
# stop
microk8s stop
```

# run integration tests

```bash
./integration_test.sh
```

# run integration tests without rebuilding java

```bash
SKIP_JAVA_BUILD=true ./integration_test.sh
```

# building docker container only

This builds to local docker host using tag `mockserver/mockserver:integration_testing`

```bash
SKIP_ALL_TESTS=true ./integration_test.sh
```

# how the docker-compose tests are structured

Each `docker_compose_*` test directory contains only:

- `integration_test.sh` — the test assertions
- `docker-compose.override.yml` — a small overlay that adds the `client:` sidecar (a `curl`+`nghttp` container the test execs into) and swaps the public `mockserver/mockserver:latest` image for the locally-built `mockserver/mockserver:integration_testing` image

The actual MockServer configuration lives **once** in `examples/docker-compose/<test-case>/docker-compose.yml`. The test harness merges the two compose files with `docker-compose -f <base> -f <overlay>`. This guarantees the published examples and the CI tests never drift apart — any change to a configuration is visible to both users and CI on the same line.

Overlays that need to reference paths inside their own test directory (e.g. for read-write volumes the test asserts on, or test-generated certificates) use the `${OVERRIDE_DIR}` env var, which is exported by `docker-compose.sh::start-up`. Relative paths in any compose file are otherwise resolved against the project directory (the first compose file's directory, i.e. the example dir).
