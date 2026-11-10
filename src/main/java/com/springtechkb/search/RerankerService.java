package com.springtechkb.search;

import java.util.List;

/**
 * B4：Reranker 精排服务。
 *
 * <p>对 E1 混合检索的 Top-N 候选（向量 + FTS 融合结果）进行 Cross-Encoder 二次排序，
 * 将 Top-20 缩减到 Top-5，输出语义相关性更精准的最终列表。
 *
 * <p>实现：{@code TeiRerankerService}（TEI {@code /rerank} 端点）。
 * 当 {@code springtechkb.reranker.enabled=false}（默认）时退化为无操作 pass-through。
 */
public interface RerankerService {

    /**
     * 对 passages 按与 query 的语义相关性重新排序。
     *
     * @param query    用户查询文本
     * @param passages 待精排的文本列表（通常来自 RRF 融合后的 Top-20）
     * @param topN     返回的最大条数（通常 5）
     * @return 按得分降序排列的 {@link RerankHit} 列表
     */
    List<RerankHit> rerank(String query, List<String> passages, int topN);
}
