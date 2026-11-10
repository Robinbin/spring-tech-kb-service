package com.springtechkb.search;

/**
 * 全文检索（FTS）单条命中结果。
 *
 * <p>与 {@link SearchHit}（最终融合结果）的区别：{@code FtsHit} 是 B3 词法检索通道的
 * 原始输出，供 E1 RRF 融合前使用，尚未关联 {@code KnowledgeSource} 完整信息。
 *
 * @param chunkId          {@code knowledge_chunk.id}
 * @param content          chunk 正文
 * @param score            {@code ts_rank_cd} BM25 风格得分（越高越相关）
 * @param springModule     所属 Spring 子模块（core / mvc / data / security …）
 * @param springVersionMin 适用的最低 Spring 版本（可为 null）
 * @param springVersionMax 适用的最高 Spring 版本（null = 至今适用）
 */
public record FtsHit(
        String chunkId,
        String content,
        double score,
        String springModule,
        String springVersionMin,
        String springVersionMax
) {}
