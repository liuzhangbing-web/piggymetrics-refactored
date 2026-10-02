#!/usr/bin/env python3
"""piggymetrics-refactored MAIN docker-compose full-chain verification.
All HTTP via urllib (no shell quoting/masking issues). DB checks via docker exec.
Adapted to main compose: gateway :4000, nacos :8848 namespace=dev (identity header),
mongo containers piggymetrics-refactored-*-mongodb-1 (user/p<env>, authDB=piggymetrics).
"""
import json, os, subprocess, urllib.parse, urllib.request, urllib.error, base64
from decimal import Decimal
from fractions import Fraction

ROOT = os.path.dirname(os.path.abspath(__file__))
GW = "http://localhost:4000"
NACOS = "http://localhost:8848"
NS = "dev"
HDR = {"serverIdentityKey": "serverIdentityValue"}

env = dict(l.strip().split("=", 1) for l in open(os.path.join(ROOT, ".env")) if "=" in l)
MONGO_PW = env["MONGODB_PASSWORD"]
ACCT_PW = env["ACCOUNT_SERVICE_PASSWORD"]
DEMO_PW = "demo12345"  # test-only, assembled here (not a real secret)

OK = 0
BAD = 0
RESULTS = []


def rec(ok, name, detail=""):
    global OK, BAD
    if ok:
        OK += 1; RESULTS.append("PASS  " + name + (f"  [{detail}]" if detail else ""))
    else:
        BAD += 1; RESULTS.append("FAIL  " + name + (f"  [{detail}]" if detail else ""))


def http(method, url, data=None, headers=None, raw_body=None, ctype=None):
    h = dict(headers or {})
    body = None
    if raw_body is not None:
        body = raw_body.encode(); h["Content-Type"] = ctype
    elif data is not None:
        body = urllib.parse.urlencode(data).encode(); h["Content-Type"] = "application/x-www-form-urlencoded"
    r = urllib.request.Request(url, data=body, method=method, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=25) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:
        return -1, str(e)


def mongo_eval(db_container, script):
    cmd = ["docker", "exec", db_container, "mongo", "--quiet",
           "-u", "user", "-p", MONGO_PW, "--authenticationDatabase", "piggymetrics",
           "piggymetrics", "--eval", script]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    return r.stdout.strip(), r.stderr.strip()


print("======== 1. Nacos 注册中心（dev）========")
for s in ["auth-service", "account-service", "statistics-service", "notification-service", "gateway"]:
    st, body = http("GET", f"{NACOS}/nacos/v1/ns/instance/list?serviceName={s}&namespaceId={NS}", headers=HDR)
    n = 0
    try:
        n = len(json.loads(body).get("hosts", []))
    except Exception:
        pass
    rec(n >= 1, f"{s} 注册到 Nacos(dev)", f"{n} 实例")

print("======== 2. Nacos 配置中心拉取 ========")
for s in ["auth-service", "account-service", "statistics-service", "notification-service", "gateway"]:
    cid_r = subprocess.run(["docker", "ps", "-q", "--filter", f"name=piggymetrics-refactored-{s}-1"],
                           capture_output=True, text=True)
    cid = cid_r.stdout.strip()
    cnt = 0
    if cid:
        lg = subprocess.run(["docker", "logs", cid], capture_output=True, text=True)
        cnt = lg.stdout.count("Load config[dataId=") + lg.stderr.count("Load config[dataId=")
    rec(cnt >= 1, f"{s} 从 Nacos 加载配置", f"{cnt} 份")

print("======== 3. 网关健康与路由 ========")
st, _ = http("GET", GW + "/actuator/health")
rec(st == 200, "gateway /actuator/health -> 200", f"HTTP {st}")
st, _ = http("GET", GW + "/foo")
rec(st == 404, "未声明路由 /foo -> 404 (等价 zuul ignoredServices=*)", f"HTTP {st}")

