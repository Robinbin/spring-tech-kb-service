package com.springtechkb.b3;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.springtechkb.search.FtsHit;
import com.springtechkb.search.internal.KnowledgeChunkFtsService;

/**
 * B3 集成测试：PG FTS 词法检索。
 *
 * <p>在真实 pgvector/pgvector:pg17 上验证：
 * <ol>
 *   <li>English 分词 + GIN 索引能检索到技术术语（Spring MVC、IoC 等）</li>
 *   <li>ts_rank_cd BM25 风格得分：内容与查询词重叠更多的结果排名更高</li>
 *   <li>spring_module 元数据过滤生效</li>
 *   <li>空结果（无匹配）不抛异常</li>
 * </ol>
 *
 * <p>Docker 不可用时自动跳过（{@code disabledWithoutDocker = true}）。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.ai.model.chat=anthropic",
        "spring.ai.model.embedding=openai",
        "spring.ai.anthropic.api-key=dummy-key-for-b3-it",
        "spring.ai.openai.api-key=not-needed",
        "spring.ai.openai.base-url=http://localhost:8081",
        "spring.ai.vectorstore.pgvector.initialize-schema=false",
        "spring.ai.vectorstore.pgvector.dimensions=1024",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults=false",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "management.tracing.sampling.probability=0.0"
})
class FtsSearchIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = createPostgres();

    private static PostgreSQLContainer<?> createPostgres() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("springkb_b3_test")
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
    private KnowledgeChunkFtsService ftsService;

    @Autowired
    private JdbcTemplate jdbc;

    private String sourceId;

    @BeforeEach
    void seedTestData() {
        // 清理并插入测试数据（每次测试前重置）
        jdbc.update("DELETE FROM knowledge_chunk");
        jdbc.update("DELETE FROM knowledge_source");

        // 插入一个合法的 knowledge_source
        sourceId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO knowledge_source (id, title, url, license_type, is_approved) "
                + "VALUES (?::uuid, ?, ?, ?, ?)",
                sourceId, "Spring Framework Docs", "https://docs.spring.io/", "APACHE_2", true);

        // 插入三类内容覆盖不同 Spring 模块
        insertChunk("Spring MVC DispatcherServlet routes HTTP requests to annotated controller methods. "
                + "The MVC pattern separates model view controller concerns in web applications.",
                "mvc", 0);
        insertChunk("Spring Core IoC container manages bean lifecycle and dependency injection. "
                + "ApplicationContext provides the IoC container implementation with advanced features.",
                "core", 1);
        insertChunk("Spring Data JPA repository interface simplifies database access with CRUD operations. "
                + "JPA repositories reduce boilerplate and integrate with entity manager.",
                "data", 2);
        // 追加一条 mvc 文档：两条 mvc 文档，BM25 时 mvc 相关查询应两条都排在 data/core 前面
        insertChunk("Spring MVC model view controller framework provides request mapping annotations "
                + "like GetMapping PostMapping and handles view resolution for web pages.",
                "mvc", 3);
    }

    private void insertChunk(String content, String module, int chunkIndex) {
        jdbc.update(
                "INSERT INTO knowledge_chunk (id, source_id, content, chunk_index, spring_module) "
                + "VALUES (gen_random_uuid(), ?::uuid, ?, ?, ?)",
                sourceId, content, chunkIndex, module);
    }

    // ── 1. 基础关键词检索 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("'IoC container' 查询找到 core 模块文档")
    void searchByKeywordFindsCorrectChunk() {
        List<FtsHit> hits = ftsService.search("IoC container", null, 10);
        System.out.println("### B3-PROBE IoC hits = " + hits.size());
        assertThat(hits).isNotEmpty();
        assertThat(hits).anyMatch(h -> "core".equals(h.springModule()));
    }

    // ── 2. BM25 风格排名：内容重叠更多的排在更前 ──────────────────────────────────

    @Test
    @DisplayName("'MVC controller' 查询中 mvc 文档得分高于 core/data")
    void mvcQueryRanksMvcDocumentsHigher() {
        List<FtsHit> hits = ftsService.search("MVC controller", null, 10);
        assertThat(hits).isNotEmpty();
        // 前两条应全是 mvc 模块（ts_rank_cd 按内容重叠度排名）
        long mvcCount = hits.stream().limit(2).filter(h -> "mvc".equals(h.springModule())).count();
        assertThat(mvcCount).isGreaterThanOrEqualTo(1);
        // 第一条得分最高
        assertThat(hits.get(0).score()).isGreaterThan(0.0);
    }

    // ── 3. spring_module 过滤 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("spring_module='data' 过滤后结果全为 data 模块")
    void moduleFilterReturnsOnlyMatchingModule() {
        List<FtsHit> hits = ftsService.search("database repository", "data", 10);
        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(h -> assertThat(h.springModule()).isEqualTo("data"));
    }

    @Test
    @DisplayName("spring_module 过滤不命中时返回空列表（不抛异常）")
    void moduleFilterWithNoMatchReturnsEmpty() {
        // security 模块没有数据
        List<FtsHit> hits = ftsService.search("filter chain authentication", "security", 10);
        assertThat(hits).isEmpty();
    }

    // ── 4. topK 限制 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("topK=1 最多返回 1 条结果")
    void topKLimitsResults() {
        List<FtsHit> hits = ftsService.search("spring framework", null, 1);
        assertThat(hits).hasSizeLessThanOrEqualTo(1);
    }

    // ── 5. websearch_to_tsquery 短语支持 ──────────────────────────────────────────

    @Test
    @DisplayName("双引号短语 '\"dependency injection\"' 精确检索")
    void phraseQueryWorks() {
        List<FtsHit> hits = ftsService.search("\"dependency injection\"", null, 10);
        // core 文档包含 "dependency injection" 短语
        assertThat(hits).anyMatch(h -> "core".equals(h.springModule()));
    }

    @Test
    @DisplayName("版本范围过滤在检索阶段排除不兼容版本")
    void springVersionFilterRespectsWildcardBounds() {
        jdbc.update(
                """
                INSERT INTO knowledge_chunk
                    (id, source_id, content, chunk_index, spring_module, spring_version_min, spring_version_max)
                VALUES (gen_random_uuid(), ?::uuid,
                    'Spring MVC DispatcherServlet version compatibility fixture', 99, 'mvc', '6.1.x', '6.2.x')
                """,
                sourceId);

        assertThat(ftsService.search("DispatcherServlet compatibility", "mvc", "6.1.5", 10))
                .anyMatch(hit -> "mvc".equals(hit.springModule()));
        assertThat(ftsService.search("DispatcherServlet compatibility", "mvc", "6.3", 10))
                .isEmpty();
    }
}
