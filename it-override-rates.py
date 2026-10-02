#!/usr/bin/env python3
"""IT-only: republish statistics-service.yml into Nacos dev with rates.url -> http://rates-mock
(D16: the real exchangeratesapi.io is dead; rates-mock provides the legacy response shape).
Does NOT touch the committed sync template. Then the caller restarts statistics-service so the
Feign client (url=${rates.url}) is rebuilt with the live source."""
import os, urllib.parse, urllib.request, urllib.error

SERVER = "http://localhost:8848"
ID_KEY, ID_VAL = "serverIdentityKey", "serverIdentityValue"
SYNC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "nacos-config", "sync", "statistics-service.yml")


def http(method, url, data=None):
    h = {ID_KEY: ID_VAL}
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


content = open(SYNC, encoding="utf-8").read()
assert "\u2026" not in content and "***" not in content, "template corrupted"
# swap the rates url for the IT mock (single line)
lines = content.splitlines()
for i, l in enumerate(lines):
    if l.strip().startswith("url:") and "exchangeratesapi" in l:
        indent = l[:len(l) - len(l.lstrip())]
        lines[i] = indent + "url: http://rates-mock   # IT override: live mock (D16 real source dead)"
content = "\n".join(lines) + "\n"

params = urllib.parse.urlencode({
    "dataId": "statistics-service.yml", "group": "PIGGYMETRICS",
    "content": content, "type": "yaml", "tenant": "dev",
}).encode()
st, text = http("POST", SERVER + "/nacos/v1/cs/configs", params)
print("publish ->", st, text.strip()[:20])

q = urllib.parse.urlencode({"dataId": "statistics-service.yml", "group": "PIGGYMETRICS", "tenant": "dev"})
_, back = http("GET", SERVER + "/nacos/v1/cs/configs?" + q)
print("readback rates.url ->", [l.strip() for l in back.splitlines() if "rates-mock" in l])
