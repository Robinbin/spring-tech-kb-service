/**
 * Search 模块内部实现（模块私有，禁止外部模块直接依赖）。
 *
 * <p>未来放置：{@code HybridSearchService}（向量 + FTS + RRF 融合，E1）、
 * {@code RerankerService}（Top-20 → Top-5，E2）、
 * {@code RagQuestionAnswerService}（单轮 RAG + SSE，E3）、
 * {@code AgenticRetrievalService}（多跳检索，E4）、
 * {@code IntentClassifier}（三路分流，E5）。
 */
package com.springtechkb.search.internal;
