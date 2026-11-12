package com.springtechkb.assessment.internal;

import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.springtechkb.assessment.internal.AssessmentPhase3Service.*;

/**
 * Sub-graph node: delegates question batch generation to {@link AssessmentPhase3Service}.
 * Sets {@code batch_id}, {@code generated_count}, and {@code quality_passed} in state.
 */
@Component
class QuestionGenerationNode implements AsyncNodeAction<AssessmentAgentState> {

    private final AssessmentPhase3Service service;

    QuestionGenerationNode(AssessmentPhase3Service service) {
        this.service = service;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(AssessmentAgentState state) {
        UUID adminId = UUID.fromString(state.adminId().orElseThrow());
        List<UUID> topicIds = state.topicIds()
                .orElse(List.of())
                .stream()
                .map(UUID::fromString)
                .toList();
        List<String> types = state.questionTypes().orElse(List.of("MULTIPLE_CHOICE"));
        int count = state.countPerTopic().orElse(3);

        BatchResult result = service.generateBatch(adminId,
                new BatchGenerateRequest(topicIds, types, count));

        long passed = result.items().stream()
                .filter(item -> item.validationErrors().isEmpty())
                .count();

        return CompletableFuture.completedFuture(Map.of(
                AssessmentAgentState.BATCH_ID,        result.batchId().toString(),
                AssessmentAgentState.GENERATED_COUNT, result.items().size(),
                AssessmentAgentState.QUALITY_PASSED,  (int) passed));
    }
}
