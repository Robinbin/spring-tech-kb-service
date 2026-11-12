package com.springtechkb.assessment.internal;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AssessmentSubGraphConfiguration {

    /**
     * Assessment sub-graph:
     *
     * <pre>
     *   START → question_generation → auto_grading → END
     * </pre>
     *
     * <p>Can be embedded in a parent orchestration graph as a named sub-graph node.
     */
    @Bean
    CompiledGraph<AssessmentAgentState> assessmentSubGraph(
            QuestionGenerationNode questionGeneration,
            AutoGradingNode autoGrading) throws GraphStateException {

        return new StateGraph<>(new ObjectStreamStateSerializer<>(AssessmentAgentState::new))
                .addNode("question_generation", questionGeneration)
                .addNode("auto_grading",        autoGrading)
                .addEdge(StateGraph.START,         "question_generation")
                .addEdge("question_generation",    "auto_grading")
                .addEdge("auto_grading",           StateGraph.END)
                .compile();
    }
}
