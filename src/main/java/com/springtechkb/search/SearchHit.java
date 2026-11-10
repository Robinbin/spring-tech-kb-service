package com.springtechkb.search;

import com.springtechkb.knowledge.KnowledgeSource;

/**
 * 单条检索命中结果。
 *
 * @param id            知识点 ID（可用于跳转详情页，满足 H-14 引用标注）
 * @param content       命中的 chunk 文本
 * @param score         融合得分（RRF + Reranker，越高越相关）
 * @param springVersion 适用的 Spring 版本标签（如 "6.1.x"）
 * @param source        内容来源（用于许可标注）
 */
public record SearchHit(
        String id,
        String content,
        double score,
        String springVersion,
        KnowledgeSource source,
        String sourceTitle,
        String sourceUrl,
        String springModule,
        String sourceLicense,
        String sourceId
) {
    public SearchHit(String id, String content, double score, String springVersion, KnowledgeSource source) {
        this(id, content, score, springVersion, source, null, null, null, null, null);
    }

    public SearchHit(
            String id, String content, double score, String springVersion, KnowledgeSource source,
            String sourceTitle, String sourceUrl, String springModule) {
        this(id, content, score, springVersion, source, sourceTitle, sourceUrl, springModule, null, null);
    }

    public SearchHit(
            String id, String content, double score, String springVersion, KnowledgeSource source,
            String sourceTitle, String sourceUrl, String springModule, String sourceLicense) {
        this(id, content, score, springVersion, source, sourceTitle, sourceUrl, springModule, sourceLicense, null);
    }
}
