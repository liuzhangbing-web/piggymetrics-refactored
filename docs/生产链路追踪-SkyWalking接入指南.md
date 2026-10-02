# 生产（金融）链路追踪接入指南 · 方案C：Apache SkyWalking

- 适用：piggymetrics-refactored 生产/准生产环境
- 编制：qwen3.8-max（2026-10-02）
- 选型依据：人工决策"生产（金融）采用方案C SkyWalking"
- 关系：联调/开发环境用方案B（Jaeger，已落地验证，见 docker-compose.tracing.yml）；本指南为生产环境接入件，**两套可并存亦可二选一**

---

## 一、为什么生产选 SkyWalking（对金融场景的适配）

| 维度 | SkyWalking 优势（相对 Jaeger） |
|---|---|
| 接入方式 | Java Agent 字节码增强，**零代码/零 pom 改动**，Dockerfile 加 -javaagent 即可，回滚=去掉参数（满足金融"可回滚"红线） |
| 能力完整度 | Trace + Metrics(JVM/端点/实例) + 服务拓扑 + 告警 一体，自带 UI；满足审计"可追溯+可监控" |
| 生态契合 | 阿里系/国内金融生产常见组合，与 Spring Cloud Alibaba 同源；对 Spring Boot 3 / JDK17 有成熟插件 |
| 存储与留存 | 后端可选 ES/BanyanDB，支持链路数据长期留存（金融审计要求） |
| 采样与性能 | Agent 侧采样率、慢 trace 阈值可下发；对高吞吐网关友好 |

> 说明：本工程已内置 micrometer-tracing-bridge-brave（方案B用）。SkyWalking Agent 与 brave **可共存**（SkyWalking 走自己的 sw8 头传播），但为避免双体系冗余，生产二选一时建议：**用 SkyWalking Agent 承载跨服务传播，日志 traceId 仍可用现有 pattern**。详见第五节共存策略。

## 二、版本选型（与本工程 Boot 3.5.3 / JDK17 对齐）

| 组件 | 版本 | 说明 |
|---|---|---|
| SkyWalking OAP（后端） | 10.2.0 | 分析平台服务 |
| SkyWalking UI | 10.2.0 | Web 控制台（随 OAP 镜像或独立） |
| SkyWalking Java Agent | 9.4.0（java-agent 9.x 线） | **支持 JDK17 + Spring Boot 3.x + Spring Cloud Gateway(WebFlux) + OpenFeign + MongoDB + Nacos** |
| 存储 | BanyanDB 0.7（推荐）或 Elasticsearch 8.x | 金融审计留存选 ES 集群 |

> ⚠️ Agent 与 OAP 大版本需匹配（Agent 9.x ↔ OAP 10.x 已验证兼容矩阵；上线前以官方 compatibility 文档复核）。所有镜像走内网私有 registry，禁止生产直连 docker.io。

## 三、接入方式（Java Agent，推荐 · 零代码改动）

### 3.1 Agent 分发

两种方式，金融生产建议 **方式A（镜像内置，可审计、可版本锁定）**：

方式A — 打进基础镜像（改 Dockerfile，非业务代码）：
```dockerfile
FROM eclipse-temurin:17-jre
# 内置 SkyWalking agent（版本锁定，随镜像审计）
COPY --from=apache/skywalking-java-agent:9.4.0-alpine /skywalking/agent /opt/skywalking-agent
ADD ./target/<service>.jar /app/
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/skywalking-agent/skywalking-agent.jar"
CMD ["java","-Xmx1g","-jar","/app/<service>.jar"]
```

方式B — 卷挂载 agent（不改镜像，运维侧注入）：
```
docker run -v /opt/skywalking-agent:/sw-agent:ro \
  -e JAVA_TOOL_OPTIONS="-javaagent:/sw-agent/skywalking-agent.jar" ...
```

### 3.2 每个服务的关键 Agent 配置（环境变量注入）

