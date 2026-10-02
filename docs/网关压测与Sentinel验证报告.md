# piggymetrics-refactored 网关压测与 Sentinel 功能验证报告

- 执行日期：2026-10-02
- 执行模型：qwen3.8-max
- 前置状态：主 docker-compose 全栈 13 容器运行中（第 2 轮冷启动联调 31/31 PASS 之后）
- 压测脚本：/Users/liuzhangbing/Downloads/D/workspace_tke/piggymetrics-refactored/stress-gateway.py
- 验证方式：Python 并发（ThreadPoolExecutor）经网关真实 HTTP 流量 + Sentinel transport API（容器内 :8719 实时指标）+ docker logs 交叉核验

---

## 一、结论（先给结论）

1. **四阶段压测全部通过（SUMMARY: ALL_PHASES_OK）**：
   - P1 基线大流量：2000 请求 / 20 并发，吞吐 ~1226-1427 QPS，p50=12ms / p95=21ms，零非预期状态码，零错误
   - P2 网关限流：Sentinel gw-flow 规则（account-service 路由 10 QPS）生效——突发 120 请求精确放行 10、拦截 110（HTTP 429），Sentinel block 计数 +110 完全吻合
   - P3 服务降级：statistics-service 停机期间账务 PUT×5 全部 200（Feign+Sentinel fallback 吞错保主流程，H4 现状语义实证），fallback 日志 5 次命中；恢复后联动自动回归（近 30s 零 fallback）
   - P4 链路追踪：同一 traceId 贯通 account-service → auth-service（Feign 跨服务传播成功）
2. **压测发现并修复 1 个真实缺陷（B8 🔴）**：Feign 调用不传播 trace 上下文（Sleuth→Micrometer 迁移遗漏 feign-micrometer 依赖），跨服务 traceId 断裂。修复后复验贯通。
3. Sentinel Dashboard（bladex 镜像 :8858）自带登录墙且默认凭据无效（401，不猜测凭据）——**监控数据改以 transport API :8719 为权威数据源**（本报告全部指标出自该 API），Dashboard 可人工登录后查看（应用名 gateway/account-service 等已在心跳注册）。

## 二、压测设计（四阶段）

| 阶段 | 目标 | 手段 | 通过标准 |
|---|---|---|---|
| P1 基线 | 大批量混合流量下网关+全服务稳定性 | 2000 请求、20 并发、7 类端点混合（health/JWKS/账务/统计/通知/404/401） | 无非预期状态码；p95<5s；Sentinel 各路由资源指标正常 |
| P2 限流 | Sentinel 网关流控真实生效 | transport API 推送 gw-flow 规则（resourceMode=0 路由级，grade=1 QPS，count=10），12 并发突发 120 请求 | 出现 429 且 Sentinel block 计数增量与 429 数吻合；测后规则清空 |
| P3 降级 | 下游故障时账务主流程不受影响（H4 现状语义） | docker stop statistics-service → PUT /accounts/current ×5 → docker start 恢复 | 停机期间全部 200 + fallback ERROR 日志命中；恢复后 PUT 200 且无新 fallback |
| P4 追踪 | traceId 跨服务贯通（Sleuth→Micrometer 等价性） | 开唯一 probe 用户触发 account→auth Feign 调用，双侧日志提取 traceId 比对 | account-service 与 auth-service 日志中 traceId 完全相同 |

## 三、执行结果（真实输出摘录）

### P1 基线大流量
```
总请求 2000 | 并发 20 | 墙钟 1.6s | 吞吐 1226 QPS（前一轮 1427 QPS）
延迟 ms: p50=12 p95=21 p99=232 max=471
状态码分布: {200: 1430, 401: 285, 404: 285}   ← 全部设计内行为，零 5xx
gateway Sentinel 资源指标(oneMinute):
  auth-service           pass=288  block=0  exception=0  avgRt=2.0ms
  statistics-service     pass=286  block=0  exception=0  avgRt=7.0ms
  account-service        pass=571  block=0  exception=0  avgRt=5.0ms
  notification-service   pass=286  block=0  exception=0  avgRt=7.0ms
```
说明：401=无 token 设计流量，404=/foo-404 未声明路由；gateway 按路由 ID 聚合资源指标，Sentinel SCG adapter 工作正常（avgRt 2-7ms）。

### P2 网关限流
```
规则推送: POST :8719/gateway/updateRules -> success
读回: [{"resource":"account-service","resourceMode":0,"grade":1,"count":10.0,"intervalSec":1}]
突发 120 请求（12 并发）: {200: 10, 429: 110}
Sentinel block 增量(account-service): +110   ← 与 429 数完全吻合
测后清理: gateway/getRules -> []
```

### P3 服务降级
```
降级前 PUT /accounts/current -> 200
docker stop statistics-service；停机期间 PUT×5 -> [200,200,200,200,200]
account-service fallback 日志("Error during update statistics"): 5 次命中
docker start 恢复；恢复后 PUT=200 且近 30s 零 fallback 日志，datapoints 正常写入
```
🔴 注：这是 H4「fallback 静默吞错」的现状语义验证——账务可用性优先、统计丢失仅记日志。**行为与旧系统一致（等价迁移达成），但生产不可长期接受**，补偿队列已列独立立项。

