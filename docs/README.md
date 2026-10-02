# piggymetrics 重构工程文档中心（供人工与 Hermes Agent 使用）

本目录是 piggymetrics 金融微服务重构项目（阶段1-5）的全部文档产物。

## 工程路径速查

| 项 | 路径 |
|---|---|
| 旧工程（基线，禁止修改） | /Users/liuzhangbing/Downloads/D/workspace_tke/piggymetrics |
| 新工程（重构产物 v2.0，2026-10-02 迁移至此） | /Users/liuzhangbing/Downloads/D/ai/piggymetrics-refactored |
| 文档中心（本目录，随仓库归档） | /Users/liuzhangbing/Downloads/D/ai/piggymetrics-refactored/docs |

## 文档索引（按流程阶段）

| 文档 | 阶段 | 内容 | 状态 |
|---|---|---|---|
| 阶段1-架构审计与升级方案报告.md | 1 | 现状摸底、架构图、技术债务D1-D18、风险R1-R10、变更分级L/M/H | ✅ 已人工确认 |
| 阶段2-重构交付报告.md | 2 | 构建验证结果、迁移映射表、零业务改动声明、遗留风险 | ✅ 已人工确认 |
| 阶段3-测试用例设计文档.md | 3 | 51 个测试用例（24 个🔴红线），含黄金数据方案 | ✅ 已生成（人工确认不切换模型，由 qwen 编制） |
| 阶段5-重构后架构文档.md | 5 | 目标架构、部署拓扑、认证架构、数据架构、遗留风险、演进路线 | ✅ |
| 阶段5-开发文档.md | 5 | 环境/构建/配置/编码规范/🔴红线区域/常见坑 | ✅ |
| 阶段5-部署文档.md | 5 | **v2.1 实证修订版**：P1-P11 检查清单、部署顺序（含强制 provision 步骤）、B7 网络拓扑、Nacos 3.x 编排三处修正、自动化脚本（provision/verify）、31 项断言冒烟、冷启动可复现声明 | ✅ 2026-10-02 |
| 回滚方案.md | 全程 | L0-L3 分级回滚、兼容性矩阵、演练要求、不可回滚点 | ✅ |
| 阶段4-测试执行报告.md | 4 | mvn test 75/75 全绿（含 10 个真实 Mongo 用例，方案A后增至 87/87）、bug 清单（B1/B2/B3 主代码 + T1-T6 测试代码）、红线签核表、遗留缺陷 K1-K3 | ✅ 已签核 |
| Nacos配置同步与拉取验证报告.md | 追加任务 | 6 份配置同步至 Nacos 3.0.3(dev/PIGGYMETRICS)、gateway+account-service 真实拉取实证、注册中心核验、B4 缺陷修复 | ✅ |
| 全链路联调报告.md | 追加任务 | IT 编排 docker-compose.it.yml 全链路 31/31 PASS、B5 配置缺陷修复 | ✅ |
| 主compose全链路联调报告.md | 追加任务 | **主 docker-compose.yml `build`+`up`** 全链路 31/31 PASS（9 镜像构建、12 容器、Nacos/MySQL/Sentinel）、B7 网络缺陷+B6 掩码损坏修复 | ✅ |
| 主compose全链路联调报告-第2轮冷启动.md | 追加任务 | 冷启动全量重建复验：空状态起 build(9镜像)+Nacos 从零 provision+up(13容器)+31/31 PASS；B7 修复复验有效 | ✅ |
| 网关压测与Sentinel验证报告.md | 追加任务 | stress-gateway.py 四阶段压测 ALL_PHASES_OK（1400QPS 基线/限流 429×110/降级 fallback/traceId 贯通）；发现修复 B8（feign-micrometer 缺失致 trace 断裂）；修复后 31/31 回归 | ✅ |
| 链路追踪落地报告-Jaeger方案B.md | 追加任务 | 方案B落地：5服务+zipkin-reporter-brave+endpoint(零业务改动)，Jaeger 实测4服务注册/跨服务trace贯通/依赖拓扑正确；回归75/75+31/31 | ✅ |
| 生产链路追踪-SkyWalking接入指南.md | 追加任务 | 方案C生产接入件：SkyWalking Java Agent(零代码)、版本选型、compose片段、SCG/WebFlux插件、B/C共存策略、回滚 | ✅ |
| SkyWalking预发部署验证报告.md | 追加任务 | 方案C实际部署：OAP 10.2.0+MySQL存储+Agent 9.4.0 注入5服务（零业务代码）；7/7自动化验证PASS、采样率差异化生效、告警规则6条+真实触发冒烟OK；修复7项环境问题；回归31/31 | ✅ |
| 阶段1-MongoDB真实调用与分布式事务分析报告.md | 追加任务(阶段1) | MongoDB真实调用验证 9/9 PASS（4服务各自独立库数据落地级证明+库间隔离+BCrypt）；分布式事务必要性分析：孤儿用户缺口已实证、Seata AT不适用MongoDB（官方支持列表核实）、候选方案A/B/C分级 | ✅ 已人工确认(选方案A) |
| 阶段2-方案A幂等补偿对账自愈交付报告.md | 追加任务(阶段2) | 方案A实施：孤儿用户/statistics漂移两缺口消除；补偿域模型+对账Job+无fallback补偿客户端+auth幂等delete端点(SCOPE_server)；单测87/87(新增12)、E2E 11/11、回归31/31；成功路径红线零改动、L0/L1回滚 | ✅ 已人工确认 |
| 阶段3-方案A测试用例设计文档.md | 追加任务(阶段3) | 方案A测试用例设计：38用例（A鉴权红线🔴5/B补偿触发8/C Job状态机8/D幂等删除4/E持久化集成4/F E2E自愈5/G回归4），18已实施+20待实施；含CMP-A01~A05 DELETE端点鉴权矩阵（当前缺口）、CMP-C08无fallback客户端守护锁 | ✅ 已人工确认 |
| [dp/README.md](dp/README.md) | 阶段3(四层版) | **四层金字塔测试用例设计（GitHub 规范）**：单元44/功能49/全链路16/系统15，共124用例·🔴红线50；含追溯矩阵（D/R/H/K→用例）、环境约定、门禁 | ✅ 已人工确认（deepseek 编制） |

