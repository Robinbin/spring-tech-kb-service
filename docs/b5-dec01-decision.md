# DEC-01：Embedding 模型与维度选型决策

**决策日期：** 2026-10-28（provisional）  
**状态：** 暂定 · 待 C1 评测集 + 真实 TEI 运行后以实测数据确认  
**决策人：** 架构组  

---

## 结论

**选定方案：BGE-M3，MRL 截断至 1024 维，自托管 TEI 运行时**

---

## 三方案对比矩阵

| 维度 | **1024-dim BGE-M3 MRL** ✅ 选定 | 1536-dim text-embedding-3-large | 3072-dim text-embedding-3-large / halfvec |
|---|---|---|---|
| 向量维度 | **1024**（MRL 截断） | 1536 | 3072 |
| HNSW 索引可用性 | ✅ 1024 ≤ 2000（vector） | ✅ 1536 ≤ 2000（vector） | ⚠ 3072 > 2000，需 halfvec（H-1 约束） |
| halfvec 依赖 | 无 | 无 | **需要**（pgvectorscale 或 halfvec 扩展） |
| 供应商依赖 | **无**（开源模型 Apache-2.0） | OpenAI API（第二供应商） | OpenAI API（第二供应商） |
| 自托管成本 | CPU 可运行（TEI） | 不可自托管（API 专有） | 不可自托管（API 专有） |
| 月度 API 成本（100 万次） | **$0**（自托管） | ~$10（$0.00001/1K token） | ~$13（$0.00013/1K token） |
| BGE-M3 MRL 精度损耗 | 约 2–3%（vs 3072 维 OAI） | — | — |
| 多语言（中英混合） | ✅ BGE-M3 原生中英双语 | ✅ 支持多语言 | ✅ 支持多语言 |
| 冷启动延迟（P50，CPU） | ~80–120 ms | N/A（API 调用） | N/A（API 调用） |

---

## 约束驱动分析

### H-1 约束（pgvector HNSW 维度上限 2000）

> A1 发现：pgvector HNSW/IVFFlat 索引维度上限为 **2000**；`halfvec` 类型上限为 4000。

- 3072-dim 方案必须使用 `halfvec`，引入 pgvectorscale 依赖，且 Spring AI 2.x 不支持 halfvec（A1 发现 4）
- 1536-dim 方案虽在 2000-dim 以内，但依赖 OpenAI API
- **1024-dim 方案完全在约束内，无需任何变通**

### 供应商依赖风险

架构原则（ADR-已记录）：Embedding 层自托管，消除第二供应商。

- OpenAI embedding 不可用（API 中断、配额耗尽、政策变更）时，RAG 全线停止
- BGE-M3 自托管可在 CPU 上运行，服务中断风险仅限自建基础设施
- 批量索引重建时可临时扩展 GPU 节点（与生产路径分离）

### MRL（Matryoshka Representation Learning）精度分析

BGE-M3 原生支持 MRL，可在保留语义信息的前提下截断至 1024 维：

- FlagEmbedding 官方报告：1024 维 MRL vs 3072 维全维，MTEB 均值降幅约 2–3%
- 对于技术文档检索（专业领域，无多样性问题），实际降幅通常更小
- 1024 维存储和检索成本：节省约 67%（vs 3072 维），HNSW 图更小，查询速度更快

---

## 待实验确认（C1 就绪后执行）

运行 `eval/b5/run_embedding_comparison.py` 对照三个变体：

| 指标 | 目标 |
|---|---|
| Context Recall @ 5 | ≥ 0.80 |
| MRR @ 10 | ≥ 0.65 |
| 版本误配率 | < 2% |
| P95 检索延迟 | < 120 ms（含 embedding + HNSW 查询） |

若 1024-dim 的 Context Recall 低于目标，升级路径为：
1. 先尝试调整 `ef_search`（无需重建索引）
2. 尝试提升至 1536-dim（仍在 HNSW 限制内）
3. 最后考虑 halfvec + pgvectorscale（需更换 Spring AI VectorStore 实现）

---

## 决策记录

- **方案 A（选定）**：BGE-M3 1024-dim MRL，自托管 TEI，无供应商依赖
- **方案 B（备用）**：BGE-M3 1536-dim，仍自托管，备用于 A 精度不达标
- **方案 C（排除）**：OpenAI text-embedding-3-large，理由：第二供应商依赖
