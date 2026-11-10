package com.springtechkb.learning.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.springtechkb.knowledge.KnowledgeGraph.Topic;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class LearningPlanValidationTest {

    private final LearningPlanService service =
            new LearningPlanService(null, null, null, null, null, JsonMapper.builder().build(), null);

    @Test
    void acceptsOnlyClosedTopicCoverageAndEarlierTaskPrerequisites() {
        UUID foundation = UUID.randomUUID();
        UUID advanced = UUID.randomUUID();
        List<Topic> closure = List.of(
                new Topic(foundation, "Foundation", "core", "Foundation topic", List.of()),
                new Topic(advanced, "Advanced", "mvc", "Advanced topic", List.of(foundation)));
        LearningPlanService.PlanDraft valid = new LearningPlanService.PlanDraft("Plan", List.of(
                new LearningPlanService.TaskDraft(foundation, "Start", "Basics", List.of(), "6"),
                new LearningPlanService.TaskDraft(advanced, "Continue", "Advanced", List.of(0), "6")));

        List<?> tasks = service.validateDraft(valid, closure, List.of(advanced));

        assertThat(tasks).hasSize(2);
    }

    @Test
    void rejectsUnknownTopicsOmittedPrerequisitesAndNonTopologicalDependencies() {
        UUID foundation = UUID.randomUUID();
        UUID advanced = UUID.randomUUID();
        List<Topic> closure = List.of(
                new Topic(foundation, "Foundation", "core", null, List.of()),
                new Topic(advanced, "Advanced", "mvc", null, List.of(foundation)));

        assertThatThrownBy(() -> service.validateDraft(new LearningPlanService.PlanDraft("Plan", List.of(
                new LearningPlanService.TaskDraft(UUID.randomUUID(), "Unknown", null, List.of(), null))),
                closure, List.of(advanced)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.validateDraft(new LearningPlanService.PlanDraft("Plan", List.of(
                new LearningPlanService.TaskDraft(advanced, "Only advanced", null, List.of(), null))),
                closure, List.of(advanced)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.validateDraft(new LearningPlanService.PlanDraft("Plan", List.of(
                new LearningPlanService.TaskDraft(foundation, "Foundation", null, List.of(), null),
                new LearningPlanService.TaskDraft(advanced, "Advanced", null, List.of(), null))),
                closure, List.of(advanced)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.validateDraft(new LearningPlanService.PlanDraft("Plan", List.of(
                new LearningPlanService.TaskDraft(foundation, "Foundation", null, List.of(0), null),
                new LearningPlanService.TaskDraft(advanced, "Advanced", null, List.of(0), null))),
                closure, List.of(advanced)))
                .isInstanceOf(ResponseStatusException.class);
    }
}
