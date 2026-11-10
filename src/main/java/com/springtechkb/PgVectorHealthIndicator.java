package com.springtechkb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * B2：pgvector 启动期自检健康指示器。
 *
 * <p>A1 发现 3：{@code PgVectorStore} bean 装配不触达数据库（fail-late）——
 * 配置错误只在首次查询时才暴露。此健康指示器在运行期持续暴露三项关键检查项，
 * 让运维人员在对话请求到来之前发现 pgvector 配置问题。
 *
 * <h2>检查项</h2>
 * <ol>
 *   <li><b>extension</b>：{@code vector} 扩展已安装（H-1 前提）</li>
 *   <li><b>dimensions</b>：{@code vector_store.embedding} 列维度 = 1024（H-1）</li>
 *   <li><b>index</b>：{@code vector_store} 表上存在 HNSW 索引（H-3）</li>
 * </ol>
 *
 * <p>数据库不可达时降级为 {@code DOWN}，不抛出异常。
 * 健康端点路径：{@code /actuator/health/pgVector}（Spring Boot 去除 HealthIndicator 后缀）。
 */
@Component
public class PgVectorHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(PgVectorHealthIndicator.class);

    // H-1：索引维度约束上限 2000，项目选 1024；atttypmod = dimensions + 4（pgvector 编码）
    private static final int EXPECTED_DIMENSIONS = 1024;
    private static final int EXPECTED_TYPMOD = EXPECTED_DIMENSIONS + 4;

    private final JdbcTemplate jdbc;

    PgVectorHealthIndicator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        try {
            String extensionStatus = checkExtension();
            int actualDimensions = checkDimensions();
            int indexCount = checkHnswIndex();

            Health.Builder builder = (extensionStatus != null && actualDimensions == EXPECTED_DIMENSIONS && indexCount > 0)
                    ? Health.up()
                    : Health.down();

            builder
                    .withDetail("extension.vector", extensionStatus != null ? "installed" : "MISSING")
                    .withDetail("dimensions.expected", EXPECTED_DIMENSIONS)
                    .withDetail("dimensions.actual", actualDimensions)
                    .withDetail("hnsw.index.count", indexCount);

            if (actualDimensions != EXPECTED_DIMENSIONS) {
                builder.withDetail("dimensions.mismatch",
                        "expected " + EXPECTED_DIMENSIONS + " but found " + actualDimensions);
            }

            return builder.build();

        } catch (DataAccessException e) {
            log.warn("pgvector health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }

    private String checkExtension() {
        return jdbc.queryForObject(
                "SELECT extname FROM pg_extension WHERE extname = 'vector'",
                String.class);
    }

    private int checkDimensions() {
        Integer typmod = jdbc.queryForObject(
                "SELECT atttypmod FROM pg_attribute pa "
                + "JOIN pg_class pc ON pa.attrelid = pc.oid "
                + "WHERE pc.relname = 'vector_store' AND pa.attname = 'embedding'",
                Integer.class);
        if (typmod == null) return -1;
        return typmod - 4; // pgvector: atttypmod = dimensions + 4
    }

    private int checkHnswIndex() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                + "WHERE tablename = 'vector_store' AND indexdef LIKE '%hnsw%'",
                Integer.class);
        return count != null ? count : 0;
    }
}
