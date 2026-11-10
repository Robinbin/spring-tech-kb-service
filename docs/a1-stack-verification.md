# A1 技术栈可行性验证报告 · 版本锁定矩阵

> **工作包：** A1（Phase 1 Week 1 硬闸门，2 人日）
> **验证日期：** 2026-10-26
> **结论：** ✅ **通过（带范围限定）** —— 风险 R-1 对核心栈解除；3 项验证因环境缺少 Docker 顺延至 A3

---

## 1. 验收标准达成情况

A1 的验收标准为「Boot 4 + Spring AI 2.x + pgvector + TEI 四者在同一工程内启动成功，`ChatClient` 与 `VectorStore` 各完成一次真实调用」。

| 验收项 | 状态 | 证据 |
|---|---|---|
| 四者依赖共存无冲突 | ✅ 通过 | `mvn dependency:resolve` 零冲突退出 |
| 同一工程内编译通过 | ✅ 通过 | `StackProbe` 使用三个核心抽象编译成功 |
| 完整应用上下文启动 | ✅ 通过 | `ApplicationContextStartupTest` —— Web + Actuator + Anthropic + OpenAI/TEI + pgvector + MCP Server + Modulith + JPA 全部自动配置共存 |
| `ChatClient` bean 装配 | ✅ 通过 | `StackCompatibilityTest#anthropicChatClientAssembles` |
| `VectorStore` bean 装配 | ✅ 通过 | `StackCompatibilityTest#pgVectorStoreAutoConfigurationIsRecognised` |
| `EmbeddingModel` bean 装配 | ✅ 通过 | `StackCompatibilityTest#teiEmbeddingModelAssembles` |
| **`ChatClient` 真实调用** | ⏸ **顺延 A3** | 需 Anthropic API Key |
| **`VectorStore` 真实调用** | ⏸ **顺延 A3** | 需真实 pgvector 实例（本环境无 Docker、无 sudo） |
| **TEI 真实 Embedding 调用** | ⏸ **顺延 A3** | 需 Docker 运行 TEI |

测试结果：`Tests run: 5, Failures: 0, Errors: 0` · `BUILD SUCCESS`

**A1 的核心目的已达成** —— 它存在的理由是回答「Spring Boot 4 与 Spring AI 的版本组合是否可运行」（风险 R-1，等级高）。该问题已有确定答案，且过程中发现 5 项会导致实际开发返工的问题。真实外部调用属连通性验证，不改变版本结论。

---

## 2. 版本锁定矩阵

以下版本为 `mvn dependency:list` 的**实际解析结果**，非文档推断。任何变更需重跑 A1 测试套件。

### 2.1 直接声明

| 组件 | 锁定版本 | 备注 |
|---|---|---|
| Java | 21 | `maven.compiler.release=21` |
| Spring Boot（parent） | **4.1.2** | ⚠️ 架构文档原写 4.0 —— 见发现 F-7 |
| Spring AI（BOM） | **2.0.2** | 当前 GA；2.1.0-RC1 在途 |
| Spring Modulith（BOM） | **2.1.2** | 当前 GA；2.2.0-RC1 在途 |

### 2.2 由 Boot / BOM 传递管理（实测解析值）

| 组件 | 解析版本 | 说明 |
|---|---|---|
| Spring Framework | 7.0.10 | 由 Boot 4.1.2 管理，确认 Framework 7 基线 |
| Spring Data JPA | 4.1.2 | |
| Hibernate ORM | 7.4.13.Final | |
| Jakarta Persistence API | 3.2.0 | 与架构文档声明一致 |
| Tomcat Embed Core | 11.0.27 | Jakarta EE 11 |
| Micrometer Core | 1.17.2 | |
| Jackson Databind | 2.21.7 | |
| PostgreSQL JDBC | 42.7.14 | |
| **anthropic-java-core** | **2.72.0** | Spring AI 2.x 的 Anthropic 集成基于 Anthropic 官方 Java SDK，非自研 REST 客户端 |

完整依赖树存档：[`a1-dependency-tree.txt`](./a1-dependency-tree.txt)

### 2.3 已确认的真实 Maven 坐标

架构文档此前未给出确切坐标。以下为 BOM 实测存在的 artifactId：

| 用途 | 坐标 |
|---|---|
| 主力 LLM | `org.springframework.ai:spring-ai-starter-model-anthropic` |
| Embedding（TEI 走 OpenAI 兼容端点） | `org.springframework.ai:spring-ai-starter-model-openai` |
| 向量存储 | `org.springframework.ai:spring-ai-starter-vector-store-pgvector` |
| MCP Server（OPT-04） | `org.springframework.ai:spring-ai-starter-mcp-server-webmvc` |
| 模块化单体（ADR-06） | `org.springframework.modulith:spring-modulith-starter-core` / `-jpa` / `-test` |

---

## 3. 发现（5 项会导致实际返工）

