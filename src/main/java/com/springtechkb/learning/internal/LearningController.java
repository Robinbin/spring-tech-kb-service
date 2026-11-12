package com.springtechkb.learning.internal;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import com.springtechkb.JwtPrincipalResolver;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/learning")
class LearningController {

    private final LearningPlanService plans;

    LearningController(LearningPlanService plans) {
        this.plans = plans;
    }

    @GetMapping("/plans")
    List<LearningPlanService.PlanSummary> list(@AuthenticationPrincipal Jwt jwt) {
        return plans.list(userId(jwt));
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    LearningPlanService.PlanView create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody LearningPlanService.CreatePlanRequest request) {
        return plans.create(userId(jwt), request);
    }

    @GetMapping("/plans/{planId}")
    LearningPlanService.PlanView get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID planId) {
        return plans.get(userId(jwt), planId);
    }

    @PatchMapping("/tasks/{taskId}")
    LearningPlanService.TaskView updateTask(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID taskId,
            @Valid @RequestBody LearningPlanService.UpdateTaskRequest request) {
        return plans.updateTask(userId(jwt), taskId, request);
    }

    @PostMapping("/plans/{planId}/adapt")
    LearningPlanService.PlanView adapt(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID planId) {
        return plans.adapt(userId(jwt), planId);
    }

    private static UUID userId(Jwt jwt) {
        return JwtPrincipalResolver.userId(jwt);
    }
}