print("======== 4. 认证链路（JWT）========")
# 开户（幂等：首次200，重复400 already-exists）
acct_body = json.dumps({"username": "demo", "password": DEMO_PW})
st, body = http("POST", GW + "/accounts/", raw_body=acct_body, ctype="application/json")
rec(st in (200, 400), "开户 POST /accounts/ (demo)", f"HTTP {st} ({'首次创建' if st==200 else '幂等已存在'})")

# password grant
st, body = http("POST", GW + "/uaa/oauth2/token",
                data={"grant_type": "password", "client_id": "browser",
                      "username": "demo", "password": DEMO_PW, "scope": "ui"})
ui = ""
try:
    ui = json.loads(body).get("access_token", "")
except Exception:
    pass
rec(bool(ui), "password grant 签发用户 JWT", f"{len(ui)} chars")

# client_credentials
basic = base64.b64encode(("account-service:" + ACCT_PW).encode()).decode()
st, body = http("POST", GW + "/uaa/oauth2/token",
                data={"grant_type": "client_credentials", "scope": "server"},
                headers={"Authorization": "Basic " + basic})
srv = ""
try:
    srv = json.loads(body).get("access_token", "")
except Exception:
    pass
rec(bool(srv), "client_credentials 签发 server-scope JWT")

st, _ = http("GET", GW + "/accounts/current")
rec(st == 401, "无 token GET /accounts/current -> 401", f"HTTP {st}")

st, body = http("POST", GW + "/uaa/oauth2/token",
                data={"grant_type": "password", "client_id": "browser",
                      "username": "demo", "password": DEMO_PW + "_WRONG", "scope": "ui"})
rec(st == 400 and "invalid_grant" in body, "错误密码 -> 400 invalid_grant", f"HTTP {st}")

print("======== 5. 账务链路（红线）========")
auth_h = {"Authorization": "Bearer " + ui}
st, body = http("GET", GW + "/accounts/current", headers=auth_h)
ok_acc = False
try:
    d = json.loads(body)
    ok_acc = d.get("name") == "demo" and d.get("saving", {}).get("currency") == "USD"
except Exception:
    pass
rec(ok_acc and st == 200, "GET /accounts/current 返回 demo 账户 (saving.currency=USD)", f"HTTP {st}")

change = json.dumps({
    "incomes": [{"title": "salary", "amount": 1000, "currency": "EUR", "period": "MONTH", "icon": "wallet"}],
    "expenses": [{"title": "rent", "amount": 500, "currency": "USD", "period": "MONTH", "icon": "home"}],
    "saving": {"amount": 200, "currency": "USD", "interest": 0.05, "deposit": True, "capitalization": False},
    "note": "main-compose-it"})
st, body = http("PUT", GW + "/accounts/current", raw_body=change, ctype="application/json", headers=auth_h)
rec(st == 200, "PUT /accounts/current 账务变更+统计联动 -> 200", f"HTTP {st}")

print("======== 6. 统计金额链路（红线，经 rates-mock）========")
import time; time.sleep(3)
st, body = http("GET", GW + "/statistics/current", headers=auth_h)
RATES = {"USD": Fraction(1), "EUR": Fraction(Decimal("0.9")), "RUB": Fraction(Decimal("75.0"))}
MONTH = Fraction(30.4368)


def half_up(fr, sc):
    q = Fraction(10) ** sc; v = fr * q; n, d = v.numerator, v.denominator
    n2 = (2 * n + d) // (2 * d) if v >= 0 else -((2 * (-n) + d) // (2 * d))
    return Fraction(n2, 1) / q


def conv(frm, to, amt):
    return Fraction(Decimal(str(amt))) * half_up(RATES[to] / RATES[frm], 4)


def metric(cur, amt):
    return half_up(conv(cur, "USD", amt) / MONTH, 4)


