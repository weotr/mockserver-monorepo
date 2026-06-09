# Mock an API from an OpenAPI spec

One command to turn an OpenAPI/Swagger specification into a running mock — every operation is served automatically using the spec's response schemas and examples.

```bash
docker compose up
```

MockServer reads the mounted spec at startup (`MOCKSERVER_INITIALIZATION_OPENAPI_PATH`) and generates an expectation per operation. Then call it:

```bash
curl http://localhost:1080/pets
# -> [{"id":1,"name":"Rex"},{"id":2,"name":"Mia"}]

curl http://localhost:1080/pets/1
# -> {"id":1,"name":"Rex"}
```

## What it shows

- `MOCKSERVER_INITIALIZATION_OPENAPI_PATH` points at a mounted spec (`./config/openapi.yaml`); the value may also be a URL or a JSON/YAML file path.
- Responses come from the spec's `examples` where present, otherwise generated from the response schema.

Swap `config/openapi.yaml` for your own spec to mock your real API. To do the same from the CLI without Docker Compose: `mockserver run --openapi /path/to/openapi.yaml`.

Stop with `docker compose down`.
