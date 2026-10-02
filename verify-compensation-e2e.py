#!/usr/bin/env python3
"""Plan-A 端到端验证：幂等补偿 + 对账自愈（真实容器环境）。

场景1（孤儿用户自愈）：
  - 开户成功 -> 手工删除 account 文档模拟"本地保存失败后的残留态"
  - 手工向 account-mongodb 插入 DELETE_AUTH_USER PENDING 任务（模拟立即补偿也失败的场景）
  - 等待对账 Job（30s 周期）-> 验证 auth 库孤儿用户被删除、任务状态 DONE
  - 验证用户名释放后可重新开户成功（旧行为是永久 401 卡死）

场景2（statistics 漂移自愈）：
  - 停 statistics-service -> PUT /accounts/current（fallback 吞掉，200）
  - 验证 compensation-tasks 集合出现 UPDATE_STATISTICS PENDING 任务（fallback 自动入队）
  - 恢复 statistics-service -> 等对账 Job -> 验证 datapoint 落库、任务 DONE
"""
import json, subprocess, time, urllib.request, urllib.error, urllib.parse, sys

ROOT = "/Users/liuzhangbing/Downloads/D/ai/piggymetrics-refactored"
GW = "http://localhost:4000"
PW = "".join(["demo", "12345"])
FAILS = []
MONGO_ACCT = "piggymetrics-refactored-account-mongodb-1"
MONGO_AUTH = "piggymetrics-refactored-auth-mongodb-1"
MONGO_STAT = "piggymetrics-refactored-statistics-mongodb-1"


def check(name, cond, detail=""):
    print(f"  [{'PASS' if cond else 'FAIL'}] {name} {detail}")
    if not cond:
        FAILS.append(name)


def api(method, path, raw=None, data=None, headers=None):
    h = dict(headers or {})
    body = raw.encode() if raw is not None else (urllib.parse.urlencode(data).encode() if data else None)
    if raw is not None:
        h["Content-Type"] = "application/json"
    elif data is not None:
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
    shell = ('mongo --quiet -u user -p "$MONGODB_PASSWORD" --authenticationDatabase piggymetrics '
             'piggymetrics --eval ' + "'" + script.replace("'", "'\\''") + "'")
    out = subprocess.run(["docker", "exec", container, "sh", "-c", shell],
                         capture_output=True, text=True, timeout=30)
    return (out.stdout + out.stderr).strip()


def token_for(user):
    st, b = api("POST", "/uaa/oauth2/token", data={
        "grant_type": "password", "client_id": "browser", "username": user, "password": PW, "scope": "ui"})
    return json.loads(b).get("access_token", "") if st == 200 else ""


