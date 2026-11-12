package com.springtechkb.search.internal;

import com.springtechkb.ai.QueryClassificationGateway;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Graph node: uses the lite LLM to classify the query into "comparison" or "retrieval",
 * driving the conditional edge that selects the downstream hop-planning strategy.
 */
@Component
class LlmRouterNode implements AsyncNodeAction<KbAgentState> {

    static final String COMPARISON = "comparison";
    static final String RETRIEVAL  = "retrieval";

    private final QueryClassificationGateway classifier;

    LlmRouterNode(QueryClassificationGateway classifier) {
        this.classifier = classifier;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(KbAgentState state) {
        String question = state.question().orElseThrow();
        String route = classifier.classify(question, COMPARISON, RETRIEVAL);
        return CompletableFuture.completedFuture(Map.of(KbAgentState.ROUTE, route));
    }
}
