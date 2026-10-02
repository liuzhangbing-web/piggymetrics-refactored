# piggymetrics-refactored 主 docker-compose 全链路联调报告

- 执行日期：2026-10-02
- 执行模型：qwen3.8-max
- 工程：/Users/liuzhangbing/Downloads/D/workspace_tke/piggymetrics-refactored
- 编排：**主 docker-compose.yml**（含 Dockerfile 镜像构建）+ docker-compose.override.yml（本机端口适配）
- 命令：`docker compose build` → `docker compose up -d` → verify-main-compose.py
- 验证脚本：verify-main-compose.py（31 项断言，纯 urllib，规避 shell 掩码）

---

## 一、结论（先给结论）

1. **`docker compose build` 成功**：9 个镜像全部构建（5 个 eclipse-temurin:17-jre 服务镜像 + 4 个 mongodb 镜像），BUILD_EXIT=0。镜像内 JDK 实测 openjdk 17.0.20.1。
2. **`docker compose up -d` 全链路联调通过：31/31 断言 PASS，0 FAIL。** 12 容器全部运行，5 业务服务稳定启动、注册 Nacos(dev)、从 Nacos 拉取配置，跨服务真实调用链（网关→认证→账务→统计→通知→四库落库）端到端可用，金额黄金值精确一致，鉴权红线不放宽。
3. 联调发现并修复主 compose 的 **1 个真实网络缺陷（B7）**，以及处置若干环境适配项。均非业务代码缺陷。
4. 与上一轮 IT 编排联调（integration-test/docker-compose.it.yml）的区别：**本轮使用项目主 compose（真实 Dockerfile 构建镜像 + Nacos 外置 MySQL 持久化 + Sentinel Dashboard）**，更贴近生产部署形态。

## 二、docker compose build 结果

| 镜像 | 大小 | 基础镜像 |
|---|---|---|
| piggymetrics-refactored-gateway | 577MB | eclipse-temurin:17-jre |
| piggymetrics-refactored-auth-service | 578MB | eclipse-temurin:17-jre |
| piggymetrics-refactored-account-service | 603MB | eclipse-temurin:17-jre |
| piggymetrics-refactored-statistics-service | 600MB | eclipse-temurin:17-jre |
| piggymetrics-refactored-notification-service | 605MB | eclipse-temurin:17-jre |
| piggymetrics-refactored-{auth,account,statistics,notification}-mongodb | 各 646MB | mongo:4.4 (build ./mongodb) |

构建前置：`mvn package -DskipTests` 产出 5 个 fat-jar（68~83MB），Dockerfile ADD ./target/<svc>.jar。

## 三、运行拓扑（12 容器）

```
宿主机 :4000 ─▶ gateway (SCG/Netty)          [piggymetrics-refactored-gateway-1]
                  │ lb:// 经 Nacos(dev) 服务发现
    ┌─────────────┼──────────────┬─────────────────┐
    ▼             ▼              ▼                 ▼
auth-service  account-service statistics-service notification-service
:15000→5000   :6000         :17000→7000        :8000
(/uaa)        (/accounts)   (/statistics)      (/notifications)
    │             │              │                 │
auth-mongodb  account-mongodb statistics-mongodb notification-mongodb
:25000        :26000        :27000             :28000
(mongo:4.4, init.sh 建 user/p<env>, 库 piggymetrics)

nacos:v3.0.3 (:8848/:9848, 外置 MySQL 持久化, namespace=dev, group=PIGGYMETRICS, healthy)
mysql:8.0.46 (:3306, nacos_config 库)
sentinel-dashboard:1.8.9 (:8858, HTTP 200)
rates-mock (nginx:alpine, 接入 default 网络, 提供 /latest 汇率 JSON — D16 替代已停服外部源)

网络：nacos-net（nacos+mysql）+ default（业务服务+mongodb+nacos）
     ↑ B7 修复：nacos 同时接入 default，业务服务方能解析 host "nacos"
```

端口说明：auth-service 宿主端口 15000、statistics-service 17000（override 重映射，规避 macOS AirPlay 占用 5000/7000）；gateway 4000、其余按主 compose。容器网络内部端口不变，不影响服务间调用。

## 四、31 项断言结果（真实执行输出）

