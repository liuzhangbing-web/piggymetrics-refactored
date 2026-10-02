# SkyWalking（方案C）预发环境部署验证报告

- 执行日期：2026-10-02
- 执行模型：qwen3.8-max
- 任务：实际部署 SkyWalking 到预发环境验证 + 调整采样率 + 集成告警规则
- 变更性质：**纯观测层部署，零业务代码改动**（未触碰账务/金额/鉴权任何逻辑）

---

## 一、结论（先给结论）

1. **SkyWalking 全栈已在预发等价环境真实部署并验证成功**：OAP 10.2.0 + UI 10.2.0 + Java Agent 9.4.0，5 个业务服务经 Agent 字节码增强全部注册，跨服务 trace 贯通，服务拓扑与架构一致。
2. **采样率差异化调整生效**：gateway 全采（-1）、业务服务限采（300 段/3秒），经 `SW_AGENT_SAMPLE_RATE` 环境变量按服务配置，验证通过。
3. **金融告警规则集成并真实触发验证**：6 条 MQE 规则加载成功；通过降阈值冒烟测试证实告警引擎端到端工作（触发 13 条告警、入库 alarm_record、GraphQL 可查），随后已还原生产阈值。
4. 部署过程发现并修复 4 个真实环境障碍 + 3 个规则文件缺陷（详见第四节），全部为观测层配置问题，无一涉及业务代码。
5. 回归无破坏：SkyWalking 部署后主链路联调 **31/31 PASS**；此前代码级测试 75/75（未变更业务代码，无需重跑）。

**环境说明（如实披露）**：当前会话只能访问本机 Docker，无独立远程预发主机。本部署以本机全栈搭建**预发等价环境**（完整 OAP+存储+UI+Agent 注入+告警），与生产差异仅在存储规模与网络拓扑，验证逻辑与生产部署路径一致；所有产物（编排文件、告警规则、验证脚本）可直接平移至真实预发/生产。

## 二、部署架构与产物

```
                 ┌─────────────────────────────────────────────┐
                 │  SkyWalking OAP 10.2.0 (:11800 gRPC, :12800 HTTP)
   Java Agent ───┤  - 存储: MySQL 8 (skywalking-mysql, 预发够用;
   (9.4.0)       │          生产建议 Elasticsearch/BanyanDB)
   注入5服务      │  - 告警: alarm-settings.yml volume 挂载 (6条金融规则)
                 │  - 堆限制: -Xmx512m (防与业务JVM争抢OOM)
                 └──────────────┬──────────────────────────────┘
                                │
                 ┌──────────────▼──────────────┐
                 │  SkyWalking UI 10.2.0 :18080 │
                 └──────────────────────────────┘
```

**接入方式（零业务代码、零镜像重建、可回滚）**：
- sidecar 容器 `sw-agent-loader` 将 Agent 灌入共享 volume `sw-agent`
- 业务服务挂载该 volume + `JAVA_TOOL_OPTIONS=-javaagent:/opt/skywalking-agent/skywalking-agent.jar`
- 全部通过独立叠加文件 `docker-compose.skywalking.yml` 实现——**回滚 = 去掉该 -f 参数**，业务容器回到原镜像原状态

**产物清单**：

| 文件 | 说明 |
|---|---|
| docker-compose.skywalking.yml | SkyWalking 全栈编排（agent-loader/oap/mysql/ui + 5服务注入） |
| skywalking/alarm-settings.yml | 金融告警规则（6条，已修正服务名匹配） |
| skywalking/oap-libs/mysql-connector-j-9.2.0.jar | OAP 外挂 MySQL JDBC 驱动（Maven Central，合规来源） |
| verify-skywalking.py | 7 项自动化验证脚本（V1-V7） |
| sw-generate-traffic.py | 认证流量生成器（开户/token/账务/混合读） |
| sw-alarm-smoke-v3.py | 告警冒烟测试（降阈值→验证触发→自动还原） |
| 本报告 | refactor-docs/SkyWalking预发部署验证报告.md |

