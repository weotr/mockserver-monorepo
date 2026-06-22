#!/usr/bin/env python3
"""Validate every example in the generated Postman collection against a live MockServer.

Walks examples/postman/MockServer.postman_collection.json and fires each request at a
running MockServer, asserting the example body is ACCEPTED (i.e. not a malformed-request
rejection). A 400/415/500 means the example body is wrong and fails the run. State-dependent
codes (404/406/409) still prove the body parsed, so they pass. Binary uploads, the
server-stopping /stop call, and not-on-classpath 501s are skipped (logged, never hidden).

    python3 scripts/collections/test_collections.py                 # starts a Docker MockServer
    python3 scripts/collections/test_collections.py --base-url URL  # use an already-running server

Exit 0 if all examples are accepted; 1 otherwise.
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
POSTMAN = os.path.join(REPO, "examples", "postman", "MockServer.postman_collection.json")

# A 400/415/500 is a genuine "the example body is wrong" failure.
FAIL_CODES = {400, 415, 500}
# Endpoints excluded from auto-testing (with reason) — binary uploads + the kill switch.
SKIP_PATHS = {
    "/mockserver/stop": "stops the server",
    "/mockserver/grpc/descriptors": "binary upload (FileDescriptorSet)",
    "/mockserver/wasm/modules": "binary upload (disabled by default)",
}


def flatten(items):
    for it in items:
        if "item" in it:
            yield from flatten(it["item"])
        else:
            yield it


def request_of(item):
    r = item["request"]
    method = r["method"]
    url = r["url"]
    raw = url["raw"]
    path = "/" + "/".join(url.get("path", []))
    enabled_query = "&".join(
        f"{q['key']}={q['value']}" for q in url.get("query", []) if not q.get("disabled"))
    headers = {h["key"]: h["value"] for h in r.get("header", [])}
    body = r.get("body", {}).get("raw")
    return method, path, enabled_query, headers, body


def wait_ready(base, timeout=40):
    for _ in range(timeout):
        try:
            req = urllib.request.Request(base + "/mockserver/status", method="PUT")
            with urllib.request.urlopen(req, timeout=3) as r:
                if r.status == 200:
                    return True
        except Exception:
            time.sleep(1)
    return False


def fire(base, method, path, query, headers, body):
    url = base + path + (("?" + query) if query else "")
    data = body.encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception as e:
        return f"ERR:{e}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", help="use an already-running MockServer instead of Docker")
    ap.add_argument("--image", default=os.environ.get("MOCKSERVER_IMAGE", "mockserver/mockserver:snapshot"))
    ap.add_argument("--port", type=int, default=1108)
    args = ap.parse_args()

    with open(POSTMAN) as f:
        coll = json.load(f)
    items = list(flatten(coll["item"]))

    container = None
    base = args.base_url
    if not base:
        container = "ms-collection-test"
        subprocess.run(["docker", "rm", "-f", container], capture_output=True)
        print(f"starting {args.image} on :{args.port} ...")
        rc = subprocess.run(["docker", "run", "-d", "--rm", "--name", container,
                             "-p", f"{args.port}:1080", args.image], capture_output=True, text=True)
        if rc.returncode != 0:
            print("docker run failed:", rc.stderr, file=sys.stderr)
            return 2
        base = f"http://localhost:{args.port}"

    try:
        if not wait_ready(base):
            print("server did not become ready", file=sys.stderr)
            return 2

        # order: keep collection order but run /reset last-ish is unnecessary; we tolerate state codes.
        passed, failed, skipped = [], [], []
        for it in items:
            method, path, query, headers, body = request_of(it)
            name = it["name"]
            if path in SKIP_PATHS:
                skipped.append((name, path, SKIP_PATHS[path]))
                continue
            code = fire(base, method, path, query, headers, body)
            tag = f"{method} {path}" + (f"?{query}" if query else "")
            if isinstance(code, int) and code == 501:
                skipped.append((name, path, "501 feature not on classpath"))
            elif isinstance(code, int) and code not in FAIL_CODES and code != 502:
                passed.append((name, tag, code))
            elif code == 502:
                # 502 = unmatched/forwarded => endpoint not present on this build
                failed.append((name, tag, f"{code} (endpoint missing on this build?)"))
            else:
                failed.append((name, tag, code))

        print(f"\n=== results: {len(passed)} passed, {len(failed)} failed, {len(skipped)} skipped ===")
        for n, t, c in passed:
            print(f"  PASS [{c}] {t}  ({n})")
        for n, p, why in skipped:
            print(f"  SKIP {p}  ({why})")
        for n, t, c in failed:
            print(f"  FAIL [{c}] {t}  ({n})")
        return 1 if failed else 0
    finally:
        if container:
            subprocess.run(["docker", "rm", "-f", container], capture_output=True)


if __name__ == "__main__":
    sys.exit(main())