| 分类 | 断言 | 结果 |
|---|---|---|
| 1 注册中心 | auth/account/statistics/notification/gateway 各 1 实例注册到 Nacos(dev) | 5 PASS |
| 2 配置中心 | 5 服务启动日志 `Load config[dataId=...] success`（statistics 因重启累积 4 份） | 5 PASS |
| 3 网关 | /actuator/health→200；未声明路由 /foo→404（等价 zuul ignoredServices='*'） | 2 PASS |
| 4 认证 JWT | 开户首次 200；password grant 签发 662 字符 JWT；client_credentials server-scope JWT；无 token→401；错误密码→400 invalid_grant | 5 PASS |
| 5 账务 🔴 | GET /accounts/current 返回 demo 账户 saving.currency=USD；PUT 账务变更+统计联动→200 | 2 PASS |
| 6 统计金额 🔴 | DataPoint 金额与黄金值精确一致：INCOMES=36.5052（1000 EUR/MONTH）、EXPENSES=16.4275（500 USD/MONTH）、SAVING=200 | 1 PASS |
| 7 通知 | PUT recipients→200；读回一致（Frequency 枚举转换器 round-trip） | 2 PASS |
| 8 鉴权红线 🔴 | ui-scope 访问他人统计→403；demo 例外→200；ui-scope PUT 服务间端点→403；server-scope PUT→200 | 4 PASS |
| 9 落库 | users/accounts/datapoints/recipients 四库各 1 条；users 密码 $2a$ BCrypt（非明文） | 5 PASS |
| 合计 | | **31 PASS / 0 FAIL** |

金额黄金值由 Python Fractions 精确复刻 BigDecimal 语义（ratio=halfUp(1/0.9,4)=1.1111；income=halfUp(1000×1.1111÷30.4368,4)=36.5052；expense=halfUp(500×1.0÷30.4368,4)=16.4275）独立算出，与服务实际输出逐位一致。

## 五、发现的问题与处置

### A. 真实缺陷（已修复）

| # | 缺陷 | 根因 | 修复 | 风险/影响 |
|---|---|---|---|---|
| B7 🔴 | 5 个业务服务启动即崩溃退出（exit 1），Nacos gRPC 注册失败 `Client not connected, current status:STARTING` / `UnknownHostException: nacos` | **主 compose 网络设计缺陷**：nacos/mysql 仅在 `nacos-net`，业务服务与各自 mongodb 在 `default` 网络，跨网络无法通过 DNS 解析 host `nacos`（config 8848 + discovery gRPC 9848 均不可达）。IT 编排因所有服务同网络而未暴露此问题 | 主 compose 的 nacos 服务 networks 增加 `default`（同时保留 nacos-net），使业务服务可解析 nacos；nacos 注册的 default 网络 IP 亦被网关 lb:// 与 Feign 正常互通 | 中→已消除。这是基础设施编排缺陷，**在 Linux 生产部署同样会触发**，属必须修复项（非本机特有）。业务代码零改动 |
| B6 | 主 compose 中 6 处服务密码环境变量引用被安全掩码破坏为 `$NOTIFI…WORD` 等（U+2026 省略号进入文件），compose 解析异常 | 早期写入时掩码机制损坏 | 用字符片段重建为 `${NOTIFICATION_SERVICE_PASSWORD}` 等正确引用；NACOS_AUTH_TOKEN 明文改为 `${NACOS_AUTH_TOKEN}`（值入 .env） | 低（编排文件完整性）；已 `docker compose config` 校验 YAML 合法 |

### B. 环境适配（本机特有，非代码缺陷）

| # | 项 | 处置 |
|---|---|---|
| E1 | macOS AirPlay 占用宿主 5000/7000 | docker-compose.override.yml 用 `!override` 重映射 auth→15000、statistics→17000（Linux 部署无需此文件） |
| E2 | Nacos 3.0.3 强制要求 NACOS_AUTH_TOKEN（Base64，≥32字节解码） | compose 注入 ${NACOS_AUTH_TOKEN}（.env 提供合法 Base64） |
| E3 | compose healthcheck 探测 /nacos/actuator/health→404（Nacos 3.x 无此端点） | 改为 v3 admin state 接口 + identity header |
| E4 | v1 auth/login 在本部署返回 500（"Handle API Compatibility failed"，auth 未启用） | provision 脚本容忍 500，改用匿名 + identity header 访问 v1 cs/configs 与 v3 admin（发布/读回均成功） |
| E5 | D16 外部汇率源 api.exchangeratesapi.io 已停服 | 起 rates-mock(nginx) 接入 default 网络，向 Nacos(dev) 发布 IT 专用 statistics-service.yml 覆盖 rates.url=http://rates-mock（**不改 sync 源模板，保留 D16 决策**），重启 statistics 生效 |
| E6 | 镜像拉取：docker.io 直连超时 | eclipse-temurin:17-jre 经 docker.1ms.run 镜像源；sentinel-dashboard 同源 |