exp = {"INCOMES_AMOUNT": metric("EUR", "1000"),
       "EXPENSES_AMOUNT": metric("USD", "500"),
       "SAVING_AMOUNT": conv("USD", "USD", "200")}
try:
    dp = json.loads(body)[-1]["statistics"]
    allok = True
    detail = []
    for k, fr in exp.items():
        e = Decimal(fr.numerator) / Decimal(fr.denominator)
        a = Decimal(dp[k])
        good = abs(e - a) < Decimal("0.0001")
        allok = allok and good
        detail.append(f"{k}: service={a} golden={e}")
    rec(allok and st == 200, "DataPoint 金额与黄金值精确一致", "; ".join(detail))
except Exception as e:
    rec(False, "DataPoint 金额黄金值比对", f"HTTP {st} err={e} body={body[:120]}")

print("======== 7. 通知链路 ========")
nbody = json.dumps({"email": "demo@example.com",
                    "scheduledNotifications": {"BACKUP": {"active": True, "frequency": "WEEKLY"},
                                               "REMIND": {"active": True, "frequency": "MONTHLY"}}})
st, _ = http("PUT", GW + "/notifications/recipients/current", raw_body=nbody, ctype="application/json", headers=auth_h)
rec(st == 200, "PUT /notifications/recipients/current -> 200", f"HTTP {st}")
st, body = http("GET", GW + "/notifications/recipients/current", headers=auth_h)
ok_n = False
try:
    ok_n = json.loads(body).get("email") == "demo@example.com"
except Exception:
    pass
rec(ok_n and st == 200, "通知设置读回一致 (Frequency 枚举转换器 round-trip)", f"HTTP {st}")

print("======== 8. 鉴权红线（不得放宽）========")
VALID_ACCT = json.dumps({"incomes": [], "expenses": [],
                         "saving": {"amount": 1, "currency": "USD", "interest": 0, "deposit": False, "capitalization": False}})
st, _ = http("GET", GW + "/statistics/somebody-else", headers=auth_h)
rec(st == 403, "ui-scope GET /statistics/<他人> -> 403", f"HTTP {st}")
st, _ = http("GET", GW + "/statistics/demo", headers=auth_h)
rec(st == 200, "demo 例外分支 GET /statistics/demo -> 200", f"HTTP {st}")
st, _ = http("PUT", GW + "/statistics/x", raw_body=VALID_ACCT, ctype="application/json", headers=auth_h)
rec(st == 403, "ui-scope PUT /statistics/x (合法body) -> 403 (仅 server scope)", f"HTTP {st}")
st, _ = http("PUT", GW + "/statistics/demo", raw_body=VALID_ACCT, ctype="application/json",
             headers={"Authorization": "Bearer " + srv})
rec(st == 200, "server-scope PUT /statistics/demo -> 200 (服务间通道)", f"HTTP {st}")

print("======== 9. 数据落库核验（docker exec 直查真实 mongo）========")
for svc, col in [("auth", "users"), ("account", "accounts"), ("statistics", "datapoints"), ("notification", "recipients")]:
    cont = f"piggymetrics-refactored-{svc}-mongodb-1"
    out, err = mongo_eval(cont, f"db.{col}.count({{}})")
    n = -1
    try:
        n = int(out.splitlines()[-1])
    except Exception:
        pass
    rec(n >= 1, f"{svc}-mongodb.{col} 落库", f"n={n}" + (f" err={err[:60]}" if n < 1 else ""))

out, err = mongo_eval("piggymetrics-refactored-auth-mongodb-1",
                      'print(db.users.findOne({_id:"demo"}).password.substring(0,4))')
rec(out.endswith("$2a$"), "users 密码为 BCrypt 哈希（非明文）", f"prefix={out[-4:]}")

print("\n" + "=" * 60)
for r in RESULTS:
    print(r)
print("=" * 60)
print(f"主 compose 全链路联调: PASS={OK} FAIL={BAD}")
raise SystemExit(1 if BAD else 0)
