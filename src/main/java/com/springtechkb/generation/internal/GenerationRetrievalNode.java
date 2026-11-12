package com.springtechkb.generation.internal;

import com.springtechkb.search.KnowledgeQueries;
import com.springtechkb.search.SearchHit;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Sub-graph node: retrieves grounding knowledge for the requested generation task. */
@Component
class GenerationRetrievalNode implements AsyncNodeAction<GenerationAgentState> {

    private static final int COMPARE_HITS_PER_SIDE = 4;
    private static final int DEFAULT_HITS           = 6;

    private final KnowledgeQueries knowledge;

    GenerationRetrievalNode(KnowledgeQueries knowledge) {
        this.knowledge = knowledge;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(GenerationAgentState state) {
        String query   = state.query().orElseThrow();
        String module  = state.module().orElse(null);
        String version = state.springVersion().orElse(null);
        String type    = state.requestType().orElse("example");

        List<SearchHit> hits;
        if ("compare".equals(type)) {
            String[] sides = query.split("\\s+vs\\s+|\\s+versus\\s+", 2);
            if (sides.length == 2) {
                hits = new java.util.ArrayList<>();
                hits.addAll(knowledge.search(sides[0].trim(), module, version, COMPARE_HITS_PER_SIDE));
                hits.addAll(knowledge.search(sides[1].trim(), module, version, COMPARE_HITS_PER_SIDE));
            } else {
                hits = knowledge.search(query, module, version, DEFAULT_HITS * 2);
            }
        } else {
            hits = knowledge.search(query, module, version, DEFAULT_HITS);
        }

        return CompletableFuture.completedFuture(Map.of(GenerationAgentState.KNOWLEDGE_HITS, hits));
    }
}
