package com.springtechkb.learning.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.springtechkb.HttpErrors;
import com.springtechkb.knowledge.ClosureValidator;
import com.springtechkb.ai.StructuredGenerationGateway;
import com.springtechkb.knowledge.KnowledgeGraph;
import com.springtechkb.knowledge.KnowledgeGraph.Topic;
import com.springtechkb.search.KnowledgeQueries;
import com.springtechkb.search.KnowledgeQueries.Source;
import com.springtechkb.user.UserSkillProfile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class LearningPlanService {

    static final int MAX_REQUESTED_TOPICS = 8;
    static final int MAX_CLOSURE_TOPICS = 40;
    static final int MAX_TASKS = 40;
    static final int MAX_ADAPTATIONS = 3;
    private static final double ADAPTATION_THRESHOLD = 0.5;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final KnowledgeGraph graph;
    private final UserSkillProfile skills;
    private final StructuredGenerationGateway generation;
    private final JsonMapper objectMapper;
    private final KnowledgeQueries knowledge;

    LearningPlanService(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            KnowledgeGraph graph,
            UserSkillProfile skills,
            StructuredGenerationGateway generation,
            JsonMapper objectMapper,
            KnowledgeQueries knowledge) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.graph = graph;
        this.skills = skills;
        this.generation = generation;
        this.objectMapper = objectMapper;
        this.knowledge = knowledge;
    }

    PlanView create(UUID userId, CreatePlanRequest request) {
        List<UUID> requestedIds = request.topicIds();
        if (requestedIds == null || requestedIds.isEmpty() || requestedIds.size() > MAX_REQUESTED_TOPICS
                || requestedIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(requestedIds).size() != requestedIds.size()) {
            throw HttpErrors.badRequest("Choose between 1 and 8 distinct learning topics");
        }
        String goal = normalizeGoal(request.goal());
        List<Topic> closure = graph.orderedClosure(requestedIds);
        if (closure == null || closure.isEmpty() || closure.size() > MAX_CLOSURE_TOPICS) {
            throw HttpErrors.badRequest("The selected topics have no valid prerequisite closure or exceed the topic limit");
        }
        Set<UUID> closureTopicIds = new HashSet<>();
        for (Topic topic : closure) {
            if (topic == null || topic.id() == null || !closureTopicIds.add(topic.id())) {
                throw HttpErrors.badRequest("The knowledge graph returned an invalid prerequisite closure");
            }
        }
        validateClosureOrder(closure);
        if (!closureTopicIds.containsAll(requestedIds)) {
            throw HttpErrors.badRequest("Every requested topic must be present in its prerequisite closure");
        }
        List<UUID> evidenceSourceIds = approvedEvidenceSourceIds(closureTopicIds);

        List<UserSkillProfile.SkillState> skillStates = skills.list(userId);
        List<SkillInput> skillInputs = skillStates.stream()
                .filter(skill -> closureTopicIds.contains(skill.knowledgeId()))
                .map(skill -> new SkillInput(skill.knowledgeId(), skill.mastery(), skill.transit(),
                        skill.guess(), skill.slip(), skill.responseCount()))
                .toList();
        String input = writeJson(new GenerationInput(goal, requestedIds, closure, skillInputs, MAX_TASKS));
        if (input.length() > 100_000) {
            throw HttpErrors.badRequest("The selected topics and skill profile exceed the plan-generation input limit");
        }
        StructuredGenerationGateway.Generated<PlanDraft> generated =
                generation.generate("learning-plan", userId.toString(), input, PlanDraft.class);
        PlanDraft draft = generated == null ? null : generated.value();
        List<PreparedTask> prepared = validateDraft(draft, closure, requestedIds);
        if (generated.modelId() == null || generated.modelId().isBlank() || generated.modelId().length() > 100
                || generated.promptVersion() == null || generated.promptVersion().isBlank()
                || generated.promptVersion().length() > 100) {
            throw new IllegalStateException("Structured generation returned incomplete provenance");
        }

        List<UUID> closureSourceIds = closure.stream().map(Topic::id).toList();
        UUID planId = transactions.execute(status -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(73519201)");
            List<Topic> currentClosure;
            try {
                currentClosure = graph.orderedClosure(requestedIds);
            } catch (ResponseStatusException changedGraph) {
                throw HttpErrors.conflict("The knowledge graph changed during plan generation; retry with the current topics");
            }
            if (!sameGraphSnapshot(closure, currentClosure)) {
                throw HttpErrors.conflict("The knowledge graph changed during plan generation; retry with the current topics");
            }
            List<UUID> currentEvidence = approvedEvidenceSourceIds(closureTopicIds);
            if (!evidenceSourceIds.equals(currentEvidence)) {
                throw HttpErrors.conflict("Approved topic evidence changed during plan generation; retry");
            }
            return persist(userId, goal, draft, prepared, closureSourceIds, evidenceSourceIds,
                    generated.modelId(), generated.promptVersion());
        });
        return get(userId, planId);
    }

    List<PlanSummary> list(UUID userId) {
        List<ListPlanRow> rows = jdbc.query("""
                SELECT lp.id, lp.title, lp.goal, lp.status, lp.model_id, lp.prompt_version,
                       lp.source_topic_ids::text AS source_topic_ids,
                       lp.source_ids::text AS source_ids,
                       lp.created_at, lp.updated_at,
                       COUNT(lt.id) AS task_count,
                       COUNT(lt.id) FILTER (WHERE lt.status = 'COMPLETED') AS completed_task_count
                FROM learning_plan lp
                LEFT JOIN learning_task lt ON lt.plan_id = lp.id
                WHERE lp.user_id = ?
                GROUP BY lp.id
                ORDER BY lp.created_at DESC
                LIMIT 50
                """, (rs, rowNum) -> new ListPlanRow(
                rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("goal"),
                rs.getString("status"), rs.getString("model_id"), rs.getString("prompt_version"),
                parseUuidArray(rs.getString("source_topic_ids")),
                parseUuidArray(rs.getString("source_ids")),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getInt("task_count"), rs.getInt("completed_task_count")), userId);
        Set<UUID> allSourceIds = rows.stream()
                .flatMap(r -> r.sourceIds().stream())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<UUID, Source> sourceCache = new java.util.LinkedHashMap<>();
        for (UUID id : allSourceIds) {
            try {
                sourceCache.put(id, knowledge.source(id));
            } catch (ResponseStatusException ignored) {}
        }
        return rows.stream().map(row -> {
            List<Source> sources = row.sourceIds().stream()
                    .map(sourceCache::get)
                    .filter(Objects::nonNull)
                    .toList();
            return new PlanSummary(row.id(), row.title(), row.goal(), row.status(), row.modelId(),
                    row.promptVersion(), row.sourceTopicIds(), sources,
                    row.createdAt(), row.updatedAt(), row.taskCount(), row.completedTaskCount());
        }).toList();
    }

    PlanView get(UUID userId, UUID planId) {
        List<PlanRow> rows = jdbc.query("""
                SELECT id, title, goal, status, model_id, prompt_version, source_topic_ids, source_ids,
                       created_at, updated_at
                FROM learning_plan WHERE id = ? AND user_id = ?
                """, (rs, rowNum) -> new PlanRow(
                rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("goal"),
                rs.getString("status"), rs.getString("model_id"), rs.getString("prompt_version"),
                parseUuidArray(rs.getString("source_topic_ids")), parseUuidArray(rs.getString("source_ids")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), planId, userId);
        if (rows.isEmpty()) throw HttpErrors.notFound("Learning plan was not found");
        PlanRow plan = rows.getFirst();
        List<TaskView> tasks = jdbc.query("""
                SELECT t.id, t.topic_id, t.title, t.description, t.task_order, t.status,
                       t.prerequisite_task_ids, t.target_version, t.created_at, t.updated_at,
                       COALESCE(p.time_spent_seconds, 0) AS time_spent_seconds
                FROM learning_task t
                LEFT JOIN user_learning_progress p ON p.task_id = t.id AND p.user_id = ?
                WHERE t.plan_id = ? ORDER BY t.task_order
                """, (rs, rowNum) -> new TaskView(
                rs.getObject("id", UUID.class), rs.getObject("topic_id", UUID.class),
                rs.getString("title"), rs.getString("description"), rs.getInt("task_order"),
                rs.getString("status"), parseUuidArray(rs.getString("prerequisite_task_ids")),
                rs.getString("target_version"), rs.getInt("time_spent_seconds"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                userId, planId);
        List<Source> sources = resolveSources(plan.sourceIds());
        return new PlanView(plan.id(), plan.title(), plan.goal(), plan.status(), plan.modelId(),
                plan.promptVersion(), plan.sourceTopicIds(), sources, plan.createdAt(), plan.updatedAt(), tasks);
    }

    @Transactional
    TaskView updateTask(UUID userId, UUID taskId, UpdateTaskRequest request) {
        if (request.status() == null || request.timeSpentSeconds() == null
                || request.timeSpentSeconds() < 0 || request.timeSpentSeconds() > 31_536_000) {
            throw HttpErrors.badRequest("Provide a valid task status and timeSpentSeconds between 0 and 31536000");
        }
        List<LockedTask> locked = jdbc.query("""
                    SELECT t.id, t.plan_id, t.topic_id, t.status, t.prerequisite_task_ids,
                           t.task_order, p.id AS owner_plan
                    FROM learning_task t JOIN learning_plan p ON p.id = t.plan_id
                    WHERE t.id = ? AND p.user_id = ? FOR UPDATE OF p, t
                    """, (rs, rowNum) -> new LockedTask(
                    rs.getObject("id", UUID.class), rs.getObject("plan_id", UUID.class),
                    rs.getObject("topic_id", UUID.class), rs.getString("status"),
                    parseUuidArray(rs.getString("prerequisite_task_ids")), rs.getInt("task_order")), taskId, userId);
            if (locked.isEmpty()) throw HttpErrors.notFound("Learning task was not found");
            LockedTask task = locked.getFirst();
            if ("COMPLETED".equals(task.status())) {
                throw HttpErrors.conflict("Completed tasks are immutable");
            }
            String next = request.status();
            if (!Set.of("PENDING", "IN_PROGRESS", "COMPLETED", "SKIPPED").contains(next)
                    || !validTransition(task.status(), next)) {
                throw HttpErrors.conflict("The requested task status transition is not allowed");
            }
            if (("IN_PROGRESS".equals(next) || "COMPLETED".equals(next))
                    && !prerequisitesCompleted(task.prerequisites(), userId)) {
                throw HttpErrors.conflict("Complete all prerequisite tasks before starting this task");
            }
            jdbc.update("""
                    UPDATE learning_task SET status = ?, updated_at = now() WHERE id = ?
                    """, next, taskId);
            jdbc.update("""
                    INSERT INTO user_learning_progress (user_id, task_id, status, time_spent_seconds, completed_at)
                    VALUES (?, ?, ?, ?, CASE WHEN ? = 'COMPLETED' THEN now() END)
                    ON CONFLICT (user_id, task_id) DO UPDATE SET
                        status = EXCLUDED.status,
                        time_spent_seconds = EXCLUDED.time_spent_seconds,
                        completed_at = CASE WHEN EXCLUDED.status = 'COMPLETED' THEN now() ELSE NULL END,
                        updated_at = now()
                    """, userId, taskId, next, request.timeSpentSeconds(), next);
        return get(userId, task.planId()).tasks().stream()
                .filter(result -> result.id().equals(taskId)).findFirst().orElseThrow();
    }

    @Transactional
    PlanView adapt(UUID userId, UUID planId) {
        List<String> planStatuses = jdbc.query("""
                    SELECT status FROM learning_plan WHERE id = ? AND user_id = ? FOR UPDATE
                    """, (rs, rowNum) -> rs.getString("status"), planId, userId);
            if (planStatuses.isEmpty()) throw HttpErrors.notFound("Learning plan was not found");
            List<String> planSources = jdbc.query("""
                    SELECT source_ids::text FROM learning_plan WHERE id = ? AND user_id = ?
                    """, (rs, rowNum) -> rs.getString(1), planId, userId);
            resolveSources(parseUuidArray(planSources.getFirst()));
            int previousAdaptations = jdbc.queryForObject("""
                    SELECT count(*) FROM learning_plan_adaptation WHERE plan_id = ? AND user_id = ?
                    """, Integer.class, planId, userId);
            if (previousAdaptations >= MAX_ADAPTATIONS) {
                throw HttpErrors.conflict("This plan has reached its adaptation limit");
            }
            long total = jdbc.queryForObject("SELECT count(*) FROM learning_task WHERE plan_id = ?", Long.class, planId);
            long completed = jdbc.queryForObject("""
                    SELECT count(*) FROM learning_task WHERE plan_id = ? AND status = 'COMPLETED'
                    """, Long.class, planId);
            if (total == 0 || total >= MAX_TASKS) throw HttpErrors.conflict("This plan cannot accept another adaptation task");
            double rate = completed / (double) total;
            if (rate >= ADAPTATION_THRESHOLD) {
                throw HttpErrors.conflict("Adaptation is only available while completion rate is below 50 percent");
            }
            List<AdaptSource> candidates = jdbc.query("""
                    SELECT id, topic_id, title, description, prerequisite_task_ids, target_version, task_order
                    FROM learning_task WHERE plan_id = ? AND status IN ('PENDING', 'IN_PROGRESS')
                    ORDER BY task_order LIMIT 1
                    """, (rs, rowNum) -> new AdaptSource(
                    rs.getObject("id", UUID.class), rs.getObject("topic_id", UUID.class),
                    rs.getString("title"), rs.getString("description"),
                    parseUuidArray(rs.getString("prerequisite_task_ids")), rs.getString("target_version"),
                    rs.getInt("task_order")), planId);
            if (candidates.isEmpty()) throw HttpErrors.conflict("No incomplete task is available to adapt");
            AdaptSource source = candidates.getFirst();
            int order = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(task_order), -1) + 1 FROM learning_task WHERE plan_id = ?
                    """, Integer.class, planId);
            List<UUID> adaptationPrerequisites = List.of(source.id());
            UUID adaptationId = jdbc.queryForObject("""
                    INSERT INTO learning_task (plan_id, topic_id, title, description, task_order, status,
                                               prerequisite_task_ids, target_version)
                    VALUES (?, ?, ?, ?, ?, 'PENDING', ?::jsonb, ?)
                    RETURNING id
                    """, UUID.class, planId, source.topicId(), "Focused review: " + source.title(),
                    "A focused review added because plan completion is below 50%. " +
                            (source.description() == null ? "" : source.description()),
                    order, writeJson(adaptationPrerequisites), source.targetVersion());
            jdbc.update("""
                    INSERT INTO learning_plan_adaptation (plan_id, user_id, completion_rate, action, details)
                    VALUES (?, ?, ?, 'ADDED_FOCUSED_REVIEW_TASK', ?::jsonb)
                    """, planId, userId, rate,
                    writeJson(Map.of("sourceTaskId", source.id(), "newTaskId", adaptationId,
                            "topicId", source.topicId(), "preservedPrerequisites", source.prerequisites(),
                            "adaptationPrerequisites", adaptationPrerequisites)));
            jdbc.update("UPDATE learning_plan SET updated_at = now() WHERE id = ?", planId);
        return get(userId, planId);
    }

    private UUID persist(
            UUID userId,
            String goal,
            PlanDraft draft,
            List<PreparedTask> tasks,
            List<UUID> requestedIds,
            List<UUID> evidenceSourceIds,
            String modelId,
            String promptVersion) {
        UUID planId = jdbc.queryForObject("""
                INSERT INTO learning_plan (user_id, title, goal, status, model_id, prompt_version, source_topic_ids, source_ids)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?::jsonb, ?::jsonb)
                RETURNING id
                """, UUID.class, userId, draft.title().trim(), goal, modelId, promptVersion,
                writeJson(requestedIds), writeJson(evidenceSourceIds));
        for (int index = 0; index < tasks.size(); index++) {
            PreparedTask task = tasks.get(index);
            List<UUID> prerequisiteIds = task.prerequisiteIndexes().stream().map(tasks::get)
                    .map(PreparedTask::id).toList();
            jdbc.update("""
                    INSERT INTO learning_task (id, plan_id, topic_id, title, description, task_order, status,
                                               prerequisite_task_ids, target_version)
                    VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?::jsonb, ?)
                    """, task.id(), planId, task.topicId(), task.title(), task.description(), index,
                    writeJson(prerequisiteIds), task.targetVersion());
        }
        return planId;
    }

    List<PreparedTask> validateDraft(PlanDraft draft, List<Topic> closure, List<UUID> requestedIds) {
        if (draft == null || draft.title() == null || draft.title().isBlank() || draft.title().length() > 500
                || draft.tasks() == null || draft.tasks().isEmpty() || draft.tasks().size() > MAX_TASKS) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The generated learning plan was invalid");
        }
        Set<UUID> allowedTopicIds = closure.stream().map(Topic::id).collect(java.util.stream.Collectors.toSet());
        Set<UUID> covered = new HashSet<>();
        List<PreparedTask> prepared = new ArrayList<>();
        for (int index = 0; index < draft.tasks().size(); index++) {
            TaskDraft task = draft.tasks().get(index);
            if (task == null || task.topicId() == null || !allowedTopicIds.contains(task.topicId())
                    || task.title() == null || task.title().isBlank() || task.title().length() > 500
                    || task.description() != null && task.description().length() > 4000
                    || task.targetVersion() != null && task.targetVersion().length() > 50) {
                throw invalidDraft();
            }
            List<Integer> prerequisites = task.prerequisiteTaskIndexes();
            if (prerequisites == null) throw invalidDraft();
            Set<Integer> distinct = new HashSet<>();
            for (Integer prerequisite : prerequisites) {
                if (prerequisite == null || prerequisite < 0 || prerequisite >= index || !distinct.add(prerequisite)) {
                    throw invalidDraft();
                }
            }
            covered.add(task.topicId());
            prepared.add(new PreparedTask(UUID.randomUUID(), task.topicId(), task.title().trim(),
                    task.description(), prerequisites, task.targetVersion()));
        }
        if (!covered.containsAll(allowedTopicIds) || !covered.containsAll(requestedIds)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The generated learning plan omitted requested topics or prerequisites");
        }
        Map<UUID, Topic> topicsById = closure.stream()
                .collect(java.util.stream.Collectors.toMap(Topic::id, topic -> topic));
        for (int index = 0; index < prepared.size(); index++) {
            List<UUID> topicPrerequisites = topicsById.get(prepared.get(index).topicId()).prerequisites();
            if (topicPrerequisites == null) continue;
            for (UUID prerequisiteTopicId : topicPrerequisites) {
                if (!hasPrerequisiteTopic(index, prerequisiteTopicId, prepared, new HashSet<>())) {
                    throw invalidDraft();
                }
            }
        }
        return prepared;
    }

    private static boolean hasPrerequisiteTopic(
            int taskIndex, UUID topicId, List<PreparedTask> tasks, Set<Integer> visited) {
        PreparedTask task = tasks.get(taskIndex);
        for (int prerequisiteIndex : task.prerequisiteIndexes()) {
            if (!visited.add(prerequisiteIndex)) continue;
            PreparedTask prerequisite = tasks.get(prerequisiteIndex);
            if (topicId.equals(prerequisite.topicId())
                    || hasPrerequisiteTopic(prerequisiteIndex, topicId, tasks, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean prerequisitesCompleted(List<UUID> prerequisiteIds, UUID userId) {
        if (prerequisiteIds.isEmpty()) return true;
        return jdbc.queryForObject("""
                SELECT count(*) = 0 FROM jsonb_array_elements_text(?::jsonb) required(id)
                LEFT JOIN learning_task t ON t.id = required.id::uuid
                LEFT JOIN learning_plan p ON p.id = t.plan_id
                WHERE p.user_id IS DISTINCT FROM ? OR t.status <> 'COMPLETED'
                """, Boolean.class, writeJson(prerequisiteIds), userId);
    }

    private static void validateClosureOrder(List<Topic> closure) {
        ClosureValidator.assertTopologicalOrder(closure);
    }

    private static boolean validTransition(String current, String next) {
        return switch (current) {
            case "PENDING" -> Set.of("IN_PROGRESS", "COMPLETED", "SKIPPED").contains(next);
            case "IN_PROGRESS" -> Set.of("COMPLETED", "SKIPPED").contains(next);
            case "SKIPPED" -> "PENDING".equals(next);
            default -> false;
        };
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Could not serialize learning plan data", exception);
        }
    }

    private List<UUID> parseUuidArray(String json) {
        try {
            List<String> values = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return values.stream().map(UUID::fromString).toList();
        } catch (Exception exception) {
            throw new IllegalStateException("Stored learning plan data is invalid", exception);
        }
    }

    private String normalizeGoal(String goal) {
        if (goal == null || goal.isBlank()) return null;
        String normalized = goal.trim();
        if (normalized.length() > 2000) throw HttpErrors.badRequest("Goal must be 2000 characters or fewer");
        return normalized;
    }

    private static PlanSummary summary(PlanView plan) {
        return new PlanSummary(plan.id(), plan.title(), plan.goal(), plan.status(), plan.modelId(),
                plan.promptVersion(), plan.sourceTopicIds(), plan.sources(), plan.createdAt(), plan.updatedAt(),
                plan.tasks().size(), (int) plan.tasks().stream().filter(task -> "COMPLETED".equals(task.status())).count());
    }

    private List<UUID> approvedEvidenceSourceIds(Set<UUID> topicIds) {
        List<Map.Entry<UUID, UUID>> evidence = jdbc.query("""
                SELECT DISTINCT kc.topic_id, ks.id AS source_id
                FROM knowledge_chunk kc
                JOIN knowledge_source ks ON ks.id = kc.source_id
                WHERE ks.is_approved = TRUE
                  AND kc.topic_id IS NOT NULL
                  AND kc.topic_id IN (SELECT value::uuid FROM jsonb_array_elements_text(?::jsonb))
                ORDER BY kc.topic_id, ks.id
                """, (rs, rowNum) -> Map.entry(
                rs.getObject("topic_id", UUID.class), rs.getObject("source_id", UUID.class)), writeJson(topicIds));
        Set<UUID> coveredTopics = evidence.stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        if (!coveredTopics.containsAll(topicIds)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Every selected topic and prerequisite needs approved, topic-linked source evidence");
        }
        List<UUID> ids = evidence.stream().map(Map.Entry::getValue).distinct().toList();
        if (ids.isEmpty() || ids.size() > 100) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Approved source evidence is missing or exceeds the supported source limit");
        }
        return ids;
    }

    private static boolean sameGraphSnapshot(List<Topic> expected, List<Topic> actual) {
        if (actual == null || expected.size() != actual.size()) return false;
        Map<UUID, Topic> expectedById = expected.stream().collect(
                java.util.stream.Collectors.toMap(Topic::id, topic -> topic));
        Map<UUID, Topic> actualById = actual.stream().collect(
                java.util.stream.Collectors.toMap(Topic::id, topic -> topic));
        return expectedById.equals(actualById);
    }

    private List<Source> resolveSources(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        try {
            return ids.stream().map(knowledge::source).toList();
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                throw new ResponseStatusException(HttpStatus.GONE, "Approved source evidence for this plan was revoked");
            }
            throw exception;
        }
    }

    private static ResponseStatusException invalidDraft() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "The generated learning plan contained invalid topics, tasks, or task dependencies");
    }

    record CreatePlanRequest(
            @NotEmpty @Size(max = MAX_REQUESTED_TOPICS) List<@NotNull UUID> topicIds,
            @Size(max = 2000) String goal) {}
    record UpdateTaskRequest(
            @NotNull @Size(max = 20) String status,
            @NotNull @Min(0) @Max(31_536_000) Integer timeSpentSeconds) {}
    public record PlanDraft(
            @jakarta.validation.constraints.NotBlank @Size(max = 500) String title,
            @jakarta.validation.constraints.NotEmpty @Size(max = MAX_TASKS) List<@Valid TaskDraft> tasks) {}
    public record TaskDraft(
            @NotNull UUID topicId,
            @jakarta.validation.constraints.NotBlank @Size(max = 500) String title,
            @Size(max = 4000) String description,
            @NotNull @Size(max = MAX_TASKS) List<@NotNull @Min(0) Integer> prerequisiteTaskIndexes,
            @Size(max = 50) String targetVersion) {}
    record GenerationInput(String goal, List<UUID> requestedTopicIds, List<Topic> prerequisiteClosure,
                           List<SkillInput> userSkills, int maxTasks) {}
    record SkillInput(
            UUID topicId, java.math.BigDecimal mastery, java.math.BigDecimal transit,
            java.math.BigDecimal guess, java.math.BigDecimal slip, int responseCount) {}
    record PlanView(UUID id, String title, String goal, String status, String modelId, String promptVersion,
                    List<UUID> sourceTopicIds, List<Source> sources,
                    Instant createdAt, Instant updatedAt, List<TaskView> tasks) {}
    record PlanSummary(UUID id, String title, String goal, String status, String modelId, String promptVersion,
                       List<UUID> sourceTopicIds, List<Source> sources, Instant createdAt, Instant updatedAt,
                       int taskCount, int completedTaskCount) {}
    record TaskView(UUID id, UUID topicId, String title, String description, int order, String status,
                    List<UUID> prerequisiteTaskIds, String targetVersion, int timeSpentSeconds,
                    Instant createdAt, Instant updatedAt) {}
    private record PlanRow(UUID id, String title, String goal, String status, String modelId, String promptVersion,
                           List<UUID> sourceTopicIds, List<UUID> sourceIds, Instant createdAt, Instant updatedAt) {}
    private record LockedTask(UUID id, UUID planId, UUID topicId, String status,
                              List<UUID> prerequisites, int order) {}
    record PreparedTask(UUID id, UUID topicId, String title, String description,
                        List<Integer> prerequisiteIndexes, String targetVersion) {}
    private record AdaptSource(UUID id, UUID topicId, String title, String description,
                               List<UUID> prerequisites, String targetVersion, int order) {}
    private record ListPlanRow(UUID id, String title, String goal, String status, String modelId,
                               String promptVersion, List<UUID> sourceTopicIds, List<UUID> sourceIds,
                               java.time.Instant createdAt, java.time.Instant updatedAt,
                               int taskCount, int completedTaskCount) {}
}
