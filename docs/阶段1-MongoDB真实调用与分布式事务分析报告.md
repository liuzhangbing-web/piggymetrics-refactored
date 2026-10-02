# 阶段1分析报告：MongoDB 真实调用验证 + 分布式事务必要性评估（Seata）

- 日期：2026-10-02
- 执行模型：qwen3.8-max
- 工程路径：/Users/liuzhangbing/Downloads/D/ai/piggymetrics-refactored
- 性质：**只读分析 + 真实环境验证**，未修改任何业务代码（按串行流程，改造需人工确认后进入阶段2）

---

## 一、结论（先给结论）

1. **MongoDB 真实调用：4/4 服务全部真实读写各自独立 Mongo 库，数据落地级验证 9/9 PASS，无需任何代码修改。** gateway 无数据库依赖（正确设计）。
2. **分布式事务：存在 1 个已实证的一致性缺口（开户链路孤儿用户），但 Seata AT 模式技术上不适用本工程（MongoDB 不在 AT 支持列表，且本地 Mongo 为 standalone 无副本集，连本地多文档事务都不支持）。** 若引入 Seata 只能用 TCC/SAGA 模式，属于侵入业务代码的变更，且触碰金融红线（开户/账务），**必须人工审核确认后才能实施**。
3. 候选方案已按低/中/高风险分级（第四节），**推荐先行方案A（低风险：幂等补偿+对账，不引入 Seata）**，方案B（Seata TCC）作为需要强一致时的升级路径。**本报告阶段不改代码，等待人工确认选型。**

## 二、任务3验证：各微服务是否真实调用自己的 MongoDB

### 2.1 静态分析（代码级）

| 服务 | Repository | 集合（@Document） | Mongo 配置 host | 结论 |
|---|---|---|---|---|
| auth-service | UserRepository extends CrudRepository | `users` | auth-mongodb | 真实调用 |
| account-service | AccountRepository extends CrudRepository | `accounts` | account-mongodb | 真实调用 |
| statistics-service | DataPointRepository extends CrudRepository | `datapoints`（复合主键 _id.account+date） | statistics-mongodb | 真实调用 |
| notification-service | RecipientRepository extends CrudRepository | `recipients` | notification-mongodb | 真实调用 |
| gateway | 无（pom 无 mongodb 依赖） | — | — | 正确：网关无状态 |

所有服务经 Spring Data Mongo Repository 真实持久化，无 mock/内存实现。4 个独立 Mongo 容器（database-per-service 模式，均连 `piggymetrics` 库名但物理隔离）。

### 2.2 动态验证（真实环境，数据落地级证明）

验证脚本：`verify-mongo-real.py`（已入库，可重复执行）。方法：API 写入 → `docker exec` 直查各 Mongo 容器核对数据落地。

```
[1] API 写入（经网关真实链路）
  [PASS] 开户 POST /accounts/ 200
  [PASS] password grant 取 token 200 (682 chars)
  [PASS] 账务变更 PUT /accounts/current 200
  [PASS] 通知设置 PUT recipients 200
[2] 直接查各服务自己的 MongoDB
  [PASS] auth-mongodb.users         有该用户且密码为 BCrypt($2a$) 非明文
  [PASS] account-mongodb.accounts   有账务文档(note/saving 落地)
  [PASS] statistics-mongodb.datapoints 有统计数据点(含汇率换算结果)
  [PASS] notification-mongodb.recipients 有收件人设置
  [PASS] 库间数据隔离（account 库无 users 集合 / auth 库无 accounts 集合）
[3] gateway pom 无 mongodb 依赖
==================================================
MONGO_VERIFY_OK 全部服务真实读写各自 MongoDB
```

补充证据：
- 各服务启动日志均有 `Monitor thread successfully connected to server ... address=<自己的>-mongodb:27017, state=CONNECTED`
- statistics 落库的 datapoint 含精确换算值（`INCOMES_AMOUNT=36.5052`、`EXPENSES_AMOUNT=16.4275`、`SAVING_AMOUNT=200.0000`），与阶段4黄金数据一致，金额精度红线未被破坏
- 连接凭证走容器环境变量（`MONGODB_PASSWORD`），日志中密码显示 `<hidden>`

