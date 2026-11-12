package com.springtechkb.learning.internal;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.springtechkb.JwtPrincipalResolver;

@RestController
@RequestMapping("/api/achievements")
class AchievementController {

    private final AchievementService achievements;

    AchievementController(AchievementService achievements) {
        this.achievements = achievements;
    }

    @GetMapping
    AchievementService.AchievementsView personal(@AuthenticationPrincipal Jwt jwt) {
        return achievements.personal(userId(jwt));
    }

    @PutMapping("/leaderboard")
    AchievementService.LeaderboardPreferenceView leaderboardPreference(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody LeaderboardPreferenceRequest request) {
        return achievements.setLeaderboardOptIn(userId(jwt), request.optedIn());
    }

    @GetMapping("/leaderboard")
    List<AchievementService.LeaderboardEntry> leaderboard(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "20") int limit) {
        return achievements.leaderboard(days, limit);
    }

    private static UUID userId(Jwt jwt) {
        return JwtPrincipalResolver.userId(jwt);
    }

    record LeaderboardPreferenceRequest(@NotNull Boolean optedIn) {}
}