def main():
    ts = str(int(time.time()))[-6:]

    # ================= 场景1：孤儿用户自愈 =================
    print("=== 场景1: 孤儿用户（DELETE_AUTH_USER 对账自愈）===")
    u1 = "orphan-heal-" + ts
    st, _ = api("POST", "/accounts/", raw=json.dumps({"username": u1, "password": PW}))
    check("开户成功", st == 200, f"HTTP {st}")

    # 模拟"auth 用户已建、account 本地保存失败"残留态 + 立即补偿也失败（入队任务）
    mongo(MONGO_ACCT, f'db.accounts.deleteOne({{"_id":"{u1}"}})')
    mongo(MONGO_ACCT, (f'db["compensation-tasks"].insertOne({{type:"DELETE_AUTH_USER",accountName:"{u1}",'
                       f'status:"PENDING",attempts:0,createdAt:new Date()}})'))
    pre = mongo(MONGO_AUTH, f'print(db.users.countDocuments({{_id:"{u1}"}}))')
    check("前置态: auth 库孤儿用户存在", pre.endswith("1"), f"users={pre}")

    print("  等待对账 Job（最长 100s）...")
    done = False
    for i in range(20):
        time.sleep(5)
        n = mongo(MONGO_AUTH, f'print(db.users.countDocuments({{_id:"{u1}"}}))')
        s = mongo(MONGO_ACCT, f'db["compensation-tasks"].find({{accountName:"{u1}"}}).forEach(t=>print(t.status))')
        if n.endswith("0") and "DONE" in s:
            done = True
            break
    check("对账 Job 删除孤儿用户", done, f"auth users left={n}")
    check("任务标记 DONE", "DONE" in s, f"task status={s.strip()}")

    # 用户名已释放：重新开户应成功（旧行为=永久 401）
    st2, _ = api("POST", "/accounts/", raw=json.dumps({"username": u1, "password": PW}))
    check("用户名释放后重新开户成功（旧行为为永久卡死）", st2 == 200, f"HTTP {st2}")

    # ================= 场景2：statistics 漂移自愈 =================
    print("\n=== 场景2: statistics 漂移（UPDATE_STATISTICS 对账自愈）===")
    u2 = "stat-heal-" + ts
    st, _ = api("POST", "/accounts/", raw=json.dumps({"username": u2, "password": PW}))
    check("开户成功", st == 200, f"HTTP {st}")
    tok = token_for(u2)
    ah = {"Authorization": "Bearer " + tok}

    subprocess.run(["docker", "stop", "piggymetrics-refactored-statistics-service-1"],
                   capture_output=True, timeout=60)
    print("  statistics-service 已停止")
    time.sleep(8)  # 等 nacos 摘除实例

    change = json.dumps({
        "incomes": [{"title": "salary", "amount": 1000, "currency": "EUR", "period": "MONTH", "icon": "w"}],
        "expenses": [{"title": "rent", "amount": 500, "currency": "USD", "period": "MONTH", "icon": "h"}],
        "saving": {"amount": 200, "currency": "USD", "interest": 0.05, "deposit": True, "capitalization": False},
        "note": "stat-heal"})
    st, _ = api("PUT", "/accounts/current", raw=change, headers=ah)
    check("statistics 宕机时账务 PUT 仍 200（fallback 保可用性，legacy 语义）", st == 200, f"HTTP {st}")

    time.sleep(3)
    q = mongo(MONGO_ACCT, f'print(db["compensation-tasks"].countDocuments({{accountName:"{u2}",type:"UPDATE_STATISTICS",status:"PENDING"}}))')
    check("fallback 自动入队 UPDATE_STATISTICS 任务", q.endswith("1"), f"pending={q}")
    dp0 = mongo(MONGO_STAT, f'print(db.datapoints.countDocuments({{"_id.account":"{u2}"}}))')
    check("statistics 库暂无 datapoint（漂移态确认）", dp0.endswith("0"), f"datapoints={dp0}")

    subprocess.run(["docker", "start", "piggymetrics-refactored-statistics-service-1"],
                   capture_output=True, timeout=60)
    print("  statistics-service 已恢复，等待注册+对账 Job（最长 150s）...")
    healed = False
    for i in range(30):
        time.sleep(5)
        dp = mongo(MONGO_STAT, f'print(db.datapoints.countDocuments({{"_id.account":"{u2}"}}))')
        if dp.endswith("1"):
            healed = True
            break
    check("对账 Job 重算 datapoint 落库（漂移自愈）", healed, f"datapoints={dp}")
    s2 = mongo(MONGO_ACCT, f'db["compensation-tasks"].find({{accountName:"{u2}",type:"UPDATE_STATISTICS"}}).forEach(t=>print(t.status))')
    check("UPDATE_STATISTICS 任务 DONE", "DONE" in s2, f"status={s2.strip()}")

    print("\n" + "=" * 56)
    if FAILS:
        print(f"COMPENSATION_E2E_FAIL: {FAILS}")
        sys.exit(1)
    print("COMPENSATION_E2E_OK 方案A 幂等补偿+对账自愈 端到端验证通过")


if __name__ == "__main__":
    main()
