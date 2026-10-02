# piggymetrics-refactored 测试用例设计文档（四层金字塔版）

- 编制角色：deepseek-chat（测试用例与文档编制，不写测试代码、不执行测试、不改业务代码）
- 被测工程：/Users/liuzhangbing/Downloads/D/ai/piggymetrics-refactored
- 技术栈：JDK17 + Spring Boot 3.5.3 + Spring Cloud 2025.0.0 + Spring Cloud Alibaba 2025.0.0.0 + Nacos 3.0.3 + Sentinel + Spring Authorization Server(JWT) + MongoDB 4.4
- 编制日期：2026-10-02
- 文档用途：作为阶段4（qwen 编写测试代码 + `mvn test` + 缺陷修复）与阶段4.5（真实容器全链路/系统验证）的**测试需求规格**，驱动测试实现

---

## 一、文档目的与定位

本目录按 **测试金字塔四层** 重新组织并补全整个重构工程的测试用例，与既有的两份按服务组织的文档（`docs/阶段3-测试用例设计文档.md` 51 用例、`docs/阶段3-方案A测试用例设计文档.md` 38 用例）**互补而非替代**：

| 维度 | 既有阶段3文档 | 本文档（dp/） |
|---|---|---|
| 组织方式 | 按服务模块 + 按方案A变更组 | 按测试层级（单元/功能/全链路/系统） |
| 粒度 | 用例清单（较简） | 每用例含前置/步骤/数据/预期/优先级/关联追溯 |
| 覆盖重心 | 等价迁移回归 + 补偿路径 | 新增**系统级非功能测试**（性能/限流/降级/可观测/安全/并发/回滚） |
| 关系 | 红线用例来源 | 红线用例继承并扩写 |

**核心原则（继承全部硬约束）**：
1. 🔴 标记 = 金融红线用例（账务/金额/鉴权），失败即阻塞发布，结果须人工签核。
2. 成功路径行为以旧工程为基准，断言"等价迁移"；金额类用例必须与黄金数据 `compareTo==0` 逐分比对。
3. 测试仅允许 `mvn test`（代码级）+ 本地真实容器脚本（全链路/系统级）；禁止 drop/truncate/deleteMany 等破坏性数据库操作。
4. 任何 🔴 用例失败，只允许修改测试适配已确认行为，严禁放宽生产代码断言来"修复"失败。

---

## 二、测试分层策略（金字塔）

```
              ┌─────────────┐
              │  系统测试     │  15 用例  SYS-*   非功能：性能/限流/降级/可观测/安全/并发/回滚
              │ (System)    │       真实容器 + 压测脚本 + 观测平台
              ├─────────────┤
              │  全链路测试   │  16 用例  E2E-*   跨服务端到端（经网关，真实 Nacos/Mongo/Sentinel）
              │  (E2E)      │
              ├─────────────┤
              │  功能测试     │  38 用例  FT-*    接口/切片/鉴权矩阵/校验/持久化（@WebMvcTest/@DataMongoTest/@SpringBootTest）
              │ (Functional)│
              ├─────────────┤
              │  单元测试     │  45 用例  UT-*    类/方法级隔离（Mockito，mock 依赖）
              │   (Unit)    │
              └─────────────┘
```

| 层级 | 目标 | 隔离边界 | 主要手段 | 数量 |
|---|---|---|---|---|
| 单元测试 UT-* | 验证单个类/方法的业务逻辑与边界 | 进程内，mock 所有外部依赖（repository/FeignClient/邮件） | JUnit5 + Mockito | 44 |
| 功能测试 FT-* | 验证单服务对外契约：接口、鉴权矩阵、参数校验、持久化映射 | 单服务切片（不跨服务） | @WebMvcTest + spring-security-test + @DataMongoTest / 真实 Mongo | 49 |
| 全链路测试 E2E-* | 验证跨服务主链路与补偿自愈在真实拓扑下正确 | 真实容器拓扑（网关→服务→库） | docker compose + verify-*.py 脚本 | 16 |
| 系统测试 SYS-* | 验证非功能需求：性能/韧性/可观测/安全/并发/回滚 | 全栈环境 + 观测平台 | 压测/注入故障/观测断言 | 15 |

**合计 124 用例**，其中 🔴 金融红线用例 50 个。

---

## 三、金融红线清单（🔴 用例索引）

红线文件（详见 `docs/阶段5-开发文档.md` 第 5 节）与用例映射：

