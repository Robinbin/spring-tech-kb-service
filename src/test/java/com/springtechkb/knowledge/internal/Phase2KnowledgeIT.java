package com.springtechkb.knowledge.internal;

import static org.assertj.core.api.Assertions.*;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.springtechkb.ai.ChatTier;
import com.springtechkb.ai.GenerationTelemetry;
import com.springtechkb.ai.LlmCallRecorder;
import com.springtechkb.ai.PromptCatalog;
import com.springtechkb.knowledge.KnowledgeGraph;
import com.springtechkb.search.KnowledgeQueries;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
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
        "spring.ai.model.chat=none", "spring.ai.anthropic.api-key=dummy-phase2",
        "spring.ai.openai.api-key=dummy", "spring.ai.vectorstore.pgvector.initialize-schema=false",
        "spring.jpa.hibernate.ddl-auto=none", "management.tracing.sampling.probability=0",
        "springtechkb.ingestion.outbox-initial-delay-ms=600000",
        "springtechkb.llm.pricing.standard.input-per-million=3",
        "springtechkb.llm.pricing.standard.output-per-million=15",
        "springtechkb.llm.pricing.standard.cache-read-per-million=0.3"
})
class Phase2KnowledgeIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = postgres();
    private static PostgreSQLContainer<?> postgres() {
        var db = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("springkb_phase2_knowledge").withUsername("test").withPassword("test")
                .withCreateContainerCmdModifier(command -> command.getHostConfig().withPidsLimit(0L));
        db.setPortBindings(List.of("5433:5432"));
        return db;
    }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeGraph graph;
    @Autowired KnowledgeQueries queries;
    @Autowired LlmCallRecorder recorder;
    @Autowired GenerationTelemetry telemetry;
    @LocalServerPort int port;

    @Test
    void recursiveDiamondClosureAndDatabaseCycleGuard() {
        UUID a = topic("a"), b = topic("b"), c = topic("c"), d = topic("d");
        jdbc.update("INSERT INTO knowledge_prerequisite VALUES (?, ?), (?, ?), (?, ?), (?, ?)", b, a, c, a, d, b, d, c);
        assertThat(graph.orderedClosure(List.of(d))).extracting(KnowledgeGraph.Topic::id).containsExactly(a, b, c, d);
        assertThat(graph.prerequisites(d)).hasSize(3);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO knowledge_prerequisite VALUES (?, ?)", a, d))
                .hasMessageContaining("DAG");
        assertThat(graph.orderedClosure(List.of(d))).hasSize(4);
    }

    @Test
    void authenticatedMcpAdvertisesExactlyFourToolsAndReturnsLicenseAttribution() {
        RestClient rest = RestClient.create("http://localhost:" + port);
        int anonymous = rest.post().uri("/mcp").body("{}")
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(anonymous).isEqualTo(401);
        Auth auth = rest.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "mcp@example.com", "username", "mcp-user", "password", "correct-horse-battery"))
                .retrieve().body(Auth.class);
        UUID source = jdbc.queryForObject("""
                INSERT INTO knowledge_source(title, url, license_type, is_approved)
                VALUES ('MCP source', 'https://docs.spring.io/mcp-test', 'APACHE_2', TRUE) RETURNING id
                """, UUID.class);
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + auth.accessToken()))
                .endpoint("/mcp").build();
        try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(15)).build()) {
            client.initialize();
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder("search_knowledge", "get_source", "compare_versions", "check_prerequisites");
            var result = client.callTool(new McpSchema.CallToolRequest("get_source", Map.of("sourceId", source.toString())));
            assertThat(result.isError()).isNotEqualTo(true);
            assertThat(result.content().toString()).contains("APACHE_2", "https://docs.spring.io/mcp-test");
            jdbc.update("UPDATE knowledge_source SET is_approved = FALSE WHERE id = ?", source);
            var rejected = client.callTool(new McpSchema.CallToolRequest("get_source", Map.of("sourceId", source.toString())));
            assertThat(rejected.isError()).isTrue();
        }
    }

    @Test
    void accountingSeparatesTokenPricesAndPersistsPromptProvenance() {
        var prompt = new PromptCatalog.Prompt("code-example", "2", "source grounded");
        telemetry.observe("code-example", null, prompt, () -> {
            recorder.recordOperation("code-example", null, "model", ChatTier.STANDARD, 1000, 100, 500L, 10L, 25, false);
            return true;
        });
        var record = jdbc.queryForMap("SELECT * FROM llm_call_log WHERE operation = 'code-example' ORDER BY created_at DESC LIMIT 1");
        assertThat(record.get("prompt_name")).isEqualTo("code-example");
        assertThat(record.get("prompt_version")).isEqualTo("2");
        assertThat((String) record.get("trace_id")).hasSize(32);
        assertThat((String) record.get("span_id")).hasSize(16);
        assertThat((String) record.get("prompt_checksum")).hasSize(64);
        assertThat(record.get("estimated_cost_usd").toString()).isEqualTo("0.00465000");
    }

    @Test
    void comparesApprovedApiSnapshotsRatherThanInventingMissingVersions() {
        UUID source = jdbc.queryForObject("""
                INSERT INTO knowledge_source(title, url, license_type, is_approved)
                VALUES ('API source', 'https://docs.spring.io/version-test', 'APACHE_2', TRUE) RETURNING id
                """, UUID.class);
        jdbc.update("""
                INSERT INTO knowledge_api_symbol(source_id,class_name,symbol_kind,signature,spring_version)
                VALUES (?, 'org.example.Bean', 'METHOD', 'public void old()', '6.1'),
                       (?, 'org.example.Bean', 'METHOD', 'public void next()', '6.2'),
                       (?, 'org.example.Bean', 'METHOD', 'public void old()', '6.2')
                """, source, source, source);
        jdbc.update("""
                UPDATE knowledge_api_symbol SET change_type = 'REMOVED'
                WHERE source_id = ? AND signature = 'public void old()' AND spring_version = '6.2'
                """, source);
        assertThat(queries.compareVersions("org.example.Bean", "6.1", "6.2"))
                .extracting(KnowledgeQueries.VersionChange::changeType).containsExactly("REMOVED", "ADDED");
        assertThatThrownBy(() -> queries.compareVersions("org.example.Bean", "6.1", "7.0"))
                .hasMessageContaining("404");
    }

    private UUID topic(String name) {
        return jdbc.queryForObject("INSERT INTO knowledge_topic(name) VALUES (?) RETURNING id",
                UUID.class, "phase2-" + name);
    }
    record Auth(String accessToken) {}
}
