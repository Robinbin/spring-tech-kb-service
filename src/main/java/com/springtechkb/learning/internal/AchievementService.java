package com.springtechkb.learning.internal;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
class AchievementService {

    private final JdbcTemplate jdbc;

    AchievementService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    AchievementsView personal(UUID userId) {
        lockUser(userId);
        Metrics metrics = metrics(userId);
        awardEarned(userId, metrics);
        List<Achievement> achievements = jdbc.query("""
                SELECT d.code, d.title, d.description, ua.awarded_at
                FROM user_achievement ua
                JOIN achievement_definition d ON d.code = ua.achievement_code
                WHERE ua.user_id = ?
                ORDER BY ua.awarded_at, d.code
                """, (rs, row) -> new Achievement(
                rs.getString("code"), rs.getString("title"), rs.getString("description"),
                rs.getTimestamp("awarded_at").toInstant()), userId);
        List<LeaderboardPreference> preference = jdbc.query("""
                SELECT opted_in, display_alias
                FROM user_leaderboard_preference
                WHERE user_id = ?
                """, (rs, row) -> new LeaderboardPreference(
                rs.getBoolean("opted_in"), rs.getString("display_alias")), userId);
        boolean optedIn = !preference.isEmpty() && preference.getFirst().optedIn();
        return new AchievementsView(achievements, metrics.currentStreakDays(), metrics.completedTasks(),
                metrics.completedAssessments(), metrics.masteredTopics(), optedIn,
                optedIn ? preference.getFirst().displayAlias() : null);
    }

