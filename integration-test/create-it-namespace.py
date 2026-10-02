#!/usr/bin/env python3
"""Create the it namespace on the integration Nacos (auth disabled)."""
import json, urllib.parse, urllib.request, urllib.error

SERVER = "http://localhost:18848"


HDR = {"serverIdentityKey": "serverIdentityValue"}


def http(method, url, data=None):
    req = urllib.request.Request(url, data=data, method=method, headers=HDR)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


st, text = http("GET", SERVER + "/nacos/v3/admin/core/namespace/list")
print("list ->", st, text[:150])
try:
    existing = {n.get("namespace") for n in json.loads(text)["data"]}
except Exception:
    existing = set()

if "it" not in existing:
    params = urllib.parse.urlencode({
        "namespaceId": "it",
        "namespaceName": "it",
        "namespaceDesc": "integration-test",
    }).encode()
    st, text = http("POST", SERVER + "/nacos/v3/admin/core/namespace", params)
    print("create ->", st, text[:150])
else:
    print("namespace it exists")

st, text = http("GET", SERVER + "/nacos/v3/admin/core/namespace/list")
print("final ->", st, text[:200])
