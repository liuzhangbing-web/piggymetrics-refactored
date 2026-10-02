#!/usr/bin/env python3
"""Publish the 6 service configs into the integration-test Nacos (it namespace, no auth).
Reads the same yml files used for the dev sync. Idempotent; verifies readback.
Run AFTER the compose nacos service is healthy, BEFORE starting the app services.
"""
import os, sys, urllib.parse, urllib.request, urllib.error

SERVER = os.environ.get("IT_NACOS", "http://localhost:18848")
NAMESPACE = os.environ.get("IT_NAMESPACE", "it")
GROUP = "PIGGYMETRICS"
HERE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "nacos-config", "sync")


def http(method, url, data=None):
    req = urllib.request.Request(url, data=data, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def wait_ready():
    for _ in range(60):
        st, _ = http("GET", SERVER + "/nacos/v1/console/health/readiness")
        if st == 200:
            return True
        st, _ = http("GET", SERVER + "/nacos/v3/admin/core/state")
        if st == 200:
            return True
        import time; time.sleep(3)
    return False


def publish(data_id, content):
    params = urllib.parse.urlencode({
        "dataId": data_id, "group": GROUP, "content": content,
        "type": "yaml", "tenant": NAMESPACE,
    }).encode()
    st, text = http("POST", SERVER + "/nacos/v1/cs/configs", params)
    return st, text.strip()


def fetch(data_id):
    q = urllib.parse.urlencode({"dataId": data_id, "group": GROUP, "tenant": NAMESPACE})
    st, text = http("GET", SERVER + "/nacos/v1/cs/configs?" + q)
    return text if st == 200 else None


def main():
    if not wait_ready():
        print("nacos not ready at", SERVER); return 1
    files = sorted(f for f in os.listdir(HERE) if f.endswith(".yml"))
    print("publishing", len(files), "configs to", SERVER, "namespace", NAMESPACE, "group", GROUP)
    bad = 0
    for f in files:
        content = open(os.path.join(HERE, f), encoding="utf-8").read()
        st, text = publish(f, content)
        back = fetch(f)
        ok = (back == content)
        print("  - %-26s publish=%s readback_identical=%s" % (f, text, ok))
        if not ok:
            bad += 1
    print("RESULT:", "all published+verified" if bad == 0 else "%d problem(s)" % bad)
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
