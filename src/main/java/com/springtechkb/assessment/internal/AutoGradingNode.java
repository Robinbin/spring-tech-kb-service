package com.springtechkb.assessment.internal;

import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Sub-graph node: triggers automated quality review for all questions in the batch.
 * Sets {@code quality_passed} to the count of questions whose quality_score >= threshold.
 */
@Component
class AutoGradingNode implements AsyncNodeAction<AssessmentAgentState> {

    private static final String COUNT_PASSED_SQL = """
            SELECT COUNT(*) FROM question_generation_batch_item bi
            JOIN question q ON bi.question_id = q.id
            WHERE bi.batch_id = ?
              AND q.quality_score >= 0.7
            """;

    private final JdbcTemplate jdbc;

    AutoGradingNode(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(AssessmentAgentState state) {
        String batchIdStr = state.batchId().orElseThrow();
        UUID batchId = UUID.fromString(batchIdStr);

        Integer passed = jdbc.queryForObject(COUNT_PASSED_SQL, Integer.class, batchId);

        return CompletableFuture.completedFuture(Map.of(
                AssessmentAgentState.QUALITY_PASSED, passed != null ? passed : 0));
    }
}
