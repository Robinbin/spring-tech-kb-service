package com.springtechkb.learning.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.springtechkb.ai.StructuredGenerationGateway;
import com.springtechkb.knowledge.KnowledgeGraph;
import com.springtechkb.knowledge.KnowledgeGraph.Topic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Phase2LearningIT.TestBeans.class)
@Testcontainers(disabledWithoutDocker = true)
class Phase2LearningIT {

    private static final UUID FOUNDATION_ID = UUID.fromString("42000000-0000-4000-8000-000000000001");
    private static final UUID ADVANCED_ID = UUID.fromString("42000000-0000-4000-8000-000000000002");
    private static final UUID FOUNDATION_CHUNK_ID = UUID.fromString("42000000-0000-4000-8000-000000000011");
    private static final UUID ADVANCED_CHUNK_ID = UUID.fromString("42000000-0000-4000-8000-000000000012");
    private static final String PASSWORD = "phase-two-test-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = createPostgres();

    private static PostgreSQLContainer<?> createPostgres() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("springkb_phase2_test")
                .withUsername("test")
                .withPassword("test")
                .withCreateContainerCmdModifier(command -> command.getHostConfig().withPidsLimit(0L));
        postgres.setPortBindings(List.of("5433:5432"));
        return postgres;
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.ai.model.chat", () -> "none");
        registry.add("spring.ai.model.embedding", () -> "openai");
        registry.add("spring.ai.anthropic.api-key", () -> "dummy-key-for-phase2-it");
        registry.add("spring.ai.openai.api-key", () -> "not-needed-for-phase2-it");
        registry.add("spring.ai.openai.base-url", () -> "http://localhost:8081");
        registry.add("spring.ai.vectorstore.pgvector.initialize-schema", () -> false);
        registry.add("spring.ai.vectorstore.pgvector.dimensions", () -> 1024);
        registry.add("spring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults", () -> false);
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("springtechkb.security.jwt.secret",
                () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("management.tracing.sampling.probability", () -> 0.0);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper objectMapper;
    @LocalServerPort int port;
    private final List<UUID> createdUsers = new ArrayList<>();

    @BeforeEach
    void seedTopics() {
        jdbc.update("""
                INSERT INTO knowledge_topic (id, name, spring_module, description)
                VALUES (?, 'Phase2 IT Foundation', 'core', 'Foundational test topic')
                ON CONFLICT (id) DO NOTHING
                """, FOUNDATION_ID);
        jdbc.update("""
                INSERT INTO knowledge_topic (id, name, spring_module, description)
                VALUES (?, 'Phase2 IT Advanced', 'mvc', 'Advanced test topic')
                ON CONFLICT (id) DO NOTHING
                """, ADVANCED_ID);
        seedEvidence(FOUNDATION_ID, FOUNDATION_CHUNK_ID, "foundation");
        seedEvidence(ADVANCED_ID, ADVANCED_CHUNK_ID, "advanced");
    }

    @AfterEach
    void cleanUp() {
        createdUsers.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
        createdUsers.clear();
        jdbc.update("DELETE FROM question WHERE content LIKE 'phase2-it-%'");
        jdbc.update("DELETE FROM knowledge_source WHERE url LIKE 'https://phase2.invalid/%'");
    }

    private void seedEvidence(UUID topicId, UUID chunkId, String name) {
        UUID sourceId = jdbc.queryForObject("""
                INSERT INTO knowledge_source (title, url, license_type, license_url, author,
                                              spring_module, is_approved)
                VALUES (?, ?, 'OFFICIAL_DOCS', 'https://spring.io/license', 'Spring', 'core', TRUE)
                ON CONFLICT (url) DO UPDATE SET is_approved = TRUE
                RETURNING id
                """, UUID.class, "Phase2 " + name, "https://phase2.invalid/" + name);
        jdbc.update("""
                INSERT INTO knowledge_chunk (id, source_id, topic_id, content, chunk_index, spring_module)
                VALUES (?, ?, ?, ?, 0, 'core')
                ON CONFLICT (id) DO UPDATE SET source_id = EXCLUDED.source_id, topic_id = EXCLUDED.topic_id
                """, chunkId, sourceId, topicId, "Approved evidence for " + name);
    }

    @Test
    void generatesPersistsAndScopesPlansAndAuditsAdaptation() throws Exception {
        Account student = register("student");
        Account other = register("other");
        UUID skillTopic = FOUNDATION_ID;
        ResponseEntity<String> skillResponse = call(HttpMethod.POST,
                "/api/users/me/skills/" + skillTopic + "/responses", student.token(), Map.of("correct", false));
        assertThat(skillResponse.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> created = call(HttpMethod.POST, "/api/learning/plans", student.token(),
                Map.of("topicIds", List.of(ADVANCED_ID), "goal", "Understand MVC"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode plan = objectMapper.readTree(created.getBody());
        UUID planId = UUID.fromString(plan.path("id").asText());
        assertThat(plan.path("modelId").asText()).isEqualTo("phase2-test-model");
        assertThat(plan.path("promptVersion").asText()).isEqualTo("v-test");
        assertThat(plan.path("sourceTopicIds").size()).isEqualTo(2);
        assertThat(plan.path("sources").size()).isEqualTo(2);
        assertThat(plan.path("sources").get(0).path("license").isMissingNode()).isFalse();
        assertThat(plan.path("sources").get(0).path("licenseUrl").asText()).isEqualTo("https://spring.io/license");
        assertThat(plan.path("tasks").size()).isEqualTo(2);
        UUID firstTask = UUID.fromString(plan.path("tasks").get(0).path("id").asText());
        assertThat(plan.path("tasks").get(1).path("prerequisiteTaskIds").get(0).asText())
                .isEqualTo(firstTask.toString());
        JsonNode generationInput = objectMapper.readTree(TestBeans.GATEWAY.lastInput.get());
        assertThat(generationInput.path("userSkills").size()).isEqualTo(1);
        assertThat(generationInput.path("prerequisiteClosure").size()).isEqualTo(2);
        assertThat(TestBeans.GATEWAY.calledInsideTransaction.get()).isFalse();

        assertThat(call(HttpMethod.GET, "/api/learning/plans/" + planId, other.token(), null)
                .getStatusCode().value()).isEqualTo(404);
        ResponseEntity<String> completed = call(HttpMethod.PATCH, "/api/learning/tasks/" + firstTask,
                student.token(), Map.of("status", "COMPLETED", "timeSpentSeconds", 60));
        assertThat(completed.getStatusCode().value()).isEqualTo(200);
        UUID secondTask = UUID.fromString(plan.path("tasks").get(1).path("id").asText());
        assertThat(call(HttpMethod.PATCH, "/api/learning/tasks/" + secondTask, student.token(),
                Map.of("status", "IN_PROGRESS", "timeSpentSeconds", 10)).getStatusCode().value()).isEqualTo(200);
        assertThat(call(HttpMethod.PATCH, "/api/learning/tasks/" + firstTask, student.token(),
                Map.of("status", "IN_PROGRESS", "timeSpentSeconds", 90)).getStatusCode().value()).isEqualTo(409);

        ResponseEntity<String> secondPlan = call(HttpMethod.POST, "/api/learning/plans", student.token(),
                Map.of("topicIds", List.of(ADVANCED_ID)));
        UUID secondPlanId = UUID.fromString(objectMapper.readTree(secondPlan.getBody()).path("id").asText());
        ResponseEntity<String> adapted = call(HttpMethod.POST,
                "/api/learning/plans/" + secondPlanId + "/adapt", student.token(), null);
        assertThat(adapted.getStatusCode().value()).isEqualTo(200);
        JsonNode adaptedPlan = objectMapper.readTree(adapted.getBody());
        assertThat(adaptedPlan.path("tasks").size()).isEqualTo(3);
        assertThat(adaptedPlan.path("tasks").get(2).path("title").asText()).startsWith("Focused review:");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM learning_plan_adaptation WHERE plan_id = ? AND user_id = ?
                """, Integer.class, secondPlanId, student.id())).isEqualTo(1);
        UUID revokedSource = jdbc.queryForObject("""
                SELECT id FROM knowledge_source WHERE url = 'https://phase2.invalid/advanced'
                """, UUID.class);
        jdbc.update("UPDATE knowledge_source SET is_approved = FALSE WHERE id = ?", revokedSource);
        assertThat(call(HttpMethod.GET, "/api/learning/plans/" + planId, student.token(), null)
                .getStatusCode().value()).isEqualTo(410);
    }

    @Test
    void rejectsInvalidGeneratedPlansAndDoesNotPersistThem() throws Exception {
        Account student = register("invalid-plan");
        TestBeans.GATEWAY.invalidNext.set(true);
        ResponseEntity<String> response = call(HttpMethod.POST, "/api/learning/plans", student.token(),
                Map.of("topicIds", List.of(ADVANCED_ID)));
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(call(HttpMethod.GET, "/api/learning/plans", student.token(), null).getBody()).isEqualTo("[]");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_plan WHERE user_id = ?",
                Integer.class, student.id())).isZero();
    }

    @Test
    void requiresApprovedEvidenceForEveryTopicBeforeInvokingGeneration() throws Exception {
        Account student = register("missing-evidence");
        jdbc.update("""
                UPDATE knowledge_source SET is_approved = FALSE
                WHERE url = 'https://phase2.invalid/foundation'
                """);
        int previousCalls = TestBeans.GATEWAY.callCount.get();

        ResponseEntity<String> response = call(HttpMethod.POST, "/api/learning/plans", student.token(),
                Map.of("topicIds", List.of(ADVANCED_ID)));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(TestBeans.GATEWAY.callCount.get()).isEqualTo(previousCalls);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_plan WHERE user_id = ?",
                Integer.class, student.id())).isZero();
    }

    @Test
    void refusesToPersistWhenTheGraphSnapshotChangesDuringGeneration() throws Exception {
        Account student = register("graph-revision");
        TestBeans.GRAPH.changedNext.set(true);

        ResponseEntity<String> response = call(HttpMethod.POST, "/api/learning/plans", student.token(),
                Map.of("topicIds", List.of(ADVANCED_ID)));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_plan WHERE user_id = ?",
                Integer.class, student.id())).isZero();
    }

    @Test
    void createsFrozenReviewedDiagnosticAndUpdatesBktOncePerIdempotentAnswer() throws Exception {
        Account student = register("diagnostic-student");
        Account ordinaryUser = register("ordinary");
        Account registeredAdmin = register("admin");
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", registeredAdmin.id());
        Account admin = login(registeredAdmin.username());

        ResponseEntity<String> noBank = call(HttpMethod.POST, "/api/assessments/initial", student.token(),
                Map.of("topicIds", List.of(ADVANCED_ID)));
        assertThat(noBank.getStatusCode().value()).isEqualTo(409);
        assertThat(noBank.getBody()).contains("/api/assessments/questions");

        Map<String, Object> authoredQuestion = Map.of(
                "topicId", ADVANCED_ID,
                "content", "phase2-it-What enables test diagnostics?",
                "sourceChunkId", ADVANCED_CHUNK_ID,
                "options", List.of(Map.of("label", "A", "text", "Reviewed bank item"),
                        Map.of("label", "B", "text", "Distractor")),
                "correctAnswer", "A",
                "explanation", "Reviewed answer explanation",
                "difficulty", "EASY");
        assertThat(call(HttpMethod.POST, "/api/assessments/questions", ordinaryUser.token(), authoredQuestion)
                .getStatusCode().value()).isEqualTo(403);
        ResponseEntity<String> createdQuestion = call(HttpMethod.POST, "/api/assessments/questions",
                admin.token(), authoredQuestion);
        assertThat(createdQuestion.getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> started = call(HttpMethod.POST, "/api/assessments/initial", student.token(),
                Map.of("topicIds", List.of(ADVANCED_ID)));
        assertThat(started.getStatusCode().value()).isEqualTo(201);
        JsonNode assessment = objectMapper.readTree(started.getBody());
        UUID assessmentId = UUID.fromString(assessment.path("id").asText());
        UUID questionId = UUID.fromString(assessment.path("questions").get(0).path("id").asText());
        JsonNode question = assessment.path("questions").get(0);
        assertThat(question.hasNonNull("correctAnswer")).isFalse();
        assertThat(question.hasNonNull("explanation")).isFalse();
        assertThat(question.path("options").get(0).has("is_correct")).isFalse();
        assertThat(question.hasNonNull("isCorrect")).isFalse();
        assertThat(question.path("source").path("license").asText()).isEqualTo("OFFICIAL_DOCS");
        assertThat(call(HttpMethod.GET, "/api/assessments/" + assessmentId, ordinaryUser.token(), null)
                .getStatusCode().value()).isEqualTo(404);

        Map<String, Object> answer = Map.of("questionId", questionId, "answer", "A");
        ResponseEntity<String> submitted = call(HttpMethod.POST,
                "/api/assessments/" + assessmentId + "/answers", student.token(), answer);
        assertThat(submitted.getStatusCode().value()).isEqualTo(200);
        assertThat(objectMapper.readTree(submitted.getBody()).path("isCorrect").asBoolean()).isTrue();
        assertThat(objectMapper.readTree(submitted.getBody()).path("completed").asBoolean()).isTrue();
        ResponseEntity<String> repeated = call(HttpMethod.POST,
                "/api/assessments/" + assessmentId + "/answers", student.token(), answer);
        assertThat(repeated.getBody()).isEqualTo(submitted.getBody());
        assertThat(jdbc.queryForObject("""
                SELECT response_count FROM user_skill_state WHERE user_id = ? AND topic_id = ?
                """, Integer.class, student.id(), ADVANCED_ID)).isEqualTo(1);
        JsonNode finalAssessment = objectMapper.readTree(call(HttpMethod.GET,
                "/api/assessments/" + assessmentId, student.token(), null).getBody());
        assertThat(finalAssessment.path("score").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(finalAssessment.path("questions").get(0).path("correctAnswer").asText()).isEqualTo("A");
        jdbc.update("""
                UPDATE knowledge_source SET is_approved = FALSE
                WHERE id = (SELECT source_id FROM knowledge_chunk WHERE id = ?)
                """, ADVANCED_CHUNK_ID);
        assertThat(call(HttpMethod.GET, "/api/assessments/" + assessmentId, student.token(), null)
                .getStatusCode().value()).isEqualTo(410);
        assertThat(call(HttpMethod.POST, "/api/assessments/" + assessmentId + "/answers",
                student.token(), answer).getStatusCode().value()).isEqualTo(410);
    }

    private Account register(String usernamePrefix) throws Exception {
        String username = usernamePrefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        String email = username + "@example.com";
        ResponseEntity<String> registration = call(HttpMethod.POST, "/api/auth/register", null,
                Map.of("email", email, "username", username, "password", PASSWORD));
        assertThat(registration.getStatusCode().value()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(registration.getBody());
        UUID userId = UUID.fromString(body.path("user").path("id").asText());
        createdUsers.add(userId);
        return new Account(userId, body.path("accessToken").asText(), username);
    }

    private Account login(String username) throws Exception {
        ResponseEntity<String> response = call(HttpMethod.POST, "/api/auth/login", null,
                Map.of("identifier", username, "password", PASSWORD));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return new Account(UUID.fromString(objectMapper.readTree(response.getBody()).path("user").path("id").asText()),
                objectMapper.readTree(response.getBody()).path("accessToken").asText(), username);
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String token, Object body) {
        RestClient client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
        RestClient.RequestBodySpec request = client.method(method).uri(path);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        return request.retrieve().toEntity(String.class);
    }

    private record Account(UUID id, String token, String username) {}

    @TestConfiguration
    static class TestBeans {
        static final StubGateway GATEWAY = new StubGateway();
        static final TestKnowledgeGraph GRAPH = new TestKnowledgeGraph();

        @Bean
        @org.springframework.context.annotation.Primary
        KnowledgeGraph phase2TestGraph() {
            return GRAPH;
        }

        @Bean
        @org.springframework.context.annotation.Primary
        StructuredGenerationGateway phase2TestGenerationGateway() {
            return GATEWAY;
        }
    }

    static class TestKnowledgeGraph implements KnowledgeGraph {
        final AtomicBoolean changedNext = new AtomicBoolean();
        private final Topic foundation = new Topic(FOUNDATION_ID, "Phase2 IT Foundation", "core",
                "Foundational test topic", List.of());
        private final Topic advanced = new Topic(ADVANCED_ID, "Phase2 IT Advanced", "mvc",
                "Advanced test topic", List.of(FOUNDATION_ID));

        @Override public List<Topic> topics() { return List.of(foundation, advanced); }

        @Override public List<Topic> prerequisites(UUID id) {
            if (ADVANCED_ID.equals(id)) return List.of(foundation);
            return List.of();
        }

        @Override public List<Topic> orderedClosure(List<UUID> ids) {
            if (ids == null || ids.stream().anyMatch(id -> !FOUNDATION_ID.equals(id) && !ADVANCED_ID.equals(id))) {
                return List.of();
            }
            if (!ids.contains(ADVANCED_ID)) return List.of(foundation);
            if (changedNext.getAndSet(false)) {
                return List.of(foundation, new Topic(ADVANCED_ID, advanced.name(), advanced.springModule(),
                        "Changed graph snapshot", advanced.prerequisites()));
            }
            return List.of(foundation, advanced);
        }
    }

    static class StubGateway implements StructuredGenerationGateway {
        final AtomicReference<String> lastInput = new AtomicReference<>();
        final AtomicBoolean invalidNext = new AtomicBoolean();
        final AtomicBoolean calledInsideTransaction = new AtomicBoolean();
        final AtomicInteger callCount = new AtomicInteger();

        @Override
        @SuppressWarnings("unchecked")
        public <T> Generated<T> generate(String promptName, String callerKey, String input, Class<T> outputType) {
            callCount.incrementAndGet();
            lastInput.set(input);
            calledInsideTransaction.set(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            if (!"learning-plan".equals(promptName)) throw new IllegalArgumentException("Unexpected prompt");
            try {
                JsonNode root = JsonMapper.builder().build().readTree(input);
                List<UUID> topicIds = new ArrayList<>();
                root.path("prerequisiteClosure").forEach(topic -> topicIds.add(UUID.fromString(topic.path("id").asText())));
                List<LearningPlanService.TaskDraft> tasks = new ArrayList<>();
                for (int index = 0; index < topicIds.size(); index++) {
                    UUID topicId = topicIds.get(index);
                    if (invalidNext.getAndSet(false) && index == 0) topicId = UUID.randomUUID();
                    tasks.add(new LearningPlanService.TaskDraft(topicId,
                            "Learn " + topicId, "Test-generated task", index == 0 ? List.of() : List.of(index - 1), "6"));
                }
                Object value = new LearningPlanService.PlanDraft("Phase 2 Learning Plan", tasks);
                return new Generated<>((T) value, "phase2-test-model", "v-test");
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
