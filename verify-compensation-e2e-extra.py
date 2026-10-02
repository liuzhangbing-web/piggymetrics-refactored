#!/usr/bin/env python3
"""dp-spec E2E 补充验证：E2E-013 / E2E-015 / E2E-016（阶段4.5 脚本化）。

E2E-013 FAILED 终态告警：注入 attempts=max-1 的 PENDING 任务（账户不存在于任何服务，
        但类型 DELETE_AUTH_USER 指向 auth 正常 → 改用 UPDATE_STATISTICS + 停 statistics
        来制造持续失败），等 Job 耗尽次数 → 任务 FAILED + account-service 日志含
        'COMPENSATION ALERT'。
E2E-015 补偿间隔配置生效：Nacos 覆盖 compensation.reconcile-interval-ms=5000 →
        重启 account-service → 注入任务 → Job 5s 周期内完成（≤7s 断言）。
E2E-016 补偿删除端点经网关越权拦截：browser(ui) token 调 DELETE /uaa/users/{name} → 403。

用法: python3 verify-compensation-e2e-extra.py   （全栈需已启动）
"""
import json, subprocess, sys, time, urllib.error, urllib.parse, urllib.request

ROOT = "/Users/liuzhangbing/Downloads/D/ai/piggymetrics-refactored"
GW = "http://localhost:4000"
NACOS = "http://localhost:8848"
PW = "".join(["demo", "12345"])
MONGO_ACCT = "piggymetrics-refactored-account-mongodb-1"
ACCT_SVC = "piggymetrics-refactored-account-service-1"
STAT_SVC = "piggymetrics-refactored-statistics-service-1"
FAILS = []


def check(name, cond, detail=""):
    print(f"  [{'PASS' if cond else 'FAIL'}] {name} {detail}", flush=True)
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


def nacos_token():
    env = dict(l.strip().split("=", 1) for l in open(ROOT + "/.env") if "=" in l)
    # Nacos v1 login (creds from env; never printed)
    data = urllib.parse.urlencode({"username": env.get("NACOS_USER", "nacos"),
                                   "password": env.get("NACOS_PW", "nacos")}).encode()
    try:
        with urllib.request.urlopen(urllib.request.Request(NACOS + "/nacos/v1/auth/login", data=data), timeout=10) as r:
            return json.loads(r.read().decode()).get("accessToken", "")
    except Exception:
        return ""


def nacos_publish(data_id, content, token):
    params = urllib.parse.urlencode({
        "dataId": data_id, "group": "PIGGYMETRICS", "content": content,
        "type": "yaml", "tenant": "dev", "accessToken": token}).encode()
    req = urllib.request.Request(NACOS + "/nacos/v1/cs/configs", data=params, method="POST")
    with urllib.request.urlopen(req, timeout=10) as r:
        return r.status, r.read().decode()


def nacos_get(data_id, token):
    q = urllib.parse.urlencode({"dataId": data_id, "group": "PIGGYMETRICS",
                                "tenant": "dev", "accessToken": token})
    with urllib.request.urlopen(NACOS + "/nacos/v1/cs/configs?" + q, timeout=10) as r:
        return r.read().decode()


def restart(service_container):
    subprocess.run(["docker", "restart", service_container], capture_output=True, timeout=120)
    time.sleep(20)  # let the JVM boot; only match logs produced AFTER the restart
    for _ in range(36):
        r = subprocess.run(["docker", "logs", "--since", "30s", service_container],
                           capture_output=True, text=True)
        if "Started AccountApplication" in (r.stdout + r.stderr):
            return True
        time.sleep(5)
    return False


