# Spring Tech KB — 开发基线与工作包分解

> **文档用途：** 本文档是 `spring-tech-kb-arch.html` (v1.3) 的**开发落地版**，用于下一步的开发任务分解。
> 架构文档记录「为什么这样设计」及三轮评审的历史，本文档只记录「要做什么、依赖什么、做到什么算完成」。
>
> **基线版本：** 架构 v1.3 · 本文档 2026-10-26
> **基线：** 架构 v1.3 · A1 已完成（2026-10-26）
> **估算口径：** 人日 = 单人净开发工时，**不含**需求澄清、评审、联调与返工（经验上需另加 25–40%）

---

## 目录

1. [定稿技术基线](#1-定稿技术基线)
2. [不可违背的硬约束](#2-不可违背的硬约束)
3. [工作包分解 (WBS)](#3-工作包分解-wbs)
4. [工期校验与阶段再平衡](#4-工期校验与阶段再平衡)
5. [关键路径与并行策略](#5-关键路径与并行策略)
6. [接口契约冻结清单](#6-接口契约冻结清单)
7. [待决策清单](#7-待决策清单)
8. [风险登记册](#8-风险登记册)
9. [工程规范与完成定义](#9-工程规范与完成定义)

---

## 1. 定稿技术基线

无历史标注的最终选型。任何偏离需走 ADR 变更流程。

### 1.1 后端

| 组件 | 版本 | 说明 |
|---|---|---|
| Java | 21 | Spring Boot 4 最低基线；Virtual Threads 用于 LLM 阻塞 I/O |
| Spring Boot | **4.1.2** | A1 锁定。Framework 7.0.10 / Hibernate 7.4.13.Final / Jakarta Persistence 3.2.0 / Tomcat 11.0.27 |
| Spring AI | **2.0.2** | A1 锁定。1.x 基线为 Boot 3.4/3.5 与 Boot 4 不兼容；2.0.2 的 Anthropic 集成基于 `com.anthropic:anthropic-java-core:2.72.0` |
| Spring Modulith | **2.1.2** | A1 锁定。Phase 1–3 单体内模块边界约束 + 事件发布登记表 |
| Spring Cloud Gateway | 5.x | 服务拆分后独立网关；当前模块化单体在 Servlet ingress filter 实现 G1 路由入口与限流 |
| Spring Security | 7.x | JWT + OAuth2 Resource Server |
| Spring Data JPA | 4.x | Jakarta Persistence 3.2 |
| Spring Batch | 6.x | **必须 6.x**，5.x 属 Boot 3.x 线 |
| Resilience4j | 2.x | 两级超时熔断 |
| JGit | 7.x | 6.x 基线为 Java 11 |
| JavaParser | 3.x | 支持 record / sealed / pattern matching |
| japicmp 或 Revapi | — | API 二进制兼容性比对 |

### 1.2 AI 与数据

| 组件 | 选型 | 说明 |
|---|---|---|
| 主力 LLM | Claude（分层路由） | 轻量档 / 标准档 / 重推理档三级 + effort 控制 |
| 备用 LLM | 第二家 Provider | 经 Spring AI `ChatModel` 抽象，模型 ID 只在配置中 |
| Embedding | 自托管开源权重（BGE-M3 / Qwen3-Embedding 系） | MRL 截断至 **1024 维** |
| Reranker | 自托管开源 Cross-Encoder（bge-reranker-v2-m3 / Qwen3-Reranker 系） | Top-20 → Top-5 |
| 推理运行时 | TEI 或 Infinity | 承载 Embedding + Reranker；在线 CPU，批量重建挂 GPU |
| 向量存储 | PostgreSQL 16 + pgvector | `vector(1024)` + HNSW |
| 词法检索 | PostgreSQL FTS（`tsvector` + GIN） | Phase 1–3；ES 后置待判定 |
| 缓存 | Redis 7（Sentinel/Cluster） | 语义缓存 · 会话 · 限流 · Token 配额 |
| 事件总线 | Modulith 事件登记表 + Postgres outbox | Phase 1–3；Kafka 后置待判定 |
| 对象存储 | MinIO / S3 | 项目骨架、附件 |
| 对外协议 | REST + SSE + **MCP Server** | 内部同步统一 HTTP/2 + SSE，无 gRPC / WebSocket |

### 1.3 前端

| 组件 | 版本 |
|---|---|
| Next.js | 15+（App Router + React 19） |
| TypeScript | 5.x |
| TailwindCSS | 4.x + shadcn/ui |
| 代码编辑器 | **CodeMirror 6**（非 Monaco，移动端可用） |
| 流式接入 | Vercel AI SDK 5.x |

### 1.4 基础设施与质量

| 组件 | 选型 |
|---|---|
| 容器 / 编排 | Docker + Kubernetes |
| 弹性伸缩 | **KEDA**（按在途请求 / 队列深度，非 CPU） |
| 沙箱运行时 | gVisor (runsc) 或 Kata / Firecracker，独立 RuntimeClass + 节点池 |
| CI/CD | GitHub Actions |
| 可观测性 | OpenTelemetry（GenAI 语义约定）+ Langfuse + Prometheus + Grafana |
| Eval | RAGAS 或 promptfoo / DeepEval + CI 闸门 |
| 数据库迁移 | Flyway 或 Liquibase |

---

## 2. 不可违背的硬约束

这些是三轮评审中踩过或识别出的坑，**写进代码评审检查项**。

| # | 约束 | 违背后果 |
|---|---|---|
| H-1 | Embedding 向量维度 ≤ 2000 才能建 HNSW 索引（`halfvec` 上限 4000） | 索引建不出来，退化为全表顺序扫描 |
| H-2 | 启用 `hnsw.iterative_scan = relaxed_order`（pgvector 0.8+） | 元数据预过滤下召回率塌陷，可能返回空 |
| H-3 | `ef_construction` ≥ 200（不用默认 64） | 技术语料召回偏低 |
| H-4 | LLM 超时**必须分两级**：TTFT 5s 计入熔断，整体流 90–120s 不计入 | 正常流式生成被判为故障，系统在正常负载下自熔断 |
| H-5 | 语义缓存 key 必须含 `spring_version + 意图类型 + 知识域` | 跨版本串答案（返回错误版本的技术细节） |
| H-6 | 学习计划、主观题批改、能力报告**禁止**进语义缓存 | 跨用户串用个性化内容 |
| H-7 | Token 必须按 `input / output / cache_read` 三列分别计量 | 成本核算失真（三者单价不同） |
| H-8 | 知识库内容更新必须**同事务**写 outbox，由投递器同步词法索引 | 向量侧与词法侧版本不一致，RRF 融合出新旧混杂结果 |
| H-9 | 第三方博客正文**不入库**（仅存 URL + 标题 + 作者）；书籍一律排除 | 著作权侵权；事后清理需级联清除向量、索引、缓存与已生成内容 |
| H-10 | 检索内容必须包裹在标注的不可信区块内，RAG 回答路径**不授予工具调用能力** | 间接 Prompt 注入，影响面为全体用户 |
| H-11 | API 兼容性判定用 japicmp / Revapi，**不用** Embedding 相似度 | 漏报真实破坏性变更（删参数时文本几乎不变） |
| H-12 | Prompt 前缀必须稳定（system → tools → 知识上下文 → 用户问题） | Prompt Caching 失效，input 成本上升一个量级 |
| H-13 | AI 生成内容标注来源、检索降权，且 ≥10% 人工抽检 | 自我强化偏差，幻觉内容沉淀入库 |
| H-14 | 输出必须附来源 + 许可标注（含 MCP 返回结果） | 合规义务未履行 |

---

## 3. 工作包分解 (WBS)

共 **67 个工作包**。依赖列中的 ID 为**硬依赖**（必须完成才能开始）。标注 `⚠` 的为阻塞多个下游的关键包。

### EPIC A — 工程基建

| ID | 工作包 | 交付物 | 依赖 | 验收标准 | 人日 |
|---|---|---|---|---|---|
| **A1** ✅ | 技术栈可行性验证闸门 | 最小工程 + [版本锁定矩阵](./a1-stack-verification.md) | — | **已完成（2026-10-26）**：依赖共存、编译、完整上下文启动、三个核心 bean 装配均通过（5/5 测试）。真实外部调用顺延 A3（本机无 Docker） | 2 |
| **A2** ✅ ⚠ | 仓库与 Modulith 骨架 | 模块目录结构 + 模块边界定义 + 校验测试 | A1 | **已完成（2026-10-27）**：6 个业务模块（ai/knowledge/search/learning/assessment/user）声明完成，`allowedDependencies` 约束写入 `@ApplicationModule`，`ModularityTests`（7/7 测试全通过）在 CI 中可拦截越权访问 | 3 |
| **A3** ✅ ⚠ | 本地开发环境 | `infra/docker-compose.yml`（pgvector/pgvector:pg17 / Redis 7 / TEI CPU / MinIO）+ `.env.example` + `postgres/init/00-extensions.sql` | A1 | **已完成（2026-10-27）**：单条命令 `docker compose --env-file .env up -d` 启动全栈依赖；可观测性服务（Prometheus / Grafana / Jaeger）通过 `--profile obs` 按需挂起；TEI 端口改为 8081（Spring Boot 占 8080）| 2 |
| **A4** ✅ | CI/CD 流水线 | `Dockerfile`（多阶段 maven→jre）+ `.dockerignore` + `.github/workflows/ci.yml` + `cd.yml` | A2 | **已完成（2026-10-27）**：PR 触发 `mvn verify`（10/10 测试，无需 Docker）；主干合并/tag 自动构建镜像并推送至 `ghcr.io`；GitHub Actions 缓存层加速 | 3 |
| **A5** ✅ | 可观测性基座 | `ObservabilityConfig`（根包）+ pom.xml 依赖（OTel bridge / OTLP exporter / Prometheus registry）+ `application.yml` 观测配置 | A2 | **已完成（2026-10-27）**：`ObservabilityBaselineTest` 3/3 通过——PrometheusMeterRegistry 已配置、OtelTracer（Micrometer bridge）已注入、`/actuator/prometheus` 返回含 `application` 公共标签的 JVM 指标；Spring AI `TracerPresentObservationConfiguration` 在 Tracer bean 存在时自动激活 GenAI 语义约定。**注**：完整 RAG 双段 span（检索 + LLM）待 E3 完成时随集成测试确认 | 4 |

> **A1 已完成，产出 7 项发现，其中 5 项影响下游工作包** —— 详见 [`a1-stack-verification.md`](./a1-stack-verification.md)。要点：
> - **锁定版本：** Spring Boot **4.1.2**（非 4.0）、Spring AI **2.0.2**、Modulith **2.1.2**、Framework 7.0.10、Hibernate 7.4.13、Jakarta Persistence 3.2.0、anthropic-java-core 2.72.0
> - **Boot 4 重定位了全部自动配置类的包路径**（`o.s.boot.autoconfigure.<模块>` → `o.s.boot.<模块>.autoconfigure`）—— 所有 Boot 3 示例代码在此处编译失败
> - **风险 R-1 对核心栈解除。**
>
> **A3–A5 补充发现（2026-10-27）：**
> - **TEI 端口须避开 8080**：`infra/docker-compose.yml` 将 TEI 映射到宿主机 :8081，`application.yml` 中 `TEI_BASE_URL` 默认值同步修改。
> - **Boot 4 移除了 Micrometer Tracing 自动配置**：`spring-boot-actuator-autoconfigure` 不再创建 `Tracer` bean；`ObservabilityConfig`（根包）中手动装配 `OpenTelemetrySdk` + `OtelTracer`，不使用 `buildAndRegisterGlobal()`（多上下文测试场景会冲突）。
> - **`MeterRegistryCustomizer` 包路径变更**：`o.s.boot.actuate.autoconfigure.metrics` → `o.s.boot.micrometer.metrics.autoconfigure`。
> - **`TestRestTemplate` 在 Boot 4 中被删除**：集成测试 HTTP 调用改用 `RestClient` + `@LocalServerPort`。
> - **`config` 子包会被 Modulith 识别为模块**：跨切关注点配置应放在根包（`com.springtechkb`）而非独立子包，否则会破坏 `ModularityTests.allSixModulesDetected`。
>
> **调整说明：** 架构文档把可观测性放在 Phase 2，本分解提前基座部分到 Phase 1 —— 事后补埋点的成本远高于一开始就建，且 Eval 与成本归因都依赖它。Langfuse 深度用法（trace 树、Prompt diff）仍留在 Phase 2（K3）。

**小计：14 人日（A1–A5 全部完成，2026-10-27）**

### EPIC B — 数据与模型层

| ID | 工作包 | 交付物 | 依赖 | 验收标准 | 人日 |
|---|---|---|---|---|---|
| **B1** ✅ ⚠ | 数据库 Schema 与迁移 | `src/main/resources/db/migration/` V1–V11 共 11 个 Flyway 迁移脚本 | A3 | **基础 Schema 已完成（2026-10-27），持续扩展至 V11（2026-10-28）**：初始 15 张表、外键与 UNIQUE 约束齐备；V9 增加知识 outbox、审核记录和 API 符号表，V10 增加 pgvector 语义缓存及知识更新失效触发器，V11 增加 Prompt 版本命名空间、灰度约束及有效校验和的默认 RAG Prompt；Flyway 自动迁移经 Podman PostgreSQL 17 验证 | 5 |
| **B2** ✅ ⚠ | pgvector 索引与检索调优 | `HnswAwarePgVectorStore`（search.internal）+ `VectorStoreConfig`（@Primary 装饰器）+ `PgVectorHealthIndicator`（根包） | B1 | **已完成（2026-10-28）**：H-1 满足（维度 1024，atttypmod=1028）；H-2 满足（`@Transactional` + `SET LOCAL hnsw.iterative_scan=relaxed_order` 保证同一物理连接）；H-3 满足（ef_construction=200 在 V4 Flyway DDL）；`PgVectorHealthIndicator` 暴露 `/actuator/health/pgVector`（扩展/维度/索引三项）；`PgVectorB2IT`（Testcontainers 2.x，4 项，Docker 可用时执行）验证元数据过滤召回不塌陷 | 4 |
| **B3** ✅ | PG FTS 词法检索 | `FtsHit`（public record）+ `KnowledgeChunkFtsService`（search.internal，`websearch_to_tsquery` + `ts_rank_cd`）+ `FtsSearchIT`（Testcontainers，5 项 IT） | B1 | **已完成（2026-10-28）**：`websearch_to_tsquery('english',?)` 检索，`ts_rank_cd` BM25 风格排名，`spring_module` 过滤，short-circuit 空结果；DEC-03（中文分词）延后，`content_tsvector` 生成列已在 V3 中预留 | 3 |
| **B4** ✅ ⚠ | 自托管 Embedding / Reranker 服务化 | `RerankerService` 接口 + `RerankHit` + `TeiRerankerService`（HTTP /rerank）+ `RerankerConfig`（@ConditionalOnProperty）+ `infra/docker-compose.yml` tei-reranker（profile: rerank）| A1, A3 | **已完成（2026-10-28，代码完整）**：`TeiRerankerServiceTest` 3/3 通过（`MockRestServiceServer`）；Embedding 已在 A1 就绪（TEI OpenAI-compatible）；Reranker 默认 disabled（pass-through），`RERANKER_ENABLED=true` 激活真实 TEI；P95 延迟验证待 Docker 环境实测 | 5 |
| **B5** ✅ | Embedding 维度与召回对比实验 | `docs/b5-dec01-decision.md`（DEC-01 暂定决策）+ `eval/b5/run_embedding_comparison.py`（实验框架，待 C1 就绪后运行） | B2, B4, C1 | **已完成（2026-10-28，决策暂定）**：DEC-01 暂定选 BGE-M3 1024-dim MRL（无供应商依赖，满足 H-1，自托管成本 $0）；实验框架已就绪，等 C1 评测集完成后做实测确认 | 3 |

**小计：20 人日（B1–B5 全部完成，2026-10-28）**

### EPIC C — Eval 基础设施（前置，不可后移）

| ID | 工作包 | 交付物 | 依赖 | 验收标准 | 人日 |
|---|---|---|---|---|---|
| **C1** ✅ ⚠ | 评测集构建 | 100 条标注样本 + `eval/c1/schema.json` + `validate_eval_set.py` | — | **已完成（2026-10-28）**：100 条样本通过 JSON Schema 与业务规则校验；四类意图各 25 条，版本敏感样本 19 条且均标注版本；评测数据格式由 IC-6 固定 | 5 |
| **C2** ✅ | Eval 运行器 | `eval/c2/run_eval.py` + 指标与固定版本 LLM-judge | C1 | **已完成（2026-10-28）**：支持真实 `/api/search` + `/api/chat/rag` 在线评测和真实响应快照重放；产出关键词召回、版本误配率、引用正确率、Faithfulness；当样本填入 `chunk_ids` 时计算 Context Precision/Recall 与 MRR@k。当前 C1 的 chunk ID 留待 D5 入库后标注，因此当前运行只产出关键词召回，不伪造 chunk 指标；数据不可用或接口错误时明确失败 | 4 |
| **C3** ✅ ⚠ | CI 质量闸门 | `.github/workflows/eval.yml` + `eval/c3/check_regression.py` + 基线存档格式 | C2, A4 | **闸门框架已完成（2026-10-28）**：PR 校验评测集与 C2/C3 测试；存在真实响应快照时执行回放和阈值/基线检查，检查失败阻断；在线评测可手动触发并在通过阈值后更新基线。**激活说明**：E1/E3 API 已实现并符合 C2 接口格式；仓库尚无经审核的真实响应快照/质量基线，因此不能伪造检索质量分数，需人工确认真实评测结果后存档并启用质量回归门禁 | 2 |

> **为什么必须前置：** 架构文档设计了 Prompt 灰度、A/B 测试与「回答质量评分」，但未定义质量如何度量。没有 C1–C3，后续所有检索调参与模型变更都无法判断是改好还是改坏。

**Epic C 使用方式：**

```bash
pip install -r eval/c2/requirements.txt
python eval/c1/validate_eval_set.py
python -m unittest discover -s eval/tests -v
python eval/c2/run_eval.py --mode online
python eval/c3/check_regression.py <eval-result.json> --baseline eval/c3/baseline/latest.json
```

在线模式需要可访问的 E1 `/api/search`、E3 `/api/chat/rag`，以及用于固定版本 Claude judge 的 `ANTHROPIC_API_KEY`。快照模式使用 `c2-snapshot-v1` 格式，按 100 个样本 ID 保存检索结果、答案、引用和预先计算的 judge 分数；PR 会在 `eval/c2/snapshots/pr.json` 存在时回放并运行闸门。仓库当前没有真实结果基线；首次创建需在人工确认评测结果后用 `--update-baseline` 更新，不能用合成结果初始化。

**小计：11 人日**

### EPIC D — 知识库内容与索引

| ID | 工作包 | 交付物 | 依赖 | 验收标准 | 人日 |
|---|---|---|---|---|---|
| **D1** ✅ | 内容来源许可清单与准入规则 | `docs/knowledge-source-allowlist.md` + `SourceAdmissionPolicy` | — | HTTPS 官方域名 / `spring-projects` 仓库白名单；许可类型、许可 URL、审核人及审批引用必填；第三方正文及 AI 内容拒绝（H-9）。生产使用前仍须提供真实法律审核证据 | 2 |
| **D2** ✅ | 官方文档采集与清洗 | `OfficialDocumentationCrawler` | D1, A3 | robots allow/disallow、条件请求 ETag/Last-Modified、按 host 限速、429/5xx 最多重试 3 次并遵循 Retry-After；剔除常见导航与页脚元素 | 5 |
| **D3** ✅ | 分块 + Contextual Retrieval | `ContextualChunker` | D2, B4 | 每块不超过 512 whitespace tokens，连续正文按 64 tokens 重叠；前缀含知识点、显式提供的 Java 类名（未提供则标注 not specified）、模块、版本与章节，不将模块误报为类 | 5 |
| **D4** ✅ | 双写落库 + outbox 投递 | `KnowledgeIngestionService` + `KnowledgeIndexOutboxDispatcher` + V9 migration | B1, B2, B3, D3 | **已完成（2026-10-28）**：来源、chunk/FTS、向量、待审记录和 outbox 同事务写入；提交后校验向量投影，指数退避重试及死信；支持按 module 重建。`EpicDKnowledgeIT` 在 Podman/5433 SSH 转发下验证，Spring Boot 自动应用迁移（当前至 V11） | 4 |
| **D5** ⚠ | 150+ 核心知识点入库与校验 | `src/main/resources/knowledge/d5-review-seed.json`（可由 `tools/d5/build_review_seed.py` 重建） | D4 | 已生成 IoC / AOP / MVC / Data / Security 各 30 条原创摘要和人工校验记录模板，全部 `PENDING`；准确率抽检、人工批准及正式检索入库待审核人员完成后验收 | 8 |
| **D6** ✅ | 源码采集 Pipeline | `ApiCompatibilityPipeline`（JGit + JavaParser + japicmp）+ `knowledge_api_symbol` | D1, A3 | **已完成（2026-10-28）**：本地 fixture 验证 tag 增量、API/Javadoc 抽取与二进制不兼容报告；数据库记录新增/修改/未变/移除符号。`EpicDKnowledgeIT` 在 Podman/5433 SSH 转发下完成集成验证，Spring Boot 自动应用迁移 | 8 |

**小计：32 人日**

D1 的审批引用字段只提供审计挂钩，不代表团队法律审批已经发生。D5 摘要必须逐条对照所列官方文档审核；当前 150 条均未批准、未作为生产检索内容。D6 由调用方提供本地仓库、两个 tag 和对应 JAR；自动发现远端最新 release 尚未配置。Podman 环境下 Testcontainers 需将 PID limit 设为 unlimited（`pids-limit=0`），并通过 SSH 转发 Windows 本地 5433 到 Podman VM；D4/D6 集成测试已在该配置下通过。

### EPIC E — RAG 检索与问答

| ID | 工作包 | 交付物 | 依赖 | 验收标准 | 人日 |
|---|---|---|---|---|---|
| **E1** ✅ | 混合检索与 RRF 融合 | `HybridSearchService` + `KnowledgeChunkFtsService` | B2, B3, D4 | **已完成（2026-10-28）**：向量/FTS 双通道以 RRF（k=60）融合；module 与数值化 Spring 版本范围在向量元数据和 FTS SQL 中预过滤，最终结果带来源/许可元数据。Podman 下 FTS/version 集成测试 7/7，混合检索集成验证通过 | 5 |
| E2 ✅ ⚠ | Reranker 精排接入 | `RerankedSearchService` + `RerankRecall` | E1, B4 | **已完成（2026-10-28，接入代码和 Top-20→Top-5 测试通过）**：兼容已有 TEI 开关，未启用时维持 RRF 顺序；提供基于相关 chunk ID 的前后 Recall 计算。**待验证**：TEI 实例启用后的实际排序质量及带标注查询集上的 Recall 对比 | 2 |
| **E3** ✅ ⚠ | 单轮 RAG 问答 API | `RagChatGateway` + Spring AI Advisor + `/api/chat/rag` SSE + 引用 DTO | E2 | **已完成（2026-10-28，接口与单元测试通过）**：token/citation/done/error SSE、来源 URL 与许可引用；稳定系统前缀、TTFT timer 和 input/output/cache_read token 指标已配置。**待验证**：需可用 Anthropic/TEI 服务实测 TTFT P95 < 1.5s | 5 |
| E4 ✅ | Agentic Retrieval 多跳路径 | `KnowledgeRetrievalTool` + `AgenticRetrievalService` | E3 | **已完成（2026-10-28）**：版本对比自动按版本执行至少 2 次检索；默认最多 3 轮，配置值始终限制在硬上限 5 内。测试覆盖两轮检索与上限约束 | 6 |
| E5 ✅ ⚠ | 意图分类与路径分流 | `QueryIntentClassifier` + 快路径 / Agentic / CAG | E3, E4 | **已完成（2026-10-28，规则分类与三路分流测试通过）**：普通问题走快路径，版本对比走 Agentic，长上下文走 CAG；学习计划、评分/批改等个性化意图绕过语义缓存。**待验证**：用经标注的真实意图集报告准确率；当前规则测试不是生产准确率证据 | 4 |
| E6 ✅ ⚠ | Prompt Caching 前缀设计 | `PromptPrefixRenderer` + Anthropic system cache + `RagPromptMetadataAdvisor` | E3 | **已完成（2026-10-28）**：固定 system → tools → knowledge context → user question 顺序；prompt version 作为缓存/追踪命名空间；启用 Anthropic system prompt caching，并统计 `cache_read` token。**待验证**：真实 API 流量中的缓存读取比例 | 3 |
| E7 ✅ | 语义缓存 | `SemanticAnswerCache` + V10 migration | E3, B1 | **已完成（2026-10-28）**：pgvector 相似度缓存 key 包含 `spring_version + intent + knowledge_domain`（额外隔离 module），6 小时 TTL；个性化任务硬绕过；知识 source 新增/删除或 module/version/content 更新时在同事务清除对应 module 缓存。Podman 集成测试验证版本隔离、命中和失效 | 3 |

**小计：28 人日**

**Epic E API/运行说明：** `GET /api/search?q=...&module=...&springVersion=...&topK=...` 返回 `{ "results": [...] }`；`POST /api/chat/rag` 接收 `{ "query": "..." }`，返回 `text/event-stream`，事件名为 `token`、`citation`、`done` 或 `error`。Reranker 默认关闭，设置 `RERANKER_ENABLED=true` 并提供 TEI 服务后启用。Agentic 轮数可用 `RAG_AGENTIC_MAX_ITERATIONS` 配置（默认 3、最大 5）；语义缓存相似阈值/TTL 可用 `RAG_CACHE_MINIMUM_SIMILARITY` / `RAG_CACHE_TTL_HOURS` 配置。**外部验收限制：** Anthropic API 和 TEI 尚未用于真实在线质量、TTFT、Reranker Recall 或 Prompt Cache 命中率测量；意图分类也需真实标注集评估。不得将当前单元/集成测试结果视为上述生产指标验收。

### EPIC F — LLM 网关与可靠性

| ID | 工作包 | 交付物 | 依赖 | 验收标准 | 人日 |
|---|---|---|---|---|---|
| **F1** ✅ ⚠ | LLM Gateway 与模型分层路由 | `AiModelConfiguration` + `RagChatGateway` + tier 路由 | A2 | **已完成（2026-10-28，三档 Bean 与应用上下文测试通过）**：关闭单选模型自动配置，以显式 Bean/Qualifier 创建独立 LITE/STANDARD/EXPERT Anthropic `ChatModel`；模型 ID、effort、token 上限可配置。普通问答走 STANDARD，多跳/长上下文走 EXPERT，配额退化走 LITE。**待验证**：真实 API 凭据下模型兼容性与成本/质量数据 | 6 |
| **F2** ✅ | 两级超时与熔断降级 | `ResilientRagChatGateway` + Resilience4j CircuitBreaker | F1 | **已完成（2026-10-28）**：首响应块 TTFT 默认 5s，失败计入按档位隔离的熔断器；整个流默认 120s 的总时限不计为熔断失败；超时/熔断时已检索引用仍返回，SSE 以 `RETRIEVAL_ONLY` 完成而非 500。单元测试覆盖 TTFT 开熔断、总时限不触发熔断及检索降级 | 4 |
| **F3** ✅ ⚠ | Token 计量与配额 | `RedisTokenQuotaService` + `JdbcLlmCallRecorder` + `llm_call_log` | F1, B1 | **已完成（2026-10-28）**：input/output/cache_read 分列记录 Micrometer 与 `llm_call_log`；`ttft_ms` / `total_ms` 已写入并由 Podman PostgreSQL 集成测试验证；按 caller/UTC 日期使用 Redis token 累计，达到默认 100,000 token 或 Redis 不可用时降级至 LITE。**待验证**：真实 Redis 连通性、并发负载及生产配额阈值标定 | 4 |
| **F4** ✅ ⚠ | Prompt Registry | V11 seeded `prompt_version` + `/api/internal/prompts` | F1, B1 | **已完成（2026-10-28）**：默认 Prompt 含 SHA-256 校验和；管理 API 需配置 `PROMPT_REGISTRY_ADMIN_TOKEN`，可新建版本、按 caller 稳定分配 canary、激活/回滚；Prompt namespace 写入 Spring AI request context，并隔离语义缓存及 cache 命中指标。Podman 集成测试验证版本、canary 和即时回滚。**待验证**：真实流量的 Prompt 质量/A-B 结果 | 5 |
| **F5** ✅ ⚠ | RAG 间接注入防护 | `InstructionInjectionDetector` + 不可信上下文分区 | E3, D3 | **已完成（2026-10-28）**：知识入库前拒绝常见指令覆盖/伪造角色标记；检索文本在 Prompt 中 HTML-escape 并置于显式不可信分隔区；RAG ChatClient 不注册任何工具。单元与 Podman 入库测试覆盖拦截和不落库。**待验证**：更大规模红队语料与绕过率评估 | 4 |

**小计：23 人日**（F1 因 A1 发现 4→6）

**Epic F 配置与运维：** `LLM_MODEL_{LITE,STANDARD,EXPERT}`、`LLM_EFFORT_{LITE,STANDARD,EXPERT}` 及 `ANTHROPIC_API_KEY` 配置模型；`LLM_TTFT_TIMEOUT` / `LLM_STREAM_TIMEOUT` 默认 `5s` / `120s`；`LLM_DAILY_TOKEN_LIMIT` 配额通过 Redis 生效；Prompt Registry 需将高熵值注入 `PROMPT_REGISTRY_ADMIN_TOKEN`，未配置时管理 API 保持关闭。Epic G2 已接入认证，RAG 请求按 principal 隔离配额并稳定分配 canary。真实 Anthropic 请求、生产 Redis 配额压力和红队覆盖仍未进行外部验收。

### EPIC G — 网关与用户

| ID | 工作包 | 交付物 | 依赖 | 验收标准 | 人日 |
|---|---|---|---|---|---|
| **G1** ✅ | API 网关与限流 | `ApiGatewayFilter` + Redis Lua fixed-window limiter + bounded local fallback | A2 | **已完成（2026-10-29）**：模块化单体沿用 Controller 路由，由 `/api/**` 和 `/mcp/**` Servlet ingress filter 统一入口限流（未引入独立 Cloud Gateway 服务）；Redis 原子计数/过期，Redis 不可用或无计数时降级到容量受限的进程内窗口；不信任未配置的 `X-Forwarded-For`。限额默认 60 次/分钟，可配置；限流器单元测试覆盖 Redis 与 fallback | 3 |
| **G2** ✅ ⚠ | 认证授权 | Spring Security 7 + OAuth2 Resource Server + JWT 本地注册登录 | A2, B1 | **已完成（2026-10-29）**：`POST /api/auth/register`、`POST /api/auth/login` 使用 BCrypt(12)，签发 15 分钟 HS256 JWT；Bearer token 由 Resource Server 验签和校验 issuer/时效；`/api/**` 默认需认证，匿名访问返回 401，认证/健康与 Prompt Registry secret API 例外。**运维要求**：生产配置至少 32 字节 Base64 `JWT_SECRET`；未设置时生成仅本进程有效的随机密钥。⚠ 未接入外部 OAuth/OIDC 身份提供商（设计未指定供应商） | 5 |
| **G3** ✅ | 用户与技能档案 | `/api/users/me` + `/api/users/me/skills` + `user_skill_state` 行锁事务 | B1, G2 | **已完成（2026-10-29）**：用户资料和技能查询由 token subject 确定用户，不接受客户端指定 `user_id`；按知识主题记录正确/错误回答并应用 BKT 更新，在事务中对 `(user_id, topic_id)` 行执行 `FOR UPDATE`，并发更新串行化、响应计数不丢失。Podman/PostgreSQL 并发集成测试覆盖同一行多并发回答 | 4 |

**小计：12 人日**

**Epic G 配置与运维：** `API_RATE_LIMIT_PER_MINUTE` 默认每 client IP 每分钟 60 次，Redis 限流 key 前缀由 `API_RATE_LIMIT_KEY_PREFIX` 配置；Redis 不可用时是每个应用进程独立兜底，集群整体限额会相应变弱。生产环境必须注入至少 32 字节 Base64 `JWT_SECRET`，并可用 `JWT_ISSUER` / `JWT_ACCESS_TOKEN_TTL` 配置 issuer/有效期；不设置密钥时每次启动生成新的随机密钥，重启后旧 token 失效。除健康检查、注册登录及其自带管理密钥的 Prompt Registry API 外，其余路由（包括 MCP）要求 Bearer JWT。G1 暂在单体内路由现有 Controller，服务拆分后再引入独立 Spring Cloud Gateway。

**监控公开例外：** `/actuator/prometheus` 保持匿名可读以兼容现有 Prometheus scrape 配置；其余 Actuator 路由需要认证。

### EPIC H — 前端

| ID | 工作包 | 交付物 | 依赖 | 验收标准 | 人日 |
|---|---|---|---|---|---|
| H1 ✅ | 前端工程基建 | 独立 `frontend`：Next.js 15 + TS + Tailwind 4 + shadcn/ui | — | **已完成（2026-10-29）**：App Router / React 19，系统/明暗主题及持久化；生产构建通过，浏览器覆盖主要页面 375px 无横向滚动；服务端同源 BFF 对接 Spring，浏览器无需跨域配置 | 3 |
| H2 ✅ | 搜索页与结果列表 | `/search` + 搜索 BFF | H1, E1 | **已完成（2026-10-29）**：对接 E1 关键词/模块/版本筛选，显示版本与许可标签、来源原文及详情跳转；包含加载、空结果、失败和会话过期状态 | 4 |
| H3 ✅ | 知识点详情页 | `/knowledge/[id]` + `GET /api/knowledge/{id}` | H1, E3 | **已完成（2026-10-29）**：展示 chunk 正文、模块、最低/最高适用版本、作者、来源与许可链接，满足 H-14；后端仅返回 approved source 的 chunk，未审核/不存在返回 404。Podman/PostgreSQL 验证真实 SQL、字段映射、401 和撤销审核后的 404 | 4 |
| H4 ✅ ⚠ | 流式对话框 | `/chat` + AI SDK 5 + Spring SSE 适配器 | H1, E3 | **已完成（2026-10-29）**：将 token/citation/done/error 转为 AI SDK UI stream；逐段文本输出、原文/详情引用跳转、停止生成、显式中断错误和 RETRIEVAL_ONLY 降级标识；验证 UTF-8 跨块与 CRLF 解码。**待验证**：真实 Anthropic/TEI 联调、生产代理流式刷新与延迟指标 | 5 |
| H5 ✅ | 登录注册与会话 | `/login`、`/register` + HttpOnly cookie BFF | H1, G2 | **已完成（2026-10-29）**：对接 G2 注册/登录，JWT 仅存 HttpOnly / SameSite=Lax cookie（生产 Secure），不返回浏览器 JSON 或 localStorage；刷新保留会话、退出清 cookie；入页及每 60 秒检查，API 401 清理会话并跳转登录；安全限定站内返回地址。当前后端无 refresh API，采用过期重新登录 | 3 |

**小计：19 人日**

**Epic H 启动与配置：** Spring 后端默认 `http://localhost:8080`；在 `frontend` 执行 `npm ci`、`Copy-Item .env.example .env.local`、`npm run dev`，访问 `http://localhost:3000`。`BACKEND_URL` 仅用于服务端，不使用 `NEXT_PUBLIC_` 暴露后端配置或密钥。生产通过同一公开源提供页面和 BFF、启用 HTTPS，保留 mutation Origin 校验；反向代理须保持公开 Host/Origin 一致，关闭 SSE 缓冲并提供超过 130 秒的流超时。后端生产须配置持久化 `JWT_SECRET`。

**Epic H 验收记录与边界：** `npm run lint`、`npm run typecheck`、`npm run build` 已通过；构建仍有 AI SDK provider-utils 动态依赖警告，不影响当前适配器构建。`npm run test:e2e` 在生产 Next server + 确定性 mock backend 下 **10/10 通过**，覆盖注册、HttpOnly 会话持久化/退出、错误登录、过期跳转与返回地址、搜索/空结果、详情归因、部分流/引用、流错误、检索降级/停止、主题/移动布局、跨源拒绝及无效请求。Java 详情单元测试 **2/2**、Modulith 边界验证及 Podman 数据库接口集成测试 **1/1** 通过（数据库端口 5433）。浏览器 mock 验收不等于真实模型质量或端到端生产联调；未测真实 LLM 指标，也未进行跨浏览器验收。启动说明与命令见 `frontend/README.md`。

---

### Phase 2 — 学习规划与内容生成

| ID | 工作包 | 依赖 | 状态与交付结果（2026-10-30） | 人日 |
|---|---|---|---|---|
| I1 ✅ | 知识图谱 DAG 存储与递归 CTE 查询（前置依赖闭包） | B1, D5 | **已完成**：V12 独立前置边表、递归 CTE、确定性拓扑顺序；数据库触发器与管理接口共用 advisory lock 防止并发成环；ADMIN 维护节点/边。分类树父子关系不冒充学习前置关系 | 4 |
| I2 ✅ ⚠ | 学习计划生成（结构化输出 + Few-shot + **DAG 拓扑校验**） | I1, F1, F4 | **实现完成**：完整前置闭包、技能档案、注册表 Few-shot Prompt、原生结构化生成；校验主题全集、顺序及实际依赖路径；网络调用不占数据库事务，保存前持锁复查图快照，变化返回 409；每个主题必须有已审核知识证据。真实模型质量验收待外部环境 | 6 |
| I3 ✅ | 学习任务看板与进度追踪 | I2, B1 | **实现完成**：用户隔离的计划/任务读写、前置任务门禁、累计学习秒数、事务更新进度；已完成任务不可变，跳过任务可恢复 | 5 |
| I4 ✅ | 自适应计划调整（基于完成率反馈） | I3 | **实现完成**：完成率低于 50% 时补充聚焦复习任务并记录调整；重复调用幂等，不覆盖已完成历史 | 4 |
| I5 ✅ | 初始摸底评估流程 | G3, I2 | **实现完成**：采用人工审核客观选择题，不提前依赖 Phase 3 自动题库；冻结题目快照、答前隐藏答案、用户隔离、幂等评分及 BKT 技能更新；ADMIN 录题必须关联本主题的已审核 chunk，无可用题明确返回 409 | 4 |
| J1 ✅ ⚠ | 代码示例三级生成（概念 / 实战 / 源码级） | F1, E3 | **已完成**：CONCEPT / PRACTICAL / SOURCE、审核知识检索与归因、用户私有持久化、版本/模型/Prompt 溯源；生成期间撤审返回 409，历史内容证据撤审返回 410；真实模型质量待验收 | 5 |
| J2 ✅ | Spring Boot 项目骨架生成与下载 | J1, A3 | **已完成**：固定 Java 21 / Boot 4.1.2 模板，安全 Maven 坐标与 ZIP 路径，包含源码、测试、POM、README；可附带本人示例作为文档，不自动执行 AI 代码；本人持久化项目可重新下载 | 4 |
| J3 ✅ ⚠ | 技术对比文档生成 | J1, I1 | **已完成**：普通技术比较及同一 Java 类跨版本 API 比较，要求已审核来源/快照，输出含许可与模型来源；缺失证据显式失败；真实模型质量待验收 | 3 |
| K1 ✅ | **MCP Server**（`search_knowledge` / `get_source` / `compare_versions` / `check_prerequisites`） | E3, E5, G2 | **已完成**：JWT 认证、入口限流的 `/mcp` Streamable HTTP，恰好注册四个只读工具；返回来源/许可，不把 MCP 工具回挂 RAG 模型；真实 MCP 客户端初始化、发现及调用由 Podman 集成测试覆盖 | 5 |
| K2 ✅ ⚠ | 原生结构化输出替换 Converter + 重试 | I2, J1 | **已完成**：Anthropic 原生 `output_config.format.type=json_schema`，不使用文本 Converter 冒充；解析、校验、截断、超大输出和期限异常有界处理，共享总期限并沿用熔断/配额/模型分档；本地 HTTP wire 验证请求格式，实际模型兼容性待验收 | 2 |
| K3 ✅ ⚠ | Langfuse 深度接入（trace 树 + Prompt diff + 成本归因） | A5, F4 | **已完成**：可选 Langfuse OTLP 导出、RAG 根/检索/模型 span 与结构化生成 trace、Prompt 名称/版本/校验和、精确行区间 diff、按模块/模型与三类 token 归因；未知用量/价格的成本保持 NULL。已验证本地导出协议，真实 Langfuse UI 与部署验收待完成 | 3 |
| H6 ✅ | 前端：学习计划页 | I2, H1 | **实现完成**：`/plans` 主题选择、目标输入、用户计划历史及来源/模型标识；另含 `/assessment` 摸底、答题恢复与结果展示 | 3 |
| H7 ✅ | 前端：学习任务看板 | I3, I4, H6 | **实现完成**：`/plans/[id]` 前置门禁、开始/完成/跳过/恢复、累计耗时、进度及调整反馈，显示来源许可 | 4 |
| H8 ✅ | 前端：代码示例页 | J1, J2, J3, H1 | **已完成**：`/examples` 三级示例、技术对比、持久结果重开及 ZIP 下载；同源 BFF 白名单转发、HttpOnly 会话、明确失败状态，代码仅作文本展示 | 3 |

**小计：55 人日**

**Phase 2 运维前置与配置：**

- Flyway V12–V15 分别交付图谱、学习/诊断、生成内容、调用溯源；使用现有 PostgreSQL/pgvector 与 Redis。本地仍使用 Podman，集成测试数据库映射 `5433:5432`，不要占用或终止不属于本工程的进程。
- 不插入生产演示题、伪造知识或自动授予 ADMIN。运维须按现有账号角色管理流程配置管理员（角色变更后重新登录），建立知识主题/前置边，将已审核知识 chunk 关联主题，然后通过 ADMIN API 录入已审核选择题。计划对闭包中每个主题要求已审核证据；无题返回 409，缺乏计划证据返回 422；来源撤审后计划、题目及生成内容不再重新展示，返回 410。
- `STRUCTURED_GENERATION_DEADLINE=90s`（上限 120s）、`STRUCTURED_GENERATION_MAX_ATTEMPTS=3`（1–3）；重试共享总期限，不逐次重置超时。模型仍由 `LLM_MODEL_LITE/STANDARD/EXPERT` 配置，所选供应商模型须实际支持原生 JSON Schema。
- Langfuse 默认关闭。设置 `LANGFUSE_ENABLED=true`、`LANGFUSE_BASE_URL`、`LANGFUSE_PUBLIC_KEY`、`LANGFUSE_SECRET_KEY` 后，额外导出至 `/api/public/otel/v1/traces`，保留原 OTLP 接收端；除本机 loopback HTTP 外强制 HTTPS。默认仅导出溯源/用量元数据，不导出题目、答案、完整 Prompt 或原始用户标识，trace 不公开；不要在生产随意启用 completion 采集。`infra/.env.example` 列出按模型档位的 input/output/cache-read 单价变量，未配置或未知用量不把成本伪记为零。
- Prompt diff 为**本地 Prompt Registry 版本**差异及 hash 关联，不宣称已发布到 Langfuse Prompt Management；管理接口须配置 `PROMPT_REGISTRY_ADMIN_TOKEN` 并提供 `X-Prompt-Admin-Token`，仍经过现有入口认证。

**Phase 2 REST 契约（UUID 为字符串，来源由服务器补齐，不能由模型自报）：** 机器可读契约见 [phase2-openapi.yaml](phase2-openapi.yaml)；MCP 工具契约见下表与运行时 `tools/list`。

| 方法与路径 | 请求 / 响应与约束 |
|---|---|
| `GET /api/knowledge/topics`；`GET /api/knowledge/topics/{id}/prerequisites` | 主题与递归拓扑前置列表：`{id,name,springModule,description,prerequisites[]}` |
| ADMIN `POST /api/knowledge/topics`；`PUT/DELETE /api/knowledge/topics/{id}/prerequisites/{prerequisiteId}` | 创建 `{name,springModule?,description?}` 返回 201 `{id}`；加/删前置边返回 204，自引用/成环拒绝 |
| `GET/POST /api/learning/plans`；`GET /api/learning/plans/{id}` | 创建 `{topicIds,goal?}`；列表返回摘要，详情返回任务。包含状态、模型/Prompt、时间、`sourceTopicIds`、`sources[]`；摘要另含任务/完成数，详情含 `tasks[]` |
| `PATCH /api/learning/tasks/{id}` | `{status,timeSpentSeconds}`；状态为 PENDING / IN_PROGRESS / COMPLETED / SKIPPED，耗时为累计秒数；服务端校验状态转移及前置完成 |
| `POST /api/learning/plans/{id}/adapt` | 返回调整后的完整计划；低完成率补充复习任务，重复调用不重复补充 |
| `POST /api/assessments/initial`；`GET /api/assessments/{id}` | 创建 `{topicIds}`；返回冻结会话与题目、`source`、作答及分数；完成前 `correctAnswer/explanation` 隐藏，无审核题返回 409 |
| `POST /api/assessments/{id}/answers` | `{questionId,answer}` 返回 `{questionId,answer,isCorrect,completed,score}`；同一答案重交不重复评分或更新技能 |
| ADMIN `POST /api/assessments/questions` | `{topicId,content,options:[{label,text}],correctAnswer,explanation?,difficulty?,springModule?,springVersion?,sourceChunkId}`；已审核、属于同一主题的来源 chunk 为必填，返回 `{id,topicId,reviewed:true}` |
| `POST /api/generation/examples` | `{query,level,module?,springVersion?}`；`level` 为 CONCEPT / PRACTICAL / SOURCE，返回 201 生成结果 |
| `POST /api/generation/comparisons` | `{left,right,module?,springVersion?}`；同类版本比较使用 `全限定类名@版本`，例如 `org.example.Bean@6.1` 与 `org.example.Bean@6.2` |
| `GET /api/generation/{id}` | 仅本人可读，其他用户 404；结果含 `id,kind,title,content,code,language,aiGenerated,origin,sources,modelId,promptVersion,level,springVersion,createdAt,project`，固定 `origin=AI_GENERATED`，不写入知识索引或语义缓存 |
| `POST /api/generation/projects`；`GET /api/generation/{id}/project` | 创建 `{groupId,artifactId,name,generationId?}`；可选示例须属本人；返回 ZIP、`X-Generation-Id`、`Content-Disposition`、private/no-store |
| `GET /api/internal/prompts/{name}/diff?from=1&to=2` | 返回版本与校验和及精确变更行区间；包括末尾换行变化；受管理密钥保护 |

统一归因对象 `Source={id,title,url,license,licenseUrl,author}`。计划来源使用 `sources[]`，诊断每题使用 `source`；生成内容每份使用 `sources[]`。前端 BFF 不开放管理录题/图谱/Prompt API。对用户拥有的数据实施所有权隔离，前端不保存或提交评分答案键。

**IC-7 MCP 契约冻结：** 客户端携带 Bearer JWT，经 `/mcp` 执行 initialize、tools/list、tools/call；四个工具不修改数据、不执行代码。

| 工具 | 入参 | 出参 |
|---|---|---|
| `search_knowledge` | `query`，可选 `module,springVersion` | `{results:[{id,content,springVersion,source:{id,url,title,license,version}}]}`，最多 20 条已审核结果 |
| `get_source` | `sourceId`（来源 UUID，不是 chunk UUID） | 已审核 `Source` |
| `compare_versions` | `className,fromVersion,toVersion` | `[{className,kind,signature,version,changeType,source}]`，ADDED / REMOVED / UNCHANGED；要求双方已审核快照，REMOVED 墓碑不计入快照实际符号 |
| `check_prerequisites` | `topicId` | 拓扑有序 `[{topic,sources,lacksApprovedSource:false}]`；任何前置主题缺乏审核来源时显式失败，不伪造归因 |

**Phase 2 验收记录：** 后端定向单元/模块测试 **44/44**，覆盖图谱、原生 schema HTTP wire、许可撤审、学习计划校验、项目模板、Prompt diff、计量及七模块边界；最终生产打包通过。Podman/PostgreSQL 集成 **10/10**（`Phase2KnowledgeIT` 4、`Phase2LearningIT` 5、`GenerationIT` 1），覆盖真实 HTTP/MCP 认证/发现/调用、数据库防环、计量溯源、计划图快照变化、进度/调整、冻结诊断与幂等技能更新、生成持久化及下载。OpenAPI YAML 及其 39 个本地引用可解析。前端 lint、typecheck、生产构建及 Chromium **17/17** 场景通过（包括原 Epic H 10 项及新增 Phase 2 7 项）。

**复验命令：** 先 `mvn -q -DskipTests test-compile`，再设置 `$env:DOCKER_HOST='npipe:////./pipe/podman-machine-default'`、`$env:TESTCONTAINERS_RYUK_DISABLED='true'`，执行 `mvn -q '-Dit.test=Phase2KnowledgeIT,Phase2LearningIT,GenerationIT' failsafe:integration-test failsafe:verify`。集成测试类顺序复用固定端口 5433，不并发启动多个 Maven 集成运行；前端命令见 `frontend/README.md`。

**验收边界：** 浏览器使用确定性 mock backend，后端模型响应受控，不等于真实模型联调。项目模板测试编译生成的 Java 源码，不等于下载 ZIP 后完整 Maven 生命周期验收。真实 Anthropic/TEI 质量与延迟、Langfuse UI、生产代理与跨浏览器验收仍需在部署环境完成。

### Phase 3 — 评估与能力模型

| ID | 工作包 | 依赖 | 状态与交付结果（2026-10-30） | 人日 |
|---|---|---|---|---|
| L1 ✅ ⚠ | 题库与题目生成（`QUESTION` 表 + Batch API + 质量检测） | B1, D5, F1 | **已完成**：V16 增加题目批次、质量审计、测试用例、Rubric 维度、IRT 统计、提交与评分表；提供 ADMIN 批量出题与审核 API，按来源审核、重复/过时证据、题型结构进行质量门禁并显式返回错误。质量评估基于当前规则集，仍需真实流量调优阈值 | 7 |
| L2 ✅ ⚠ | 代码沙箱（gVisor/Kata + 资源限制 + 网络隔离） | A3 | **已完成（基线版）**：新增沙箱抽象与本地 Java runner，具备编译/运行超时、内存上限、静态危险模式拦截（进程/网络/反射等）及结构化结果。当前实现为本地进程隔离与策略拦截，不等同内核级 gVisor/Kata 生产隔离 | 8 |
| L3 ✅ | 在线判题与静态分析 | L2, L1 | **已完成**：编程题运行检查与提交接口，输出编译/运行/测试/静态提示，提交结果持久化并按阈值更新 BKT；对重复提交使用幂等键去重 | 5 |
| L4 ✅ ⚠ | 主观题 AI 批改 + Rubric 多维评分 | L1, F1 | **已完成**：主观题评分接口基于结构化输出返回维度分、总分与反馈，持久化评分明细并驱动 BKT。真实模型评分一致性与 Rubric 细粒度校准仍需线上验证 | 5 |
| L5 ✅ | BKT 知识追踪模型 | L3, L4, I1 | **已完成**：客观题、编程题与主观题统一走 BKT 更新，提交记录使用 `bkt_applied` 防重复加分，事务内保证一致性 | 6 |
| L6 ✅ ⚠ | IRT 题目参数标定与自适应难度 | L5, L1 | **已完成**：新增 IRT 参数表与校准任务（含定时/手动触发），提供自适应下一题接口，按用户掌握度与题目参数选题。当前为工程化近似实现，非完整科研级 IRT 训练流水线 | 6 |
| L7 ✅ | 能力报告与技能雷达图 | L5 | **已完成**：后端提供能力报告聚合（掌握度、近期评估、强弱项、建议）；前端 `/report` 输出确定性 SVG 雷达图 | 4 |
| M1 ✅ | CodeMirror 6 答题编辑器 | H1 | **已完成**：新增 `/practice`，支持 Java/TS/JS/Python 模式、运行检查、提交与结果面板；代码仅文本提交，不在浏览器执行 | 4 |
| M2 ✅ | 服务拆分：ai-engine + kb-indexer 独立部署 | A2, E3, D6 | **已完成（部署级）**：新增运行角色开关 `springtechkb.runtime.role`（all/ai-engine/kb-indexer）及网关阻断逻辑，新增 `infra/k8s/ai-engine-deployment.yaml` 与 `kb-indexer-deployment.yaml`，并给出拆分说明 `docs/phase3-service-split.md` | 6 |
| M3 ✅ | **DEC-04 Kafka 判定**（判定 1 人日；如引入 +5 人日） | M2 | **已完成判定**：当前阶段维持 PostgreSQL outbox + dispatcher，不引入 Kafka；重评估阈值与迁移预留见 `docs/dec-04-kafka-decision.md` | 1 |

**小计：52–57 人日**

**Phase 3 契约与配置：**

- 新增机器可读契约：`docs/phase3-openapi.yaml`（评估批量出题/审核、编程判题、主观评分、IRT、自适应与能力报告）。
- 新增迁移：`V16__phase3_assessment_engine.sql`。已包含题目质量审计、测试用例、Rubric、IRT、提交与评分明细，不破坏既有 Phase 2 迁移。
- 新增运行配置（`application.yml`）：
  - `springtechkb.assessment.sandbox.*`（策略、超时、内存、源码长度）
  - `springtechkb.assessment.quality.*`（草稿/审核门槛、证据过期天数）
  - `springtechkb.assessment.irt.cron`
  - `springtechkb.runtime.role`（all / ai-engine / kb-indexer）

**Phase 3 验收记录：**

- 后端：`mvn -q -DskipTests test-compile`、定向单测（含沙箱策略、本地 runner、评估/学习回归、模块边界）通过；`mvn -q -DskipTests package` 通过。
- Podman 集成：`SchemaIntegrationIT`（8）、`Phase2KnowledgeIT`（4）、`Phase2LearningIT`（5）、`GenerationIT`（1）均通过；一次批量运行出现 Windows socket 缓冲区耗尽导致 MCP 用例连接失败，单独重跑通过，记录为环境瞬时问题而非代码回归。
- 前端：lint/typecheck/build 通过；Playwright 覆盖 `frontend.spec.ts`（10）+ `phase2.spec.ts`（7）+ `phase3.spec.ts`（2）全部通过（总 19）。

**Phase 3 验收边界：**

- 代码沙箱当前为本地受限进程策略，尚未完成 gVisor/Kata 级别隔离与独立节点池。
- 主观题评分与题目生成质量门槛已工程化落地，但真实模型一致性、偏差与成本仍需线上数据持续标定。
- IRT 为可运行近似方案，尚未引入完整离线参数拟合/收敛监控流水线。

### Phase 4 — 扩展与运营（交付状态）

| ID | 工作包 | 状态与交付结果 | 人日 |
|---|---|---|---|
| N1 ✅ ⚠ | 知识库扩充（Cloud / Batch / Integration） | 新增三类官方 Spring 文档目录及受控爬取接口：只接受目录 key，限制下载体积与摘录长度，要求 ADMIN 与审核引用；更新 `docs/knowledge-source-allowlist.md` 和 `docs/phase4-openapi.yaml`。完成服务/契约单测；真实外网抓取与来源审核流程仍需运营环境验收 | 8 |
| N2 ✅ | 知识图谱可视化（D3.js 力导向图） | 新增 `/graph` D3 力导向图、前置关系与可访问主题详情，接入 BFF、mock 与导航；Phase 4 Playwright 验证图谱展示 | 5 |
| N3 ✅ | 成就体系 + Streak + 排行榜 | V17 持久化成就、连续学习与排行榜数据；排行榜使用生成别名并允许 opt-in，避免暴露用户 ID/邮箱。新增 `/achievements` 与对应 BFF/mock/浏览器验证 | 6 |
| N4 ✅ ⚠ | 成本优化与监控大盘（Prompt Caching 命中率 / Batch 占比 / 熔断状态） | 新增 ADMIN-only `GET /api/operations/summary?days=1..30`，返回分模型层级 token/cost、cache-read 比率、Batch 占比、未知成本计数和熔断状态；新增 `/operations` 仪表盘。成本准确度仍依赖生产 provider 用量数据，尚未进行线上校准 | 5 |
| N5 ✅ ⚠ | K8s 生产部署 + **KEDA** 按在途请求伸缩 | 增加 ai-engine/indexer 部署、Pod Prometheus 抓取/RBAC 与两个 KEDA Prometheus `ScaledObject`；在线服务按在途 API/MCP 请求扩缩，indexer 按 outbox backlog 扩缩；新增 gateway 并发及可配置 backlog 指标。清单 YAML 已解析验证；镜像 tag、Secret、Prometheus 地址需按环境配置，未在目标 Kubernetes 集群做部署/扩缩容验收 | 6 |
| N6 ✅ | **DEC-05 ES 判定**（判定 2 人日；如引入 +8 人日） | `docs/dec-05-elasticsearch-decision.md` 决定当前继续 PostgreSQL FTS + pgvector，不引入 Elasticsearch；记录现有证据缺口、可复现的重评估门槛及后续双写/回退原则 | 2–10 |

**小计：32–40 人日**

**Phase 4 验收记录：**

- 后端：`mvn -q -DskipTests test-compile` 通过；Phase 4 定向测试覆盖网关并发/异常与 outbox 指标、官方文档目录/摘录、成就及运营摘要；`SchemaIntegrationIT` 在 Podman 上 **9/9** 通过（含 V17 schema 断言）。
- 回归测试：其余后端单测 **85/85** 通过；整套运行中，`ResilientRagChatGatewayTest` 和 `LocalJavaCodeSandboxServiceTest` 各有一项时限敏感测试偶发断路器/沙箱超时，两个类单独重跑 **4/4** 通过，因此记录为环境/时序敏感而非稳定全量全绿。
- 前端：lint、typecheck、生产构建通过；Phase 4 Playwright **3/3** 通过。全套 22 项回归执行期间，既有登录/流式测试遭遇 Windows socket/端口耗尽（`10055` / `ERR_ADDRESS_IN_USE`）而不稳定；能力报告与登录反馈用例单独复验通过，不将全套回归记为全绿。
- Kubernetes / Prometheus YAML 语法解析通过；没有可用于真实集群部署、Prometheus 查询和 KEDA 扩缩容的目标集群，本阶段交付的是可配置部署基线而非生产环境验收。
- ES 决策基于现有实现与缺少对比评估/线上负载证据；文档阈值是未来重评估条件，不代表已测得的性能结果。

---

## 4. 工期校验与阶段再平衡

### 4.1 总量

| 阶段 | 人日 | 原计划工期 | 隐含所需 FTE |
|---|---|---|---|
| Phase 1 | **159** | 6 周（30 工作日） | **5.3** |
| Phase 2 | 55 | 6 周 | 1.8 |
| Phase 3 | 52–57 | 6 周 | 1.8 |
| Phase 4 | 32–40 | 6 周 | 1.1–1.3 |
| **合计** | **298–311** | 24 周（120 工作日） | **2.5 平均** |

### 4.2 发现：阶段间严重不均衡

原 24 周四阶段计划隐含约 **2.5 FTE** 的平均投入，但 **Phase 1 的负载密度要求 5.3 FTE** —— 前重后轻，相差近 3 倍。按 2.5 人的实际配置，Phase 1 会实际耗时约 12–13 周而非 6 周，后续阶段全部顺延。

**这不是估算偏差，是阶段划分本身的问题。** 原因是 Phase 1 被要求同时交付：工程基建 + 数据层 + Eval + 内容采集 + 检索 + 问答 + 网关认证 + 前端四个页面 —— 这实质上是一个完整产品的首个可用版本，而非 MVP。

### 4.3 两种处置方案

**方案 A — 裁剪 Phase 1 至最小可验证集（推荐）**

Phase 1 的唯一价值假设是「RAG 能否在 Spring 技术域给出高质量、版本正确、可溯源的回答」。验证它需要：

```
A1 A2 A3 A4        (10)  工程基建（A5 可观测性基座简化为日志+基础 metrics）
B1 B2 B3 B4        (17)  数据层与推理运行时
C1 C2 C3           (11)  Eval —— 不可裁剪，它是「验证」本身的前提
D1 D2 D3 D4 D5     (24)  内容采集与入库（D6 源码 Pipeline 原计划延后）
E1 E2 E3 E6 E7     (18)  单轮 RAG + 缓存（E4/E5 Agentic 延后）
F1 F2 F3           (14)  LLM 网关与可靠性（原计划延后 F4/F5；现 F1–F5 均已实现，见 Epic F）
G1 G2              ( 8)  网关与认证（原计划将 G3 技能档案延后至 Phase 2；现 G1–G3 均已实现）
H1 H2 H3 H4        (16)  前端四项（原计划延后 H5；现 H1–H5 均已实现，见 Epic H）
────────────────────────
                   118 人日  →  1 人 ≈ 24 周 / 2 人 ≈ 12 周 / 4 人 ≈ 6 周
```

**执行状态修订：** 上述是原计划基线；D6 已在 Epic D 实现并通过本地 tag/JAR 测试，不再属于待实施的 Phase 2 工作；F4/F5 虽列为原计划延期项，现已随 Epic F 实现；G3、H5 也已提前完成。其余延期项仍按原计划管理，Phase 2 原估 96 人日中扣除 D6 的 8 人日后为 88 人日（G3 的 4 人日亦已实现）；D5 人工审核仍待完成。

> **安全状态：** `F5 注入防护` 已实现基础入库检测、不可信上下文分隔和无工具调用约束；检测器并非完整注入分类器，大规模红队语料与绕过率尚未评估。是否对外开放仍需据此确定额外审核和运行控制，不能将当前测试等同于生产安全验收。

**方案 B — 保留全量范围，Phase 1 工期改为 12 周**

总工期从 24 周拉长到约 30 周。适合人力固定在 2–3 人且不愿裁剪范围的情况。

### 4.4 不同人力下的工期预测（方案 A）

| 人力 | Phase 1 | 全程（298 人日） | 备注 |
|---|---|---|---|
| 1 人 | ~24 周 | ~60 周 | 全栈串行，关键路径几无压缩空间 |
| 2 人 | ~12 周 | ~30 周 | 前后端分工，需严格冻结接口契约 |
| 3 人 | ~8 周 | ~20 周 | 后端 2 + 前端 1，接近原计划节奏 |
| 4–5 人 | ~6 周 | ~15 周 | 可达原计划；但需额外 15% 协同开销 |

> 上述未计入评审、联调与返工的 25–40% 附加开销。按 2 人团队、方案 A 计，**全程现实预期为 37–42 周**。

---

## 5. 关键路径与并行策略

### 5.1 关键路径（Phase 1，方案 A）

```
A1 ──► A2 ──► B1 ──► B2 ──┐
  └──► A3 ──► B4 ──────────┼──► E1 ──► E2 ──► E3 ──► H4
                 D1 ──► D2 ─┤                   │
                      └─ D3 ─┴──► D4 ───────────┘
                                   └──► D5
```

**关键路径长度：** A1(2) → A2(3) → B1(5) → B2(3) → E1(5) → E2(2) → E3(5) → H4(5) = **30 人日**

这是单人情况下的理论最短周期下限（不含 D5 内容入库的 8 人日，该项可与前端并行）。

### 5.2 可立即并行启动的工作包（无前置依赖）

- **C1 评测集构建**（5 人日）—— 纯人工标注，可与所有工程工作并行，且越早越好
- **D1 内容许可清单**（2 人日）—— 法律前置，必须早于任何入库动作
- **H1 前端工程基建**（3 人日）—— 不依赖任何后端

> 建议第一天同时启动 **A1 + C1 + D1 + H1** 四条线。A1 是技术风险闸门，C1/D1 是无依赖的长尾工作，H1 让前端不空等。

### 5.3 两人分工建议

| 角色 | 负责 EPIC | 同步点 |
|---|---|---|
| 后端 / AI | A, B, C, D, E, F, G | 契约冻结（见第 6 节）；B5 决策点 |
| 前端 / 全栈 | H + C1 标注协作 + D5 内容校验 | 基于已就绪的 E3 API 集成 H4 |

E1/E3 API 已就绪，前端可按第 6 节冻结的接口契约并行集成；C1 标注与 D5 内容校验仍可作为前端协作项，但 D5 内容须经人工审核后才能进入正式检索。

---

## 6. 接口契约冻结清单

并行开发的前提。每项契约需在**依赖它的工作包开始前**冻结，变更走评审。

| # | 契约 | 载体 | 冻结时点 | 下游依赖 |
|---|---|---|---|---|
| IC-1 | 数据库 Schema | 迁移脚本 + ER 图 | B1 完成 | 所有数据访问类工作包 |
| IC-2 | 检索与问答 REST API | OpenAPI 规范 | E3 开始前 | H2, H3, H4, K1 |
| IC-3 | SSE 流事件格式 | 事件类型表（token / citation / done / error） | E3 开始前 | H4 |
| IC-4 | 领域事件 payload | outbox 事件 schema | D4 完成 | 投递器、notification、kb-indexer |
| IC-5 | ai-engine 内部接口 | 模块 API 定义 | F1 完成 | M2 服务拆分时平移 |
| IC-6 | Eval 数据集格式 | JSON schema + 字段说明 | C1 完成 | C2, C3, 后续所有调参 |
| IC-7 | MCP 工具 schema | 工具定义（名称 / 入参 / 出参 / 许可字段） | K1 开始前 | 外部 Agent 接入方 |
| IC-8 | Prompt 模板变量 | 变量清单 + 类型 | F4 完成 | I2, J1, L4 |

---

## 7. 待决策清单

| # | 待决事项 | 决策依据来源 | 决策时点 | 拖延后果 |
|---|---|---|---|---|
| **DEC-01** | Embedding 模型与维度定稿 | B5 实验报告 | Phase 1 第 3–4 周 | 影响 B2 索引定义；拖延导致 D3/D5 全量重建 |
| DEC-02 | Reranker 部署形态（CPU / GPU） | B4 延迟与成本实测 | Phase 1 第 3 周 | 影响基础设施预算与 E2 延迟预期 |
| DEC-03 | 中文分词方案（zhparser / pg_trgm / 其他） | B3 检索效果对比 | Phase 1 第 2–3 周 | 影响 FTS 索引结构，变更需重建索引 |
| DEC-04 | 是否引入 Kafka | M2 完成后的实际事件量与消费者数 | Phase 3 末 | 无 —— 判定点设计本就允许延后 |
| DEC-05 | 是否引入 Elasticsearch | Phase 1 Eval 基线中 PG FTS 的相关性表现 | Phase 4 初 | 无 —— 同上 |
| DEC-06 | 三档模型的具体选型与 effort 配比 | F1 + 真实流量成本实测 | Phase 1 第 5 周 | 成本不可控；但不阻塞功能 |
| DEC-07 | 沙箱运行时（gVisor / Kata） | L2 调研 + 宿主内核支持情况 | Phase 3 初 | 影响节点池规划 |
| DEC-08 | 第三方博客是否做 URL 跳转推荐 | 法务确认 | Phase 1 第 1 周（D1 内） | 阻塞 D1 完成；可能需返工内容准入逻辑 |
| DEC-09 | Phase 1 是否对外开放 | 产品决策 | Phase 1 第 1 周 | 决定是否需在基础 F5 之外增加审核与运行控制（见 4.3 安全状态） |

---

## 8. 风险登记册

| # | 风险 | 等级 | 缓解措施 | 负责工作包 |
|---|---|---|---|---|
| R-1 | Spring Boot 4 生态适配问题（Spring AI 2.x、Batch 6.x 及第三方库） | **高** | A1 作为 Week 1 硬闸门，不通过则立即评估回退 Boot 3.5 + Spring AI 1.x | A1 |
| R-2 | Phase 1 负载密度与工期不匹配（需 5.3 FTE vs 平均 2.5） | **高** | 采用第 4.3 节方案 A 裁剪，或方案 B 延长工期 | 项目管理 |
| R-3 | 内容许可合规（第三方博客 / 书籍） | **高** | D1 法律前置；准入规则代码化；输出侧许可标注 | D1, DEC-08 |
| R-4 | 评测集标注质量决定后续所有判断的可信度 | 中高 | 双人交叉标注争议样本；持续从真实查询回补难例 | C1 |
| R-5 | 自托管推理服务的运维能力（TEI 部署、模型更新、容量规划） | 中 | 在线用 CPU 降低复杂度；保留托管 API 作冷启动兜底 | B4 |
| R-6 | Agentic Retrieval 的成本与延迟不可预测 | 中 | **迭代次数设硬上限**；按路径分别统计成本；超时回落单轮路径 | E4, E5 |
| R-7 | 用户代码沙箱逃逸 | 中 | gVisor/Kata 内核级隔离 + 独立节点池 + 无出站网络 | L2 |
| R-8 | 语义缓存串答案（跨版本 / 跨用户） | 中 | 命名空间键 + 个性化硬绕过；**专项测试用例覆盖** | E7 |
| R-9 | pgvector 元数据过滤下召回塌陷 | 中 | H-2 约束 + B2 带过滤条件的召回测试 | B2 |
| R-10 | Prompt Caching 与 Prompt 灰度发布互相失效 | 低中 | 按 Prompt 版本分缓存命名空间；避开高峰切换 | E6, F4 |

---

## 9. 工程规范与完成定义

### 9.1 仓库结构（Modulith 单体）

```
spring-tech-kb-service/
├── docs/                          # 本文档、ADR、OpenAPI
├── src/main/java/.../
│   ├── knowledge/                 # 知识入库/离线索引/图谱；公共 KnowledgeGraph
│   ├── search/                    # 检索/RAG/Prompt Registry/MCP；公共 KnowledgeQueries、PromptCatalog 实现
│   ├── ai/                        # 分档网关/原生结构化生成/调用计量/trace；公共网关与 PromptCatalog
│   ├── learning/                  # 学习规划模块
│   ├── assessment/                # 评估模块
│   ├── generation/                # 三级示例/比较文档/项目骨架
│   ├── user/                      # 用户与技能档案
│   └── */internal/                # 各模块非公开实现
├── src/test/java/.../
│   └── ModularityTests.java       # Modulith 边界校验
├── eval/                          # 评测集、运行器、基线存档
├── frontend/                      # Next.js 页面、同源 BFF 与浏览器测试
└── infra/
    ├── docker-compose.yml
    └── k8s/
```

**模块间通信规则：** 当前为 knowledge / search / ai / learning / assessment / generation / user 七个模块。通过各模块根包的公开接口、DTO 与事件通信，在 `package-info.java` 声明允许依赖；禁止跨模块引用 `internal` 实现，由 `ModularityTests` 强制校验。不存在通用 `shared` 实现包；未来服务拆分沿公开契约平移。

### 9.2 完成定义 (Definition of Done)

一个工作包视为完成，需同时满足：

- [ ] 功能满足该工作包的验收标准
- [ ] 单元测试覆盖核心逻辑；涉及模块边界的有模块测试
- [ ] `ModularityTests` 通过（无跨模块违规依赖）
- [ ] 涉及检索 / Prompt / Embedding / 分块的变更，**Eval 指标不低于基线**（C3 闸门）
- [ ] 涉及 API 变更的，OpenAPI 规范已同步更新
- [ ] 涉及知识内容的，来源与许可字段完整（H-9 / H-14）
- [ ] 涉及 LLM 调用的，Token 三列计量与 `ttft_ms` 已落库（H-7）
- [ ] 新增硬约束相关代码，已对照第 2 节检查项自查

### 9.3 CI 闸门

| 阶段 | 检查项 | 失败处理 |
|---|---|---|
| PR 提交 | 编译 + 单元测试 + Modulith 校验 + 代码风格 | 阻断 |
| PR 提交 | 变更涉及检索/Prompt 路径 → 触发 Eval | 低于基线则阻断 |
| PR 提交 | 依赖版本变更 → 对照版本锁定矩阵（A1 产出） | 阻断 |
| 主干合并 | 集成测试 + 镜像构建推送 | 通知 |
| 每日 | 全量 Eval + 成本报表 | 通知 |

### 9.4 测试分层

| 层级 | 范围 | 工具 |
|---|---|---|
| 单元 | 单类 / 单方法逻辑 | JUnit 5 + AssertJ |
| 模块 | Modulith 模块内切片 | `@ApplicationModuleTest` |
| 集成 | 含 PG + Redis + TEI 的端到端 | Testcontainers |
| 契约 | REST / SSE / MCP 接口 | OpenAPI 校验 + 快照测试 |
| **Eval** | 检索召回与生成质量 | RAGAS / promptfoo（独立于常规测试，见 C2） |

---

## 附录：工作包依赖索引

便于导入项目管理工具。格式 `WP-ID: 依赖列表`。

```
A1:  —
A2:  A1
A3:  A1
A4:  A2
A5:  A2
B1:  A3
B2:  B1
B3:  B1
B4:  A1, A3
B5:  B2, B4, C1
C1:  —
C2:  C1
C3:  C2, A4
D1:  —
D2:  D1, A3
D3:  D2, B4
D4:  B1, B2, B3, D3
D5:  D4
D6:  D1, A3
E1:  B2, B3, D4
E2:  E1, B4
E3:  E2
E4:  E3
E5:  E3, E4
E6:  E3
E7:  E3, B1
F1:  A2
F2:  F1
F3:  F1, B1
F4:  F1, B1
F5:  E3, D3
G1:  A2
G2:  A2, B1
G3:  B1, G2
H1:  —
H2:  H1, E1
H3:  H1, E3
H4:  H1, E3
H5:  H1, G2
I1:  B1, D5
I2:  I1, F1, F4
I3:  I2, B1
I4:  I3
I5:  G3, I2
J1:  F1, E3
J2:  J1, A3
J3:  J1, I1
K1:  E3, E5, G2
K2:  I2, J1
K3:  A5, F4
L1:  B1, D5, F1
L2:  A3
L3:  L2, L1
L4:  L1, F1
L5:  L3, L4, I1
L6:  L5, L1
L7:  L5
M1:  H1
M2:  A2, E3, D6
M3:  M2
N1:  D4, D6
N2:  I1
N3:  G3, L5
N4:  F3, E6
N5:  A4, M2
N6:  C2
H6:  I2
H7:  I3
H8:  J1
```

### Review Findings

- [ ] [Review][Patch] L2 沙箱实现与基线约束不一致（gVisor/Kata）[src/main/java/com/springtechkb/assessment/internal/LocalJavaCodeSandboxService.java:16-116]

- [ ] [Review][Patch] Prompt Registry 管理接口绕过统一 JWT 鉴权边界 [src/main/java/com/springtechkb/SecurityConfiguration.java:49-54]
- [ ] [Review][Patch] Redis 配额读取存在解析异常未兜底，可能触发 500 [src/main/java/com/springtechkb/ai/internal/RedisTokenQuotaService.java:45-50]
- [ ] [Review][Patch] 代码沙箱未限制 testCases 数量，存在资源耗尽风险 [src/main/java/com/springtechkb/assessment/internal/LocalJavaCodeSandboxService.java:31-48]
- [ ] [Review][Patch] SpringVersionRange 未校验 minimum/maximum 格式，异常边界可被静默接受 [src/main/java/com/springtechkb/search/internal/SpringVersionRange.java:21-22,43-61]
- [ ] [Review][Patch] SpringVersionRange 排序键仅取前三段，超过三段版本会折叠冲突 [src/main/java/com/springtechkb/search/internal/SpringVersionRange.java:30-38]
- [ ] [Review][Patch] RAG 在“无 token 且无 error”路径下未记录 TTFT [src/main/java/com/springtechkb/search/internal/RagAnswerService.java:179-207]
- [ ] [Review][Patch] 缺少 /api/search 与 /api/chat/rag HTTP 契约回归测试 [src/main/java/com/springtechkb/search/internal/SearchController.java:38-54]
- [ ] [Review][Patch] 缺少 Prompt Registry 管理接口鉴权矩阵测试 [src/main/java/com/springtechkb/search/internal/PromptRegistryController.java:37-83]

- [x] [Review][Defer] 异步超时/错误回调路径的 in-flight 指标泄漏风险待证实 [src/main/java/com/springtechkb/ApiGatewayFilter.java:92-104] — deferred: 需补充容器级 async timeout/error 集成测试，验证 onTimeout/onError 后是否仍触发 onComplete；当前证据不足以下结论。

#### Rejected

- [Review][Rejected] “ADMIN 接口缺少授权闸”—— **false**：`AssessmentController`、`KnowledgeSourceCatalogController`、`KnowledgeGraphController`、`OperationsSummaryController` 均有 `@PreAuthorize("hasRole('ADMIN')")`，并非裸暴露。
- [Review][Rejected] “仅用 remoteAddr 限流是缺陷”—— **false**：文档已明确“不信任未配置 X-Forwarded-For”，当前实现是显式策略选择，不是遗漏。
- [Review][Rejected] “禁用 CSRF 必然导致漏洞”—— **maybe-false/驳回**：该结论未在当前调用链中被证明可利用，需具体 PoC 或跨站调用路径证据。
- [Review][Rejected] “文档存在 `******` 敏感占位泄露”—— **false**：当前仓库版本未检索到该占位内容。
- [Review][Rejected] “文档仍硬编码 /mnt/d 路径”—— **false**：当前仓库版本未检索到该路径。
- [Review][Rejected] “规划产物目录文件应视为缺陷”—— **low（不采纳）**：属于仓库治理策略问题，非直接代码缺陷。
- [Review][Rejected] “OpenAPI 缺统一错误模型”—— **reject by rule**：该修复需要修改被评审规范文档本身，不作为本轮代码缺陷处理。
- [Review][Rejected] “HS256 缺轮换即缺陷”—— **low（不采纳）**：当前实现满足最小安全基线，属于增强项而非阻断性缺陷。
- [Review][Rejected] “D5 未完成与硬依赖叙述冲突”—— **reject by rule**：该项是文档状态一致性问题，修复路径主要为文档/流程同步。
- [Review][Rejected] “DoD 与回归记录矛盾”—— **reject by rule**：该项是治理口径冲突，修复路径主要为文档口径收敛。
