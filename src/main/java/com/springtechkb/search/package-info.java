/**
 * 检索与问答模块 — 混合检索（向量 + FTS）、Rerank 精排、单轮 RAG 与 Agentic 多跳。
 *
 * <p>检索路径：向量相似度 + PG FTS + 元数据过滤 → RRF 融合 → Reranker 精排 →
 * Top-K chunk → 组装 Prompt → 流式回答 + 引用溯源。
 *
 * <h2>公开 API</h2>
 * <ul>
 *   <li>{@link com.springtechkb.search.SearchHit} — 单条检索命中结果</li>
 *   <li>{@link com.springtechkb.search.SearchResponse} — 检索 REST API 响应</li>
 *   <li>{@link com.springtechkb.search.RagRequest} — 单轮 RAG 查询请求</li>
 *   <li>{@link com.springtechkb.search.RagCitation} — 可跳转的来源引用</li>
 *   <li>{@link com.springtechkb.search.KnowledgeQueries} — 已审核来源、检索与 API 版本对比</li>
 * </ul>
 *
 * <p>前端详情接口 {@code GET /api/knowledge/{id}} 返回已批准来源的 chunk，
 * 包含版本适用上下限、作者、来源 URL 及许可信息；未批准或不存在的内容返回 404。
 * 认证的 Streamable HTTP MCP 入口 {@code /mcp} 仅注册四个只读知识工具；
 * 此工具集合不会注册到 RAG 模型，保持 H-10 的无工具执行边界。
 *
 * <h2>硬约束</h2>
 * <ul>
 *   <li>H-2: 带元数据过滤的检索须开 {@code hnsw.iterative_scan=relaxed_order}</li>
 *   <li>H-10: 检索内容须包裹在不可信区块内，RAG 回答路径不授予工具调用</li>
 *   <li>H-14: 输出必须附来源 + 许可标注</li>
 * </ul>
 */
@ApplicationModule(displayName = "Search & Q-A", allowedDependencies = {"knowledge", "ai"})
package com.springtechkb.search;

import org.springframework.modulith.ApplicationModule;
