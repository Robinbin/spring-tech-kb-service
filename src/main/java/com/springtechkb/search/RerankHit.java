package com.springtechkb.search;

/**
 * Reranker 精排单条结果。
 *
 * @param originalIndex 在输入 passages 列表中的原始位置（0-based）
 * @param score         Cross-Encoder 相关性得分（越高越相关，范围 [0, 1]）
 */
public record RerankHit(int originalIndex, double score) {}
