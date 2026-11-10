package com.springtechkb.user;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public user-module facade for reading and updating a user's BKT skill profile.
 */
public interface UserSkillProfile {
    List<SkillState> list(UUID userId);

    SkillState recordResponse(UUID userId, UUID topicId, boolean correct);

    record SkillState(
            UUID knowledgeId,
            BigDecimal mastery,
            BigDecimal transit,
            BigDecimal guess,
            BigDecimal slip,
            int responseCount,
            Instant lastUpdated) {}
}
