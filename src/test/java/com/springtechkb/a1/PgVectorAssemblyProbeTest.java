package com.springtechkb.a1;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 判定性探针：PgVectorStore 的 bean 创建是否需要真实数据库连接。
 *
 * <p>结论直接决定 A1 能声称什么 —— 若 bean 在无库时也能装配，则「上下文启动成功」
 * 不构成 pgvector 可用性的证据，H-1/H-2/H-3 三项硬约束必须留到 A3 用真实实例验证。
 */
class PgVectorAssemblyProbeTest {

    @Test
    @DisplayName("判定：无数据库时 VectorStore bean 是否仍可创建")
    void vectorStoreBeanCreationDoesNotRequireLiveDatabase() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class,
                        PgVectorStoreAutoConfiguration.class))
                .withPropertyValues(
                        // 指向一个确定不存在的端口，排除「碰巧有库」的可能
                        "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/nonexistent",
                        "spring.datasource.username=nobody",
                        "spring.datasource.password=nothing",
                        "spring.ai.vectorstore.pgvector.initialize-schema=false",
                        "spring.ai.vectorstore.pgvector.dimensions=1024",
                        "spring.ai.vectorstore.pgvector.index-type=hnsw",
                        "spring.ai.vectorstore.pgvector.distance-type=cosine_distance")
                .withBean(EmbeddingModel.class, () -> new StubEmbeddingModel(1024))
                .run(context -> {
                    boolean assembled = context.getStartupFailure() == null
                            && context.getBeanNamesForType(VectorStore.class).length == 1;
                    System.out.println("### A1-PROBE pgvector bean assembled without DB = " + assembled);
                    if (!assembled && context.getStartupFailure() != null) {
                        System.out.println("### A1-PROBE startup failure = "
                                + context.getStartupFailure().getClass().getName() + ": "
                                + context.getStartupFailure().getMessage());
                    }
                    // 无条件断言：若通过，证明 bean 创建是惰性的，不触达数据库
                    assertThat(assembled)
                            .as("PgVectorStore bean 在无可用数据库时是否仍被创建")
                            .isTrue();
                });
    }
}