## 项目硬约束（任何 Agent 接手必读）

1. **强制串行流程**：阶段1→2→3→4→5，每阶段完成必须等待【人工确认】，严禁自动推进。
2. **🔴 金融红线**：账务/交易/金额核心逻辑禁止私自修改（红线文件清单见《阶段5-开发文档.md》第 5 节）。任何红线变更须附风险评估+回滚方案+人工审核。
3. **测试约束**：仅执行 `mvn test`；禁止 drop/truncate 等破坏性数据库操作；集成测试禁止连接生产/共享数据库。
4. **禁止事项**：自动删除代码、高危命令自动执行、猜测业务规则（不确定必须提问）。
5. **构建环境**：JDK 17 = $(/usr/libexec/java_home -v 17)；Maven = ~/tools/apache-maven-3.9.9/bin/mvn；禁用 mvn -T 并行（本地仓库锁冲突）。
6. **旧工程零修改**：/workspace_tke/piggymetrics 是回滚基线，只读。

## 当前项目状态（截至 2026-10-01，✅ 五阶段全部闭环）

- 阶段1 ✅ 审计完成，人工已确认（SCA 2025.0.0.0 + Boot 3.5.x + Nacos 3.0.3 + Sentinel + JWT）
- 阶段2 ✅ 重构完成：5 模块编译打包通过，网关冒烟通过，人工已确认
- 阶段3 ✅ 测试用例文档已生成（既有 51 用例 qwen 编制 + 方案A 38 用例 + 四层金字塔 124 用例 docs/dp/，deepseek 编制，2026-10-02 人工确认）
- 阶段4 ✅ 测试代码编写 + mvn clean test 87/87 全绿（含 10 个真实 MongoDB 连接用例）；发现并修复 3 个主代码缺陷（B1/B2 SAS 认证缺口、B3 Mongo 4.x 派生查询兼容）；🔴 红线用例 2026-10-01 人工签核通过
- 阶段5 ✅ 架构/开发/部署/回滚全套文档由 deepseek 编制并更新（GitHub 规范，v2.2），已纳入方案A补偿架构、四层测试设计、真实路径
- 追加任务 ✅ Nacos 配置同步（6 份，dev/PIGGYMETRICS）+ 真实拉取实证，2026-10-01 人工确认；配置策略已决策：保留 optional:nacos: 降级设计，不做 fail-fast
- 冒烟进程已按授权停止（gateway:4000 / account-service:6001），Nacos 临时实例已摘除

## 项目收尾状态：✅ 五阶段全部闭环（2026-10-01）

交付物清单：
- 新工程：/Users/liuzhangbing/Downloads/D/ai/piggymetrics-refactored（JDK17 + Boot 3.5.3 + SC 2025.0.0 + SCA 2025.0.0.0 + Nacos 3.0.3 + Sentinel + JWT）
- 旧工程：/Users/liuzhangbing/Downloads/D/workspace_tke/piggymetrics（零修改，回滚基线）
- 文档中心：本目录（索引见上表，含 docs/dp/ 四层测试用例设计）

后续独立立项事项（不属本次重构范围，按优先级）：
1. 🔴 H6 JWT 密钥治理（生产上线前置阻塞项：固定密钥 + KMS/Nacos 加密 + 轮换）
2. 🔴 D16 汇率数据源替换（现数据源已停服，统计链路上线前必须解决）
3. H1/H2/H4 分布式一致性增强（开户补偿、账务-统计可靠投递、对账）
4. H5 账务审计字段（版本号/操作流水）
5. K1 ItemMetric equals/hashCode 契约、K2 汇率 fallback NPE、K3 补偿队列
6. 生产环境部署时创建 staging/prod 命名空间并复用 nacos-config/sync 脚本
