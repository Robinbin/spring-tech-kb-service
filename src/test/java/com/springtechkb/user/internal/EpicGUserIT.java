package com.springtechkb.user.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class EpicGUserIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = createPostgres();

    private static PostgreSQLContainer<?> createPostgres() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("springkb_g_test")
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
        registry.add("spring.ai.anthropic.api-key", () -> "dummy-key-for-g-it");
        registry.add("spring.ai.openai.api-key", () -> "not-needed-for-g-it");
        registry.add("spring.ai.openai.base-url", () -> "http://localhost:8081");
        registry.add("spring.ai.vectorstore.pgvector.initialize-schema", () -> false);
        registry.add("spring.ai.vectorstore.pgvector.dimensions", () -> 1024);
        registry.add("spring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults", () -> false);
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("springtechkb.security.jwt.secret",
                () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("management.tracing.sampling.probability", () -> 0.0);
    }

    @Autowired
    private RestClient.Builder restClientBuilder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserSkillService skills;

    @LocalServerPort
    private int port;

    @Test
    void registersAndLogsInUsersAndRejectsAnonymousProtectedRequests() throws Exception {
        RestClient client = client();
        int anonymousStatus = client.get().uri("/api/users/me")
                .exchange((request, response) -> response.getStatusCode().value());
        org.assertj.core.api.Assertions.assertThat(anonymousStatus).isEqualTo(401);

        var registration = client.post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "email", "student@example.com",
                        "username", "student",
                        "password", "correct-horse-battery"))
                .retrieve()
                .toEntity(AuthResult.class);
        org.assertj.core.api.Assertions.assertThat(registration.getStatusCode().value()).isEqualTo(201);
        org.assertj.core.api.Assertions.assertThat(registration.getBody().user().username()).isEqualTo("student");
        String token = registration.getBody().accessToken();

        var profile = client.get()
                .uri("/api/users/me")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toEntity(UserResult.class);
        org.assertj.core.api.Assertions.assertThat(profile.getStatusCode().value()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(profile.getBody().email()).isEqualTo("student@example.com");

        var login = client.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("identifier", "STUDENT", "password", "correct-horse-battery"))
                .retrieve()
                .toEntity(AuthResult.class);
        org.assertj.core.api.Assertions.assertThat(login.getStatusCode().value()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(login.getBody().tokenType()).isEqualTo("Bearer");
    }

    @Test
    void serializesConcurrentSkillResponsesForOneUserAndKnowledgeTopic() throws Exception {
        UUID userId = jdbc.queryForObject("""
                INSERT INTO users (email, username, password_hash)
                VALUES (?, ?, ?)
                RETURNING id
                """, UUID.class, "skill@example.com", "skill-user", "test-hash");
        UUID topicId = jdbc.queryForObject("""
                INSERT INTO knowledge_topic (name)
                VALUES (?)
                RETURNING id
                """, UUID.class, "concurrent-topic-" + UUID.randomUUID());

        int responseCount = 24;
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<CompletableFuture<Void>> requests = new ArrayList<>();
            for (int i = 0; i < responseCount; i++) {
                boolean correct = i % 2 == 0;
                requests.add(CompletableFuture.runAsync(
                        () -> skills.recordResponse(userId, topicId, correct), executor));
            }
            CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();
        }

        org.assertj.core.api.Assertions.assertThat(skills.list(userId))
                .singleElement()
                .satisfies(state -> {
                    org.assertj.core.api.Assertions.assertThat(state.knowledgeId()).isEqualTo(topicId);
                    org.assertj.core.api.Assertions.assertThat(state.responseCount()).isEqualTo(responseCount);
                });
    }

    private RestClient client() {
        return restClientBuilder.baseUrl("http://localhost:" + port).build();
    }

    record AuthResult(String accessToken, String tokenType, UserResult user) {}

    record UserResult(UUID id, String email, String username, String role) {}
}