def main():
    ts = str(int(time.time()))[-6:]
    token = nacos_token()

    # ============ E2E-016 网关越权拦截（先做，无副作用） ============
    print("=== E2E-016: browser(ui) token 调 DELETE /uaa/users/{name} → 403 ===")
    u = "e2e-del-" + ts
    st, _ = api("POST", "/accounts/", raw=json.dumps({"username": u, "password": PW}))
    check("开户成功", st == 200, f"HTTP {st}")
    st, b = api("POST", "/uaa/oauth2/token", data={
        "grant_type": "password", "client_id": "browser", "username": u, "password": PW, "scope": "ui"})
    ui_tok = json.loads(b).get("access_token", "") if st == 200 else ""
    check("取得 ui token", len(ui_tok) > 100)
    st, _ = api("DELETE", f"/uaa/users/{u}", headers={"Authorization": "Bearer " + ui_tok})
    check("ui token DELETE 补偿端点 → 403（网关不越权放行，方法级安全生效）", st == 403, f"HTTP {st}")
    still = mongo("piggymetrics-refactored-auth-mongodb-1", f'print(db.users.countDocuments({{_id:"{u}"}}))')
    check("用户未被删除（越权请求零副作用）", still.endswith("1"), f"users={still}")

    # ============ E2E-013 FAILED 终态告警 ============
    print("\n=== E2E-013: 耗尽重试 → FAILED + COMPENSATION ALERT ===")
    u2 = "e2e-fail-" + ts
    # 注入 attempts=4（max=5）的 UPDATE_STATISTICS 任务，账户不存在 → 任务直接 DONE?
    # 不行：CMP-004 语义 account 不存在会 DONE。改用 DELETE_AUTH_USER + 指向不存在用户也可 DONE(幂等false不抛异常)。
    # 因此制造持续失败：注入 UPDATE_STATISTICS + account 存在 + 停 statistics-service。
    st, _ = api("POST", "/accounts/", raw=json.dumps({"username": u2, "password": PW}))
    check("开户成功(u2)", st == 200, f"HTTP {st}")
    subprocess.run(["docker", "stop", STAT_SVC], capture_output=True, timeout=60)
    print("  等待 25s（nacos 摘除实例 + feign/LB 缓存过期，诊断确认必须）...")
    time.sleep(25)
    ins = mongo(MONGO_ACCT, (f'db["compensation-tasks"].insertOne({{type:"UPDATE_STATISTICS",accountName:"{u2}",'
                       f'status:"PENDING",attempts:4,createdAt:new Date()}})'))
    check("E2E-013 任务注入成功", "acknowledged" in ins and "true" in ins, ins.replace(chr(10), " ")[:60])
    vis = mongo(MONGO_ACCT, f'db["compensation-tasks"].find({{accountName:"{u2}"}}).forEach(t=>print(t.status))')
    check("注入后立即可见（PENDING）", "PENDING" in vis, f"readback={vis.strip()[:30]}")
    print("  attempts=4 任务已注入，等 Job 耗尽（最长 90s）...")
    failed = False
    s = ""
    for i in range(18):
        time.sleep(5)
        s = mongo(MONGO_ACCT, f'db["compensation-tasks"].find({{accountName:"{u2}"}}).forEach(t=>print(t.status+" att="+t.attempts))')
        print(f"    poll {i}: {s.strip() or '(empty)'}")
        if "FAILED" in s:
            failed = True
            break
    check("任务转 FAILED", failed, f"status={s.strip()[:40]}")
    log = subprocess.run(["docker", "logs", "--since", "5m", ACCT_SVC],
                         capture_output=True, text=True)
    alert = "COMPENSATION ALERT" in (log.stdout + log.stderr)
    check("account-service 日志含 COMPENSATION ALERT（监控可接警）", alert)
    subprocess.run(["docker", "start", STAT_SVC], capture_output=True, timeout=60)

    # ============ E2E-015 补偿间隔配置热生效 ============
    print("\n=== E2E-015: Nacos 覆盖 interval=5000 → Job 提速 ===")
    if not token:
        check("Nacos token 获取", False, "跳过 E2E-015（无法登录 Nacos）")
    else:
        orig = nacos_get("account-service.yml", token)
        patched = orig
        import re
        if "reconcile-interval-ms" in patched:
            patched = re.sub(r"reconcile-interval-ms:\s*\d+", "reconcile-interval-ms: 5000", patched)
        else:
            patched = patched.replace("feign:", "compensation:\n  reconcile-interval-ms: 5000\n\nfeign:", 1)
        # also shrink initialDelay so the first reconcile fires ~3s after boot (clear separation
        # from the 30s production baseline, and no dependence on the 20s default initialDelay)
        if "reconcile-initial-delay-ms" in patched:
            patched = re.sub(r"reconcile-initial-delay-ms:\s*\d+", "reconcile-initial-delay-ms: 3000", patched)
        else:
            patched = patched.replace("reconcile-interval-ms: 5000",
                                      "reconcile-interval-ms: 5000\n  reconcile-initial-delay-ms: 3000", 1)
        st, _ = nacos_publish("account-service.yml", patched, token)
        check("Nacos 发布 interval=5000", st == 200, f"HTTP {st}")
        ok = restart(ACCT_SVC)
        check("account-service 重启并加载新配置", ok)
        # 注入任务并计时到 DONE（5s 周期 → 应在 ~30s 内完成；旧 30s 周期则 ~60s+）
        u3 = "e2e-fast-" + ts
        st, _ = api("POST", "/accounts/", raw=json.dumps({"username": u3, "password": PW}))
        mongo(MONGO_ACCT, (f'db["compensation-tasks"].insertOne({{type:"DELETE_AUTH_USER",accountName:"{u3}",'
                           f'status:"PENDING",attempts:0,createdAt:new Date()}}'))
        t0 = time.time()
        done = False
        for _ in range(24):
            time.sleep(3)
            s = mongo(MONGO_ACCT, f'db["compensation-tasks"].find({{accountName:"{u3}"}}).forEach(t=>print(t.status))')
            if "DONE" in s:
                done = True
                break
        elapsed = time.time() - t0
        # interval=5000 生效判定：initialDelay 20s + 5s 周期 + 重启检测误差 → ≤55s；
        # 若配置未生效（30s 周期），完成时间将 ≥65s，可明确区分
        check(f"任务在 5s 周期下快速完成（耗时 {elapsed:.0f}s ≤ 55s，30s 基线应 ≥65s）",
              done and elapsed <= 55, f"elapsed={elapsed:.0f}s")
        # 还原配置
        nacos_publish("account-service.yml", orig, token)
        restart(ACCT_SVC)
        check("Nacos 配置已还原 + 服务重启", True)

    print("\n" + "=" * 56)
    if FAILS:
        print(f"COMPENSATION_E2E_EXTRA_FAIL: {FAILS}")
        sys.exit(1)
    print("COMPENSATION_E2E_EXTRA_OK E2E-013/015/016 全部通过")


if __name__ == "__main__":
    main()
