#!/usr/bin/env python3
"""Generate cross-service traffic through the gateway for SkyWalking verification.
Covers: account->auth (Feign create user), account->statistics (Feign update),
gateway->all services, notification endpoint."""
import json, time, urllib.request, urllib.error, urllib.parse, base64, os

GW = "http://localhost:4000"
ROOT = os.path.dirname(os.path.abspath(__file__))
env = dict(l.strip().split("=", 1) for l in open(os.path.join(ROOT, ".env")) if "=" in l)
ACCT_PW = env["ACCOUNT_SERVICE_PASSWORD"]
PW = "".join(["demo", "12345"])


def req(method, url, data=None, headers=None, raw=None, ctype=None):
    h = dict(headers or {})
    body = None
    if raw is not None:
        body = raw.encode(); h["Content-Type"] = ctype
    elif data is not None:
        body = urllib.parse.urlencode(data).encode(); h["Content-Type"] = "application/x-www-form-urlencoded"
    r = urllib.request.Request(url, data=body, method=method, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:
        return -1, str(e)


probe = "swt-" + str(int(time.time()))[-6:]
st, body = req("POST", GW + "/accounts/", raw=json.dumps({"username": probe, "password": PW}), ctype="application/json")
print("create account %s -> %s" % (probe, st))

st, body = req("POST", GW + "/uaa/oauth2/token", data={
    "grant_type": "password", "client_id": "browser",
    "username": probe, "password": PW, "scope": "ui"})
tok = json.loads(body).get("access_token", "") if st == 200 else ""
print("password grant -> %s, token %d chars" % (st, len(tok)))

auth_h = {"Authorization": "Bearer " + tok}
change = json.dumps({
    "incomes": [{"title": "salary", "amount": 1000, "currency": "EUR", "period": "MONTH", "icon": "w"}],
    "expenses": [{"title": "rent", "amount": 500, "currency": "USD", "period": "MONTH", "icon": "h"}],
    "saving": {"amount": 200, "currency": "USD", "interest": 0.05, "deposit": True, "capitalization": False},
    "note": "sw-trace"})
st, _ = req("PUT", GW + "/accounts/current", raw=change, ctype="application/json", headers=auth_h)
print("PUT /accounts/current (-> statistics Feign) -> %s" % st)

# mixed read traffic across all services
codes = {}
for i in range(15):
    for path in ["/statistics/current", "/accounts/current", "/notifications/recipients/current",
                 "/actuator/health", "/uaa/oauth2/jwks"]:
        s, _ = req("GET", GW + path, headers=auth_h)
        codes[s] = codes.get(s, 0) + 1
print("mixed read traffic done, status distribution:", dict(sorted(codes.items())))

# client_credentials traffic (service-to-service channel)
basic = base64.b64encode(("account-service:" + ACCT_PW).encode()).decode()
st, body = req("POST", GW + "/uaa/oauth2/token",
               data={"grant_type": "client_credentials", "scope": "server"},
               headers={"Authorization": "Basic " + basic})
print("client_credentials -> %s" % st)
print("TRAFFIC_DONE")
