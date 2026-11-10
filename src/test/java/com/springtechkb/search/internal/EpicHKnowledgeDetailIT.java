package com.springtechkb.search.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=openai",
        "spring.ai.anthropic.api-key=dummy-key-for-h-it",
        "spring.ai.openai.api-key=not-needed-for-h-it",
        "spring.ai.openai.base-url=http://localhost:8081",
        "spring.ai.vectorstore.pgvector.initialize-schema=false",
        "spring.ai.vectorstore.pgvector.dimensions=1024",
        "spring.jpa.hibernate.ddl-auto=none",
        "management.tracing.sampling.probability=0.0",
        "springtechkb.ingestion.outbox-initial-delay-ms=600000"
})
class EpicHKnowledgeDetailIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = createPostgres();

    private static PostgreSQLContainer<?> createPostgres() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("springkb_h_test")
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
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RestClient.Builder restClientBuilder;

    @LocalServerPort
    int port;

    @Test
    void exposesVersionBoundsAndAttributionOnlyWhileSourceIsApproved() {
        UUID sourceId = jdbc.queryForObject("""
                INSERT INTO knowledge_source
                    (title, url, license_type, license_url, author, is_approved)
                VALUES ('Spring Beans', 'https://docs.spring.io/h-test', 'APACHE_2',
                        'https://www.apache.org/licenses/LICENSE-2.0', 'Spring', TRUE)
                RETURNING id
                """, UUID.class);
        UUID chunkId = jdbc.queryForObject("""
                INSERT INTO knowledge_chunk
                    (source_id, content, chunk_index, spring_module, spring_version_min, spring_version_max)
                VALUES (?, 'Bean lifecycle', 0, 'core', '6.1', '6.2')
                RETURNING id
                """, UUID.class, sourceId);
        RestClient client = restClientBuilder.baseUrl("http://localhost:" + port).build();
        String path = "/api/knowledge/" + chunkId;
        int anonymousStatus = client.get().uri(path)
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(anonymousStatus).isEqualTo(401);

        AuthResult auth = client.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "h@example.com", "username", "h-student",
                        "password", "correct-horse-battery"))
                .retrieve().body(AuthResult.class);
        var detail = client.get().uri(path).header("Authorization", "Bearer " + auth.accessToken())
                .retrieve().body(KnowledgeDetailController.KnowledgeDetail.class);
        assertThat(detail.id()).isEqualTo(chunkId);
        assertThat(detail.content()).isEqualTo("Bean lifecycle");
        assertThat(detail.springModule()).isEqualTo("core");
        assertThat(detail.springVersionMin()).isEqualTo("6.1");
        assertThat(detail.springVersionMax()).isEqualTo("6.2");
        assertThat(detail.source()).isEqualTo(new KnowledgeDetailController.DetailSource(
                sourceId, "Spring Beans", "https://docs.spring.io/h-test", "APACHE_2",
                "https://www.apache.org/licenses/LICENSE-2.0", "Spring"));

        jdbc.update("UPDATE knowledge_source SET is_approved = FALSE WHERE id = ?", sourceId);
        for (String unavailable : List.of(path, "/api/knowledge/" + UUID.randomUUID())) {
            int unavailableStatus = client.get().uri(unavailable).header("Authorization", "Bearer " + auth.accessToken())
                    .exchange((request, response) -> response.getStatusCode().value());
            assertThat(unavailableStatus).isEqualTo(404);
        }
    }

    record AuthResult(String accessToken) {}
}