### 2.3 验证过程中发现并处理的环境问题（非代码缺陷）

| 问题 | 处理 |
|---|---|
| D16 遗留：外部汇率源 exchangeratesapi.io 已停服 → statistics 换算 NPE，datapoint 不落库（**这正是"静默降级导致数据漂移"的真实案例**，见 3.2） | 启动 rates-mock 容器（接入主网络）+ 用 `it-override-rates.py` 将 Nacos dev 命名空间 statistics-service.yml 的 rates.url 覆盖为 http://rates-mock（不改提交的模板） |
| 无 | 本次验证未修改任何业务代码/配置模板 |

**当前环境状态披露**：rates-mock 容器运行中；Nacos dev 命名空间 statistics-service.yml 为 rates-mock 覆盖版（与 IT 联调时一致）。

## 三、任务4分析：是否需要分布式事务

### 3.1 跨服务写路径盘点（全工程零 @Transactional，已核实）

| 链路 | 写操作序列 | 失败窗口 | 后果 | 实证 |
|---|---|---|---|---|
| **开户** `POST /accounts/` | ① Feign→auth 建用户（先）② 本地存 account（后） | ①成功②失败 | **auth 库孤儿用户；重试开户被"user already exists"永久卡死（401）** | ✅ 已实证复现：删除 account 文档模拟②失败后，重试返回 401，孤儿用户确认存在（测试数据已清理） |
| **账务变更** `PUT /accounts/current` | ① 本地存 account（先）② Feign→statistics 更新统计（后） | ②失败 | statistics 数据陈旧（漂移）；fallback 仅记 ERROR 日志，**调用方拿到 200 无感知** | ✅ 已实证：汇率源停服期间 PUT 返回 200 但 datapoints 无数据 |
| 通知设置 `PUT recipients` | 单库单文档写 | — | 无跨库问题 | — |
| notification cron（提醒/备份） | 只读 account | — | 只读，无需事务 | — |

### 3.2 一致性需求评估（金融视角）

- **开户链路（缺口1）**：孤儿用户 = 用户名被永久占用、用户无法完成注册。属**可用性问题而非资金正确性问题**（无金额写入）。发生概率低（①②间毫秒级窗口 + 需恰好本地写失败），但一旦发生无自愈手段。**需要修复，修复方式不必然是分布式事务框架。**
- **账务变更链路（缺口2）**：account（真相源）先写成功，statistics（派生数据）失败——这是**可最终一致**的场景：派生数据可由真相源重算。旧系统（Netflix 版）行为完全相同（同款 fallback 静默降级），本工程按"等价迁移、零业务改动"原则保留。**引入强一致反而改变了 legacy 语义**；更合适的是补偿重算/对账。
- **资金正确性**：两条链路均无双写金额（金额只在 account 单库落地），不存在"扣款成功入账失败"类资金撕裂场景。**分布式事务的必要性为"中"而非"高"。**

### 3.3 Seata 适用性核查（关键技术约束，官方文档核实）

| Seata 模式 | 对本工程适用性 | 依据 |
|---|---|---|
| **AT** | ❌ **不适用** | 官方支持库仅 MySQL/Oracle/PostgreSQL/TiDB/MariaDB（JDBC DataSourceProxy + undo_log 表机制），**MongoDB 不支持**；且本地 Mongo 为 standalone（rs.status().ok=0，无副本集），MongoDB 4.0+ 的多文档本地事务本身就要求副本集 |
| **TCC** | ⚠️ 可用但侵入 | 与存储无关（Try/Confirm/Cancel 手写业务补偿），需改 account/auth 服务代码——**触碰金融红线（开户/账务），必须人工审核** |
| **SAGA** | ⚠️ 可用但重 | 需状态机定义 + 补偿服务，对 2 步链路而言复杂度不成比例 |
| XA | ❌ 不适用 | 同 AT，依赖 XA 数据源 |

**结论：如果确认要强一致改造，唯一可行路线是 Seata TCC（AT 被 MongoDB 硬性排除）。** 但 TCC 需要把 auth.createUser/account.save 拆成 Try-Confirm-Cancel 三接口，属于对红线区域业务代码的结构性修改——按项目硬约束，此类变更**单独标记为高风险，必须人工审核确认后实施**。

