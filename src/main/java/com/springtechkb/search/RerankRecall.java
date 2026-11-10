package com.springtechkb.search;

import java.util.Set;
import java.util.stream.Collectors;

/** Offline relevance comparison helper for evaluating the E2 reranking stage. */
public record RerankRecall(double before, double after) {

    public static RerankRecall measure(
            Set<String> relevantChunkIds,
            java.util.List<SearchHit> candidates,
            java.util.List<SearchHit> reranked) {
        if (relevantChunkIds.isEmpty()) {
            throw new IllegalArgumentException("At least one relevant chunk ID is required");
        }
        return new RerankRecall(
                recall(relevantChunkIds, candidates),
                recall(relevantChunkIds, reranked));
    }

    private static double recall(Set<String> relevantIds, java.util.List<SearchHit> hits) {
        Set<String> retrievedIds = hits.stream().map(SearchHit::id).collect(Collectors.toSet());
        long relevantRetrieved = relevantIds.stream().filter(retrievedIds::contains).count();
        return (double) relevantRetrieved / relevantIds.size();
    }
}
