# piggymetrics-refactored 主 docker-compose 全链路联调报告（第 2 轮 · 冷启动全量重建）

- 执行日期：2026-10-02
- 执行模型：qwen3.8-max
- 工程：/Users/liuzhangbing/Downloads/D/workspace_tke/piggymetrics-refactored
- 编排：**主 docker-compose.yml**（真实 Dockerfile 构建）+ docker-compose.override.yml（本机端口适配）
- 本轮特点：**从干净状态冷启动** —— 执行前所有容器/命名卷已清空，`docker compose build` 全量重建镜像，Nacos 从零 provision，完整走 build → 基础设施 → 配置发布 → up → 验证全流程
- 命令序列：mvn package → docker compose build → up(mysql/nacos/sentinel) → provision-main-nacos.py → up(全部) → rates-mock + it-override-rates.py → verify-main-compose.py

---

## 一、结论（先给结论）

1. **`docker compose build` 成功（冷启动全量）**：BUILD_EXIT=0，9 镜像全部构建。5 个服务镜像为本轮新构建（CreatedSince=4 分钟），4 个 mongodb 镜像命中 Docker 层缓存（构建上下文未变，属正常增量行为）。镜像内 JDK 实测 openjdk 17.0.20.1。
2. **`docker compose up -d` 全链路联调通过：31/31 断言 PASS，0 FAIL。** 13 容器运行，5 业务服务从全新 Nacos（数据卷为空、dev 命名空间本轮新建）成功拉取配置、注册、启动，跨服务真实调用链端到端可用，金额黄金值精确一致，鉴权红线不放宽。
3. **B7（主 compose 网络缺陷）修复经本轮冷启动复验有效**：业务服务未再出现上一轮的 `UnknownHostException: nacos` 崩溃，稳定 Up。
4. 本轮是**可复现的冷启动验证**（非热重启），证明从零部署路径完整可用。

## 二、docker compose build 结果（本轮）

| 镜像 | 大小 | 本轮状态 |
|---|---|---|
| piggymetrics-refactored-gateway | 577MB | 新构建（4 分钟前） |
| piggymetrics-refactored-auth-service | 578MB | 新构建 |
| piggymetrics-refactored-account-service | 603MB | 新构建 |
| piggymetrics-refactored-statistics-service | 600MB | 新构建 |
| piggymetrics-refactored-notification-service | 605MB | 新构建 |
| piggymetrics-refactored-{auth,account,statistics,notification}-mongodb | 各 646MB | 缓存命中（上下文未变） |

构建日志统计：9 个 "Built"，BUILD_EXIT=0。

## 三、运行拓扑（13 容器）

```
宿主机 :4000 ─▶ gateway (SCG/Netty)
                  │ lb:// 经 Nacos(dev) 服务发现
    ┌─────────────┼──────────────┬─────────────────┐
    ▼             ▼              ▼                 ▼
auth-service  account-service statistics-service notification-service
:15000→5000   :6000         :17000→7000        :8000
(/uaa)        (/accounts)   (/statistics)      (/notifications)
    │             │              │                 │
auth-mongodb  account-mongodb statistics-mongodb notification-mongodb
:25000        :26000        :27000             :28000  (mongo:4.4, init.sh 建 user, 库 piggymetrics)

nacos:v3.0.3 (:8848/:9848, 外置 MySQL 持久化, namespace=dev 本轮新建, group=PIGGYMETRICS, healthy)
mysql:8.0.46 (:3306, nacos_config)
sentinel-dashboard:1.8.9 (:8858, HTTP 200)
rates-mock (nginx:alpine, default 网络, /latest 汇率 JSON — D16 替代已停服外部源)

网络：nacos-net（nacos+mysql）+ default（业务服务+mongodb+nacos+rates-mock）
     B7 修复：nacos 同时接入 default，业务服务方能解析 host "nacos"（config 8848 + discovery gRPC 9848）
```

## 四、31 项断言结果（真实执行输出，本轮）

| 分类 | 断言 | 结果 |
|---|---|---|
| 1 注册中心 | auth/account/statistics/notification/gateway 各 1 实例注册 Nacos(dev) | 5 PASS |
| 2 配置中心 | 5 服务启动日志 `Load config[dataId=...] success`（statistics 因重启累积 4 份） | 5 PASS |
| 3 网关 | /actuator/health→200；未声明路由 /foo→404（等价 zuul ignoredServices='*'） | 2 PASS |
| 4 认证 JWT | 开户首次 200；password grant 签发 662 字符 JWT；client_credentials server-scope JWT；无 token→401；错误密码→400 invalid_grant | 5 PASS |
| 5 账务 🔴 | GET /accounts/current 返回 demo 账户 saving.currency=USD；PUT 账务变更+统计联动→200 | 2 PASS |
| 6 统计金额 🔴 | DataPoint 金额与黄金值精确一致：INCOMES=36.5052（1000 EUR/MONTH）、EXPENSES=16.4275（500 USD/MONTH）、SAVING=200 | 1 PASS |
| 7 通知 | PUT recipients→200；读回一致（Frequency 枚举转换器 round-trip） | 2 PASS |
| 8 鉴权红线 🔴 | ui-scope 访问他人统计→403；demo 例外→200；ui-scope PUT 服务间端点→403；server-scope PUT→200 | 4 PASS |
| 9 落库 | users/accounts/datapoints/recipients 四库各 1 条；users 密码 $2a$ BCrypt（非明文） | 5 PASS |
| 合计 | | **31 PASS / 0 FAIL** |

