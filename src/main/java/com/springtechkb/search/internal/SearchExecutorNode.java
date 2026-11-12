package com.springtechkb.search.internal;

import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.springtechkb.search.SearchHit;

/** Graph node: executes the planned hop queries and merges deduplicated results. */
@Component
class SearchExecutorNode implements AsyncNodeAction<KbAgentState> {

    private static final int HARD_ITERATION_LIMIT = 5;

    private final KnowledgeRetrievalTool retrievalTool;
    private final int maxIterations;

    SearchExecutorNode(
            KnowledgeRetrievalTool retrievalTool,
            @Value("${springtechkb.rag.agentic.max-iterations:3}") int configuredMax) {
        this.retrievalTool = retrievalTool;
        this.maxIterations = Math.max(1, Math.min(HARD_ITERATION_LIMIT, configuredMax));
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(KbAgentState state) {
        List<String> hops    = state.hops().orElse(List.of());
        String module        = state.module().filter(s -> !s.isBlank()).orElse(null);
        String stateVersion  = state.version().filter(s -> !s.isBlank()).orElse(null);

        Map<String, SearchHit> uniqueHits = new LinkedHashMap<>();
        int iterations = 0;

        for (String hop : hops) {
            if (iterations >= maxIterations) break;
            String hopVersion = stateVersion != null ? stateVersion : HopPlannerNode.versionFrom(hop);
            List<SearchHit> hits = retrievalTool.search(hop, module, hopVersion);
            iterations++;
            for (SearchHit hit : hits) uniqueHits.putIfAbsent(hit.id(), hit);
            if (hits.isEmpty() && iterations > 1) break;
        }

        return CompletableFuture.completedFuture(Map.of(
                KbAgentState.HITS,       List.copyOf(uniqueHits.values()),
                KbAgentState.ITERATIONS, iterations));
    }
}