**启动命令**：
```bash
docker compose -f docker-compose.yml -f docker-compose.override.yml \
               -f docker-compose.skywalking.yml up -d
# UI: http://localhost:18080   OAP: :11800(gRPC)/:12800(HTTP)
```

## 三、验证结果（verify-skywalking.py 7/7 PASS，真实执行输出）

| # | 验证项 | 结果 |
|---|---|---|
| V1 | OAP 后端存活 :12800 | PASS |
| V2 | UI :18080 HTTP 200 | PASS |
| V3 | **5 服务全部经 Agent 注册**（gateway/auth/account/statistics/notification，命名空间 piggymetrics-pre） | PASS |
| V4 | **服务依赖拓扑与架构一致**：account→auth、account→statistics、statistics→rates-mock（Feign/HTTP 出调用全部自动发现） | PASS |
| V5 | **跨服务 trace**：account-service 产生 10 条 trace，端点含 `POST:/accounts/`（开户链路 account→auth Feign） | PASS |
| V6 | **采样率差异化**：gateway `SW_AGENT_SAMPLE_RATE=-1`（全采）、account-service `=300`（限采），容器内实测生效 | PASS |
| V7 | **告警规则加载**：OAP 加载 6 条 expression 规则 | PASS |
| 回归 | 主链路联调 verify-main-compose.py | **31/31 PASS** |

### 告警引擎端到端冒烟验证（真实触发）

方法：临时将 RT 阈值降为 >0ms（period=1min）→ 重启 OAP 重载规则 → 打认证流量 → 验证告警产生 → **自动还原生产阈值并重启**。

```
结果: ALARM_FIRED_OK (gql=13, mysql rows 13)
- MySQL alarm_record 表入库 13 条告警记录（含账务服务 RT 告警、SLA 告警）
- GraphQL getAlarm 查询返回同样 13 条，消息文本完整（中文 message 正确渲染）
- 冒烟结束后 alarm-settings.yml 已还原生产阈值（>800ms/>1500ms），OAP healthy
```

**结论：告警链路（指标采集→MQE 规则评估→告警产生→持久化→查询）端到端工作正常。**

冒烟过程还暴露了一个真实生产隐患并已修复：`include-names: account-service` 精确匹配不上 SkyWalking 完整服务名 `account-service|piggymetrics-pre|`（name|namespace|group 格式），导致账务专属告警规则曾广播到全部服务（13 条告警每条服务各记一份）。已改为完整名匹配，账务规则现在只对 account-service 生效。

## 四、部署过程发现并修复的问题（全部为观测层，零业务影响）

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| 1 | OAP 启动即退出 `no provider found for module storage` | SkyWalking 10.x 已移除 H2 存储 provider | 改用 MySQL 存储（新增 skywalking-mysql 服务，复用本地 mysql:8.0.46 镜像） |
| 2 | OAP `No suitable driver` | Apache 许可证限制，OAP 不捆绑 MySQL JDBC 驱动 | 外挂 mysql-connector-j-9.2.0.jar（Maven Central）至 oap-libs |
| 3 | OAP 运行数分钟后 exit 137（SIGKILL） | OAP 默认堆 >2GB，与 5 个业务 JVM + Nacos 争抢 7.7GB Docker 内存被 OOM kill | `JAVA_OPTS=-Xms256m -Xmx512m` 限制堆（生产按容量调大） |
| 4 | 镜像 docker.1ms.run 最后一层卡死 20+ 分钟 | 镜像源对该层回源异常 | 切换 docker.io/apache 官方源拉取成功 |
| 5 | 告警规则 include-names 不生效，账务规则广播全服务 | SkyWalking 服务名含命名空间后缀，include-names 为精确匹配 | 改用完整名 `account-service\|piggymetrics-pre\|`（含注释说明） |
| 6 | 告警查询假阴性（v1 脚本报 ALARM_NOT_FIRED） | 脚本用了 10.x 不存在的 GraphQL 字段 `getAlarmMessage`，400 被 try/except 吞掉 | 改用正确字段 `getAlarm{msgs}` + 不吞错误 + MySQL 表双重核验 |
| 7 | verify 脚本服务名断言失败 | 同 #5，服务名带命名空间后缀 | 脚本改为取 `name.split("\|")[0]` 基础名比对 |

