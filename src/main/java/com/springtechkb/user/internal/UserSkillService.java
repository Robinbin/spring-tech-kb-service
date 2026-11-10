package com.springtechkb.user.internal;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.UUID;

import com.springtechkb.user.UserSkillProfile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class UserSkillService implements UserSkillProfile {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL128;
    private static final BigDecimal ONE = BigDecimal.ONE;

    private final JdbcTemplate jdbc;

    UserSkillService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SkillState> list(UUID userId) {
        return jdbc.query("""
                SELECT topic_id, p_mastery, p_transit, p_guess, p_slip, response_count, last_updated
                FROM user_skill_state
                WHERE user_id = ?
                ORDER BY topic_id
                """, (rs, rowNum) -> new SkillState(
                rs.getObject("topic_id", UUID.class),
                rs.getBigDecimal("p_mastery"),
                rs.getBigDecimal("p_transit"),
                rs.getBigDecimal("p_guess"),
                rs.getBigDecimal("p_slip"),
                rs.getInt("response_count"),
                rs.getTimestamp("last_updated").toInstant()), userId);
    }

    @Transactional
    @Override
    public SkillState recordResponse(UUID userId, UUID knowledgeId, boolean correct) {
        Boolean topicExists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM knowledge_topic WHERE id = ?)",
                Boolean.class, knowledgeId);
        if (!Boolean.TRUE.equals(topicExists)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Knowledge topic was not found");
        }

        jdbc.update("""
                INSERT INTO user_skill_state (user_id, topic_id)
                VALUES (?, ?)
                ON CONFLICT (user_id, topic_id) DO NOTHING
                """, userId, knowledgeId);
        SkillState current = jdbc.queryForObject("""
                SELECT topic_id, p_mastery, p_transit, p_guess, p_slip, response_count, last_updated
                FROM user_skill_state
                WHERE user_id = ? AND topic_id = ?
                FOR UPDATE
                """, (rs, rowNum) -> new SkillState(
                rs.getObject("topic_id", UUID.class),
                rs.getBigDecimal("p_mastery"),
                rs.getBigDecimal("p_transit"),
                rs.getBigDecimal("p_guess"),
                rs.getBigDecimal("p_slip"),
                rs.getInt("response_count"),
                rs.getTimestamp("last_updated").toInstant()), userId, knowledgeId);

        BigDecimal nextMastery = updateMastery(current, correct);
        jdbc.update("""
                UPDATE user_skill_state
                SET p_mastery = ?, response_count = response_count + 1, last_updated = now()
                WHERE user_id = ? AND topic_id = ?
                """, nextMastery, userId, knowledgeId);
        return jdbc.queryForObject("""
                SELECT topic_id, p_mastery, p_transit, p_guess, p_slip, response_count, last_updated
                FROM user_skill_state
                WHERE user_id = ? AND topic_id = ?
                """, (rs, rowNum) -> new SkillState(
                rs.getObject("topic_id", UUID.class),
                rs.getBigDecimal("p_mastery"),
                rs.getBigDecimal("p_transit"),
                rs.getBigDecimal("p_guess"),
                rs.getBigDecimal("p_slip"),
                rs.getInt("response_count"),
                rs.getTimestamp("last_updated").toInstant()), userId, knowledgeId);
    }

    private static BigDecimal updateMastery(SkillState current, boolean correct) {
        BigDecimal mastery = current.mastery();
        BigDecimal likelihoodMastered = correct ? ONE.subtract(current.slip()) : current.slip();
        BigDecimal likelihoodNotMastered = correct ? current.guess() : ONE.subtract(current.guess());
        BigDecimal masteredEvidence = mastery.multiply(likelihoodMastered, MATH_CONTEXT);
        BigDecimal denominator = masteredEvidence.add(
                ONE.subtract(mastery).multiply(likelihoodNotMastered, MATH_CONTEXT), MATH_CONTEXT);
        BigDecimal posterior = masteredEvidence.divide(denominator, MATH_CONTEXT);
        return posterior.add(ONE.subtract(posterior).multiply(current.transit(), MATH_CONTEXT), MATH_CONTEXT);
    }

}