### F-1 ⚠️ Spring Boot 4 重定位了全部自动配置类的包路径

Boot 3 的 `org.springframework.boot.autoconfigure.<模块>.XxxAutoConfiguration` 在 Boot 4 中迁移为 **`org.springframework.boot.<模块>.autoconfigure.XxxAutoConfiguration`**。

| Boot 3 路径 | Boot 4 实际路径 |
|---|---|
| `o.s.boot.autoconfigure.jdbc.DataSourceAutoConfiguration` | `o.s.boot.jdbc.autoconfigure.DataSourceAutoConfiguration` |
| `o.s.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration` | `o.s.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration` |
| `o.s.boot.autoconfigure.web.client.RestClientAutoConfiguration` | `o.s.boot.restclient.autoconfigure.RestClientAutoConfiguration` |
| `o.s.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration` | `o.s.boot.webclient.autoconfigure.WebClientAutoConfiguration` |

**影响：** 所有基于 Boot 3 的教程、示例与 AI 生成的代码在此处都会编译失败。**不要凭记忆写自动配置类的包路径** —— 从 jar 的 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 读取，或用 `javap` 确认。

### F-2 ⚠️ 两个 ChatModel bean 冲突 —— 直接影响 WP-F1 的设计

同时引入 `spring-ai-starter-model-anthropic` 与 `spring-ai-starter-model-openai` 时，上下文中会出现 `anthropicChatModel` 与 `openAiChatModel` 两个 `ChatModel`，而 `ChatClientAutoConfiguration` 要求**唯一**实例：

```
NoUniqueBeanDefinitionException: No qualifying bean of type
'org.springframework.ai.chat.model.ChatModel' available:
expected single matching bean but found 2: anthropicChatModel,openAiChatModel
```

根因：`OpenAiChatAutoConfiguration` 的 `@ConditionalOnProperty(name="spring.ai.model.chat", havingValue="openai", matchIfMissing=true)` —— **`matchIfMissing=true`**，即只要 starter 在 classpath 上，默认就激活。

**A1 阶段的解法：** 用 Spring AI 的模型选择属性显式选择 Anthropic

```yaml
spring:
  ai:
    model:
      chat: anthropic      # 仅 Anthropic 提供 ChatModel
      embedding: openai    # Embedding 走 OpenAI 兼容端点（自托管 TEI）
```

可选值来自 `org.springframework.ai.model.SpringAIModels`：`anthropic`、`openai`、`ollama`、`bedrock-converse`、`google-genai`、`deepseek` 等。

**对 WP-F1（LLM Gateway 与模型分层路由）的硬性约束：** `spring.ai.model.chat` 是**单选开关**，自动配置无法同时产出多个 `ChatModel`。因此 ADR-03 的双 Provider 故障转移与 OPT-05 的三档分层路由**不能依赖自动配置**。

**F1 实施状态（2026-10-28）：** 应用现通过 `spring.ai.model.chat=none` 禁用单选 ChatModel 自动配置，并在 `AiModelConfiguration` 中显式构造 LITE、STANDARD、EXPERT 三个 Anthropic `ChatModel`，由 `SpringAiRagChatGateway` 按档位装配客户端。模型 ID 与 effort 均可配置，应用上下文测试验证三档 Bean 可启动。架构中提到的第二家 Provider 故障转移尚未实现；真实 Anthropic API、流式 SSE 与 TTFT 数据仍待外部环境验证。

### F-3 ⚠️ pgvector bean 装配不触达数据库 —— 「启动成功」不等于「pgvector 可用」

实测判定（`PgVectorAssemblyProbeTest`，数据源指向确定不存在的 `127.0.0.1:1`）：在 `initialize-schema=false` 且显式给出 `dimensions` 时，**`PgVectorStore` bean 创建成功，连接推迟到首次查询**。

两面含义：

- **有利：** CI 与本地开发无需真实 PG 即可启动上下文、跑单元测试
- **风险（fail-late）：** 维度不匹配、缺 `vector` 扩展、索引缺失等问题在启动期**不暴露**，而是在第一次检索时才失败。需在 WP-B2 增加启动期自检或 Actuator 健康指示器，显式校验扩展存在、维度一致、索引存在
- **范围限定：** 硬约束 H-1 / H-2 / H-3 **未被本次验证覆盖**，必须在 A3 用真实 pgvector 实例验证

### F-4 ⚠️ Spring AI 的 PgVectorStore 不支持 halfvec，也不暴露索引调参 —— 影响 H-2 / H-3 的落地方式

从 `spring-ai-pgvector-store-2.0.2.jar` 的字符串常量与枚举实测：

- 建表 DDL 为 `embedding vector(%d)` —— 列类型硬编码为 `vector`，**无 `halfvec` 支持**
- `PgIndexType` 枚举只有 `NONE` / `IVFFLAT` / `HNSW`
- 建索引 DDL 为 `CREATE INDEX ... USING %s (embedding %s)` —— **无 `WITH (m=..., ef_construction=...)` 子句**
- jar 内不存在 `iterative_scan`、`ef_search`、`ef_construction` 任何字符串