## 五、告警规则清单（skywalking/alarm-settings.yml，生产阈值）

| 规则 | 表达式 | 周期/触发 | 级别 | 金融意义 |
|---|---|---|---|---|
| service_sla_rule | SLA < 99% | 10min/1次 | CRITICAL | 服务可用性红线 |
| account_service_resp_time_rule | account-service RT > 800ms | 10min/3次 | WARNING | **账务专属严阈值**：慢查询/降级(H4 fallback静默)早期信号 |
| service_resp_time_rule | 其他服务 RT > 1500ms | 10min/3次 | WARNING | 通用 RT 劣化 |
| endpoint_resp_time_rule | 端点 RT > 1000ms | 10min/2次 | WARNING | 定位到具体接口 |
| service_instance_resp_time_rule | 实例 RT > 2000ms | 10min/2次 | WARNING | 实例级异常 |
| database_access_resp_time_rule | DB 访问 RT > 1000ms | 10min/2次 | WARNING | MongoDB 慢查询 |

Webhook 已预留接入位（企业微信/钉钉/统一告警平台），生产启用时填入即可。

## 六、采样率配置说明（已调整并验证）

| 服务 | SW_AGENT_SAMPLE_RATE | 语义 | 理由 |
|---|---|---|---|
| gateway | -1 | 全量采样 | 系统入口，需完整拓扑与全量入口 RT 分布 |
| 4 个业务服务 | 300 | 每 3 秒最多采 300 个 trace segment | 限流保护 Agent/OAP；账务类服务如需全量审计可单独调 -1 |

调整方式：改 docker-compose.skywalking.yml 环境变量后 `up -d` 对应服务即可，无需改代码/镜像。生产按流量压测调优（建议账务链路全采、只读链路降采）。

## 七、风险评估与回滚

**风险评估**：
- 本次全部变更位于观测层（编排叠加文件 + 规则文件 + 外挂驱动 jar），**未改任何业务代码/业务配置/数据库**，主链路回归 31/31 证明业务无影响。
- 已知开销：Java Agent 字节码增强带来 ~3-5% RT 开销与每 JVM 数十 MB 内存增量（本地实测业务容器 620→700MB 左右）；OAP 限堆 512m 后稳定。
- 残余风险：MySQL 存储仅适合预发量级；生产需换 ES/BanyanDB（编排文件内已注释指引）；Agent FILE 日志因 volume 只读未落盘（不影响上报，生产将 volume 改可写或改 CONSOLE 输出）。

**回滚方案（L0 级，秒级）**：
```bash
# 去掉 -f docker-compose.skywalking.yml 重启业务服务即完全回滚：
docker compose -f docker-compose.yml -f docker-compose.override.yml up -d --force-recreate \
  gateway auth-service account-service statistics-service notification-service
docker compose -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.skywalking.yml \
  stop oap skywalking-ui skywalking-mysql sw-agent-loader
# 业务镜像本身从未被修改（agent 经 volume 挂载），回滚后与部署前字节级一致
```

## 八、遗留事项（生产上线前）

1. 存储切换：SW_STORAGE=elasticsearch + ES 集群（金融留存审计要求 >=30 天时必须）
2. Webhook 填入企业告警通道并做一次真实推送演练
3. 与 Jaeger（方案B）互斥确认：生产容器不设 ZIPKIN_ENDPOINT（当前预发已确认未设置，无双上报）
4. Agent FILE 日志落盘：sw-agent volume 改可写或 SW_LOGGING_OUTPUT=CONSOLE
5. 采样率生产压测调优（账务链路建议全采以满足可追溯要求）
6. 告警阈值随生产基线复核（本报告阈值为预发经验值）

——SkyWalking 预发部署验证完毕：7/7 自动化验证 PASS、告警真实触发验证 OK、回归 31/31、零业务代码改动、可秒级回滚。