| 变量 | 值 | 说明 |
|---|---|---|
| SW_AGENT_NAME | 各服务名（account-service 等） | UI 中显示的服务名，与 spring.application.name 一致 |
| SW_AGENT_NAMESPACE | piggymetrics-prod | 逻辑隔离（多环境/多系统共用 OAP 时） |
| SW_AGENT_INSTANCE_NAME | ${HOSTNAME} | 实例标识（容器 ID） |
| SW_AGENT_COLLECTOR_BACKEND_SERVICES | oap.skywalking:11800 | OAP gRPC 上报地址 |
| SW_AGENT_SAMPLE_RATE | 3000（=每秒3000条，负数为采样率倒数） | 生产采样，按流量调；全量=-1 |
| SW_LOGGING_OUTPUT | FILE | Agent 日志落文件，避免污染 stdout |
| SW_TRACE_IGNORE_PATH | /actuator/**,Sentinel** | 忽略健康检查/内部端点，降噪 |

### 3.3 生产 compose 片段（示意，不进本工程仓库的联调编排）

```yaml
services:
  oap:
    image: <内网registry>/apache/skywalking-oap-server:10.2.0
    environment:
      SW_STORAGE: elasticsearch            # 金融审计留存用 ES；试点可用 banyandb/h2
      SW_STORAGE_ES_CLUSTER_NODES: es:9200
      SW_CORE_RECORD_DATA_TTL: "7"          # 明细留存 7 天
      SW_CORE_METRICS_DATA_TTL: "30"        # 指标留存 30 天
      SW_HEALTH_CHECKER: default
    ports: ["11800:11800", "12800:12800"]
    healthcheck:
      test: ["CMD-SHELL", "/skywalking/bin/swctl health"]
      interval: 15s
      retries: 20

  skywalking-ui:
    image: <内网registry>/apache/skywalking-ui:10.2.0
    environment:
      SW_OAP_ADDRESS: http://oap:12800
    ports: ["8080:8080"]

  # 业务服务示例（每个服务加同一段 environment + javaagent）
  account-service:
    environment:
      SW_AGENT_NAME: account-service
      SW_AGENT_COLLECTOR_BACKEND_SERVICES: oap:11800
      SW_AGENT_SAMPLE_RATE: "3000"
      JAVA_TOOL_OPTIONS: "-javaagent:/opt/skywalking-agent/skywalking-agent.jar"
    # 依赖 OAP 就绪（生产用 depends_on: oap: condition: service_healthy）
```

### 3.4 Spring Cloud Gateway（WebFlux）特别说明

- Agent 9.x 含 `apm-spring-cloud-gateway-4.x-plugin` 与 `apm-spring-webflux-6.x-plugin`，本工程 SCG 4.3 / WebFlux 需确认插件在 agent 的 `plugins/` 目录已启用（默认启用；如缺失从 `optional-plugins/` 移入）。
- 网关是链路入口，务必开启，否则拓扑图缺少 gateway 节点。

## 四、部署后验证（生产上线检查）

1. OAP 健康：`swctl health` 或 UI 可访问（:8080）。
2. 各服务启动日志出现 SkyWalking agent banner 且无 `Send GRPC ... failed`。
3. UI → General Service：5 个服务（gateway/auth/account/statistics/notification）均在线、有实例。
4. UI → Topology：出现调用拓扑，**与方案B Jaeger 实测拓扑一致**：
   ```
   gateway -> account-service / auth-service / statistics-service / notification-service
   account-service -> auth-service        (开户 Feign)
   account-service -> statistics-service  (账务变更 Feign)
   notification-service -> account-service(备份附件 Feign)
   ```
5. 触发一次开户，UI → Trace 查到跨服务 trace（gateway→account→auth），span 含 `POST /uaa/users`。
6. 日志 traceId：SkyWalking 提供 `apm-toolkit-logback-1.x`，可在 logback pattern 注入 `%tid`（TraceId），与现有 `[服务名,traceId,spanId]` 并存或替换。
7. 告警：OAP 配置 service_resp_time / service_sla 规则，接入企业告警通道。

## 五、方案B(brave) 与 方案C(SkyWalking) 共存策略

本工程已含 micrometer-tracing-bridge-brave（方案B）。生产切 SkyWalking 时二选一：

- 推荐：**SkyWalking Agent 承载跨服务传播与后端存储**；关闭 brave 的 zipkin 上报（不设 management.zipkin.tracing.endpoint，或删除 zipkin-reporter-brave 依赖），避免重复上报。
- 日志 traceId：可继续用 brave 的 MDC（traceId/spanId 已在 pattern），或改用 SkyWalking `%tid`。**两者 traceId 值不同**，需团队统一口径，避免"日志 traceId 与 APM traceId 对不上"。
- 若坚持保留 brave 生态（Micrometer Observation 指标），可让 brave 只做本地 span+日志、SkyWalking 做跨服务，但运维复杂度上升，金融生产不建议双活。

决策建议：生产以 SkyWalking 为唯一 APM 真源；开发/联调保留 Jaeger（轻量、已在工程内验证）。切换点集中在：① Dockerfile 加 javaagent；② 移除/停用 zipkin endpoint；③ 日志 traceId 口径统一。三处均非业务代码。

## 六、风险与回滚

| 项 | 说明 |
|---|---|
| 回滚 | 去掉 -javaagent（JAVA_TOOL_OPTIONS 置空）即恢复无 APM 状态，业务零影响；镜像方式回滚=切回旧镜像 tag |
| 性能 | Agent 字节码增强有轻微开销（通常 <5% RT）；用采样率与 SW_TRACE_IGNORE_PATH 控制 |
| 版本兼容 | Agent 9.x ↔ OAP 10.x ↔ Boot3.5/JDK17 上线前必须用一台预发实测全链路（本工程 CI 未含 agent，属部署期验证） |
| 插件缺失 | SCG/WebFlux/OpenFeign/Mongo/Nacos 插件须在 agent plugins 目录启用，缺一则对应节点断链——上线检查第 4 步拓扑完整性即为此 |
| 数据留存 | ES 存储容量与 TTL 按审计要求设定（明细≥7天、指标≥30天为起步） |

## 七、与联调环境（方案B）的对应关系

| 项 | 联调/开发（已落地） | 生产（本指南） |
|---|---|---|
| 后端 | Jaeger all-in-one（memory 存储） | SkyWalking OAP + ES/BanyanDB |
| 接入 | zipkin-reporter-brave 依赖 + endpoint 配置 | Java Agent（-javaagent），零代码 |
| UI | Jaeger UI :16686 | SkyWalking UI :8080 |
| 传播头 | W3C traceparent / b3（brave） | sw8（SkyWalking） |
| 验证状态 | ✅ 已实测：4 服务注册、依赖拓扑正确、开户跨服务 trace 贯通 | ⏳ 部署期验证（需预发环境，CI 不含 agent） |

——生产 SkyWalking 接入指南完毕。落地时需人工在预发环境执行第四节验证。
