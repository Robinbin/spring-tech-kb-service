package com.springtechkb.assessment.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.springtechkb.HttpErrors;
import com.springtechkb.ai.StructuredGenerationGateway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Handles AI-driven question batch generation and admin review. */
@Service
class QuestionGenerationService {

    private static final Set<String> QUESTION_TYPES = Set.of("MULTIPLE_CHOICE", "CODE_COMPLETION", "OPEN_ENDED");

    private final JdbcTemplate jdbc;
    private final StructuredGenerationGateway gateway;
    private final JsonMapper objectMapper;
    private final AssessmentPhase3Properties properties;

    QuestionGenerationService(
            JdbcTemplate jdbc,
            StructuredGenerationGateway gateway,
            JsonMapper objectMapper,
            AssessmentPhase3Properties properties) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional
    AssessmentPhase3Service.BatchResult generateBatch(UUID adminId, AssessmentPhase3Service.BatchGenerateRequest request) {
        if (request == null || request.countPerTopic() < 1 || request.countPerTopic() > 10
                || request.topicIds() == null || request.topicIds().isEmpty() || request.topicIds().size() > 20
                || request.types() == null || request.types().isEmpty()) {
            throw HttpErrors.badRequest("Provide 1-20 topics, 1-10 questions per topic, and at least one type");
        }
        if (request.topicIds().stream().anyMatch(Objects::isNull) || request.types().stream().anyMatch(Objects::isNull)
                || request.types().stream().anyMatch(type -> !QUESTION_TYPES.contains(type))) {
            throw HttpErrors.badRequest("topicIds and types must be non-null; types must be MULTIPLE_CHOICE/CODE_COMPLETION/OPEN_ENDED");
        }
        for (UUID topicId : request.topicIds()) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM knowledge_topic WHERE id = ?)", Boolean.class, topicId))) {
                throw HttpErrors.notFound("Topic not found: " + topicId);
            }
        }

        UUID batchId = jdbc.queryForObject("""
                INSERT INTO question_generation_batch (created_by, topic_ids, requested_types, requested_count, status)
                VALUES (?, ?::jsonb, ?::jsonb, ?, 'COMPLETED')
                RETURNING id
                """, UUID.class, adminId, writeJson(request.topicIds()), writeJson(request.types()), request.countPerTopic());

        List<AssessmentPhase3Service.BatchItemResult> items = new ArrayList<>();
        String modelId = null;
        String promptVersion = null;
        for (UUID topicId : request.topicIds()) {
            String context = buildGenerationContext(topicId, request.countPerTopic(), request.types());
            var generated = gateway.generate("assessment-question-batch", adminId.toString(), context,
                    AssessmentPhase3Service.GeneratedDraftBatch.class);
            modelId = generated.modelId();
            promptVersion = generated.promptVersion();
            List<AssessmentPhase3Service.GeneratedDraftQuestion> drafts =
                    generated.value() == null ? List.of() : generated.value().questions();
            for (AssessmentPhase3Service.GeneratedDraftQuestion draft : drafts) {
                List<String> rawErrors = new ArrayList<>(qualityErrors(topicId, draft));
                BigDecimal score = qualityScore(rawErrors);
                if (score.compareTo(BigDecimal.valueOf(properties.quality().minimumScore().draft())) < 0) {
                    rawErrors.add("Quality score is below draft threshold");
                }
                List<String> errors = List.copyOf(rawErrors);
                UUID questionId = null;
                if (!hasBlockingErrors(errors)) {
                    questionId = persistDraft(batchId, topicId, draft, score, errors);
                    jdbc.update("""
                            INSERT INTO question_generation_batch_item (batch_id, question_id) VALUES (?, ?)
                            """, batchId, questionId);
                }
                items.add(new AssessmentPhase3Service.BatchItemResult(
                        questionId, topicId, draft == null ? null : draft.type(), score, errors));
            }
        }
        jdbc.update("""
                UPDATE question_generation_batch SET model_id = ?, prompt_version = ?
                WHERE id = ?
                """, modelId, promptVersion, batchId);
        return new AssessmentPhase3Service.BatchResult(batchId, modelId, promptVersion, List.copyOf(items));
    }

    @Transactional
    AssessmentPhase3Service.ReviewResult reviewQuestion(
            UUID reviewerId, UUID questionId, AssessmentPhase3Service.ReviewRequest request) {
        QuestionMeta question = jdbc.query("""
                SELECT id, review_status, quality_score
                FROM question WHERE id = ?
                """, (rs, row) -> new QuestionMeta(
                rs.getObject("id", UUID.class),
                rs.getString("review_status"),
                rs.getBigDecimal("quality_score")), questionId).stream().findFirst()
                .orElseThrow(() -> HttpErrors.notFound("Question was not found"));
        boolean approve = request != null && request.approve();
        if (approve && (question.qualityScore() == null
                || question.qualityScore().compareTo(BigDecimal.valueOf(properties.quality().minimumScore().approval())) < 0)) {
            throw HttpErrors.badRequest("Question quality score is below approval threshold");
        }
        String status = approve ? "APPROVED" : "REJECTED";
        jdbc.update("""
                UPDATE question
                SET is_reviewed = ?, review_status = ?, reviewed_by = ?, reviewed_at = now()
                WHERE id = ?
                """, approve, status, reviewerId, questionId);
        List<String> errors = request == null || request.validationErrors() == null ? List.of() : request.validationErrors();
        jdbc.update("""
                INSERT INTO question_quality_audit
                    (question_id, quality_score, review_status, validation_errors, reviewer_user_id, note)
                VALUES (?, ?, ?, ?::jsonb, ?, ?)
                """, questionId, question.qualityScore(), status, writeJson(errors), reviewerId,
                request == null ? null : request.note());
        return new AssessmentPhase3Service.ReviewResult(questionId, approve, status, question.qualityScore(), errors);
    }

    private UUID persistDraft(UUID batchId, UUID topicId, AssessmentPhase3Service.GeneratedDraftQuestion draft,
            BigDecimal qualityScore, List<String> errors) {
        String type = normalizeType(draft.type());
        String content = blankToNull(draft.content());
        UUID sourceChunkId = draft.sourceChunkId();
        String difficulty = draft.difficulty() == null ? "MEDIUM" : draft.difficulty().toUpperCase(Locale.ROOT);
        String correctAnswer = blankToNull(draft.correctAnswer());
        String explanation = blankToNull(draft.explanation());
        String optionsJson = null;
        if ("MULTIPLE_CHOICE".equals(type)) {
            List<Map<String, Object>> options = new ArrayList<>();
            for (AssessmentPhase3Service.GeneratedOption option : draft.options()) {
                options.add(Map.of(
                        "label", option.label(),
                        "text", option.text(),
                        "is_correct", Objects.equals(option.label(), correctAnswer)));
            }
            optionsJson = writeJson(options);
        }
        UUID questionId = jdbc.queryForObject("""
                INSERT INTO question (topic_id, source_chunk_id, type, difficulty, content, options, correct_answer,
                                      explanation, spring_module, spring_version, is_reviewed, generated_batch_id,
                                      quality_score, review_status, last_quality_checked_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, FALSE, ?, ?, 'DRAFT', now())
                RETURNING id
                """, UUID.class, topicId, sourceChunkId, type, difficulty, content, optionsJson, correctAnswer,
                explanation, draft.springModule(), draft.springVersion(), batchId, qualityScore);
        if ("CODE_COMPLETION".equals(type)) {
            int order = 0;
            for (AssessmentPhase3Service.GeneratedTestCase testCase : draft.testCases()) {
                jdbc.update("""
                        INSERT INTO question_test_case(question_id, case_order, input_text, expected_output, is_hidden, weight)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, questionId, order++, testCase.input(), testCase.expectedOutput(),
                        Boolean.TRUE.equals(testCase.hidden()), BigDecimal.valueOf(Math.max(0.1d, testCase.weight())));
            }
        }
        if ("OPEN_ENDED".equals(type)) {
            int order = 0;
            for (AssessmentPhase3Service.GeneratedRubricDimension dimension : draft.rubricDimensions()) {
                jdbc.update("""
                        INSERT INTO question_rubric_dimension
                            (question_id, dimension_code, name, description, max_score, weight, dimension_order)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, questionId, dimension.code(), dimension.name(), dimension.description(),
                        dimension.maxScore(), dimension.weight(), order++);
            }
        }
        jdbc.update("""
                INSERT INTO question_quality_audit
                    (question_id, quality_score, review_status, validation_errors, reviewer_user_id, note)
                VALUES (?, ?, 'DRAFT', ?::jsonb, null, 'auto quality gate')
                """, questionId, qualityScore, writeJson(errors));
        return questionId;
    }

    private List<String> qualityErrors(UUID topicId, AssessmentPhase3Service.GeneratedDraftQuestion draft) {
        List<String> errors = new ArrayList<>();
        if (draft == null) {
            errors.add("Generated question is null");
            return errors;
        }
        String type = normalizeType(draft.type());
        if (!QUESTION_TYPES.contains(type)) errors.add("Unsupported type: " + draft.type());
        if (blankToNull(draft.content()) == null) errors.add("Question content is blank");
        if (draft.sourceChunkId() == null) {
            errors.add("sourceChunkId is missing");
        } else {
            Boolean sourceApproved = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1
                        FROM knowledge_chunk kc
                        JOIN knowledge_source ks ON ks.id = kc.source_id
                        WHERE kc.id = ? AND kc.topic_id = ? AND ks.is_approved = TRUE)
                    """, Boolean.class, draft.sourceChunkId(), topicId);
            if (!Boolean.TRUE.equals(sourceApproved)) errors.add("sourceChunkId is not approved evidence for the topic");
            Instant crawledAt = jdbc.query("""
                    SELECT ks.crawled_at
                    FROM knowledge_chunk kc
                    JOIN knowledge_source ks ON ks.id = kc.source_id
                    WHERE kc.id = ?
                    """, rs -> rs.next() && rs.getTimestamp("crawled_at") != null
                    ? rs.getTimestamp("crawled_at").toInstant() : null, draft.sourceChunkId());
            if (crawledAt != null && crawledAt.isBefore(
                    Instant.now().minus(properties.quality().staleSourceDays(), ChronoUnit.DAYS))) {
                errors.add("Evidence source is stale");
            }
        }
        Boolean duplicate = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM question
                    WHERE topic_id = ? AND type = ? AND lower(content) = lower(?))
                """, Boolean.class, topicId, type, blankToNull(draft.content()));
        if (Boolean.TRUE.equals(duplicate)) errors.add("Possible duplicate question content");

        if ("MULTIPLE_CHOICE".equals(type)) {
            if (draft.options() == null || draft.options().size() < 2 || draft.options().size() > 6) {
                errors.add("MULTIPLE_CHOICE requires 2-6 options");
            } else {
                long valid = draft.options().stream()
                        .filter(o -> o != null && blankToNull(o.label()) != null && blankToNull(o.text()) != null)
                        .count();
                if (valid != draft.options().size()) errors.add("Option label/text cannot be blank");
            }
            if (blankToNull(draft.correctAnswer()) == null) errors.add("correctAnswer is required");
            if (draft.options() != null && draft.correctAnswer() != null
                    && draft.options().stream().noneMatch(o -> draft.correctAnswer().equals(o.label()))) {
                errors.add("correctAnswer must match one option label");
            }
        }
        if ("CODE_COMPLETION".equals(type)) {
            if (blankToNull(draft.expectedSignature()) == null) errors.add("expectedSignature is required");
            if (draft.testCases() == null || draft.testCases().isEmpty()) errors.add("CODE_COMPLETION requires testCases");
        }
        if ("OPEN_ENDED".equals(type)) {
            if (draft.rubricDimensions() == null || draft.rubricDimensions().size() < 2) {
                errors.add("OPEN_ENDED requires at least two rubric dimensions");
            }
        }
        return List.copyOf(errors);
    }

    private static boolean hasBlockingErrors(List<String> errors) {
        return errors.stream().anyMatch(error -> !error.startsWith("Possible duplicate")
                && !error.startsWith("Evidence source is stale"));
    }

    private BigDecimal qualityScore(List<String> errors) {
        int score = 100;
        for (String error : errors) {
            if (error.startsWith("Possible duplicate")) score -= 25;
            else if (error.startsWith("Evidence source is stale")) score -= 15;
            else score -= 20;
        }
        return BigDecimal.valueOf(Math.max(0, score)).setScale(2, RoundingMode.HALF_UP);
    }

    private String buildGenerationContext(UUID topicId, int count, List<String> types) {
        List<Map<String, Object>> chunks = jdbc.query("""
                SELECT kc.id, kc.content, ks.title, ks.url
                FROM knowledge_chunk kc
                JOIN knowledge_source ks ON ks.id = kc.source_id
                WHERE kc.topic_id = ? AND ks.is_approved = TRUE
                ORDER BY kc.created_at DESC
                LIMIT 8
                """, (rs, row) -> Map.of(
                "chunkId", rs.getObject("id", UUID.class).toString(),
                "content", rs.getString("content"),
                "title", rs.getString("title"),
                "url", rs.getString("url")), topicId);
        if (chunks.isEmpty()) throw HttpErrors.conflict("No approved knowledge chunks available for topic " + topicId);
        return """
                topicId=%s
                count=%s
                types=%s
                chunks=%s
                """.formatted(topicId, count, types, writeJson(chunks));
    }

    private static String normalizeType(String type) {
        return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize JSON", exception);
        }
    }

    private record QuestionMeta(UUID id, String reviewStatus, BigDecimal qualityScore) {}
}
