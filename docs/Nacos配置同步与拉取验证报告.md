# Nacos 配置同步与服务拉取验证报告

- 执行日期：2026-10-01
- 执行模型：qwen3.8-max
- 目标：将重构后全部微服务配置同步至 Nacos 3.0.3；验证服务真实从 Nacos 拉取配置（替代旧 Config Server）
- **确认状态：✅ 2026-10-01 人工确认同步结果**

---

## 一、结论（先给结论）

1. 6 份配置已全部发布到本机 Nacos 3.0.3（namespace=dev，group=PIGGYMETRICS），读回字节级一致校验通过。
2. gateway 与 account-service 两个服务已**真实启动验证**：启动日志明确显示 `Load config[dataId=...] success`，actuator/env 证明运行时属性来源为 Nacos 属性源（PIGGYMETRICS@application-common.yml / PIGGYMETRICS@gateway.yml），服务同时注册到 Nacos dev 命名空间（注册中心功能一并验证）。
3. 过程中发现并修复 1 个配置缺陷（B4：YAML 重复顶级键导致 account/notification 配置加载失败），已复验通过。
4. 同步工具已沉淀为可重复执行脚本：piggymetrics-refactored/nacos-config/sync/（sync-to-nacos.py + create-namespace.py），供后续环境（staging/prod）复用。

## 二、Nacos 环境

| 项 | 值 |
|---|---|
| 服务端 | nacos/nacos-server:v3.0.3（本机已有容器 nacos-3.0.3，standalone + MySQL 外置存储，端口 8848/9848） |
| 鉴权 | NACOS_AUTH_ENABLE=true；客户端凭据经环境变量注入（凭据不落盘、不入代码库） |
| namespace | dev（本次脚本创建；staging/prod 待部署时同法创建） |
| group | PIGGYMETRICS |
| 客户端 | nacos-client 3.0.3（SCA 2025.0.0.0 BOM 管理，与服务端版本精确对齐） |

## 三、已同步配置清单（Nacos 控制台可查，读回校验 identical=True）

| dataId | 内容来源 | 大小 |
|---|---|---|
| application-common.yml | 旧 config/shared/application.yml 的仍有效部分（日志级别、actuator、tracing 采样）+ Nacos 来源标记 nacosConfigMarker.source | 430B |
| gateway.yml | 4 条路由（/uaa 固定 url、其余 lb://）+ 20s 超时（等价旧 zuul/ribbon）+ Sentinel dashboard | 986B |
| auth-service.yml | 端口 5000、context-path /uaa、Mongo 连接 | 348B |
| account-service.yml | 端口 6000、/accounts、Mongo、OAuth2 client 注册（client_credentials→/uaa/oauth2/token）、JWT issuer-uri、feign.sentinel | 957B |
| statistics-service.yml | 端口 7000、/statistics、Mongo、OAuth2 client、JWT、rates.url | 950B |
| notification-service.yml | 端口 8000、/notifications、Mongo、OAuth2 client、JWT、mail、backup/remind cron 与邮件模板（业务时点未动） | 1600B |

说明：敏感值（Mongo/服务密码）在 Nacos 配置中以 ${ENV_VAR} 占位符引用，实际值仍由环境变量注入——密钥治理（H6）另行立项。

## 四、服务从 Nacos 拉取配置的实证

### 4.1 gateway（端口 4000）

启动日志：
```
[Nacos Config] Load config[dataId=application-common.yml, group=PIGGYMETRICS] success
[Nacos Config] Load config[dataId=gateway.yml, group=PIGGYMETRICS] success
Netty started on port 4000 (http)
Started GatewayApplication in 2.256 seconds
[Nacos Config] Listening config: dataId=gateway.yml ...（动态刷新监听已注册）
```

actuator/env 属性来源证明：
```
nacosConfigMarker.source   -> source = PIGGYMETRICS@application-common.yml（该属性只存在于 Nacos，本地 application.yml 无此键）
management.endpoints...    -> source = PIGGYMETRICS@application-common.yml
propertySources 列表含：PIGGYMETRICS@application-common.yml、PIGGYMETRICS@gateway.yml
```

路由行为（配置由 Nacos 下发）：/accounts/current→503（lb 路由命中、无下游实例）；/foo→404（未声明路由不暴露，等价旧 zuul.ignoredServices='*'）。

### 4.2 account-service（冒烟端口 6001）

启动日志：
```
[Nacos Config] Load config[dataId=application-common.yml, group=PIGGYMETRICS] success
[Nacos Config] Load config[dataId=account-service.yml, group=PIGGYMETRICS] success
Tomcat started on port 6001 (http) with context path '/accounts'   ← context-path 来自 Nacos 配置
[REGISTER-SERVICE] dev registering service account-service ...
nacos registry, account-service 192.168.1.32:6001 register finished
Started AccountApplication in 2.645 seconds
```

### 4.3 Nacos 注册中心侧核验

```
v1/ns/service/list?namespaceId=dev -> {"count":2,"doms":["gateway","account-service"]}
gateway         实例 192.168.1.32:4000 healthy=True
account-service 实例 192.168.1.32:6001 healthy=True
v3/admin/cs/config/list (dev, PIGGYMETRICS) -> totalCount=6（全部 dataId 在列）
```

结论：配置中心拉取 + 注册中心注册双通道均已真实验证；旧 Config Server/Eureka 依赖彻底移除（服务启动全程未连接 config:8888/registry:8761）。

## 五、过程中发现并修复的缺陷

| # | 缺陷 | 根因 | 修复 | 风险 |
|---|---|---|---|---|
| B4 | account-service/notification-service 从 Nacos 加载配置时抛 DuplicateKeyException，服务启动失败 | 同步脚本追加的来源标记使用了与正文相同的顶级键 piggymetrics:，YAML 出现重复键（gateway 等无该键所以幸免——说明该缺陷只有真实加载才能暴露） | 标记改用独立顶级键 nacosConfigMarker；重新同步并复验两服务加载成功 | 无业务影响（标记仅用于验证）；同时验证了 Nacos 配置内容错误会被服务端如实暴露，同步脚本必须带读回校验 |

另：本地冒烟时曾用空 username 覆盖 Nacos 下发的 Mongo username 导致连接失败——属验证操作参数问题，与迁移无关，已用 --spring.data.mongodb.uri 覆盖解决（本地 mongo-it 容器无鉴权）。

## 六、遗留事项处置记录（2026-10-01 人工指令）

1. ✅ 已处置：冒烟进程 gateway(:4000)、account-service(:6001) 已按人工授权停止（SIGTERM 优雅停机，端口已释放，Nacos 临时实例已自动摘除，实测 instance/list hosts 为空）。mongo-it(:27117) 测试容器按指示保留未动。
2. staging/prod 命名空间与配置发布：用同一套脚本 --namespace <env> 执行；生产密钥治理见 H6。
3. ✅ 已决策（人工确认）：**保留现状** —— 服务本地 application.yml 保留完整默认值 + `optional:nacos:` 前缀。设计意图确认为回滚/降级保障：Nacos 不可达时服务仍可按本地配置启动，不做 fail-fast 变更。生产部署沿用此策略；Nacos 中的配置为权威来源（覆盖本地同名键），本地默认值仅兜底。

——本报告完毕。
