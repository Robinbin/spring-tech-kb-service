package com.springtechkb.search.internal;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * B2：向量存储 Bean 配置。
 *
 * <p>将 Spring AI 自动配置的 {@link PgVectorStore}（bean 名称 {@code vectorStore}）
 * 包装进 {@link HnswAwarePgVectorStore}，并将后者注册为 {@code @Primary VectorStore}。
 *
 * <p>注入点：
 * <ul>
 *   <li>注入 {@code VectorStore} → 得到 {@link HnswAwarePgVectorStore}（含 H-2 会话参数）</li>
 *   <li>注入 {@code PgVectorStore} → 仍得到原始 auto-configured bean（无 H-2 包装）</li>
 * </ul>
 */
@Configuration
class VectorStoreConfig {

    /**
     * 包装自动配置的 {@link PgVectorStore}，使每次相似度检索自动开启
     * {@code hnsw.iterative_scan = relaxed_order}（H-2）。
     *
     * <p>注意：此处注入的是 {@code PgVectorStore}（具体类型），而非 {@code VectorStore}
     * 接口，以避免 {@code @Primary} 循环依赖。
     */
    @Bean
    @Primary
    VectorStore hnswAwareVectorStore(PgVectorStore pgVectorStore, JdbcTemplate jdbc) {
        return new HnswAwarePgVectorStore(pgVectorStore, jdbc);
    }
}
