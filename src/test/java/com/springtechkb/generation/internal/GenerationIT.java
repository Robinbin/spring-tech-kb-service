package com.springtechkb.generation.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.springtechkb.ai.StructuredGenerationGateway;
import com.springtechkb.knowledge.KnowledgeSource;
import com.springtechkb.search.KnowledgeQueries;
import com.springtechkb.search.SearchHit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static com.springtechkb.generation.internal.GenerationDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.ai.model.chat=none", "spring.ai.model.embedding=openai",
        "spring.ai.anthropic.api-key=dummy-generation-it", "spring.ai.openai.api-key=dummy-generation-it",
        "spring.ai.openai.base-url=http://localhost:8081",
        "spring.ai.vectorstore.pgvector.initialize-schema=false",
        "spring.ai.vectorstore.pgvector.dimensions=1024", "spring.jpa.hibernate.ddl-auto=none",
        "management.tracing.sampling.probability=0.0", "springtechkb.ingestion.outbox-initial-delay-ms=600000"
})
class GenerationIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = createPostgres();

    private static PostgreSQLContainer<?> createPostgres() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("springkb_generation_test").withUsername("test").withPassword("test")
                .withCreateContainerCmdModifier(command -> command.getHostConfig().withPidsLimit(0L));
        postgres.setPortBindings(List.of("5433:5432"));
        return postgres;
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean StructuredGenerationGateway gateway;
    @MockitoBean KnowledgeQueries knowledge;
    @Autowired JdbcTemplate jdbc;
    @Autowired RestClient.Builder clients;
    @LocalServerPort int port;

    @Test
    void persistsGroundedOutputsEnforcesOwnershipDownloadsSafeProjectAndNeverIndexes() throws Exception {
        RestClient client = clients.baseUrl("http://localhost:" + port).build();
        String owner = register(client, "generation-owner");
        String stranger = register(client, "generation-stranger");
        UUID sourceId = jdbc.queryForObject("""
                INSERT INTO knowledge_source(title,url,license_type,license_url,author,is_approved)
                VALUES ('Approved guide','https://spring.io/generation-it','APACHE_2',
                        'https://www.apache.org/licenses/LICENSE-2.0','Spring',TRUE) RETURNING id
                """, UUID.class);
        var source = new KnowledgeQueries.Source(sourceId, "Approved guide", "https://spring.io/generation-it",
                "APACHE_2", "https://www.apache.org/licenses/LICENSE-2.0", "Spring");
        when(knowledge.source(sourceId)).thenAnswer(invocation -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_approved FROM knowledge_source WHERE id=?", Boolean.class, sourceId)) ? source : null);
        when(knowledge.search(anyString(), any(), any(), anyInt())).thenReturn(List.of(
                new SearchHit(UUID.randomUUID().toString(), "Bean lifecycle", 1, "7.0",
                        KnowledgeSource.SPRING_OFFICIAL_DOCS, "guide", source.url(), "core", "APACHE_2",
                        sourceId.toString())));
        when(gateway.generate(anyString(), anyString(), anyString(), eq(Draft.class))).thenReturn(
                new StructuredGenerationGateway.Generated<>(new Draft("Bean example", "Source grounded",
                        "class Example {}", "java"), "mock-model", "code-example:v1"));
        long before = jdbc.queryForObject("SELECT count(*) FROM knowledge_chunk", Long.class);

        int anonymousStatus = client.post().uri("/api/generation/examples").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", "beans", "level", "CONCEPT"))
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(anonymousStatus).isEqualTo(401);
        Output example = client.post().uri("/api/generation/examples").header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", "beans", "level", "PRACTICAL", "module", "core", "springVersion", "7.0"))
                .retrieve().body(Output.class);
        assertThat(example.origin()).isEqualTo("AI_GENERATED");
        assertThat(example.sources()).containsExactly(source);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM generated_content WHERE id=?", Long.class, example.id()))
                .isEqualTo(1);
        int strangerContentStatus = client.get().uri("/api/generation/" + example.id())
                .header("Authorization", "Bearer " + stranger)
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(strangerContentStatus).isEqualTo(404);

        Output comparison = client.post().uri("/api/generation/comparisons")
                .header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("left", "Bean", "right", "Component", "springVersion", "7.0"))
                .retrieve().body(Output.class);
        assertThat(comparison.kind()).isEqualTo(Kind.COMPARISON);

        var archive = client.post().uri("/api/generation/projects").header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ProjectRequest("com.example", "safe-demo", "Safe Demo", example.id()))
                .retrieve().toEntity(byte[].class);
        UUID projectId = UUID.fromString(archive.getHeaders().getFirst("X-Generation-Id"));
        var files = ProjectArchiveBuilderTest.read(archive.getBody());
        assertThat(files).containsKeys("pom.xml", "LICENSE.txt", "PROVENANCE.json", "reference/generated-example.txt");
        int strangerProjectStatus = client.get().uri("/api/generation/" + projectId + "/project")
                .header("Authorization", "Bearer " + stranger)
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(strangerProjectStatus).isEqualTo(404);
        assertThat(client.get().uri("/api/generation/" + projectId + "/project")
                .header("Authorization", "Bearer " + owner).retrieve().body(byte[].class)).isEqualTo(archive.getBody());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM knowledge_chunk", Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM prompt_version
                WHERE name IN ('learning-plan','code-example','technical-comparison') AND is_active
                AND checksum = encode(sha256(convert_to(content,'UTF8')), 'hex')
                """, Long.class)).isEqualTo(3);
        jdbc.update("UPDATE knowledge_source SET is_approved=FALSE WHERE id=?", sourceId);
        int revokedSourceStatus = client.get().uri("/api/generation/" + example.id())
                .header("Authorization", "Bearer " + owner)
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(revokedSourceStatus).isEqualTo(410);
    }

    private String register(RestClient client, String username) {
        return client.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", username + "@example.com", "username", username,
                        "password", "correct-horse-battery"))
                .retrieve().body(Auth.class).accessToken();
    }

    record Auth(String accessToken) {}
}
