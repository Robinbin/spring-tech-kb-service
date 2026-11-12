package com.springtechkb.assessment.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Periodically calibrates IRT difficulty/discrimination parameters from observed answer data. */
@Service
class IrtCalibrationService {

    private final JdbcTemplate jdbc;

    IrtCalibrationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${springtechkb.assessment.irt.cron:0 */30 * * * *}")
    @Transactional
    void calibrateScheduled() {
        calibrate();
    }

    @Transactional
    AssessmentPhase3Service.IrtCalibrationResult calibrate() {
        List<IrtStat> stats = jdbc.query("""
                WITH objective AS (
                    SELECT ua.question_id, CASE WHEN ua.is_correct THEN 1.0 ELSE 0.0 END AS score
                    FROM user_answer ua
                ), coding AS (
                    SELECT cs.question_id, CASE WHEN cs.score >= 0.70 THEN 1.0 ELSE 0.0 END AS score
                    FROM coding_submission cs
                ), subjective AS (
                    SELECT sg.question_id,
                           CASE WHEN sumdim.max_score > 0 AND (sg.total_score / sumdim.max_score) >= 0.70
                                THEN 1.0 ELSE 0.0 END AS score
                    FROM subjective_grading_result sg
                    JOIN (
                        SELECT question_id, SUM(max_score) AS max_score
                        FROM question_rubric_dimension
                        GROUP BY question_id
                    ) sumdim ON sumdim.question_id = sg.question_id
                ), merged AS (
                    SELECT * FROM objective
                    UNION ALL
                    SELECT * FROM coding
                    UNION ALL
                    SELECT * FROM subjective
                )
                SELECT q.id AS question_id, q.type, COUNT(m.score) AS sample_size, COALESCE(AVG(m.score), 0) AS success_rate
                FROM question q
                LEFT JOIN merged m ON m.question_id = q.id
                GROUP BY q.id, q.type
                HAVING COUNT(m.score) > 0
                """, (rs, row) -> new IrtStat(
                rs.getObject("question_id", UUID.class), rs.getString("type"),
                rs.getInt("sample_size"), rs.getDouble("success_rate")));

        int updated = 0;
        for (IrtStat stat : stats) {
            double c = "MULTIPLE_CHOICE".equals(stat.type()) ? 0.25d : 0.10d;
            double p = Math.max(c + 0.01d, Math.min(0.99d, stat.successRate()));
            double adjusted = (p - c) / (1d - c);
            adjusted = Math.max(0.01d, Math.min(0.99d, adjusted));
            double b = Math.log((1d - adjusted) / adjusted);
            double a = 0.8d + Math.min(2.0d, Math.max(0d, stat.sampleSize() / 40d));
            jdbc.update("""
                    INSERT INTO question_irt_stats(question_id, discrimination, difficulty, guessing, sample_size, updated_at)
                    VALUES (?, ?, ?, ?, ?, now())
                    ON CONFLICT (question_id) DO UPDATE
                    SET discrimination = EXCLUDED.discrimination,
                        difficulty = EXCLUDED.difficulty,
                        guessing = EXCLUDED.guessing,
                        sample_size = EXCLUDED.sample_size,
                        updated_at = now()
                    """, stat.questionId(), BigDecimal.valueOf(a).setScale(5, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(b).setScale(5, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(c).setScale(5, RoundingMode.HALF_UP), stat.sampleSize());
            updated++;
        }
        return new AssessmentPhase3Service.IrtCalibrationResult(updated);
    }

    private record IrtStat(UUID questionId, String type, int sampleSize, double successRate) {}
}
