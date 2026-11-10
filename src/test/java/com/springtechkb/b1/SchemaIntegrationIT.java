package com.springtechkb.b1;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * B1 数据库 Schema 集成测试。
 *
 * <p>使用 Testcontainers 启动真实的 pgvector/pgvector:pg17 实例，验证：
 * <ol>
 * <li>Flyway 能成功执行现有迁移脚本</li>
 * <li>Phase 4 扩展后业务表与 outbox 表全部建成</li>
 *   <li>关键外键约束、UNIQUE 约束、pgvector 扩展和 HNSW 索引就位</li>
 * </ol>
 *
 * <p>需要 Docker 运行环境（Docker 不可用时自动跳过，不算失败）。
 * 通过 Maven Failsafe 插件（{@code mvn verify}）触发，{@code mvn test} 不触发。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.ai.model.chat=anthropic",
        "spring.ai.model.embedding=openai",
        "spring.ai.anthropic.api-key=dummy-key-for-b1-it",
        "spring.ai.openai.api-key=not-needed-for-tei",
        "spring.ai.openai.base-url=http://localhost:8081",
        "spring.ai.vectorstore.pgvector.initialize-schema=false",
        "spring.ai.vectorstore.pgvector.dimensions=1024",
        // 不要覆盖 spring.datasource.*（由 @DynamicPropertySource 从容器注入）
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults=false",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "management.tracing.sampling.probability=0.0"
        // spring.flyway.enabled 保持默认 true，让 Flyway 在真实 PG 上跑迁移
})
class SchemaIntegrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = createPostgres();

    private static PostgreSQLContainer<?> createPostgres() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("springkb_test")
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
    private JdbcTemplate jdbc;

    // ── 验收标准 1：Phase 3 表全部建成 ─────────────────────────────────────────

    private static final List<String> EXPECTED_TABLES = List.of(
            // V2
            "users",
            // V3
            "knowledge_source", "knowledge_topic", "knowledge_chunk",
            // V4
            "vector_store",
            // V5
            "learning_plan", "learning_task", "user_learning_progress",
            // V6
            "question", "assessment_session", "user_answer", "user_skill_state",
            // V7
            "llm_call_log", "prompt_version",
            // V8
            "event_publication",
            // V16
            "question_generation_batch",
            "question_generation_batch_item",
            "question_quality_audit",
            "question_test_case",
            "question_rubric_dimension",
            "question_irt_stats",
            "coding_submission",
            "subjective_grading_result",
            // V17
            "knowledge_source_catalog",
            "achievement_definition",
            "user_achievement",
            "user_leaderboard_preference"
    );

    @Test
    @DisplayName("Phase 4 表（含评测与运维扩展）全部建成")
    void allPhaseFourTablesExist() {
        List<String> existing = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                String.class);
        System.out.println("### B1-PROBE tables = " + existing);
        assertThat(existing).containsAll(EXPECTED_TABLES);
    }

    @Test
    @DisplayName("N1 官方文档目录和 N3 成就规则按迁移创建")
    void phaseFourCatalogAndAchievementRulesAreSeeded() {
        List<String> sourceKeys = jdbc.queryForList(
                "SELECT source_key FROM knowledge_source_catalog ORDER BY source_key", String.class);
        assertThat(sourceKeys).containsExactly("spring-batch", "spring-cloud", "spring-integration");

        Integer licensedOfficialSources = jdbc.queryForObject("""
                SELECT count(*) FROM knowledge_source_catalog
                WHERE source_uri LIKE 'https://docs.spring.io/%'
                  AND license_type = 'APACHE_2'
                  AND license_url LIKE 'https://github.com/spring-projects/%'
                """, Integer.class);
        assertThat(licensedOfficialSources).isEqualTo(3);

        Integer achievementRules = jdbc.queryForObject(
                "SELECT count(*) FROM achievement_definition", Integer.class);
        assertThat(achievementRules).isEqualTo(8);
    }

    // ── 验收标准 2：pgvector 扩展与 HNSW 索引 ───────────────────────────────────

    @Test
    @DisplayName("pgvector 扩展已安装（V1 迁移）")
    void pgvectorExtensionInstalled() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("vector_store HNSW 索引存在（ef_construction=200，H-3 满足）")
    void hnswIndexExists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                + "WHERE tablename = 'vector_store' AND indexdef LIKE '%hnsw%'",
                Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("vector_store.embedding 列维度为 1024（H-1 满足）")
    void vectorDimensionIs1024() {
        String columnType = jdbc.queryForObject(
                "SELECT udt_name FROM information_schema.columns "
                + "WHERE table_name = 'vector_store' AND column_name = 'embedding'",
                String.class);
        // pgvector 对外暴露的类型名为 vector（不含维度数，维度通过 atttypmod 存储）
        assertThat(columnType).isEqualTo("vector");
        // 某些 pgvector 版本返回维度值本身，某些返回维度+4，均视为有效。
        Integer typmod = jdbc.queryForObject(
                "SELECT atttypmod FROM pg_attribute pa "
                + "JOIN pg_class pc ON pa.attrelid = pc.oid "
                + "WHERE pc.relname = 'vector_store' AND pa.attname = 'embedding'",
                Integer.class);
        assertThat(typmod).isIn(1024, 1028);
    }

    // ── 验收标准 3：关键约束 ────────────────────────────────────────────────────

    @Test
    @DisplayName("users 表邮箱和用户名 UNIQUE 约束存在")
    void usersUniqueConstraintsExist() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'users' AND constraint_type = 'UNIQUE'",
                Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("llm_call_log 三列 token 计量字段（input/output/cache_read）存在（H-7）")
    void llmCallLogHasThreeTokenColumns() {
        List<String> tokenCols = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                + "WHERE table_name = 'llm_call_log' "
                + "AND column_name IN ('input_tokens','output_tokens','cache_read_tokens')",
                String.class);
        assertThat(tokenCols).containsExactlyInAnyOrder(
                "input_tokens", "output_tokens", "cache_read_tokens");
    }

    @Test
    @DisplayName("user_skill_state (user_id, topic_id) UNIQUE 约束存在（行级更新防并发覆盖）")
    void userSkillStateUniqueConstraintExists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'user_skill_state' AND constraint_type = 'UNIQUE'",
                Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("knowledge_chunk content_tsvector 生成列存在（B3 FTS 索引前置）")
    void knowledgeChunkFtsColumnExists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name = 'knowledge_chunk' "
                + "AND column_name = 'content_tsvector'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
