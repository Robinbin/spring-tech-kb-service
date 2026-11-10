# Phase 3 M2 服务拆分实施说明（ai-engine / kb-indexer）

本文档记录 Phase 3 的服务拆分落地方案：在不破坏当前 Modulith 代码边界的前提下，先以**同一代码仓、同一镜像、多运行角色**方式交付独立部署能力，再保留后续彻底物理拆库与独立仓库的演进空间。

## 1. 拆分目标与边界

- **ai-engine**：面向在线请求，承载检索/RAG、学习计划、评估、内容生成、MCP。
- **kb-indexer**：面向离线任务，承载知识抓取、入库、索引、API 兼容抽取与事件投递。
- 两者共享 PostgreSQL/Redis（Phase 3 范围内不做数据库物理拆分）。

## 2. 运行角色

统一镜像通过环境变量选择运行角色：

- `SPRINGTECHKB_RUNTIME_ROLE=all`（默认，单体行为）
- `SPRINGTECHKB_RUNTIME_ROLE=ai-engine`
- `SPRINGTECHKB_RUNTIME_ROLE=kb-indexer`

角色路由策略：

- `ai-engine`：允许 `/api/search`、`/api/chat`、`/api/learning`、`/api/assessments`、`/api/generation`、`/api/users`、`/mcp` 等在线接口。
- `kb-indexer`：拒绝在线业务入口，仅保留健康检查、运维指标与离线入口（如 ingestion/indexing 管道触发）。

## 3. 部署清单

- `infra/k8s/ai-engine-deployment.yaml`
- `infra/k8s/kb-indexer-deployment.yaml`

二者均引用同一应用镜像，通过不同 `SPRINGTECHKB_RUNTIME_ROLE` 与资源配额实现逻辑隔离。

## 4. 数据与事件

- 继续使用既有 outbox/event publication 机制，避免在 Phase 3 引入 Kafka 的额外运维成本。
- ai-engine 与 kb-indexer 的跨角色协作通过数据库事件表 + 调度器完成。

## 5. 验收标准（M2）

1. `all` 角色行为与 Phase 2 完全兼容。
2. `ai-engine` 部署可独立对外提供在线 API。
3. `kb-indexer` 部署不会暴露在线 API（请求返回 404）。
4. 两角色在同一数据库实例上可并行运行，模块边界测试继续通过。

## 6. 非目标（Phase 3 不覆盖）

- 不引入新的消息中间件（Kafka/Pulsar）。
- 不做跨库事务与多集群容灾设计。
- 不拆分前端 BFF；前端仍指向 ai-engine。
