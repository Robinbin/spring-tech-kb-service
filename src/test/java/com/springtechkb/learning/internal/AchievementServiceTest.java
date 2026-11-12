package com.springtechkb.learning.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class AchievementServiceTest {

    @Test
    void countsStreakIncludingTodayOrYesterdayOnly() {
        LocalDate today = LocalDate.of(2026, 10, 30);

        assertThat(AchievementService.currentStreakDays(
                List.of(today, today.minusDays(1), today.minusDays(2)), today)).isEqualTo(3);
        assertThat(AchievementService.currentStreakDays(
                List.of(today.minusDays(1), today.minusDays(2), today.minusDays(3)), today)).isEqualTo(3);
        assertThat(AchievementService.currentStreakDays(
                List.of(today.minusDays(2), today.minusDays(3)), today)).isZero();
    }

    @Test
    void treatsEachDateOnceAndStopsAtFirstGap() {
        LocalDate today = LocalDate.of(2026, 10, 30);

        assertThat(AchievementService.currentStreakDays(List.of(
                today, today, today.minusDays(1), today.minusDays(3)), today)).isEqualTo(2);
    }
}