## 四、候选方案（按风险分级，等待人工选型确认）

### 方案A：幂等补偿 + 对账自愈（🟢 低风险，推荐先行）

不引入 Seata，不改红线语义，纯增量：
1. **开户链路**：account-service 在本地 save 失败时执行**补偿删除**（调 auth 删除刚建用户）；补偿也失败则落**补偿任务表**（Mongo 本地集合）+ 定时对账任务重试（auth.createUser 幂等化改造仅需"已存在且密码哈希一致→视为成功"，语义为收紧非放宽）。
2. **账务变更链路**：statistics fallback 从"仅记日志"升级为"记日志 + 落重算任务表"，定时任务按 accountName 重算 datapoint（真相源重算，天然幂等）。
3. 新增对账 Job（每服务本地集合 + @Scheduled），无新中间件。
- 风险：补偿窗口内仍有短暂不一致（秒级）；对账任务本身需幂等设计。
- 回滚：删除新增类与配置即回滚，原链路零改动。

### 方案B：Seata TCC 改造开户链路（🟡 中风险 → 实施即触碰红线须人工审核）

1. 部署 Seata Server 2.x（TC，MySQL 存储可复用现有 mysql8-nacos 实例）。
2. account-service 开户方法加 `@GlobalTransactional`；auth-service 提供 TCC 三接口（Try=预占用户名/Confirm=落库/Cancel=释放）。
3. statistics 链路**保持最终一致**（不建议纳入 TCC，派生数据强一致收益低、可用性代价高）。
- 风险：TCC 空回滚/悬挂/幂等三大经典问题需逐一防御；auth-service 接口结构变化（新增端点）；Seata TC 成为新单点；**改动文件位于红线清单（account/auth），必须附人工审核**。
- 回滚：去 @GlobalTransactional + 恢复原 Feign 直调，Seata 容器下线，L1 级回滚。

### 方案C：全链路 Seata SAGA（🔴 高风险，不推荐）

状态机编排开户+账务变更全链路。对 2 步链路过度设计，SAGA 无隔离性（金融场景中间态可见），运维复杂度最高。仅在业务扩展为多步长事务（如转账、清结算）时再评估。

### 选型建议

**推荐 A 先行（1-2 天工作量，零红线侵入），B 作为二期**：当前两条链路的失败后果均为"可用性/派生数据"级而非"资金正确性"级，方案A 的补偿+对账已消除永久卡死与静默漂移；若未来业务出现真实资金双写（如转账），再上 Seata TCC 且届时红线变更走人工审核。

## 五、风险评估汇总

| 风险 | 等级 | 说明 |
|---|---|---|
| 维持现状（不改造） | 中 | 孤儿用户缺口仍在：低概率、无自愈、影响可用性不影响资金 |
| 方案A 实施 | 低 | 增量代码，不触红线；补偿逻辑自身需测试覆盖（含补偿失败路径） |
| 方案B 实施 | 中高 | 触碰红线文件（account/auth 核心链路），TCC 三大防御问题，新增 TC 单点；**必须人工审核** |
| 误用 Seata AT | 高（已排除） | AT 对 MongoDB 无效，若强行引入会得到"看似有事务实际无保护"的假安全感——**本分析已明确排除** |

## 六、下一步（等待人工确认）

- [ ] **确认 MongoDB 验证结论**（4/4 真实调用，无需代码修改）
- [ ] **选型确认**：方案A（低风险补偿对账）/ 方案B（Seata TCC，红线变更需同步审核）/ A+B 分期 / 维持现状
- [ ] 确认后进入阶段2 实施改造（B 方案需额外提供红线变更人工审核记录）

## 附：本次验证产物清单

| 产物 | 说明 |
|---|---|
| verify-mongo-real.py | MongoDB 真实读写验证脚本（9 断言，可重复执行，含数据隔离与 BCrypt 校验） |
| 本报告 | docs/阶段1-MongoDB真实调用与分布式事务分析报告.md |
| 环境变更记录 | rates-mock 容器（联调辅助）+ Nacos dev statistics-service.yml rates.url 覆盖（既有 IT 机制，非新增改动） |
