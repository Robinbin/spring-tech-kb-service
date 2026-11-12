package com.springtechkb.assessment.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.springtechkb.HashUtils;
import com.springtechkb.HttpErrors;
import com.springtechkb.ai.StructuredGenerationGateway;
import com.springtechkb.user.UserSkillProfile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Handles AI-graded open-ended questions and BKT skill updates. */
@Service
class SubjectiveGradingService {

    private final JdbcTemplate jdbc;
    private final StructuredGenerationGateway gateway;
    private final UserSkillProfile skills;
    private final JsonMapper objectMapper;
    private final AssessmentPhase3Properties properties;

    SubjectiveGradingService(
            JdbcTemplate jdbc,
            StructuredGenerationGateway gateway,
            UserSkillProfile skills,
            JsonMapper objectMapper,
            AssessmentPhase3Properties properties) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.skills = skills;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional
    AssessmentPhase3Service.SubjectiveGradeResult gradeOpenEnded(
            UUID userId, AssessmentPhase3Service.SubjectiveGradeRequest request) {
        if (request == null || request.questionId() == null || request.answer() == null || request.answer().isBlank()) {
            throw HttpErrors.badRequest("questionId and answer are required");
        }
        QuestionTypeRow question = openEndedQuestion(request.questionId());
        String answerHash = HashUtils.sha256Hex(request.answer().trim());
        AssessmentPhase3Service.SubjectiveGradeResult existing =
                findExistingSubjective(userId, request.questionId(), answerHash);
        if (existing != null) return existing;

        List<RubricDimension> dimensions = jdbc.query("""
                SELECT dimension_code, name, max_score, weight, description
                FROM question_rubric_dimension
                WHERE question_id = ?
                ORDER BY dimension_order
                """, (rs, row) -> new RubricDimension(
                rs.getString("dimension_code"), rs.getString("name"),
                rs.getBigDecimal("max_score"), rs.getBigDecimal("weight"),
                rs.getString("description")), request.questionId());
        if (dimensions.isEmpty()) throw HttpErrors.badRequest("Open-ended question has no rubric dimensions");
        String gradingInput = """
                Grade the answer for an open-ended Spring question.
                QUESTION: %s
                ANSWER: %s
                RUBRIC: %s
                """.formatted(request.questionId(), request.answer(), writeJson(dimensions));
        var generated = gateway.generate(
                "assessment-subjective-grade", userId.toString(), gradingInput,
                AssessmentPhase3Service.GeneratedGrade.class);
        AssessmentPhase3Service.GeneratedGrade grade = generated.value();
        if (grade == null || grade.dimensionScores() == null || grade.dimensionScores().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI grading output is invalid");
        }
        BigDecimal maxScore = dimensions.stream().map(RubricDimension::maxScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = grade.totalScore() == null ? BigDecimal.ZERO : grade.totalScore();
        if (total.compareTo(BigDecimal.ZERO) < 0) total = BigDecimal.ZERO;
        if (total.compareTo(maxScore) > 0) total = maxScore;
        BigDecimal normalized = maxScore.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : total.divide(maxScore, 3, RoundingMode.HALF_UP);
        boolean correct = normalized.compareTo(BigDecimal.valueOf(0.70)) >= 0;

        UUID resultId;
        try {
            resultId = jdbc.queryForObject("""
                    INSERT INTO subjective_grading_result (
                        user_id, session_id, question_id, response_text, response_hash, total_score, feedback,
                        rubric_scores, model_id, prompt_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                    RETURNING id
                    """, UUID.class, userId, request.sessionId(), request.questionId(), request.answer(),
                    answerHash, total, grade.feedback(), writeJson(grade.dimensionScores()),
                    generated.modelId(), generated.promptVersion());
        } catch (DuplicateKeyException exception) {
            AssessmentPhase3Service.SubjectiveGradeResult retry =
                    findExistingSubjective(userId, request.questionId(), answerHash);
            if (retry != null) return retry;
            throw HttpErrors.conflict("Duplicate open-ended submission");
        }
        applySubjectiveBktOnce(resultId, userId, question.topicId(), correct);
        return new AssessmentPhase3Service.SubjectiveGradeResult(resultId, request.questionId(), total, maxScore,
                normalized, grade.feedback(), grade.dimensionScores(), false);
    }

    private AssessmentPhase3Service.SubjectiveGradeResult findExistingSubjective(
            UUID userId, UUID questionId, String responseHash) {
        return jdbc.query("""
                SELECT id, total_score, feedback, rubric_scores
                FROM subjective_grading_result
                WHERE user_id = ? AND question_id = ? AND response_hash = ?
                """, rs -> {
            if (!rs.next()) return null;
            List<AssessmentPhase3Service.DimensionScore> dimensions = readDimensionScores(rs.getString("rubric_scores"));
            BigDecimal max = dimensions.stream().map(AssessmentPhase3Service.DimensionScore::maxScore)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal total = rs.getBigDecimal("total_score");
            BigDecimal normalized = max.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : total.divide(max, 3, RoundingMode.HALF_UP);
            return new AssessmentPhase3Service.SubjectiveGradeResult(
                    rs.getObject("id", UUID.class), questionId, total, max, normalized,
                    rs.getString("feedback"), dimensions, true);
        }, userId, questionId, responseHash);
    }

    private void applySubjectiveBktOnce(UUID gradingId, UUID userId, UUID topicId, boolean correct) {
        int updated = jdbc.update("""
                UPDATE subjective_grading_result
                SET bkt_applied = TRUE
                WHERE id = ? AND bkt_applied = FALSE
                """, gradingId);
        if (updated == 1) skills.recordResponse(userId, topicId, correct);
    }

    private QuestionTypeRow openEndedQuestion(UUID questionId) {
        return typedQuestion(questionId, "OPEN_ENDED");
    }

    private QuestionTypeRow typedQuestion(UUID questionId, String type) {
        return jdbc.query("""
                SELECT id, topic_id, type
                FROM question WHERE id = ?
                """, (rs, row) -> new QuestionTypeRow(
                rs.getObject("id", UUID.class), rs.getObject("topic_id", UUID.class),
                rs.getString("type")), questionId).stream().findFirst()
                .map(row -> {
                    if (!type.equals(row.type())) throw HttpErrors.badRequest("Question type is not " + type);
                    return row;
                })
                .orElseThrow(() -> HttpErrors.notFound("Question was not found"));
    }

    private List<AssessmentPhase3Service.DimensionScore> readDimensionScores(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray()) return List.of();
            List<AssessmentPhase3Service.DimensionScore> values = new ArrayList<>();
            for (JsonNode item : node) {
                values.add(new AssessmentPhase3Service.DimensionScore(
                        item.path("code").asText(),
                        item.path("score").decimalValue(),
                        item.path("maxScore").decimalValue(),
                        item.path("feedback").asText("")));
            }
            return List.copyOf(values);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize JSON", exception);
        }
    }

    private record QuestionTypeRow(UUID id, UUID topicId, String type) {}
    private record RubricDimension(String code, String name, BigDecimal maxScore, BigDecimal weight, String description) {}
}
