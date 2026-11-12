package com.springtechkb.generation.internal;

import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.springtechkb.generation.internal.GenerationDtos.*;

/**
 * Sub-graph node: delegates to {@link GenerationService} to produce the final output,
 * routing to example / compare based on the request type stored in state.
 */
@Component
class GenerationOutputNode implements AsyncNodeAction<GenerationAgentState> {

    private final GenerationService service;

    GenerationOutputNode(GenerationService service) {
        this.service = service;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(GenerationAgentState state) {
        String type    = state.requestType().orElse("example");
        String query   = state.query().orElseThrow();
        String module  = state.module().orElse("spring-general");
        String version = state.springVersion().orElse("6.x");
        UUID ownerId   = UUID.fromString(state.ownerId().orElseThrow());

        Output output = switch (type) {
            case "compare" -> service.compare(ownerId,
                    new ComparisonRequest(query, query, version, module));
            default -> service.example(ownerId,
                    new ExampleRequest(query, Level.PRACTICAL, module, version));
        };

        return CompletableFuture.completedFuture(Map.of(
                GenerationAgentState.OUTPUT_ID, output.id().toString()));
    }
}
