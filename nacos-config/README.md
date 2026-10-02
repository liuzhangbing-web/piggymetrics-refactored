# Nacos 3.0.3 配置导入说明

命名空间（namespace）：dev / staging / prod（按环境隔离，回滚方案的一部分）
分组（group）：PIGGYMETRICS

| dataId | 说明 |
|---|---|
| application-common.yml | 公共配置（原 config/shared/application.yml 中仍然有效的部分） |
| gateway.yml | 网关动态配置（可选，静态路由已在服务本地 application.yml 中） |
| auth-service.yml 等 | 各服务环境差异化配置（如 mongodb 主机名、密码引用） |

原 shared/*.yml 中已废弃的配置段（eureka、hystrix、ribbon、security.oauth2 旧格式、
feign.hystrix）不迁移；等价值已映射到各服务本地 application.yml（见文件内 MIGRATION 注释）。

导入方式：Nacos 控制台 -> 配置管理 -> 导入，或 API：
curl -X POST "http://<nacos>:8848/nacos/v3/admin/cs/config" ...（3.x admin API）
