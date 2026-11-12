package com.springtechkb.generation.internal;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class GenerationSubGraphConfiguration {

    /**
     * Generation sub-graph:
     *
     * <pre>
     *   START → generation_retrieval → generation_output → END
     * </pre>
     *
     * <p>Can be embedded in a parent graph as a named sub-graph node.
     */
    @Bean
    CompiledGraph<GenerationAgentState> generationSubGraph(
            GenerationRetrievalNode retrieval,
            GenerationOutputNode output) throws GraphStateException {

        return new StateGraph<>(new ObjectStreamStateSerializer<>(GenerationAgentState::new))
                .addNode("generation_retrieval", retrieval)
                .addNode("generation_output",    output)
                .addEdge(StateGraph.START,         "generation_retrieval")
                .addEdge("generation_retrieval",   "generation_output")
                .addEdge("generation_output",      StateGraph.END)
                .compile();
    }
}