    @Transactional
    LeaderboardPreferenceView setLeaderboardOptIn(UUID userId, boolean optedIn) {
        lockUser(userId);
        List<LeaderboardPreference> existing = jdbc.query("""
                SELECT opted_in, display_alias
                FROM user_leaderboard_preference
                WHERE user_id = ?
                """, (rs, row) -> new LeaderboardPreference(
                rs.getBoolean("opted_in"), rs.getString("display_alias")), userId);
        String alias;
        if (existing.isEmpty()) {
            alias = "Learner-" + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 16).toUpperCase(java.util.Locale.ROOT);
            jdbc.update("""
                    INSERT INTO user_leaderboard_preference (user_id, opted_in, display_alias)
                    VALUES (?, ?, ?)
                    """, userId, optedIn, alias);
        } else {
            alias = existing.getFirst().displayAlias();
            jdbc.update("""
                    UPDATE user_leaderboard_preference
                    SET opted_in = ?, updated_at = now()
                    WHERE user_id = ?
                    """, optedIn, userId);
        }
        return new LeaderboardPreferenceView(optedIn, optedIn ? alias : null);
    }

    List<LeaderboardEntry> leaderboard(int days, int limit) {
        if (days < 7 || days > 30 || limit < 1 || limit > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be 7-30 and limit must be 1-50");
        }
        LocalDate from = LocalDate.now(ZoneOffset.UTC).minusDays(days - 1L);
        return jdbc.query("""
                WITH activity AS (
                    SELECT user_id, (completed_at AT TIME ZONE 'UTC')::date AS activity_day
                    FROM user_learning_progress
                    WHERE status = 'COMPLETED' AND completed_at >= ?::date
                    UNION
                    SELECT s.user_id, (a.answered_at AT TIME ZONE 'UTC')::date AS activity_day
                    FROM user_answer a
                    JOIN assessment_session s ON s.id = a.session_id
                    WHERE a.answered_at >= ?::date
                    UNION
                    SELECT user_id, (created_at AT TIME ZONE 'UTC')::date AS activity_day
                    FROM coding_submission WHERE created_at >= ?::date
                    UNION
                    SELECT user_id, (created_at AT TIME ZONE 'UTC')::date AS activity_day
                    FROM subjective_grading_result WHERE created_at >= ?::date
                ), activity_counts AS (
                    SELECT user_id, count(DISTINCT activity_day) AS active_days
                    FROM activity GROUP BY user_id
                ), task_counts AS (
                    SELECT user_id, count(*) AS completed_tasks
                    FROM user_learning_progress
                    WHERE status = 'COMPLETED' AND completed_at >= ?::date
                    GROUP BY user_id
                ), assessment_counts AS (
                    SELECT user_id, count(*) AS completed_assessments
                    FROM assessment_session
                    WHERE status = 'COMPLETED' AND completed_at >= ?::date
                    GROUP BY user_id
                )
                SELECT p.display_alias,
                       COALESCE(ac.active_days, 0) AS active_days,
                       COALESCE(tc.completed_tasks, 0) AS completed_tasks,
                       COALESCE(sc.completed_assessments, 0) AS completed_assessments,
                       (COALESCE(ac.active_days, 0) * 2
                         + COALESCE(tc.completed_tasks, 0) * 10
                         + COALESCE(sc.completed_assessments, 0) * 5) AS score
                FROM user_leaderboard_preference p
                JOIN users u ON u.id = p.user_id AND u.is_active = TRUE
                LEFT JOIN activity_counts ac ON ac.user_id = p.user_id
                LEFT JOIN task_counts tc ON tc.user_id = p.user_id
                LEFT JOIN assessment_counts sc ON sc.user_id = p.user_id
                WHERE p.opted_in = TRUE
                ORDER BY score DESC, p.display_alias
                LIMIT ?
                """, (rs, row) -> new LeaderboardEntry(
                rs.getString("display_alias"), rs.getLong("score"), rs.getLong("active_days"),
                rs.getLong("completed_tasks"), rs.getLong("completed_assessments")),
                from, from, from, from, from, from, limit);
    }

    static int currentStreakDays(List<LocalDate> activityDates, LocalDate today) {
        Set<LocalDate> dates = new HashSet<>(activityDates);
        LocalDate start = dates.contains(today) ? today : today.minusDays(1);
        if (!dates.contains(start)) return 0;
        int streak = 0;
        while (dates.contains(start.minusDays(streak))) streak++;
        return streak;
    }

    private void lockUser(UUID userId) {
        List<UUID> users = jdbc.query("SELECT id FROM users WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getObject("id", UUID.class), userId);
        if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User was not found");
    }

    private Metrics metrics(UUID userId) {
        long tasks = count("""
                SELECT count(*) FROM user_learning_progress
                WHERE user_id = ? AND status = 'COMPLETED'
                """, userId);
        long assessments = count("""
                SELECT count(*) FROM assessment_session
                WHERE user_id = ? AND status = 'COMPLETED'
                """, userId);
        long mastered = count("""
                SELECT count(*) FROM user_skill_state
                WHERE user_id = ? AND p_mastery >= 0.8
                """, userId);
        long streak = jdbc.queryForObject("""
                WITH RECURSIVE activity_days AS (
                    SELECT DISTINCT activity_day
                    FROM (
                        SELECT (completed_at AT TIME ZONE 'UTC')::date AS activity_day
                        FROM user_learning_progress
                        WHERE user_id = ? AND status = 'COMPLETED'
                        UNION ALL
                        SELECT (a.answered_at AT TIME ZONE 'UTC')::date
                        FROM user_answer a
                        JOIN assessment_session s ON s.id = a.session_id
                        WHERE s.user_id = ?
                        UNION ALL
                        SELECT (created_at AT TIME ZONE 'UTC')::date
                        FROM coding_submission WHERE user_id = ?
                        UNION ALL
                        SELECT (created_at AT TIME ZONE 'UTC')::date
                        FROM subjective_grading_result WHERE user_id = ?
                    ) activity
                    WHERE activity_day <= ?::date
                ), anchor AS (
                    SELECT max(activity_day) AS activity_day
                    FROM activity_days
                    WHERE activity_day >= ?::date - 1
                ), streak_days(activity_day) AS (
                    SELECT activity_day FROM anchor WHERE activity_day IS NOT NULL
                    UNION ALL
                    SELECT streak_days.activity_day - 1
                    FROM streak_days
                    WHERE EXISTS (
                        SELECT 1 FROM activity_days
                        WHERE activity_days.activity_day = streak_days.activity_day - 1
                    )
                )
                SELECT count(*) FROM streak_days
                """, Long.class, userId, userId, userId, userId,
                LocalDate.now(ZoneOffset.UTC), LocalDate.now(ZoneOffset.UTC));
        return new Metrics(tasks, assessments, mastered, Math.toIntExact(streak));
    }

    private void awardEarned(UUID userId, Metrics metrics) {
        List<AchievementDefinition> definitions = jdbc.query("""
                SELECT code, metric_code, threshold
                FROM achievement_definition
                ORDER BY code
                """, (rs, row) -> new AchievementDefinition(
                rs.getString("code"), rs.getString("metric_code"), rs.getInt("threshold")));
        for (AchievementDefinition definition : definitions) {
            long value = switch (definition.metricCode()) {
                case "COMPLETED_TASKS" -> metrics.completedTasks();
                case "COMPLETED_ASSESSMENTS" -> metrics.completedAssessments();
                case "MASTERED_TOPICS" -> metrics.masteredTopics();
                case "CURRENT_STREAK_DAYS" -> metrics.currentStreakDays();
                default -> 0;
            };
            if (value >= definition.threshold()) {
                jdbc.update("""
                        INSERT INTO user_achievement (user_id, achievement_code)
                        VALUES (?, ?)
                        ON CONFLICT (user_id, achievement_code) DO NOTHING
                        """, userId, definition.code());
            }
        }
    }

    private long count(String sql, UUID userId) {
        return jdbc.queryForObject(sql, Long.class, userId);
    }

    record AchievementsView(
            List<Achievement> achievements, int currentStreakDays, long completedTasks,
            long completedAssessments, long masteredTopics, boolean leaderboardOptedIn, String displayAlias) {}

    record Achievement(String code, String title, String description, java.time.Instant awardedAt) {}

    record LeaderboardPreferenceView(boolean optedIn, String displayAlias) {}

    record LeaderboardEntry(
            String displayAlias, long score, long activeDays, long completedTasks, long completedAssessments) {}

    private record LeaderboardPreference(boolean optedIn, String displayAlias) {}

    private record AchievementDefinition(String code, String metricCode, int threshold) {}

    private record Metrics(long completedTasks, long completedAssessments, long masteredTopics, int currentStreakDays) {}
}
