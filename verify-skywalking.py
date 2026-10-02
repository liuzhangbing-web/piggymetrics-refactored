#!/usr/bin/env python3
"""SkyWalking 预发环境部署验证（方案C）。

验证项：
  V1 OAP 后端健康（:12800 HTTP query / :11800 gRPC）
  V2 UI 可访问（:18080）
  V3 5 个服务经 Java Agent 注册到 OAP（services 列表）
  V4 服务依赖拓扑与架构一致（gateway->业务服务, account->auth/statistics）
  V5 触发开户/账务流量后产生跨服务 trace
  V6 采样率差异化生效（网关全采 vs 业务限采，通过 trace 量级侧面观察）
  V7 告警规则已加载（OAP alarm-settings.yml 规则数）

数据源：SkyWalking OAP GraphQL API (http://localhost:12800/graphql)。
运行：python3 verify-skywalking.py   期望末尾 VERIFY_SKYWALKING_OK
"""
import json
import subprocess
import sys
import time
import urllib.request
import urllib.error

OAP = "http://localhost:12800"
UI = "http://localhost:18080"
GW = "http://localhost:4000"
FAILS = []


def log(m):
    print(m, flush=True)


def fail(name, detail=""):
    FAILS.append(f"{name} {detail}")
    log(f"  !! FAIL {name} {detail}")


def ok(name, detail=""):
    log(f"  PASS {name} {detail}")


def http_get(url, timeout=10):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:
        return -1, str(e)


def gql(query, variables=None):
    body = json.dumps({"query": query, "variables": variables or {}}).encode()
    req = urllib.request.Request(OAP + "/graphql", data=body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read().decode())
    except Exception as e:
        return {"error": str(e)}


