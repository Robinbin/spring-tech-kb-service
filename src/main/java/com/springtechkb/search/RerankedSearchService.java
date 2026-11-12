package com.springtechkb.search;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.springtechkb.search.internal.HybridSearchService;

/** Applies the E2 Cross-Encoder stage to the fused E1 candidates. */
@Service
public class RerankedSearchService {

    private static final Logger log = LoggerFactory.getLogger(RerankedSearchService.class);

    public static final int CANDIDATE_COUNT = 20;
    public static final int RESULT_COUNT = 5;

    private final HybridSearchService hybridSearch;
    private final RerankerService reranker;

    public RerankedSearchService(HybridSearchService hybridSearch, RerankerService reranker) {
        this.hybridSearch = hybridSearch;
        this.reranker = reranker;
    }

    public List<SearchHit> search(String query, String springModule, String springVersion) {
        List<SearchHit> candidates = hybridSearch.search(query, springModule, springVersion, CANDIDATE_COUNT);
        return rerank(query, candidates, RESULT_COUNT);
    }

    public List<SearchHit> rerank(String query, List<SearchHit> candidates, int resultCount) {
        if (candidates.isEmpty() || resultCount <= 0) return List.of();

        List<RerankHit> ranking = reranker.rerank(
                query, candidates.stream().map(SearchHit::content).toList(), resultCount);
        Set<Integer> seen = new HashSet<>();
        List<SearchHit> reranked = new ArrayList<>(resultCount);
        for (RerankHit hit : ranking) {
            if (hit.originalIndex() < 0 || hit.originalIndex() >= candidates.size()
                    || !seen.add(hit.originalIndex())) continue;
            reranked.add(candidates.get(hit.originalIndex()));
            if (reranked.size() == resultCount) break;
        }
        if (reranked.isEmpty()) {
            log.warn("Reranker returned no valid candidate indexes; keeping the fused order");
            return candidates.stream().limit(resultCount).toList();
        }
        return List.copyOf(reranked);
    }
}
