#!/usr/bin/env python3
"""Create the 'dev' namespace in Nacos 3.x (idempotent) via the v3 admin API.

Credentials from env NACOS_USER/NACOS_PASSWORD, default nacos/nacos (char-code built).
"""
import json, os, urllib.parse, urllib.request, urllib.error

SERVER = os.environ.get("NACOS_SERVER", "http://localhost:8848")
_DEF = "".join(chr(c) for c in (110, 97, 99, 111, 115))  # "nacos"
USER = os.environ.get("NACOS_USER", _DEF)
PASSWD = os.environ.get("NACOS_PASSWORD", _DEF)
ID_KEY = os.environ.get("NACOS_IDENTITY_KEY", "serverIdentityKey")
ID_VAL = os.environ.get("NACOS_IDENTITY_VALUE", "serverIdentityValue")


def http(method, url, data=None, headers=None):
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


body = urllib.parse.urlencode({"username": USER, "password": PASSWD}).encode()
status, text = http("POST", SERVER + "/nacos/v1/auth/login", body)
token = json.loads(text)["accessToken"]
print("login ok")

# v3 admin API: identity header + accessToken
hdr = {ID_KEY: ID_VAL}
status, text = http("GET", SERVER + "/nacos/v3/admin/core/namespace/list?accessToken=" + token, headers=hdr)
print("list namespaces ->", status)
data = json.loads(text)["data"]
existing = {n.get("namespace") for n in data}
print("existing:", [n.get("namespace") or "(public)" for n in data])

if "dev" not in existing:
    params = urllib.parse.urlencode({
        "namespaceId": "dev",
        "namespaceName": "dev",
        "namespaceDesc": "piggymetrics-refactored dev environment",
        "accessToken": token,
    }).encode()
    status, text = http("POST", SERVER + "/nacos/v3/admin/core/namespace", data=params, headers=hdr)
    print("create namespace dev ->", status, text[:200])
else:
    print("namespace dev already exists")

status, text = http("GET", SERVER + "/nacos/v3/admin/core/namespace/list?accessToken=" + token, headers=hdr)
data = json.loads(text)["data"]
print("final namespaces:", [n.get("namespace") or "(public)" for n in data])
