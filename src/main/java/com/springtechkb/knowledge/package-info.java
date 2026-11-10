/**
 * 知识库内容模块 — 内容采集、分块、Contextual Retrieval、双写落库。
 *
 * <p>本模块持有知识内容的生命周期：从许可合规的来源采集原始文档，
 * 经过分块与上下文前置处理后写入向量存储与词法索引（H-8 同事务 outbox）。
 *
 * <h2>公开 API</h2>
 * <ul>
 *   <li>{@link com.springtechkb.knowledge.KnowledgeSource} — 内容来源枚举</li>
 *   <li>{@link com.springtechkb.knowledge.KnowledgeGraph} — 多前置 DAG、递归闭包与拓扑顺序</li>
 * </ul>
 *
 * <h2>硬约束</h2>
 * <ul>
 *   <li>H-8: 内容更新须同事务写 outbox，由投递器同步词法索引</li>
 *   <li>H-9: 第三方博客正文不入库，书籍一律排除</li>
 *   <li>H-13: AI 生成内容标注来源、检索降权，≥10% 人工抽检</li>
 * </ul>
 */
@ApplicationModule(displayName = "Knowledge", allowedDependencies = {"ai"})
package com.springtechkb.knowledge;

import org.springframework.modulith.ApplicationModule;
