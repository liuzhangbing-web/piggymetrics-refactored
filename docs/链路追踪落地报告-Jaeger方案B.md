# 链路追踪落地报告 · 方案B（Jaeger，联调/开发环境）

- 执行日期：2026-10-02
- 执行模型：qwen3.8-max
- 选型依据：人工决策"联调/开发环境采用方案B（Jaeger）"；生产方案C（SkyWalking）见《生产链路追踪-SkyWalking接入指南.md》
- 变更性质：**配置级/依赖级低风险变更，零业务代码改动**（未触碰任何账务/金额/鉴权逻辑）

---

## 一、结论（先给结论）

1. **Jaeger 方案已落地并真实验证成功**：4 个服务（gateway/auth/account/statistics）注册到 Jaeger，跨服务 trace 贯通，Jaeger 自动生成的服务依赖拓扑与架构完全一致。
2. 落地方式：5 服务 pom 增加 `zipkin-reporter-brave`（Boot BOM 管理版本，brave 原生上报器）+ application.yml/Nacos 配置 zipkin endpoint 指向 Jaeger 的 Zipkin 兼容端口 9414。**未改任何 Java 业务代码。**
3. 回归无破坏：mvn clean test **75/75 全绿**；全链路联调 **31/31 PASS**（含 traceId 跨服务贯通 P4）。
4. 编排隔离：Jaeger 通过独立 docker-compose.tracing.yml 叠加，**不进主 compose**（生产用 SkyWalking，不用 Jaeger）；开发按需 `-f` 叠加启用。

## 二、实施内容（变更清单）

| 变更 | 文件 | 说明 |
|---|---|---|
| +zipkin-reporter-brave 依赖 | 5×pom.xml | 版本由 Boot 3.5.3 BOM 管理（无需显式版本）；已验证进 5 个 fat-jar |
| +zipkin endpoint + sampling 配置 | 5×src/main/resources/application.yml | `management.zipkin.tracing.endpoint=${ZIPKIN_ENDPOINT:http://jaeger:9414/api/v2/spans}`；gateway 合并进已有 management 块，其余追加（各文件仅 1 个 management 块，无重复键） |
| +zipkin endpoint（权威源） | nacos-config/sync/application-common.yml | Nacos 公共配置同步该键；endpoint 用环境变量占位，便于 Jaeger/SkyWalking 切换 |
| +Jaeger 编排 | docker-compose.tracing.yml（新增） | jaegertracing/all-in-one:1.62.0，开 Zipkin 兼容口 9414、UI 16686；healthcheck 用 wget 探 UI（镜像无 curl） |
| 无改动 | 所有 *.java 业务代码 | 纯依赖+配置，零业务逻辑变更 |

关键设计：endpoint 走 `${ZIPKIN_ENDPOINT:...}` 环境变量——**同一套 jar 不改代码即可切换后端**（联调=Jaeger，生产=停用/改指 SkyWalking OAP）。

## 三、验证结果（真实执行输出）

### 3.1 Jaeger 服务注册
```
GET http://localhost:16686/api/services
-> ["account-service","auth-service","gateway","statistics-service"]   (4 服务在线)
```

### 3.2 跨服务 trace 贯通（Feign 链路）
```
开户 trace 6abea3b959d73bee -> services=[account-service, auth-service, gateway]
  span operations: authenticate bearertoken, http post /, HTTP POST, http post /users ...
  （gateway 入口 -> account-service -> Feign 调 auth-service POST /users，同一 traceId）
```

### 3.3 Jaeger 自动依赖拓扑（与架构一致）
```
gateway -> account-service    (callCount=22)
gateway -> auth-service       (callCount=1)
gateway -> statistics-service (callCount=20)
account-service -> statistics-service (callCount=1)   ← 账务变更 Feign
account-service -> auth-service       (callCount=1)   ← 开户 Feign
```

### 3.4 回归（无破坏）
| 回归项 | 结果 |
|---|---|
| mvn clean test（75 用例，含真实 Mongo 集成） | **75/75 BUILD SUCCESS**（gateway3/auth19/account20/statistics21/notification12） |
| verify-main-compose.py 全链路 | **31/31 PASS**（含 P4 traceId 跨服务贯通） |

## 四、如何查看链路（开发者使用指引）

### 4.1 启动带追踪的全栈
```
cd /Users/liuzhangbing/Downloads/D/workspace_tke/piggymetrics-refactored
# 三文件叠加：主 compose + 端口适配 + 追踪
docker compose -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.tracing.yml up -d
```

### 4.2 打开 Jaeger UI
```
http://localhost:16686
- Service 下拉选 account-service/gateway/...
- Operation 选具体端点，Find Traces 查调用链
- 点某条 trace 看瀑布图（各 span 耗时、跨服务父子关系）
- 左侧 System Architecture 看服务依赖拓扑图
```

### 4.3 按日志 traceId 反查链路
```
# 服务日志 pattern: [服务名,traceId,spanId]
docker logs piggymetrics-refactored-account-service-1 2>&1 | grep "new account has been created"
# 取 traceId（如 6abea3b959d73bee...），Jaeger UI 或直接 API：
curl "http://localhost:16686/api/traces/<traceId>"
```

### 4.4 API 直查（脚本/CI 用）
```
curl http://localhost:16686/api/services                      # 服务列表
curl "http://localhost:16686/api/traces?service=account-service&limit=20"   # 某服务 trace
curl "http://localhost:16686/api/dependencies?endTs=<ms>&lookback=3600000"  # 拓扑
```

## 五、生产切换（方案C SkyWalking）要点

联调用 Jaeger，生产切 SkyWalking（人工决策）。切换零业务代码改动，三处调整：
1. Dockerfile 加 `-javaagent:/opt/skywalking-agent/skywalking-agent.jar`（Agent 承载跨服务传播）
2. 停用 zipkin 上报（不设 ZIPKIN_ENDPOINT 或移除 zipkin-reporter-brave 依赖）避免双上报
3. 日志 traceId 口径统一（brave MDC 或 SkyWalking %tid 二选一）
详见《生产链路追踪-SkyWalking接入指南.md》（含版本选型、Agent 配置、compose 片段、SCG/WebFlux 插件注意、共存策略、回滚）。

## 六、注意事项与遗留

1. Jaeger v1 已 EOL（运行有警告，功能正常）。all-in-one 用 memory 存储（重启丢 trace），**仅联调用**；生产 SkyWalking 配 ES/BanyanDB 持久留存。
2. Jaeger 镜像 tag 固定 1.62.0（latest 为 v1 EOL 线，勿用 latest 以免拉到 v2 导致 env 配置方式变更）。
3. 采样率联调=1.0（全量）；生产 SkyWalking 用 SW_AGENT_SAMPLE_RATE 按流量降采样。
4. 本工程 brave 与未来 SkyWalking agent 可共存但建议生产二选一（避免双体系），见 SkyWalking 指南第五节。
5. zipkin endpoint 默认值 http://jaeger:9414 仅在叠加 tracing 编排时可达；未叠加时上报静默失败（不影响业务，brave reporter 异步且容错），符合 optional 降级设计。

——方案B（Jaeger）落地报告完毕。已真实验证、回归无破坏、编排隔离、生产切换路径清晰。
