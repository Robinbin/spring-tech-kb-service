package com.springtechkb.b2;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.springtechkb.PgVectorHealthIndicator;

/**
 * B2 集成测试：pgvector 索引调优 + 启动期自检。
 *
 * <p>验证：
 * <ol>
 *   <li>Flyway V1–V8 在真实 pgvector/pgvector:pg17 上执行成功</li>
 *   <li>{@link PgVectorHealthIndicator} 报告 UP，检查项（扩展 / 维度 / 索引）全部通过</li>
 *   <li>带元数据过滤的向量检索可以正确返回结果（H-2 不塌陷）</li>
 *   <li>{@code SET LOCAL hnsw.iterative_scan = relaxed_order} 在真实数据库上无错误</li>
 * </ol>
 *
 * <p>Docker 不可用时自动跳过（{@code disabledWithoutDocker = true}）。
 * 由 Maven Failsafe（{@code mvn verify}）触发。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.ai.model.chat=anthropic",
        "spring.ai.model.embedding=openai",
        "spring.ai.anthropic.api-key=dummy-key-for-b2-it",
        "spring.ai.openai.api-key=not-needed-for-tei",
        "spring.ai.openai.base-url=http://localhost:8081",
        "spring.ai.vectorstore.pgvector.initialize-schema=false",
        "spring.ai.vectorstore.pgvector.dimensions=1024",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults=false",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "management.tracing.sampling.probability=0.0"
})
class PgVectorB2IT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = createPostgres();

    private static PostgreSQLContainer<?> createPostgres() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("springkb_b2_test")
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
    private VectorStore vectorStore;          // → HnswAwarePgVectorStore（@Primary）

    @Autowired
    private PgVectorHealthIndicator healthIndicator;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearVectorStore() {
        jdbc.update("DELETE FROM vector_store");
    }

    // ── 1. 健康指示器 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("PgVectorHealthIndicator 报告 UP：扩展已安装、维度=1024、HNSW 索引存在")
    void healthIndicatorReportsUp() {
        Health health = healthIndicator.health();
        System.out.println("### B2-PROBE health = " + health);
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("extension.vector", "installed");
        assertThat(health.getDetails()).containsEntry("dimensions.actual", 1024);
        assertThat((Integer) health.getDetails().get("hnsw.index.count")).isGreaterThanOrEqualTo(1);
    }

    // ── 2. iterative_scan 设置不报错 ──────────────────────────────────────────

    @Test
    @DisplayName("SET LOCAL hnsw.iterative_scan 在事务中可执行（H-2）")
    void hnswIterativeScanCanBeSet() {
        // 直接执行 SET LOCAL 验证参数名可用（pgvector 0.8+ 才引入此参数）
        assertThat(vectorStore.getName()).startsWith("HnswAware[");

        // 调用 similaritySearch 触发 HnswAwarePgVectorStore 内部的 SET LOCAL
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("test query")
                        .topK(1)
                        .similarityThresholdAll()
                        .build());
        // 空库返回空列表，不抛异常即验证通过
        assertThat(results).isNotNull();
    }

    // ── 3. 带元数据过滤的向量检索（H-2 不塌陷） ───────────────────────────────

    @Test
    @DisplayName("带 spring_module 元数据过滤的相似度检索返回正确结果（H-2 满足）")
    void metadataFilteredSearchReturnsCorrectResults() {
        // 插入 5 条 mvc 文档 + 5 条 core 文档
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            docs.add(new Document("Spring MVC controller mapping " + i,
                    Map.of("spring_module", "mvc", "chunk_index", i)));
        }
        for (int i = 0; i < 5; i++) {
            docs.add(new Document("Spring Core IoC container " + i,
                    Map.of("spring_module", "core", "chunk_index", i)));
        }
        vectorStore.add(docs);

        // 只查 mvc
        List<Document> mvcResults = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("spring mvc annotation driven controller")
                        .topK(10)
                        .similarityThresholdAll()
                        .filterExpression("spring_module == 'mvc'")
                        .build());

        System.out.println("### B2-PROBE mvc results = " + mvcResults.size());
        assertThat(mvcResults).isNotEmpty();
        assertThat(mvcResults).allSatisfy(doc ->
                assertThat(doc.getMetadata().get("spring_module")).isEqualTo("mvc"));

        // 只查 core
        List<Document> coreResults = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("spring core ioc dependency injection")
                        .topK(10)
                        .similarityThresholdAll()
                        .filterExpression("spring_module == 'core'")
                        .build());

        System.out.println("### B2-PROBE core results = " + coreResults.size());
        assertThat(coreResults).isNotEmpty();
        assertThat(coreResults).allSatisfy(doc ->
                assertThat(doc.getMetadata().get("spring_module")).isEqualTo("core"));
    }

    @Test
    @DisplayName("无过滤条件的检索可以跨 spring_module 返回结果")
    void unfilteredSearchReturnsResultsAcrossModules() {
        vectorStore.add(List.of(
                new Document("Spring MVC DispatcherServlet", Map.of("spring_module", "mvc")),
                new Document("Spring Core ApplicationContext", Map.of("spring_module", "core"))
        ));

        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("spring framework context")
                        .topK(5)
                        .similarityThresholdAll()
                        .build());

        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
    }

    // ── Stub EmbeddingModel（返回归一化随机向量，避免全零导致 NaN cosine distance）──

    @TestConfiguration
    static class StubEmbeddingConfig {

        @Bean
        @Primary
        EmbeddingModel stubEmbeddingModel() {
            return new NormalizedRandomEmbeddingModel(1024);
        }
    }

    static class NormalizedRandomEmbeddingModel implements EmbeddingModel {

        private static final Random RNG = new Random(12345L); // deterministic seed
        private final int dims;

        NormalizedRandomEmbeddingModel(int dims) {
            this.dims = dims;
        }

        @Override
        public int dimensions() {
            return dims;
        }

        @Override
        public float[] embed(String text) {
            float[] v = new float[dims];
            double norm = 0;
            synchronized (RNG) {
                for (int i = 0; i < dims; i++) {
                    v[i] = (float) RNG.nextGaussian();
                    norm += (double) v[i] * v[i];
                }
            }
            float scale = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < dims; i++) v[i] *= scale;
            return v;
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                embeddings.add(new Embedding(embed(request.getInstructions().get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }
    }
}
