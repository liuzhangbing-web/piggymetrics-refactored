#!/usr/bin/env python3
"""SkyWalking alarm smoke test v3.

v2 root-cause of ALARM_NOT_FIRED: threshold `service_resp_time > 1` never evaluated true
because measured avg RT of the fast endpoints is exactly 1ms (1 > 1 == false), and the
alarm query/schema was already proven correct in v2 (baseline query returned 200).

v3 changes:
- threshold `> 0` (any traffic at all fires), period=1, silence=1 -> fastest possible fire
- dual verification: OAP GraphQL getAlarm AND MySQL alarm_record table row count
- authenticated traffic to account/statistics
- restores production alarm-settings.yml and restarts OAP afterwards
"""
import json, time, urllib.request, urllib.error, urllib.parse, subprocess, sys

OAP = "http://localhost:12800/graphql"
GW = "http://localhost:4000"
ROOT = "/Users/liuzhangbing/Downloads/D/workspace_tke/piggymetrics-refactored"
ALARM = ROOT + "/skywalking/alarm-settings.yml"
PW = "".join(["demo", "12345"])


def gql(query, variables=None):
    body = json.dumps({"query": query, "variables": variables or {}}).encode()
    req = urllib.request.Request(OAP, data=body, headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def http(method, url, data=None, headers=None, raw=None, ctype=None):
    h = dict(headers or {}); body = None
    if raw is not None:
        body = raw.encode(); h["Content-Type"] = ctype
    elif data is not None:
        body = urllib.parse.urlencode(data).encode()
        h["Content-Type"] = "application/x-www-form-urlencoded"
    r = urllib.request.Request(url, data=body, method=method, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=15) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:
        return -1, str(e)


def win(minus=30):
    e = time.gmtime(time.time()); s = time.gmtime(time.time() - minus * 60)
    return time.strftime("%Y-%m-%d %H%M", s), time.strftime("%Y-%m-%d %H%M", e)


def alarms_graphql(label):
    start, end = win()
    st, d = gql('''query q($d: Duration!) {
        getAlarm(keyword: "", scope: Service, duration: $d, paging: {pageNum: 1, pageSize: 30}) {
          msgs { startTime scope id name message } } }''',
                {"d": {"start": start, "end": end, "step": "MINUTE"}})
    if st != 200 or "errors" in d:
        print(f"[{label}] GQL ERROR st={st} {json.dumps(d)[:150]}")
        return None
    msgs = d["data"]["getAlarm"]["msgs"]
    print(f"[{label}] gql alarms={len(msgs)}")
    for m in msgs[:6]:
        print("   -", m.get("name"), "|", str(m.get("message", ""))[:90])
    return msgs


def alarms_mysql():
    out = subprocess.run(
        ["docker", "exec", "skywalking-mysql", "mysql", "-uroot", "-psw-root-pw", "swtest", "-N", "-e",
         "SELECT COUNT(*) FROM alarm_record_20261002;"],
        capture_output=True, text=True, timeout=30)
    try:
        return int(out.stdout.strip())
    except Exception:
        print("  mysql query err:", out.stdout[:80], out.stderr[:80])
        return -1


def traffic(rounds=10):
    probe = "swa3-" + str(int(time.time()))[-6:]
    st, _ = http("POST", GW + "/accounts/", raw=json.dumps({"username": probe, "password": PW}),
                 ctype="application/json")
    st2, body = http("POST", GW + "/uaa/oauth2/token", data={
        "grant_type": "password", "client_id": "browser", "username": probe, "password": PW, "scope": "ui"})
    tok = json.loads(body).get("access_token", "") if st2 == 200 else ""
    if not tok:
        return {"no_token": 1}
    ah = {"Authorization": "Bearer " + tok}
    codes = {}
    for i in range(rounds):
        for path in ["/accounts/current", "/statistics/current"]:
            s, _ = http("GET", GW + path, headers=ah)
            codes[s] = codes.get(s, 0) + 1
    return codes


def restart_oap():
    subprocess.run(["docker", "restart", "skywalking-oap"], capture_output=True, timeout=90)
    for _ in range(45):
        st = subprocess.run(["docker", "inspect", "--format", "{{.State.Health.Status}}", "skywalking-oap"],
                            capture_output=True, text=True, timeout=15).stdout.strip()
        if st == "healthy":
            return True
        time.sleep(5)
    return False


def main():
    orig = open(ALARM).read()
    m0 = alarms_mysql()
    print("baseline mysql alarm rows:", m0)

    print("=== patch alarm: global RT>0ms, period=1, count>=1 ===")
    t = orig.replace("expression: sum(service_resp_time > 800) >= 3",
                     "expression: sum(service_resp_time > 0) >= 1")
    t = t.replace('''period: 10
    silence-period: 10
    # 仅对 account-service 生效
    include-names:
      - account-service
''', 'period: 1\n    silence-period: 1\n')
    assert "service_resp_time > 0" in t and "include-names:\n      - account-service" not in t
    open(ALARM, "w").write(t)
    print("restart OAP to reload rules...")
    if not restart_oap():
        open(ALARM, "w").write(orig); print("OAP unhealthy - restored, abort"); sys.exit(5)
    print("OAP healthy; patched rules loaded")

    fired = None
    for cycle in range(6):
        codes = traffic(10)
        print(f"cycle-{cycle} traffic={codes}")
        time.sleep(50)
        fired = alarms_graphql(f"cycle-{cycle}")
        m = alarms_mysql()
        print(f"  mysql alarm rows now: {m} (baseline {m0})")
        if fired:
            break
    # final mysql cross-check
    m_final = alarms_mysql()

    print("=== restore production alarm-settings.yml ===")
    open(ALARM, "w").write(orig)
    ok = restart_oap()
    print("restored + OAP healthy:", ok)

    if fired or (m_final > m0 >= 0):
        print(f"\nRESULT: ALARM_FIRED_OK (gql={len(fired) if fired else 0}, mysql rows {m0}->{m_final})")
        sys.exit(0)
    print("\nRESULT: ALARM_NOT_FIRED")
    sys.exit(2)


if __name__ == "__main__":
    main()
