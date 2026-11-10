package com.springtechkb.user.internal;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import com.springtechkb.JwtPrincipalResolver;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.springtechkb.user.UserSkillProfile;

@RestController
@RequestMapping("/api/users/me/skills")
class UserSkillController {

    private final UserSkillService skills;

    UserSkillController(UserSkillService skills) {
        this.skills = skills;
    }

    @GetMapping
    List<UserSkillProfile.SkillState> list(@AuthenticationPrincipal Jwt jwt) {
        return skills.list(userId(jwt));
    }

    @PostMapping("/{knowledgeId}/responses")
    UserSkillProfile.SkillState recordResponse(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID knowledgeId,
            @Valid @RequestBody SkillResponse request) {
        return skills.recordResponse(userId(jwt), knowledgeId, request.correct());
    }

    private static UUID userId(Jwt jwt) {
        return JwtPrincipalResolver.userId(jwt);
    }

    record SkillResponse(@NotNull Boolean correct) {}
}