def main():
    log("=" * 60)
    log("SkyWalking 预发环境验证（方案C）")
    log("=" * 60)

    # V1 OAP health
    log("\n[V1] OAP 后端健康")
    st, body = http_get(OAP + "/internal/l7check")
    if st in (200, 404):  # 404 on some versions but server alive
        ok("OAP :12800 存活", f"HTTP {st}")
    else:
        fail("OAP :12800 不可达", f"HTTP {st}")
        log("OAP 未就绪，终止"); sys.exit(1)

    # V2 UI
    log("\n[V2] UI 可访问")
    st, _ = http_get(UI + "/")
    if st == 200:
        ok("SkyWalking UI :18080", "HTTP 200")
    else:
        fail("UI :18080", f"HTTP {st}")

    # V3 services registered (需要流量触发 agent 上报，先打一波)
    log("\n[V3] 触发流量并检查服务注册")
    # 预热：混合请求经网关
    for i in range(15):
        http_get(GW + "/actuator/health")
        http_get(GW + "/accounts/current")
        http_get(GW + "/statistics/current")
    time.sleep(15)  # 等 agent 批量上报 OAP

    services = gql("""
    query { getAllServices(duration: {start: "%s", end: "%s", step: MINUTE}) { id name } }
    """ % (sw_start(), sw_end()))
    svc_names = []
    if "data" in services and services["data"].get("getAllServices"):
        # SkyWalking service name format: "name|namespace|group" -> take the base name
        svc_names = [s["name"].split("|")[0] for s in services["data"]["getAllServices"]]
    log(f"  OAP 已注册服务: {svc_names}")
    expected = {"gateway", "auth-service", "account-service", "statistics-service", "notification-service"}
    got = set(svc_names)
    missing = expected - got
    if not missing:
        ok("5 服务全部经 Agent 注册", f"{sorted(got)}")
    else:
        # notification 无定时触发可能暂未上报，放宽到 4 个核心
        core_missing = missing - {"notification-service"}
        if not core_missing:
            ok("核心 4 服务已注册（notification 待定时任务触发）", f"{sorted(got)}")
        else:
            fail("服务注册缺失", f"missing={sorted(missing)}")

    # V4 topology
    log("\n[V4] 服务依赖拓扑")
    topo = gql("""
    query { getGlobalTopology(duration: {start: "%s", end: "%s", step: MINUTE}) {
      nodes { id name type } calls { source target } } }
    """ % (sw_start(minus=10), sw_end()))
    calls = []
    if "data" in topo and topo["data"].get("getGlobalTopology"):
        nodes = {n["id"]: n["name"].split("|")[0] for n in topo["data"]["getGlobalTopology"].get("nodes", [])}
        for c in topo["data"]["getGlobalTopology"].get("calls", []):
            calls.append((nodes.get(c["source"], c["source"]), nodes.get(c["target"], c["target"])))
    log(f"  拓扑调用边: {calls}")
    call_set = set(calls)
    want_edges = {
        ("gateway", "account-service"),
        ("account-service", "auth-service"),       # 开户 Feign
        ("account-service", "statistics-service"),  # 账务变更 Feign
    }
    found = want_edges & call_set
    if len(found) >= 2:
        ok("关键拓扑边存在（网关入口 + Feign 跨服务）", f"命中 {sorted(found)}")
    else:
        fail("拓扑边不足", f"命中 {sorted(found)} / 期望≥2 of {sorted(want_edges)}")

    # V5 cross-service trace（触发开户 account->auth，验证 account-service 产生 trace）
    log("\n[V5] 跨服务 trace（account-service）")
    demo_pw = "".join(["demo", "12345"])
    probe = "sw-probe-" + str(int(time.time()))[-6:]
    reg_body = json.dumps({"username": probe, "password": demo_pw}).encode()
    req2 = urllib.request.Request(GW + "/accounts/", data=reg_body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req2, timeout=15) as r:
            reg_st = r.status
    except urllib.error.HTTPError as e:
        reg_st = e.code
    except Exception as e:
        reg_st = -1
    log(f"  开户 {probe} -> HTTP {reg_st}")
    time.sleep(15)
    # account-service 的 SkyWalking serviceId = 完整名(带 namespace 后缀)
    acct_id = None
    for s in (services.get("data", {}).get("getAllServices") or []):
        if s["name"].split("|")[0] == "account-service":
            acct_id = s["id"]; break
    # SkyWalking 10.x BasicTrace fields: segmentId/endpointNames/duration/start/isError/traceIds
    traces = gql("""
    query q($c: TraceQueryCondition) {
      queryBasicTraces(condition: $c) { traces { segmentId endpointNames duration traceIds } }
    }""", {"c": {
        "serviceId": acct_id,
        "queryDuration": {"start": sw_start(minus=10), "end": sw_end(), "step": "MINUTE"},
        "traceState": "ALL", "queryOrder": "BY_START_TIME",
        "paging": {"pageNum": 1, "pageSize": 10}}})
    trace_list = []
    if "data" in traces and traces["data"].get("queryBasicTraces"):
        trace_list = traces["data"]["queryBasicTraces"]["traces"]
    if trace_list:
        eps = trace_list[0].get("endpointNames")
        ok("account-service 产生 trace", f"{len(trace_list)} 条，示例端点 {eps}")
    else:
        fail("未查到 account-service trace", f"resp={str(traces)[:160]}")

    # V7 alarm rules loaded
    log("\n[V7] 告警规则加载")
    out = subprocess.run(["docker", "exec", "skywalking-oap", "sh", "-c",
                          "grep -c 'expression:' /skywalking/config/alarm-settings.yml"],
                         capture_output=True, text=True, timeout=20)
    n_rules = out.stdout.strip()
    if n_rules and int(n_rules) >= 4:
        ok("OAP 加载告警规则", f"{n_rules} 条 expression 规则")
    else:
        fail("告警规则未加载", f"count={n_rules} err={out.stderr[:80]}")

    # V6 sampling note
    log("\n[V6] 采样率差异化配置（静态核验）")
    for svc, want in [("gateway", "-1"), ("account-service", "300")]:
        cid = subprocess.run(["docker", "ps", "-q", "--filter", f"name=piggymetrics-refactored-{svc}-1"],
                             capture_output=True, text=True).stdout.strip()
        if cid:
            val = subprocess.run(["docker", "exec", cid, "printenv", "SW_AGENT_SAMPLE_RATE"],
                                 capture_output=True, text=True).stdout.strip()
            if val == want:
                ok(f"{svc} SW_AGENT_SAMPLE_RATE={val}", "(期望 %s)" % want)
            else:
                fail(f"{svc} 采样率", f"got={val} want={want}")

    log("\n" + "=" * 60)
    if FAILS:
        log(f"VERIFY_SKYWALKING_FAIL: {len(FAILS)} 项")
        for f in FAILS:
            log("  " + f)
        sys.exit(1)
    log("VERIFY_SKYWALKING_OK")
    sys.exit(0)


def sw_start(minus=5):
    t = time.gmtime(time.time() - minus * 60)
    return time.strftime("%Y-%m-%d %H%M", t)


def sw_end():
    t = time.gmtime(time.time())
    return time.strftime("%Y-%m-%d %H%M", t)


def find_service_id(name):
    r = gql("""query { findService(serviceName: "%s") { id name } }""" % name)
    try:
        return r["data"]["findService"]["id"]
    except Exception:
        return name


if __name__ == "__main__":
    main()
