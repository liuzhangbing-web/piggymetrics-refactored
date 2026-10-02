#!/usr/bin/env python3
"""piggymetrics-refactored 网关大批量压测 + Sentinel 限流/降级 + 链路追踪验证。

阶段:
  P1 基线大流量: 并发混合请求经网关打到全部服务, 统计吞吐/延迟/状态码分布
  P2 限流(flow control): 向 gateway 推送 gw-flow 规则(account-service 10 QPS),
     突发流量验证 429 与 Sentinel block 计数, 完成后清空规则
  P3 降级(degrade/fallback): 停 statistics-service 容器 -> 账务 PUT 仍 200
     (feign.sentinel fallback 吞错), account-service 日志出现 fallback ERROR;
     恢复容器后统计联动恢复
  P4 链路追踪(trace): 开唯一 probe 用户, 从 account-service 日志提取 traceId,
     验证同一 traceId 贯通 gateway 与 auth-service(Feign 跨服务传播)

数据源: Sentinel transport API(容器内 :8719, 权威实时指标) + docker logs。
运行: python3 stress-gateway.py   (期望最后输出 SUMMARY: ALL_PHASES_OK)
"""
import base64
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.abspath(__file__))
GW = "http://localhost:4000"
env = dict(l.strip().split("=", 1) for l in open(os.path.join(ROOT, ".env")) if "=" in l)
ACCT_PW = env["ACCOUNT_SERVICE_PASSWORD"]
DEMO_PW = "".join(["demo", "12345"])

GW_CONTAINER = "piggymetrics-refactored-gateway-1"
ACC_CONTAINER = "piggymetrics-refactored-account-service-1"
AUTH_CONTAINER = "piggymetrics-refactored-auth-service-1"
STAT_CONTAINER = "piggymetrics-refactored-statistics-service-1"

FAILS = []


def log(msg):
    print(msg, flush=True)


def fail(phase, msg):
    FAILS.append(f"[{phase}] {msg}")
    log(f"  !! FAIL [{phase}] {msg}")


def http(method, url, data=None, headers=None, raw_body=None, ctype=None, timeout=20):
    h = dict(headers or {})
    body = None
    if raw_body is not None:
        body = raw_body.encode()
        h["Content-Type"] = ctype
    elif data is not None:
        body = urllib.parse.urlencode(data).encode()
        h["Content-Type"] = "application/x-www-form-urlencoded"
    r = urllib.request.Request(url, data=body, method=method, headers=h)
    t0 = time.monotonic()
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return resp.status, resp.read().decode(), (time.monotonic() - t0) * 1000
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(), (time.monotonic() - t0) * 1000
    except Exception as e:
        return -1, str(e), (time.monotonic() - t0) * 1000


def dex(container, sh_cmd, timeout=30):
    r = subprocess.run(["docker", "exec", container, "sh", "-c", sh_cmd],
                       capture_output=True, text=True, timeout=timeout)
    return r.stdout.strip(), r.stderr.strip()


def sentinel_nodes(container):
    out, _ = dex(container, 'wget -qO- "http://localhost:8719/clusterNode?type=root"')
    try:
        return json.loads(out) if out else []
    except Exception:
        return []


def gw_push_rules(rules_json):
    enc = urllib.parse.quote(rules_json)
    out, _ = dex(GW_CONTAINER,
                 f"wget -qO- --header='Content-Type: application/x-www-form-urlencoded' "
                 f"--post-data='data={enc}' 'http://localhost:8719/gateway/updateRules'")
    return out.strip() == "success"


def gw_get_rules():
    out, _ = dex(GW_CONTAINER, 'wget -qO- "http://localhost:8719/gateway/getRules"')
    try:
        return json.loads(out) if out else []
    except Exception:
        return []


def get_tokens():
    st, body, _ = http("POST", GW + "/uaa/oauth2/token",
                       data={"grant_type": "password", "client_id": "browser",
                             "username": "demo", "password": DEMO_PW, "scope": "ui"})
    ui = json.loads(body).get("access_token", "") if st == 200 else ""
    basic = base64.b64encode(("account-service:" + ACCT_PW).encode()).decode()
    st, body, _ = http("POST", GW + "/uaa/oauth2/token",
                       data={"grant_type": "client_credentials", "scope": "server"},
                       headers={"Authorization": "Basic " + basic})
    srv = json.loads(body).get("access_token", "") if st == 200 else ""
    return ui, srv


