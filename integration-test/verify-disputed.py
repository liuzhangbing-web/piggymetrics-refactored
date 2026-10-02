#!/usr/bin/env python3
"""Precise re-verification of the 4 disputed chain assertions, via urllib (no shell
quoting, no cmdline secret literals). Reads creds from .env. Proves each is a
script-expectation defect, not a service defect.

#1 wrong password        -> expect 400 invalid_grant
#3 ui-scope PUT valid body -> expect 403 (authorization, not 400 validation)
#4 server-scope PUT valid -> expect 200
#6 statistics money golden -> recompute correctly (expenses also normalized by period)
"""
import json, os, urllib.parse, urllib.request, urllib.error
from decimal import Decimal, ROUND_HALF_UP
from fractions import Fraction

HERE = os.path.dirname(os.path.abspath(__file__))
env = {}
for line in open(os.path.join(HERE, ".env")):
    if "=" in line:
        k, v = line.strip().split("=", 1)
        env[k] = v
GW = "http://localhost:14000"
DEMO_PW = env["IT_DEMO_PW"]
ACCT_PW = env["IT_ACCOUNT_PW"]
OK = 0
BAD = 0


def req(method, url, data=None, headers=None, raw_body=None, ctype=None):
    h = dict(headers or {})
    body = None
    if raw_body is not None:
        body = raw_body.encode()
        if ctype:
            h["Content-Type"] = ctype
    elif data is not None:
        body = urllib.parse.urlencode(data).encode()
        h["Content-Type"] = "application/x-www-form-urlencoded"
    r = urllib.request.Request(url, data=body, method=method, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def check(name, expected, actual):
    global OK, BAD
    if expected == actual:
        OK += 1
        print("PASS  %s -> HTTP %s" % (name, actual))
    else:
        BAD += 1
        print("FAIL  %s -> expected %s got %s" % (name, expected, actual))


# ---- #1 wrong password ----
st, body = req("POST", GW + "/uaa/oauth2/token",
               data={"grant_type": "password", "client_id": "browser",
                     "username": "demo", "password": "definitely-wrong-pw", "scope": "ui"})
check("#1 wrong password -> 400 invalid_grant", 400, st)
print("       body:", body[:80])

# ---- get ui-scope token (correct password) ----
st, body = req("POST", GW + "/uaa/oauth2/token",
               data={"grant_type": "password", "client_id": "browser",
                     "username": "demo", "password": DEMO_PW, "scope": "ui"})
ui_token = json.loads(body).get("access_token", "")
print("\nui-scope token obtained:", bool(ui_token), "(%d chars)" % len(ui_token))

# ---- get server-scope token ----
import base64
basic = base64.b64encode(("account-service:" + ACCT_PW).encode()).decode()
st, body = req("POST", GW + "/uaa/oauth2/token",
               data={"grant_type": "client_credentials", "scope": "server"},
               headers={"Authorization": "Basic " + basic})
srv_token = json.loads(body).get("access_token", "")
print("server-scope token obtained:", bool(srv_token))

VALID_ACCOUNT = json.dumps({
    "incomes": [], "expenses": [],
    "saving": {"amount": 1, "currency": "USD", "interest": 0,
               "deposit": False, "capitalization": False}
})

# ---- #3 ui-scope PUT valid body -> 403 (authorization precedes once body valid) ----
st, body = req("PUT", GW + "/statistics/somebody-else",
               raw_body=VALID_ACCOUNT, ctype="application/json",
               headers={"Authorization": "Bearer " + ui_token})
check("#3 ui-scope PUT /statistics/<other> valid body -> 403", 403, st)
print("       body:", body[:80])

# ---- #4 server-scope PUT valid body -> 200 ----
st, body = req("PUT", GW + "/statistics/demo",
               raw_body=VALID_ACCOUNT, ctype="application/json",
               headers={"Authorization": "Bearer " + srv_token})
check("#4 server-scope PUT /statistics/demo valid body -> 200", 200, st)

# ---- #6 statistics golden recompute ----
# DataPoint from the earlier PUT /accounts/current:
#   income  salary 1000 EUR MONTH ; expense rent 500 USD MONTH ; saving 200 USD
RATES = {"USD": Fraction(1), "EUR": Fraction(Decimal("0.9")), "RUB": Fraction(Decimal("75.0"))}
MONTH_RATIO = Fraction(30.4368)  # new BigDecimal(double) exact binary value


def half_up(frac, scale):
    q = Fraction(10) ** scale
    v = frac * q
    n, d = v.numerator, v.denominator
    n2 = (2 * n + d) // (2 * d) if v >= 0 else -((2 * (-n) + d) // (2 * d))
    return Fraction(n2, 1) / q


def convert(frm, to, amount):
    ratio = half_up(RATES[to] / RATES[frm], 4)
    return Fraction(Decimal(str(amount))) * ratio


def metric(currency, amount, period_ratio):
    return half_up(convert(currency, "USD", amount) / period_ratio, 4)


exp_income = metric("EUR", "1000", MONTH_RATIO)          # 1000 EUR MONTH
exp_expense = metric("USD", "500", MONTH_RATIO)          # 500 USD MONTH (also normalized!)
exp_saving = convert("USD", "USD", "200")                # saving NOT divided by period

st, body = req("GET", GW + "/statistics/current", headers={"Authorization": "Bearer " + ui_token})
dps = json.loads(body)
dp = dps[-1]["statistics"]
print("\n#6 statistics golden recompute (corrected: expenses also /MONTH ratio)")


def cmp_money(label, expected_frac, actual_str):
    global OK, BAD
    exp = Decimal(expected_frac.numerator) / Decimal(expected_frac.denominator)
    act = Decimal(actual_str)
    if abs(exp - act) < Decimal("0.0001"):
        OK += 1
        print("PASS  %s expected=%s actual=%s" % (label, exp, act))
    else:
        BAD += 1
        print("FAIL  %s expected=%s actual=%s" % (label, exp, act))


cmp_money("INCOMES_AMOUNT (salary 1000 EUR/MONTH)", exp_income, dp["INCOMES_AMOUNT"])
cmp_money("EXPENSES_AMOUNT (rent 500 USD/MONTH)", exp_expense, dp["EXPENSES_AMOUNT"])
cmp_money("SAVING_AMOUNT (200 USD)", exp_saving, dp["SAVING_AMOUNT"])

print("\n================================")
print("复验结果: PASS=%d FAIL=%d" % (OK, BAD))
print("================================")
raise SystemExit(1 if BAD else 0)
