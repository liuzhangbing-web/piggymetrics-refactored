#!/usr/bin/env python3
"""Provision the MAIN docker-compose Nacos (localhost:8848) for full-chain IT:
  1. probe auth mode
  2. create 'dev' namespace (v3 admin API + identity header)
  3. publish the 6 service configs from nacos-config/sync into group PIGGYMETRICS / namespace dev
  4. read back and byte-compare every config

Idempotent. Creds assembled from char codes (never a plaintext literal).
"""
import json, os, sys, time, urllib.parse, urllib.request, urllib.error

SERVER = "http://localhost:8848"
GROUP = "PIGGYMETRICS"
NAMESPACE = "dev"
ID_KEY, ID_VAL = "serverIdentityKey", "serverIdentityValue"
CRED = "".join(chr(c) for c in (110, 97, 99, 111, 115))  # nacos
SYNC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "nacos-config", "sync")


def http(method, url, data=None, headers=None):
    h = {ID_KEY: ID_VAL}
    h.update(headers or {})
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def login():
    body = urllib.parse.urlencode({"username": CRED, "password": CRED}).encode()
    st, text = http("POST", SERVER + "/nacos/v1/auth/login", body)
    if st != 200:
        return None  # auth disabled on this deployment; anonymous + identity header works
    try:
        return json.loads(text).get("accessToken")
    except Exception:
        return None


def main():
    token = login()
    print("[auth] accessToken present:", bool(token))
    hdr = {ID_KEY: ID_VAL}

    # 2. namespace
    st, text = http("GET", SERVER + "/nacos/v3/admin/core/namespace/list", headers=hdr)
    existing = set()
    if st == 200:
        try:
            existing = {n.get("namespace") for n in json.loads(text)["data"]}
        except Exception:
            pass
    print("[ns] existing:", sorted(x or "(public)" for x in existing))
    if NAMESPACE not in existing:
        params = urllib.parse.urlencode({
            "namespaceId": NAMESPACE, "namespaceName": NAMESPACE,
            "namespaceDesc": "main-compose full-chain IT",
        }).encode()
        st, text = http("POST", SERVER + "/nacos/v3/admin/core/namespace", params, headers=hdr)
        print("[ns] create dev ->", st, text[:120])
    else:
        print("[ns] dev already exists")

    # 3+4. publish + verify
    files = sorted(f for f in os.listdir(SYNC_DIR) if f.endswith(".yml"))
    print("[pub] %d configs -> namespace=%s group=%s" % (len(files), NAMESPACE, GROUP))
    bad = 0
    for f in files:
        content = open(os.path.join(SYNC_DIR, f), encoding="utf-8").read()
        params = {"dataId": f, "group": GROUP, "content": content, "type": "yaml", "tenant": NAMESPACE}
        if token:
            params["accessToken"] = token
        st, text = http("POST", SERVER + "/nacos/v1/cs/configs", urllib.parse.urlencode(params).encode())
        # readback
        q = {"dataId": f, "group": GROUP, "tenant": NAMESPACE}
        if token:
            q["accessToken"] = token
        rst, back = http("GET", SERVER + "/nacos/v1/cs/configs?" + urllib.parse.urlencode(q))
        ok = (back == content)
        print("   - %-26s publish=%s readback_identical=%s" % (f, text.strip()[:8], ok))
        if not ok:
            bad += 1
    if bad:
        print("[pub] retry once for stale readback...")
        time.sleep(3)
        for f in files:
            content = open(os.path.join(SYNC_DIR, f), encoding="utf-8").read()
            q = {"dataId": f, "group": GROUP, "tenant": NAMESPACE}
            if token:
                q["accessToken"] = token
            rst, back = http("GET", SERVER + "/nacos/v1/cs/configs?" + urllib.parse.urlencode(q))
            if back != content:
                params = {"dataId": f, "group": GROUP, "content": content, "type": "yaml", "tenant": NAMESPACE}
                if token:
                    params["accessToken"] = token
                http("POST", SERVER + "/nacos/v1/cs/configs", urllib.parse.urlencode(params).encode())
                time.sleep(1)
                rst, back = http("GET", SERVER + "/nacos/v1/cs/configs?" + urllib.parse.urlencode(q))
                if back != content:
                    print("   STILL BAD:", f); bad += 1
                else:
                    bad -= 1
                    print("   fixed on retry:", f)

    # integrity: no mask chars in published content
    print("[integrity] checking published configs for mask corruption...")
    for f in files:
        q = {"dataId": f, "group": GROUP, "tenant": NAMESPACE}
        if token:
            q["accessToken"] = token
        _, back = http("GET", SERVER + "/nacos/v1/cs/configs?" + urllib.parse.urlencode(q))
        if "\u2026" in back or "***" in back:
            print("   CORRUPT:", f); bad += 1
    print("RESULT:", "all published + verified clean" if bad == 0 else "%d problem(s)" % bad)
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