### C. 验证脚本工程化（吸取上一轮教训）

上一轮 IT 联调中 bash 脚本的密码/变量引用被终端安全掩码反复破坏（`Bearer ***`、省略号入文件致 bad substitution），耗时多轮排查。**本轮改用纯 Python urllib 验证脚本**（verify-main-compose.py）：无 shell 引号转义、无命令行明文密码（凭据从 .env 读取、demo 测试密码在脚本内拼装），一次性 31/31 通过，未再出现掩码类假失败。此教训已沉淀入 skill。

## 六、Nacos 配置与注册实证

- 配置发布：provision-main-nacos.py 创建 dev 命名空间 + 发布 6 份配置到 group=PIGGYMETRICS，读回字节级一致，掩码完整性检查通过（无 U+2026/***）。
- 服务拉取：5 服务启动日志均 `Load config[dataId=<app>.yml] success` + `Load config[dataId=application-common.yml] success`，并注册动态刷新监听。
- 注册中心：v1 ns/instance/list（dev 命名空间）返回 5 服务各 1 健康实例。
- Sentinel Dashboard :8858 HTTP 200，服务已接入（feign.sentinel.enabled=true）。

## 七、当前环境状态与复跑方式

运行中容器（12 + rates-mock）：nacos-3.0.3、mysql8-nacos、sentinel-dashboard、5 业务服务、4 mongodb、rates-mock。均保留供人工复核。

复跑（从干净状态）：
```
cd /Users/liuzhangbing/Downloads/D/workspace_tke/piggymetrics-refactored
mvn package -DskipTests
docker compose build
docker compose up -d mysql nacos sentinel-dashboard   # 等 nacos healthy
python3 provision-main-nacos.py                        # 建 dev 命名空间 + 发布 6 配置
docker compose up -d                                   # 起全部业务服务（depends_on 门禁）
docker run -d --name rates-mock --network piggymetrics-refactored_default \
  -v $(pwd)/integration-test/rates-mock/latest.json:/usr/share/nginx/html/latest.json:ro \
  -v $(pwd)/integration-test/rates-mock/default.conf:/etc/nginx/conf.d/default.conf:ro nginx:alpine
python3 it-override-rates.py && docker compose restart statistics-service   # D16 mock 覆盖
python3 verify-main-compose.py                         # 期望 PASS=31 FAIL=0
```

清理（需人工授权，高危命令不自动执行）：
```
docker compose down            # 停业务服务+nacos+mysql（保留数据卷）
docker rm -f rates-mock
docker compose down -v         # 连数据卷一并删除（彻底清理）
```

## 八、遗留与生产前置项（如实披露）

1. 🔴 H6：JWT 签名密钥仍为启动时生成，多实例生产部署前必须接入 KMS/Nacos 加密固定密钥。
2. 🔴 D16：本轮用 rates-mock 替代汇率源仅为联调；生产必须替换为可审计的银行内部牌价服务。
3. B7 修复已入主 compose；E1 端口重映射在 override（Linux 部署可删）。
4. rates-mock 非主 compose 服务（临时联调组件），生产编排不含它。
5. mongodb 初始化镜像仍 mongo:4.4 + legacy mongo shell（原样保留），生产建议独立立项升级 mongosh。

## 九、与既有验证的关系（三层验证全覆盖）

| 层级 | 手段 | 结果 |
|---|---|---|
| 代码级 | mvn clean test（单元+切片+真实 Mongo 集成） | 75/75 全绿 |
| 部署级（IT 编排） | docker-compose.it.yml（同 jar，临时网络） | 31/31 PASS |
| 部署级（主 compose） | **本轮：docker compose build + up（真实 Dockerfile 镜像 + Nacos/MySQL/Sentinel）** | **31/31 PASS** |

三层验证的 🔴 红线断言（账务链路、金额黄金值、鉴权不放宽、数据落库、BCrypt）结果完全一致。

——主 compose 全链路联调报告完毕。