| 红线区域 | 文件 | 对应用例 |
|---|---|---|
| 开户链路调用顺序 | AccountServiceImpl.create | UT-ACC-003/004/005/012, FT-ACC-001/002/003 |
| 账务保存+统计联动 | AccountServiceImpl.saveChanges | UT-ACC-006/007, E2E-002/010 |
| 金额换算 scale=4/HALF_UP | StatisticsServiceImpl / ExchangeRatesServiceImpl | UT-STA-001/002/003, UT-EXR-001/002, FT-STA-*, E2E-002/010 |
| 汇率基准/周期常量 | TimePeriod.baseRatio | UT-STA-001/002 |
| ItemMetric equals/hashCode | ItemMetric.java | UT-STA-006 |
| 客户端 grant/scopes | RegisteredClientRepositoryConfig | FT-AUTH-001~008/017 |
| 端点鉴权白名单 | WebSecurityConfig + 各 ResourceServerConfig | FT-ACC-007/008/009, FT-AUTH-012/013/014/015, FT-STA-002/003 |
| @PreAuthorize 表达式 | 各 Controller | FT-ACC-001/002/003, FT-STA-001/002/003, FT-AUTH-009/010 |
| 通知 cron 时点 | notification application.yml | UT-NOT-004/005 |
| 补偿删除端点鉴权 | UserController.deleteUser | FT-AUTH-012/013/014/015 |

---

## 四、追溯矩阵（债务/风险/缺陷 → 用例）

| 溯源项 | 内容 | 覆盖用例 |
|---|---|---|
| D5/D6/D7/D8 | OAuth2 体系替换、token 无状态化、NoOpPasswordEncoder 废除 | FT-AUTH-001~008/016, SYS-011 |
| D9/D11/D12 | Jackson1.x→fasterxml、javax→jakarta、校验注解迁移 | FT-ACC-006, FT-NOT-*, UT-EML-003 |
| D16 | 汇率源停服 | UT-EXR-006, SYS-003/005 |
| D17/D18 | 审计字段缺失、日志注入 | SYS-014, UT-USR-004, UT-CMP-003 |
| R1/R2 | 开户竞态、孤儿用户 | UT-ACC-004/009/010, E2E-007 |
| R3 | 账务-统计不一致 | UT-ACC-008, E2E-008 |
| R4 | 汇率缓存并发 | UT-EXR-005, SYS-008 |
| R5 | 金额精度累积 | UT-STA-002, UT-EXR-001 |
| R6/R7 | 通知重复发送/线程饥饿 | UT-NOT-*, SYS-009 |
| R8 | 越权读取 | FT-ACC-003, FT-STA-002/003 |
| H6 | JWT 密钥按启动生成 | SYS-011, FT-AUTH-006 |
| 方案A | 幂等补偿+对账自愈 | UT-CMP-001~009, FT-AUTH-012~015, E2E-007/008/013 |
| K1/K2/K3 | ItemMetric equals、汇率 NPE、补偿队列 | UT-STA-006, UT-EXR-002/006, UT-CMP-* |

---

## 五、环境与工具约定

| 项 | 约定 |
|---|---|
| JDK | 17（`export JAVA_HOME=$(/usr/libexec/java_home -v 17)`） |
| Maven | `~/tools/apache-maven-3.9.9/bin/mvn`（禁止 `-T` 并行，本地仓库锁冲突） |
| 代码级测试命令 | 仅 `mvn test` |
| 真实 Mongo | 复用 compose 内 4 个 mongodb 容器；集成测试经 `TEST_MONGO_URI` 注入（@EnabledIf 机制）；**禁止 Testcontainers**（本机 docker-java 对 Docker Desktop 返回 400） |
| 全链路脚本 | `verify-main-compose.py`(31 断言) / `verify-compensation-e2e.py`(11) / `verify-mongo-real.py`(9) / `verify-skywalking.py`(7) |
| 凭据 | 一律 `.env` 读取或字符拼接构造，严禁命令行明文（Hermes 掩码坑） |
| 测试账户命名 | `ut-*`/`ft-*`/`e2e-*`/`orphan-heal-*`/`stat-heal-*`（时间戳后缀，用后即清） |

---

## 六、子文档索引

| 文档 | 层级 | 用例数 | 内容 |
|---|---|---|---|
| [01-单元测试用例.md](./01-单元测试用例.md) | 单元 | 44 | 服务层/转换器/域对象隔离测试，Mockito |
| [02-功能测试用例.md](./02-功能测试用例.md) | 功能 | 49 | 接口契约/鉴权矩阵/参数校验/持久化映射 |
| [03-全链路测试用例.md](./03-全链路测试用例.md) | 全链路 | 16 | 跨服务主链路 + 补偿自愈 + 回归断言 |
| [04-系统测试用例.md](./04-系统测试用例.md) | 系统 | 15 | 性能/限流/降级/可观测/安全/并发/回滚 |

---

## 七、执行门禁与验收标准

1. **阶段4（qwen）**：按本目录 01/02 层编写测试代码，`mvn test` 全绿；🔴 用例人工签核。
2. **阶段4.5（qwen，真实容器）**：按 03/04 层执行脚本化验证，全链路/系统断言全 PASS。
3. **验收**：114 用例全绿；既有 87 单测 + 31 E2E + 9 Mongo + 7 SkyWalking 断言零回归；金额黄金值 `compareTo==0`。

—— 本目录为测试需求规格，**等待人工确认后**进入测试代码实现阶段。