**影响：**

1. **ADR-07 的 halfvec 备选路径在 Spring AI 内置实现下不可用。** 若最终需要 3072 维，必须自行实现 `VectorStore` 或在 Spring AI 之外管理 schema。这**强化了** ADR-07 选择 1024 维原生 `vector` 的决策 —— 它是唯一开箱可用的路径。
2. **H-2（`hnsw.iterative_scan`）与 H-3（`ef_construction` ≥ 200）无法通过 Spring AI 属性设置。** 它们必须落在：
   - `ef_construction` / `m` → **Flyway 迁移脚本中的索引 DDL**（WP-B1/B2）
   - `hnsw.iterative_scan` → 会话级 `SET` 或数据库/连接池级参数（WP-B2）
3. 因此 **`initialize-schema` 应固定为 `false`，schema 所有权归 Flyway**，不交给 Spring AI 自动建表 —— 否则拿不到调参后的索引。

### F-5 Spring AI 2.x 的 `EmbeddingModel` 有两个抽象 `embed` 方法

实现该接口需同时覆写 `embed(String)` 与 **`embed(Document)`**，以及 `call(EmbeddingRequest)`、`dimensions()`。自定义 Embedding 适配器时需注意。

### F-6 MCP Server 原生可用，OPT-04 可行性确认

`spring-ai-starter-mcp-server-webmvc` 存在且可与其余自动配置共存启动（已含在完整上下文测试中）。OPT-04（MCP Server 暴露知识库，估 5 人日）的「低成本」判断成立。

### F-7 Spring Boot 4.0 已被 4.1.x GA 取代

Maven Central 实测可用版本：`4.0.x` 最高 4.0.9，**GA 线已至 4.1.2**，4.2.0-RC1 在途。架构文档写的「Spring Boot 4.0」已落后一个小版本线。本次锁定 **4.1.2**。

---

## 4. 本次验证未覆盖的内容（顺延至 A3）

环境限制：**无 Docker、无本地 PostgreSQL、`sudo` 需交互认证**，故以下无法在 A1 完成。它们属连通性与行为验证，不影响版本结论。

| 顺延项 | 归属 | 验证内容 |
|---|---|---|
| pgvector 真实读写 | A3 / B2 | 硬约束 H-1（维度上限）、H-2（`iterative_scan` 下的过滤召回）、H-3（`ef_construction` 效果） |
| TEI 真实 Embedding | A3 / B4 | 端点连通、`/v1/embeddings` OpenAI 兼容性、1024 维 MRL 截断实际行为 |
| Anthropic 真实调用 | A3 / F1 | API Key 有效性、流式 SSE、TTFT 实测（H-4 的两级超时需真实延迟数据标定） |

**建议：** A3（本地开发环境，2 人日）优先级提升，因为它是解除上述三项的唯一前置。A3 的 `docker-compose.yml` 必须包含 `pgvector/pgvector` 镜像与 TEI 镜像。

---

## 5. 对后续工作包的具体影响

| 工作包 | 调整 |
|---|---|
| **B1** 数据库 Schema | `initialize-schema=false` 固定；向量列与 HNSW 索引 DDL 全部由 Flyway 管理（F-4） |
| **B2** pgvector 调优 | 新增：启动期自检 / 健康指示器（扩展存在、维度一致、索引存在）—— 应对 F-3 的 fail-late；`ef_construction` 写进索引 DDL，`iterative_scan` 走会话参数 |
| **F1** LLM Gateway | **已实施**：显式 `@Bean` 构造 LITE/STANDARD/EXPERT 各档 `ChatModel`，网关按 tier 路由，不能依赖自动配置（F-2）。真实 Provider 调用仍待验证；第二 Provider 故障转移未实现 |
| **A3** 开发环境 | 优先级提升；必须含 pgvector 与 TEI 镜像 |
| 全部 Java 工作包 | 自动配置类包路径按 F-1 的 Boot 4 规则，不沿用 Boot 3 写法 |

---

## 6. 复现方式

```bash
cd /mnt/d/ws/github/spring-tech-kb-service
mvn -B dependency:resolve     # 验证依赖共存
mvn -B test                   # 5 项验证测试
mvn -B dependency:tree        # 重新生成版本矩阵
```

测试类位于 `src/test/java/com/springtechkb/a1/`：

- `StackCompatibilityTest` —— 三个核心 bean 的隔离装配验证
- `PgVectorAssemblyProbeTest` —— 判定 pgvector bean 是否需要真实数据库（F-3 的证据）
- `ApplicationContextStartupTest` —— 完整应用上下文启动

`src/main/java/com/springtechkb/a1/StackProbe.java` 为 API 形态探针，不在生产路径上，可在 B 组工作包开始后删除。
