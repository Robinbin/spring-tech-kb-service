# DEC-05 是否引入 Elasticsearch（Phase 4）

**结论：当前不引入 Elasticsearch；继续使用 PostgreSQL FTS + pgvector 混合检索。**

## 决策依据

- 当前工程已有 PostgreSQL 全文检索与 pgvector 检索实现，统一维护事务、审核状态和来源归因。
- 仓库中没有可复核的 Phase 1 PostgreSQL 与 Elasticsearch 对照评估数据，也没有达到迁移门槛的生产吞吐或延迟观测。本决策不把缺乏数据解释为 PostgreSQL 已被证明优于 Elasticsearch。
- 引入 Elasticsearch 会增加集群、索引同步、故障恢复、数据一致性与运维成本；现有证据不足以证明该复杂度有净收益。

## 重评估门槛

在同一数据快照、同一查询集和相同硬件预算下，对 PostgreSQL 与 Elasticsearch 执行可复现的离线及影子流量基准。出现以下任一情况时重新评估：

- 经过索引、查询和容量调优后，检索 p95 延迟仍连续 7 天超过 300 ms，且目标 SLO 为 200 ms。
- 代表性评估集上的 Recall@20 低于 0.90，且经 PG 参数/索引调优仍未达标。
- 知识库达到 1,000 万 chunks 或检索峰值持续超过 200 QPS，且容量测试显示 PostgreSQL 无法在现有资源预算内达标。
- 索引构建或重建影响在线负载，无法通过现有数据库资源隔离解决。

任一门槛触发后，先记录基线、数据集、硬件、查询分布和成本，再决定是否引入 ES；不得仅凭单次微基准切换。

## 若后续引入

- PostgreSQL 仍是知识内容、许可审核和事务状态的权威来源。
- 通过版本化 outbox 异步投影到 Elasticsearch，并设计重试、死信、重建和切换回 PostgreSQL 的流程。
- 搜索响应继续返回来源 ID 与归因字段，不允许 ES 文档成为内容授权状态的唯一依据。