金额黄金值由 Python Fractions 精确复刻 BigDecimal 语义独立算出（ratio=halfUp(1/0.9,4)=1.1111；income=halfUp(1000×1.1111÷30.4368,4)=36.5052；expense=halfUp(500×1.0÷30.4368,4)=16.4275），与服务实际输出逐位一致。

## 五、本轮处置记录

### A. 缺陷状态（本轮为复验，无新增业务/编排缺陷）

| # | 缺陷 | 本轮状态 |
|---|---|---|
| B7 🔴 | 主 compose 网络拆分导致业务服务无法解析 nacos（启动崩溃） | **修复复验通过**：nacos 已接入 default 网络，本轮冷启动 5 服务全部稳定 Up，无 UnknownHostException |
| B6 | 主 compose 密码环境变量引用被掩码破坏 | 已修复并经 `docker compose config` + 本轮 up 复验，无解析错误 |
| B5 | Nacos 配置模板密码占位符损坏 | sync 模板已是正确 `${VAR:}`，本轮发布前经掩码完整性检查（无 U+2026/***）通过 |

### B. 环境适配（沿用上一轮方案，本机特有，非缺陷）

| # | 项 | 本轮处置 |
|---|---|---|
| E1 | macOS AirPlay 占用 5000/7000 | override 重映射 auth→15000、statistics→17000 |
| E2 | Nacos 3.x 强制 NACOS_AUTH_TOKEN | compose 注入 ${NACOS_AUTH_TOKEN}（.env 合法 Base64） |
| E3 | healthcheck /actuator/health 404 | 探测 v3 admin state + identity header |
| E4 | v1 auth/login 返回 500（auth 未启用） | provision 脚本匿名 + identity header 发布/读回成功 |
| E5 | D16 外部汇率源停服 | rates-mock + it-override-rates.py 向 Nacos(dev) 发布 IT 专用 rates.url（不改 sync 源模板） |
| — | Nacos 配置首次 readback 时序 | provision 首次 readback 全 False，sleep 后重跑全 True（Nacos 写后读最终一致，非内容问题） |

## 六、冷启动可复现性

本轮验证了**从零部署**完整路径（执行前容器与命名卷均为空）：
1. mvn package → 5 fat-jar
2. docker compose build → 9 镜像（BUILD_EXIT=0）
3. up 基础设施 → nacos healthy（全新 MySQL schema 初始化）
4. provision-main-nacos.py → dev 命名空间新建 + 6 配置发布读回一致
5. up 全部 → 13 容器运行，5 服务稳定启动
6. rates-mock + rates.url 覆盖 → statistics 重启
7. verify-main-compose.py → 31/31 PASS

复跑命令见《主compose全链路联调报告.md》第七节（本轮与上轮命令序列一致，唯一区别是本轮起始状态为全空）。

## 七、当前环境状态

运行中：13 容器（nacos-3.0.3、mysql8-nacos、sentinel-dashboard、5 业务服务、4 mongodb、rates-mock）+ gateway。均保留供人工复核。

清理（需人工授权，高危命令不自动执行）：
```
docker compose down            # 停业务服务+nacos+mysql（保留数据卷）
docker rm -f rates-mock
docker compose down -v         # 连数据卷一并删除（彻底清理，回到本轮起始的空状态）
```

## 八、遗留与生产前置项（如实披露）

1. 🔴 H6：JWT 签名密钥仍为启动时生成（JwtKeyProvider），多实例生产部署前必须接入 KMS/Nacos 加密固定密钥。本轮单实例联调不受影响。
2. 🔴 D16：rates-mock 仅为联调替代；生产必须替换为可审计的银行内部牌价服务。
3. mongodb 初始化镜像仍 mongo:4.4 + legacy mongo shell（原样保留），生产建议独立立项升级 mongosh。
4. E1 端口重映射在 override 文件（Linux 生产部署可删除该文件，用主 compose 标准端口）。

## 九、三层验证累计结论

| 层级 | 手段 | 结果 |
|---|---|---|
| 代码级 | mvn clean test（单元+切片+真实 Mongo 集成） | 75/75 全绿 |
| 部署级（IT 编排） | docker-compose.it.yml 全链路 | 31/31 PASS |
| 部署级（主 compose · 第1轮） | docker compose build + up（热态） | 31/31 PASS |
| 部署级（主 compose · 本轮冷启动） | **docker compose build 全量 + up 从零** | **31/31 PASS** |

四层验证的 🔴 红线断言（账务链路、金额黄金值、鉴权不放宽、数据落库、BCrypt）结果完全一致，重构系统在真实容器编排下可复现、可冷启动、端到端可用。

——第 2 轮主 compose 冷启动全量联调报告完毕。