### P4 链路追踪
```
第一次执行（修复前）:
  account-service traceId = 6abe97d38f30fe68b00c7c9a3fc2e7c6
  auth-service     traceId = 6abe97d340474071a0adff4f9f803187   ← 断裂！
修复（B8）后复验:
  account-service traceId = 6abe9a64b02e2cc39ef5a11a13f9b774
  auth-service     traceId = 6abe9a64b02e2cc39ef5a11a13f9b774   ← 贯通 ✓
  命中统计: {gateway: 0, auth-service: 2, account-service: 2}
  （gateway 为 WebFlux 无业务日志行属正常，trace 头由 SCG+brave 注入转发）
```

## 四、发现并修复的缺陷

| # | 缺陷 | 根因 | 修复 | 验证 |
|---|---|---|---|---|
| B8 🔴 | Feign 跨服务调用 traceId 断裂（account→auth 各自新 trace），链路追踪名存实亡；旧系统 Sleuth 自动传播，属迁移回归 | Spring Cloud OpenFeign 4.x 的 Micrometer 传播需显式依赖 io.github.openfeign:feign-micrometer（提供 MicrometerCapability，向 Feign 请求注入 W3C traceparent/b3 头）；阶段2 迁移时遗漏。依赖树实证：修复前 3 个服务 feign-micrometer 计数=0 | account/statistics/notification 三个 pom 增加 feign-micrometer（版本由 SC BOM 管理=13.6）；重打包+重建 3 镜像+重启 | P4 复验 traceId 完全一致；31 项全链路回归 PASS=31 FAIL=0；mvn clean test 全量回归见第五节 |

影响评估：纯可观测性缺陷（不影响业务正确性），但违反金融系统"可追溯"要求，定级 🔴；修复为**纯增量依赖**，零业务代码改动。

## 五、修复后回归验证

| 回归项 | 结果 |
|---|---|
| verify-main-compose.py 31 项全链路 | **PASS=31 FAIL=0**（B8 修复+3 服务镜像重建后） |
| mvn clean test 全量（75 用例） | 见文末附记（后台执行，结果另行更新） |
| stress-gateway.py 四阶段 | ALL_PHASES_OK |

## 六、Sentinel 能力矩阵（实测确认）

| 能力 | 状态 | 证据 |
|---|---|---|
| 网关流控（QPS 限流，路由级） | ✅ 实测生效 | P2：规则推送→120 突发→429×110→block 计数吻合→规则清空 |
| Feign 熔断降级（fallback） | ✅ 实测生效 | P3：下游停机主流程 200 + fallback 日志；恢复自动回归 |
| 实时指标（pass/block/exception/RT） | ✅ transport API :8719 | P1 输出各路由 oneMinute 指标 |
| Dashboard 心跳注册 | ✅ | gateway 日志 "Find sentinel dashboard server list [sentinel-dashboard:8858]" |
| Dashboard UI 查看 | ⚠️ 需人工登录 | bladex 镜像 401 登录墙（sentinel/sentinel 等默认凭据无效，未猜测）；API 数据不受影响 |
| 规则持久化（Nacos datasource） | ⏳ 未启用 | 当前规则经 transport API 推送（重启即失，适合联调）；生产应配 sentinel-datasource-nacos 持久化——列入部署建议 |
| 链路追踪（Micrometer+brave） | ✅ B8 修复后贯通 | P4：跨服务同 traceId；日志 pattern [服务名,traceId,spanId] |

## 七、生产化建议（源自本次压测）

1. Sentinel 规则持久化：接入 sentinel-datasource-nacos（规则存 Nacos，重启不丢、支持灰度）；当前 transport API 推送仅联调用。
2. Dashboard：替换 bladex 社区镜像为自建官方镜像并启用鉴权（生产必须）；或直接用 Nacos 规则 + actuator/prometheus 指标出图。
3. 限流阈值基线：本次实测单实例 gateway ~1400 QPS（p95≈21ms）无压力；生产按压测容量设定各路由 QPS 与熔断 RT/异常比例阈值。
4. H4 补偿立项前，将 "Error during update statistics" fallback 日志接入告警（当前静默降级唯一可观测信号）。
5. 采样率：压测/生产建议 management.tracing.sampling.probability=0.1（部署文档已含）。

## 八、脚本使用说明（可重复执行）

```
cd /Users/liuzhangbing/Downloads/D/workspace_tke/piggymetrics-refactored
python3 stress-gateway.py        # 期望 SUMMARY: ALL_PHASES_OK，退出码 0
```
脚本特性：幂等（P2 测后自动清空规则；P3 自动恢复容器；P4 每次生成唯一 probe 用户）；
凭据从 .env 读取（无命令行明文）；纯 urllib 实现（规避 shell 掩码假失败，S1 教训）。

——压测报告完毕。
