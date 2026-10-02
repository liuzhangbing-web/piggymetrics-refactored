#!/usr/bin/env python3
"""验证各微服务真实读写自己的 MongoDB 库（数据落地级证明）。

方法：API 写入业务数据 -> 直接 docker exec 进各 mongodb 容器查询对应集合，
证明: auth DB 有用户(BCrypt)、account DB 有账务文档、statistics DB 有 DataPoint、
notification DB 有收件人设置，且 gateway 无任何 DB。
"""
import json, subprocess, time, urllib.request, urllib.error, urllib.parse, os

ROOT = "/Users/liuzhangbing/Downloads/D/ai/piggymetrics-refactored"
GW = "http://localhost:4000"
PW = "".join(["demo", "12345"])
env = dict(l.strip().split("=", 1) for l in open(ROOT + "/.env") if "=" in l)
FAILS = []


def api(method, path, raw=None, ctype="application/json", data=None, headers=None):
    h = dict(headers or {})
    body = raw.encode() if raw is not None else (urllib.parse.urlencode(data).encode() if data else None)
    if body and raw is not None:
        h["Content-Type"] = ctype
    if body and data is not None:
        h["Content-Type"] = "application/x-www-form-urlencoded"
    r = urllib.request.Request(GW + path, data=body, method=method, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:
        return -1, str(e)


def mongo(container, script):
    """Run mongo shell inside the container with auth (creds from container env)."""
    shell = 'mongo --quiet -u user -p "$MONGODB_PASSWORD" --authenticationDatabase piggymetrics piggymetrics --eval ' + "'" + script.replace("'", "\'") + "'"
    out = subprocess.run(["docker", "exec", container, "sh", "-c", shell],
                         capture_output=True, text=True, timeout=30)
    return (out.stdout + out.stderr).strip()


def check(name, cond, detail=""):
    tag = "PASS" if cond else "FAIL"
    print(f"  [{tag}] {name} {detail}")
    if not cond:
        FAILS.append(name)


user = "mongo-verify-" + str(int(time.time()))[-6:]
print(f"=== 测试用户: {user} ===\n")

print("[1] API 写入")
st, _ = api("POST", "/accounts/", raw=json.dumps({"username": user, "password": PW}))
check("开户 POST /accounts/", st == 200, f"HTTP {st}")

st, body = api("POST", "/uaa/oauth2/token", data={
    "grant_type": "password", "client_id": "browser", "username": user, "password": PW, "scope": "ui"})
tok = json.loads(body).get("access_token", "") if st == 200 else ""
check("password grant 取 token", st == 200 and len(tok) > 100, f"HTTP {st}, token {len(tok)}c")
ah = {"Authorization": "Bearer " + tok}

change = json.dumps({
    "incomes": [{"title": "salary", "amount": 1000, "currency": "EUR", "period": "MONTH", "icon": "w"}],
    "expenses": [{"title": "rent", "amount": 500, "currency": "USD", "period": "MONTH", "icon": "h"}],
    "saving": {"amount": 200, "currency": "USD", "interest": 0.05, "deposit": True, "capitalization": False},
    "note": "mongo-verify"})
st, _ = api("PUT", "/accounts/current", raw=change, headers=ah)
check("账务变更 PUT /accounts/current", st == 200, f"HTTP {st}")

st, _ = api("PUT", "/notifications/recipients/current", headers=ah, raw=json.dumps({
    "email": user + "@example.com",
    "scheduledNotifications": {
        "BACKUP": {"active": True, "frequency": "WEEKLY"},
        "REMIND": {"active": True, "frequency": "MONTHLY"}}}))
check("通知设置 PUT recipients", st in (200, 204), f"HTTP {st}")

time.sleep(3)
print("\n[2] 直接查各服务自己的 MongoDB（数据落地证明）")

# auth-mongodb: users collection with bcrypt
out = mongo("piggymetrics-refactored-auth-mongodb-1",
            f'db.users.find({{"_id":"{user}"}},{{password:1}}).forEach(u=>print("AUTHDB "+u._id+" "+u.password.substring(0,7)))')
check("auth-mongodb.users 有该用户且密码为 BCrypt($2a$)", "AUTHDB " + user in out and "$2a$" in out,
      out.splitlines()[-1][:80] if out else "(empty)")

# account-mongodb: account document with note
out = mongo("piggymetrics-refactored-account-mongodb-1",
            f'db.accounts.find({{"_id":"{user}"}},{{note:1,"saving.amount":1}}).forEach(a=>print("ACCTDB "+a._id+" note="+a.note+" saving="+a.saving.amount))')
check("account-mongodb.accounts 有账务文档(note=mongo-verify)", f"ACCTDB {user} note=mongo-verify" in out,
      out.splitlines()[-1][:80] if out else "(empty)")

# statistics-mongodb: datapoint
out = mongo("piggymetrics-refactored-statistics-mongodb-1",
            f'print("STATDB count=" + db.datapoints.countDocuments({{"_id.account":"{user}"}}))')
check("statistics-mongodb.datapoints 有统计数据点", "STATDB count=1" in out or "STATDB count=2" in out,
      out.splitlines()[-1][:60] if out else "(empty)")

# notification-mongodb: recipient
out = mongo("piggymetrics-refactored-notification-mongodb-1",
            f'db.recipients.find({{"_id":"{user}"}},{{email:1}}).forEach(r=>print("NOTIDB "+r._id+" "+r.email))')
check("notification-mongodb.recipients 有收件人设置", f"NOTIDB {user} {user}@example.com" in out,
      out.splitlines()[-1][:70] if out else "(empty)")

# 数据隔离: account-mongodb 里不应有 user 集合数据，auth-mongodb 里不应有 account 数据
out1 = mongo("piggymetrics-refactored-account-mongodb-1", 'print("X user_coll=" + db.users.countDocuments({}))')
out2 = mongo("piggymetrics-refactored-auth-mongodb-1", 'print("X account_coll=" + db.accounts.countDocuments({}))')
check("库间数据隔离（account 库无 user 集合数据 / auth 库无 account 数据）",
      "user_coll=0" in out1 and "account_coll=0" in out2, f"{out1.splitlines()[-1][:30]} / {out2.splitlines()[-1][:30]}")

print("\n[3] gateway 无数据库核验")
pom = open(ROOT + "/gateway/pom.xml").read()
check("gateway pom 无 mongodb 依赖", "data-mongodb" not in pom)

print("\n" + "=" * 50)
if FAILS:
    print(f"MONGO_VERIFY_FAIL: {FAILS}")
    raise SystemExit(1)
print("MONGO_VERIFY_OK 全部服务真实读写各自 MongoDB")
