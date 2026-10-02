#!/usr/bin/env python3
"""
Sync all piggymetrics-refactored service configs into Nacos 3.0.3.

Usage:
    python3 sync-to-nacos.py [--server http://localhost:8848] [--namespace <id>]
                             [--group PIGGYMETRICS] [--user nacos] [--password ***

Behavior:
  - logs in (v1 auth) when the server has auth enabled; works anonymously otherwise
  - publishes every *.yml in this directory as dataId=<file>.yml, type=yaml
  - idempotent: re-running overwrites content (Nacos keeps history for rollback)
  - reads back every published config and verifies byte-identical content
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def http(method, url, data=None, headers=None):
    req = urllib.request.Request(url, data=data, method=method,
                                 headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def login(server, user, password):
    try:
        body = urllib.parse.urlencode({"username": user, "password": password}).encode()
        status, text = http("POST", f"{server}/nacos/v1/auth/login", data=body)
        token = json.loads(text).get("accessToken")
        if token:
            print(f"[auth] login OK as {user}")
            return token
    except Exception as e:
        print(f"[auth] login failed ({e}); continuing anonymously")
    return None


def publish(server, token, namespace, group, data_id, content):
    params = {"dataId": data_id, "group": group, "content": content, "type": "yaml"}
    if namespace:
        params["tenant"] = namespace
    if token:
        params["accessToken"] = token
    body = urllib.parse.urlencode(params).encode()
    status, text = http("POST", f"{server}/nacos/v1/cs/configs", data=body)
    ok = text.strip() == "true" or status == 200 and "true" in text
    return ok, status, text.strip()


def fetch(server, token, namespace, group, data_id):
    params = {"dataId": data_id, "group": group}
    if namespace:
        params["tenant"] = namespace
    if token:
        params["accessToken"] = token
    url = f"{server}/nacos/v1/cs/configs?{urllib.parse.urlencode(params)}"
    status, text = http("GET", url)
    return text


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default=os.environ.get("NACOS_SERVER", "http://localhost:8848"))
    ap.add_argument("--namespace", default=os.environ.get("NACOS_NAMESPACE", ""))
    ap.add_argument("--group", default=os.environ.get("NACOS_GROUP", "PIGGYMETRICS"))
    ap.add_argument("--user", default=os.environ.get("NACOS_USER", "nacos"))
    ap.add_argument("--password", default=os.environ.get("NACOS_PASSWORD", "nacos"))
    args = ap.parse_args()

    files = sorted(f for f in os.listdir(HERE) if f.endswith(".yml"))
    if not files:
        print("no *.yml files found beside this script")
        return 1

    token = login(args.server, args.user, args.password)

    print(f"\n[publish] server={args.server} namespace={args.namespace or '(public)'} "
          f"group={args.group}")
    failed = []
    for f in files:
        content = open(os.path.join(HERE, f), encoding="utf-8").read()
        ok, status, text = publish(args.server, token, args.namespace, args.group, f, content)
        print(f"  - {f:28s} -> HTTP {status} published={ok}")
        if not ok:
            failed.append((f, status, text))

    print("\n[verify] read-back byte-comparison")
    for f in files:
        local = open(os.path.join(HERE, f), encoding="utf-8").read()
        remote = fetch(args.server, token, args.namespace, args.group, f)
        same = remote == local
        print(f"  - {f:28s} -> identical={same} (remote {len(remote)}B / local {len(local)}B)")
        if not same:
            failed.append((f, "readback-mismatch", ""))

    if failed:
        print(f"\nRESULT: {len(failed)} failure(s): {failed}")
        return 1
    print(f"\nRESULT: all {len(files)} configs published and verified in Nacos")
    return 0


if __name__ == "__main__":
    sys.exit(main())
