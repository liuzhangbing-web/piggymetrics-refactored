#!/usr/bin/env python3
"""Clean isolated #6 verification: re-PUT the account change, then read statistics
and compare against analytically-recomputed golden values (expenses ALSO normalized
by the MONTH baseRatio, which the original verify-chain.sh got wrong)."""
import json, os, urllib.parse, urllib.request, urllib.error
from decimal import Decimal
from fractions import Fraction

HERE = os.path.dirname(os.path.abspath(__file__))
env = dict(l.strip().split("=", 1) for l in open(os.path.join(HERE, ".env")) if "=" in l)
GW = "http://localhost:14000"
DEMO_PW = env["IT_DEMO_PW"]


def req(method, url, data=None, headers=None, raw_body=None, ctype=None):
    h = dict(headers or {})
    body = None
    if raw_body is not None:
        body = raw_body.encode(); h["Content-Type"] = ctype
    elif data is not None:
        body = urllib.parse.urlencode(data).encode(); h["Content-Type"] = "application/x-www-form-urlencoded"
    r = urllib.request.Request(url, data=body, method=method, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


st, body = req("POST", GW + "/uaa/oauth2/token",
               data={"grant_type": "password", "client_id": "browser",
                     "username": "demo", "password": DEMO_PW, "scope": "ui"})
tok = json.loads(body)["access_token"]

acct = json.dumps({
    "incomes": [{"title": "salary", "amount": 1000, "currency": "EUR", "period": "MONTH", "icon": "wallet"}],
    "expenses": [{"title": "rent", "amount": 500, "currency": "USD", "period": "MONTH", "icon": "home"}],
    "saving": {"amount": 200, "currency": "USD", "interest": 0.05, "deposit": True, "capitalization": False},
    "note": "it-clean"})
st, _ = req("PUT", GW + "/accounts/current", raw_body=acct, ctype="application/json",
            headers={"Authorization": "Bearer " + tok})
print("PUT /accounts/current ->", st)

st, body = req("GET", GW + "/statistics/current", headers={"Authorization": "Bearer " + tok})
dp = json.loads(body)[-1]["statistics"]
print("service DataPoint:", dp)

RATES = {"USD": Fraction(1), "EUR": Fraction(Decimal("0.9")), "RUB": Fraction(Decimal("75.0"))}
MONTH = Fraction(30.4368)


def half_up(frac, scale):
    q = Fraction(10) ** scale; v = frac * q; n, d = v.numerator, v.denominator
    n2 = (2 * n + d) // (2 * d) if v >= 0 else -((2 * (-n) + d) // (2 * d))
    return Fraction(n2, 1) / q


def conv(frm, to, amt):
    return Fraction(Decimal(str(amt))) * half_up(RATES[to] / RATES[frm], 4)


def metric(cur, amt):
    return half_up(conv(cur, "USD", amt) / MONTH, 4)


exp = {
    "INCOMES_AMOUNT": metric("EUR", "1000"),
    "EXPENSES_AMOUNT": metric("USD", "500"),
    "SAVING_AMOUNT": conv("USD", "USD", "200"),
}
ok = True
for k, frac in exp.items():
    e = Decimal(frac.numerator) / Decimal(frac.denominator)
    a = Decimal(dp[k])
    good = abs(e - a) < Decimal("0.0001")
    ok = ok and good
    print(("PASS  " if good else "FAIL  ") + "%s golden=%s service=%s" % (k, e, a))
print("\n#6 money red-line:", "ALL MATCH" if ok else "MISMATCH")
raise SystemExit(0 if ok else 1)
