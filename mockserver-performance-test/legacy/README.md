# Legacy Locust performance harness (retired)

These files are the previous **Locust**-based performance harness, retained for
one release for reference. The current performance suite is
[**k6**](../k6/) — use `../scripts/runK6.sh` or `../scripts/runAll.sh` instead.

## Files

| File | Purpose |
|------|---------|
| `locustfile.py` | Locust user — create-expectation + `GET /simple` tasks |
| `docker_performance_test.sh` | HTTP/HTTPS user-count sweep orchestrator |
| `expectations.json` | the 4 seeded expectations (now embedded in [`../k6/lib/expectations.js`](../k6/lib/expectations.js)) |
| `runLocust.sh` | docker runner |

## Running (if needed)

`docker_performance_test.sh` calls `curl`, which the base `locustio/locust`
image does not ship (the old `mockserver/mockserver:performance` image added it,
but that tag is now the k6 image). Provide an image with both `locust` and
`curl`:

```bash
LOCUST_IMAGE=my-locust-with-curl ./runLocust.sh [host]
```

This harness will be removed in a future release once the k6 suite has bedded
in.
