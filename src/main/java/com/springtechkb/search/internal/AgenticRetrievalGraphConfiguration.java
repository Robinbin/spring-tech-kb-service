package com.springtechkb.search.internal;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Configuration(proxyBeanMethods = false)
class AgenticRetrievalGraphConfiguration {

    /**
     * Agentic retrieval graph:
     *
     * <pre>
     *   START → router ──┬─(comparison)─→ comparison_planner ─→ search_executor → END
     *                    └─(retrieval)──→ hop_planner        ─→ search_executor → END
     * </pre>
     */
    @Bean
    CompiledGraph<KbAgentState> agenticRetrievalGraph(
            LlmRouterNode router,
            HopPlannerNode hopPlanner,
            ComparisonHopPlannerNode comparisonPlanner,
            SearchExecutorNode searchExecutor,
            JdbcCheckpointSaver checkpointSaver) throws GraphStateException {

        return new StateGraph<>(new ObjectStreamStateSerializer<>(KbAgentState::new))
                .addNode("router",              router)
                .addNode("hop_planner",         hopPlanner)
                .addNode("comparison_planner",  comparisonPlanner)
                .addNode("search_executor",     searchExecutor)
                .addEdge(StateGraph.START, "router")
                .addConditionalEdges(
                        "router",
                        state -> CompletableFuture.completedFuture(
                                state.route().orElse(LlmRouterNode.RETRIEVAL)),
                        Map.of(
                                LlmRouterNode.COMPARISON, "comparison_planner",
                                LlmRouterNode.RETRIEVAL,  "hop_planner"))
                .addEdge("hop_planner",        "search_executor")
                .addEdge("comparison_planner", "search_executor")
                .addEdge("search_executor",    StateGraph.END)
                .compile(CompileConfig.builder()
                        .checkpointSaver(checkpointSaver)
                        .build());
    }
}