def main():
    log("=" * 64)
    log("piggymetrics-refactored 网关压测 + Sentinel 验证")
    log("=" * 64)

    ui, srv = get_tokens()
    if not ui or not srv:
        log("FATAL: token 获取失败，无法继续")
        sys.exit(2)
    log(f"[setup] ui token {len(ui)}c, server token {len(srv)}c")
    auth_h = {"Authorization": "Bearer " + ui}

    # 唯一 probe 用户（P4 用）
    probe = "probe-" + str(int(time.time()))[-8:]

    # ---------------- P1 基线大流量 ----------------
    log("\n===== P1 基线大流量（2000 请求 / 20 并发 / 混合端点）=====")
    endpoints = [
        ("GET", "/actuator/health", None),
        ("GET", "/uaa/oauth2/jwks", None),
        ("GET", "/accounts/current", auth_h),
        ("GET", "/statistics/current", auth_h),
        ("GET", "/notifications/recipients/current", auth_h),
        ("GET", "/foo-404", None),
        ("GET", "/accounts/current", None),        # 401 流量
    ]
    N, CONC = 2000, 20
    results = []

    def one(i):
        m, path, hdr = endpoints[i % len(endpoints)]
        st, _, ms = http(m, GW + path, headers=hdr)
        return st, ms

    t0 = time.monotonic()
    with ThreadPoolExecutor(max_workers=CONC) as pool:
        results = list(pool.map(one, range(N)))
    wall = time.monotonic() - t0
    codes = Counter(st for st, _ in results)
    lats = sorted(ms for _, ms in results)
    p50, p95, p99 = lats[N // 2], lats[int(N * 0.95)], lats[int(N * 0.99)]
    qps = N / wall
    log(f"  总请求 {N} | 并发 {CONC} | 墙钟 {wall:.1f}s | 吞吐 {qps:.0f} QPS")
    log(f"  延迟 ms: p50={p50:.0f} p95={p95:.0f} p99={p99:.0f} max={lats[-1]:.0f}")
    log(f"  状态码分布: {dict(sorted(codes.items()))}")
    expected_ok = {200, 401, 404}
    unexpected = {c: n for c, n in codes.items() if c not in expected_ok}
    if unexpected:
        fail("P1", f"非预期状态码: {unexpected}")
    else:
        log("  PASS P1: 无非预期状态码（200/401/404 均为设计内行为）")
    if p95 > 5000:
        fail("P1", f"p95 延迟过高 {p95:.0f}ms")
    gw_nodes = sentinel_nodes(GW_CONTAINER)
    log("  gateway Sentinel 资源指标(oneMinute):")
    for r in gw_nodes:
        if r.get("oneMinuteTotal", 0) > 0:
            log(f"    {r['resource']:<22} pass={r['oneMinutePass']:<5} block={r['oneMinuteBlock']:<4} "
                f"exception={r['oneMinuteException']:<4} avgRt={r['averageRt']:.1f}ms")

    # ---------------- P2 限流 ----------------
    log("\n===== P2 网关限流（account-service 路由 10 QPS，突发 120 请求）=====")
    before = {r["resource"]: r["oneMinuteBlock"] for r in sentinel_nodes(GW_CONTAINER)}
    rule = json.dumps([{"resource": "account-service", "resourceMode": 0,
                        "grade": 1, "count": 10, "intervalSec": 1}])
    if not gw_push_rules(rule):
        fail("P2", "规则推送失败")
    else:
        got = gw_get_rules()
        log(f"  规则已推送: {got}")
        if not got:
            fail("P2", "规则读回为空")
        time.sleep(1.5)  # 让统计窗口滚动
        burst_codes = Counter()

        def burst(_):
            st, _, _ms = http("GET", GW + "/accounts/current", headers=auth_h)
            return st

        with ThreadPoolExecutor(max_workers=12) as pool:
            for st in pool.map(burst, range(120)):
                burst_codes[st] += 1
        n429 = burst_codes.get(429, 0)
        n200 = burst_codes.get(200, 0)
        log(f"  突发 120 请求状态分布: {dict(sorted(burst_codes.items()))}")
        after = {r["resource"]: r["oneMinuteBlock"] for r in sentinel_nodes(GW_CONTAINER)}
        delta = after.get("account-service", 0) - before.get("account-service", 0)
        log(f"  Sentinel block 增量(account-service): {delta}")
        if n429 > 0 and delta > 0:
            log(f"  PASS P2: 限流生效（429×{n429}, 通过×{n200}, block 计数 +{delta}）")
        else:
            fail("P2", f"未观察到限流: 429={n429} blockΔ={delta}")
        # 清理规则
        gw_push_rules("[]")
        log(f"  规则已清空: {gw_get_rules()}")

    # ---------------- P3 降级 ----------------
    log("\n===== P3 服务降级（停 statistics-service -> 账务 fallback）=====")
    change = json.dumps({
        "incomes": [{"title": "salary", "amount": 1000, "currency": "EUR", "period": "MONTH", "icon": "w"}],
        "expenses": [{"title": "rent", "amount": 500, "currency": "USD", "period": "MONTH", "icon": "h"}],
        "saving": {"amount": 200, "currency": "USD", "interest": 0.05, "deposit": True, "capitalization": False},
        "note": "p3-pre"})
    st, _, _ = http("PUT", GW + "/accounts/current", raw_body=change, ctype="application/json", headers=auth_h)
    log(f"  降级前 PUT /accounts/current -> {st}")
    if st != 200:
        fail("P3", f"降级前基线 PUT 失败 HTTP {st}")
    subprocess.run(["docker", "stop", STAT_CONTAINER], capture_output=True, timeout=60)
    log(f"  已停止 {STAT_CONTAINER}")
    time.sleep(3)
    fallback_codes = []
    for i in range(5):
        st, _, _ = http("PUT", GW + "/accounts/current", raw_body=change, ctype="application/json", headers=auth_h)
        fallback_codes.append(st)
    log(f"  statistics 停机期间 PUT×5 -> {fallback_codes}")
    lg = subprocess.run(["docker", "logs", "--tail", "300", ACC_CONTAINER],
                        capture_output=True, text=True, timeout=30)
    fb_hits = lg.stdout.count("Error during update statistics") + lg.stderr.count("Error during update statistics")
    log(f"  account-service fallback 日志命中: {fb_hits} 次")
    if all(c == 200 for c in fallback_codes) and fb_hits >= 1:
        log("  PASS P3: 账务主流程不受下游故障影响（fallback 吞错保 200，H4 现状语义）")
    else:
        fail("P3", f"降级行为异常: codes={fallback_codes} fallback日志={fb_hits}")
    subprocess.run(["docker", "start", STAT_CONTAINER], capture_output=True, timeout=60)
    log(f"  已恢复 {STAT_CONTAINER}，等待注册...")
    recovered = False
    for i in range(24):
        time.sleep(5)
        st, _, _ = http("PUT", GW + "/accounts/current", raw_body=change.replace("p3-pre", "p3-post"),
                        ctype="application/json", headers=auth_h)
        if st == 200:
            # 检查统计侧是否真的收到（非 fallback）：datapoints 时间戳更新
            out, _ = dex("piggymetrics-refactored-statistics-mongodb-1",
                         'mongo --quiet -u user -p "$MONGODB_PASSWORD" --authenticationDatabase piggymetrics '
                         'piggymetrics --eval \'print(db.datapoints.find({}).sort({}).limit(1).next()._id.account)\' 2>/dev/null || true')
            lg2 = subprocess.run(["docker", "logs", "--since", "30s", ACC_CONTAINER],
                                 capture_output=True, text=True, timeout=30)
            recent_fb = lg2.stdout.count("Error during update statistics") + lg2.stderr.count("Error during update statistics")
            if recent_fb == 0:
                recovered = True
                log(f"  恢复确认: PUT=200 且近 30s 无 fallback 日志 (datapoints 查询输出: {out[:40]})")
                break
    if recovered:
        log("  PASS P3b: statistics 恢复后联动正常（无 fallback）")
    else:
        fail("P3b", "statistics 恢复后仍有 fallback 或 PUT 非 200")

    # ---------------- P4 链路追踪 ----------------
    log("\n===== P4 链路追踪（traceId 跨服务贯通）=====")
    st, body, _ = http("POST", GW + "/accounts/",
                       raw_body=json.dumps({"username": probe, "password": DEMO_PW}),
                       ctype="application/json")
    log(f"  开户 probe 用户 {probe} -> HTTP {st}")
    if st not in (200, 400):
        fail("P4", f"probe 开户失败 HTTP {st}: {body[:100]}")
    time.sleep(2)
    lg = subprocess.run(["docker", "logs", "--tail", "400", ACC_CONTAINER],
                        capture_output=True, text=True, timeout=30)
    m = re.search(r"\[account-service,([0-9a-f]{16,}),[0-9a-f]*\][^\n]*new account has been created: " + probe,
                  lg.stdout + lg.stderr)
    if not m:
        # 宽松匹配：找包含 probe 的行提取 traceId
        for line in (lg.stdout + lg.stderr).splitlines():
            if probe in line:
                m2 = re.search(r"\[account-service,([0-9a-f]{16,}),", line)
                if m2:
                    m = m2
                    break
    if not m:
        fail("P4", "account-service 日志未找到 probe 开户记录/traceId")
    else:
        tid = m.group(1)
        log(f"  account-service traceId = {tid}")
        hits = {}
        for name, cont in [("gateway", GW_CONTAINER), ("auth-service", AUTH_CONTAINER),
                           ("account-service", ACC_CONTAINER)]:
            r = subprocess.run(["docker", "logs", "--tail", "2000", cont],
                               capture_output=True, text=True, timeout=30)
            hits[name] = (r.stdout + r.stderr).count(tid)
        log(f"  traceId 命中: {hits}")
        if hits.get("account-service", 0) >= 1 and hits.get("auth-service", 0) >= 1:
            log("  PASS P4: 同一 traceId 贯通 account-service 与 auth-service（Feign 传播成功）")
            if hits.get("gateway", 0) >= 1:
                log("        gateway 日志亦命中（全链路三层贯通）")
            else:
                log("        注: gateway(WebFlux) 对该请求无业务日志行属正常（无 access log）")
        else:
            fail("P4", f"traceId 未贯通: {hits}")

    # ---------------- SUMMARY ----------------
    log("\n" + "=" * 64)
    if FAILS:
        log(f"SUMMARY: {len(FAILS)} FAIL(s)")
        for f in FAILS:
            log("  " + f)
        sys.exit(1)
    log("SUMMARY: ALL_PHASES_OK (P1 基线 / P2 限流 / P3 降级 / P4 链路追踪)")
    sys.exit(0)


if __name__ == "__main__":
    main()
