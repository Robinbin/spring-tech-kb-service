package com.springtechkb.search.internal;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import com.springtechkb.search.SearchHit;

/** Delegates bounded multi-hop retrieval to the LangGraph4j agentic retrieval graph. */
@Service
public class AgenticRetrievalService {

    private final CompiledGraph<KbAgentState> graph;

    AgenticRetrievalService(CompiledGraph<KbAgentState> agenticRetrievalGraph) {
        this.graph = agenticRetrievalGraph;
    }

    public RetrievalResult retrieve(String question, String module, String requestedVersion) {
        return retrieve(question, module, requestedVersion, null);
    }

    /**
     * @param sessionId when non-null the graph will persist and restore checkpoint state
     *                  via the JDBC saver, enabling cross-request memory for the session.
     */
    public RetrievalResult retrieve(String question, String module, String requestedVersion, String sessionId) {
        Map<String, Object> input = Map.of(
                KbAgentState.QUESTION, question,
                KbAgentState.MODULE,   module != null ? module : "",
                KbAgentState.VERSION,  requestedVersion != null ? requestedVersion : "");

        RunnableConfig config = sessionId != null
                ? RunnableConfig.builder().threadId(sessionId).build()
                : RunnableConfig.builder().build();

        return graph.invoke(input, config)
                .map(state -> new RetrievalResult(
                        state.hits().orElse(List.of()),
                        state.iterations().orElse(0)))
                .orElse(new RetrievalResult(List.of(), 0));
    }

    /**
     * Package-private factory for unit tests: builds a Phase-1 style graph
     * (hop_planner → search_executor, no LLM router) so tests avoid external calls.
     */
    static AgenticRetrievalService forTesting(KnowledgeRetrievalTool tool, int maxIterations) {
        try {
            var graph = new StateGraph<>(new ObjectStreamStateSerializer<>(KbAgentState::new))
                    .addNode("hop_planner",     new HopPlannerNode())
                    .addNode("search_executor", new SearchExecutorNode(tool, maxIterations))
                    .addEdge(StateGraph.START,   "hop_planner")
                    .addEdge("hop_planner",      "search_executor")
                    .addEdge("search_executor",  StateGraph.END)
                    .compile();
            return new AgenticRetrievalService(graph);
        } catch (GraphStateException e) {
            throw new IllegalStateException("Failed to build test graph", e);
        }
    }

    public record RetrievalResult(List<SearchHit> hits, int iterations) {}
}
