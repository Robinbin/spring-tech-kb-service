package com.springtechkb.search.internal;

import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import com.springtechkb.search.RerankHit;
import com.springtechkb.search.RerankerService;

/**
 * B4：TEI {@code /rerank} 端点的 HTTP 客户端实现。
 *
 * <h2>TEI Rerank API</h2>
 * <pre>
 * POST /rerank
 * {"query":"...", "texts":["p1","p2",...], "truncate":true}
 * → [{"index":0,"score":0.95}, {"index":1,"score":0.3}, ...]
 * </pre>
 *
 * <p>调用方在启用前应检查 {@code springtechkb.reranker.enabled}；
 * 此类仅负责 HTTP 通信，不包含开关逻辑（由 {@link RerankerConfig} 处理）。
 */
public class TeiRerankerService implements RerankerService {

    private static final Logger log = LoggerFactory.getLogger(TeiRerankerService.class);

    private final RestClient restClient;

    public TeiRerankerService(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<RerankHit> rerank(String query, List<String> passages, int topN) {
        if (passages.isEmpty()) return List.of();

        log.debug("reranking {} passages for query='{}', topN={}", passages.size(), query, topN);

        List<TeiRerankScore> scores = restClient.post()
                .uri("/rerank")
                .body(new TeiRerankRequest(query, passages, true))
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<>() {});

        if (scores == null || scores.isEmpty()) return List.of();

        return scores.stream()
                .sorted(Comparator.comparingDouble(TeiRerankScore::score).reversed())
                .limit(topN)
                .map(s -> new RerankHit(s.index(), s.score()))
                .toList();
    }

    record TeiRerankRequest(String query, List<String> texts, boolean truncate) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TeiRerankScore(int index, double score) {}
}
