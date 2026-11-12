package com.springtechkb.assessment.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.springtechkb.HttpErrors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Handles adaptive question selection and ability reporting. */
@Service
class AdaptiveTestService {

    private final JdbcTemplate jdbc;
    private final JsonMapper objectMapper;

    AdaptiveTestService(JdbcTemplate jdbc, JsonMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    AssessmentPhase3Service.AdaptiveQuestion adaptiveNext(UUID userId, UUID topicId) {
        if (topicId == null) throw HttpErrors.badRequest("topicId is required");
        BigDecimal mastery = jdbc.query("""
                SELECT p_mastery FROM user_skill_state WHERE user_id = ? AND topic_id = ?
                """, rs -> rs.next() ? rs.getBigDecimal("p_mastery") : BigDecimal.valueOf(0.1), userId, topicId);
        double theta = (mastery.doubleValue() - 0.5d) * 6.0d;
        List<AssessmentPhase3Service.AdaptiveQuestion> questions = jdbc.query("""
                SELECT q.id, q.topic_id, q.type, q.difficulty, q.content, q.options,
                       COALESCE(irt.difficulty, 0) AS irt_difficulty,
                       COALESCE(irt.discrimination, 1) AS irt_discrimination,
                       COALESCE(irt.guessing, CASE WHEN q.type = 'MULTIPLE_CHOICE' THEN 0.25 ELSE 0.10 END) AS irt_guessing
                FROM question q
                LEFT JOIN question_irt_stats irt ON irt.question_id = q.id
                WHERE q.topic_id = ? AND q.is_reviewed = TRUE AND q.review_status = 'APPROVED'
                ORDER BY ABS(COALESCE(irt.difficulty, 0) - ?) ASC, q.created_at DESC
                LIMIT 1
                """, (rs, row) -> new AssessmentPhase3Service.AdaptiveQuestion(
                rs.getObject("id", UUID.class), rs.getObject("topic_id", UUID.class), rs.getString("type"),
                rs.getString("difficulty"), rs.getString("content"), safeOptions(rs.getString("options")),
                BigDecimal.valueOf(theta).setScale(3, RoundingMode.HALF_UP),
                rs.getBigDecimal("irt_difficulty"), rs.getBigDecimal("irt_discrimination"), rs.getBigDecimal("irt_guessing")),
                topicId, theta);
        if (questions.isEmpty()) throw HttpErrors.notFound("No approved question is available for this topic");
        return questions.getFirst();
    }

    @Transactional(readOnly = true)
    AssessmentPhase3Service.AbilityReport abilityReport(UUID userId) {
        List<AssessmentPhase3Service.RadarDimension> dimensions = jdbc.query("""
                SELECT s.topic_id, t.name, s.p_mastery, s.response_count, s.last_updated
                FROM user_skill_state s
                JOIN knowledge_topic t ON t.id = s.topic_id
                WHERE s.user_id = ?
                ORDER BY s.p_mastery DESC, t.name
                """, (rs, row) -> new AssessmentPhase3Service.RadarDimension(
                rs.getObject("topic_id", UUID.class), rs.getString("name"),
                rs.getBigDecimal("p_mastery"),
                BigDecimal.valueOf((rs.getBigDecimal("p_mastery").doubleValue() - 0.5d) * 6d)
                        .setScale(3, RoundingMode.HALF_UP),
                rs.getInt("response_count"), rs.getTimestamp("last_updated").toInstant()), userId);
        AssessmentPhase3Service.AssessmentStats assessment = jdbc.query("""
                SELECT COUNT(*) AS sessions, COALESCE(AVG(score), 0) AS avg_score
                FROM assessment_session
                WHERE user_id = ? AND status = 'COMPLETED' AND submitted_at >= ?
                """, rs -> {
            rs.next();
            return new AssessmentPhase3Service.AssessmentStats(rs.getInt("sessions"), rs.getBigDecimal("avg_score"));
        }, userId, java.sql.Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)));
        AssessmentPhase3Service.ActivityStats coding = jdbc.query("""
                SELECT COUNT(*) AS submissions, COALESCE(AVG(score), 0) AS avg_score
                FROM coding_submission
                WHERE user_id = ? AND created_at >= ?
                """, rs -> {
            rs.next();
            return new AssessmentPhase3Service.ActivityStats(rs.getInt("submissions"), rs.getBigDecimal("avg_score"));
        }, userId, java.sql.Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)));
        AssessmentPhase3Service.ActivityStats subjective = jdbc.query("""
                SELECT COUNT(*) AS submissions, COALESCE(AVG(total_score), 0) AS avg_score
                FROM subjective_grading_result
                WHERE user_id = ? AND created_at >= ?
                """, rs -> {
            rs.next();
            return new AssessmentPhase3Service.ActivityStats(rs.getInt("submissions"), rs.getBigDecimal("avg_score"));
        }, userId, java.sql.Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)));

        List<String> strengths = dimensions.stream()
                .filter(d -> d.mastery().compareTo(BigDecimal.valueOf(0.75)) >= 0)
                .map(AssessmentPhase3Service.RadarDimension::topicName).limit(3).toList();
        List<String> weaknesses = dimensions.stream()
                .filter(d -> d.mastery().compareTo(BigDecimal.valueOf(0.45)) <= 0)
                .sorted((a, b) -> a.mastery().compareTo(b.mastery()))
                .map(AssessmentPhase3Service.RadarDimension::topicName).limit(3).toList();
        List<String> recommendations = weaknesses.stream()
                .map(w -> "Focus review and coding drills on " + w)
                .toList();
        BigDecimal mastery = dimensions.isEmpty() ? BigDecimal.ZERO :
                dimensions.stream().map(AssessmentPhase3Service.RadarDimension::mastery)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(dimensions.size()), 3, RoundingMode.HALF_UP);
        return new AssessmentPhase3Service.AbilityReport(mastery, assessment, coding, subjective,
                dimensions, strengths, weaknesses, recommendations);
    }

    @Transactional(readOnly = true)
    AssessmentPhase3Service.FrontendAbilityReport assessmentReport(UUID userId, UUID assessmentId) {
        AssessmentScoreRow row = jdbc.query("""
                SELECT score FROM assessment_session
                WHERE id = ? AND user_id = ?
                """, (rs, rowNum) -> new AssessmentScoreRow(rs.getBigDecimal("score")), assessmentId, userId)
                .stream().findFirst().orElseThrow(() -> HttpErrors.notFound("Assessment was not found"));
        AssessmentPhase3Service.AbilityReport report = abilityReport(userId);
        List<AssessmentPhase3Service.FrontendAbilityLevel> levels = report.radar().stream()
                .limit(5)
                .map(r -> new AssessmentPhase3Service.FrontendAbilityLevel(
                        r.topicName(),
                        r.mastery().multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue()))
                .toList();
        int overall = row.score() == null
                ? report.assessment().averageScore().setScale(0, RoundingMode.HALF_UP).intValue()
                : row.score().setScale(0, RoundingMode.HALF_UP).intValue();
        return new AssessmentPhase3Service.FrontendAbilityReport(
                assessmentId,
                Instant.now(),
                Math.max(0, Math.min(100, overall)),
                levels,
                report.strengths(),
                report.recommendations());
    }

    private List<Map<String, Object>> safeOptions(String optionsJson) {
        try {
            JsonNode root = objectMapper.readTree(optionsJson);
            if (root == null || !root.isArray()) return List.of();
            List<Map<String, Object>> options = new ArrayList<>();
            for (JsonNode option : root) {
                options.add(Map.of(
                        "label", option.path("label").asText(""),
                        "text", option.path("text").asText("")));
            }
            return List.copyOf(options);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private record AssessmentScoreRow(BigDecimal score) {}
}
