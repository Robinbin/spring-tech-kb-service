package com.springtechkb.a1;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;

/**
 * A1 技术栈可行性验证闸门。
 *
 * <p>验证 Spring Boot 4.1.2 + Spring AI 2.0.2 的自动配置能否在同一上下文内装配出
 * 架构所依赖的三个核心 bean：ChatClient、EmbeddingModel、VectorStore。
 *
 * <p>使用 {@link ApplicationContextRunner} 做隔离装配，不触发任何真实网络调用或
 * 数据库连接 —— 真实调用验证属 A1-c，需 Docker 环境（见 A1 验证报告）。
 */
class StackCompatibilityTest {

    /** Anthropic 主力 LLM 自动配置。 */
    @Test
    @DisplayName("Anthropic ChatModel + ChatClient.Builder 可装配")
    void anthropicChatClientAssembles() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RestClientAutoConfiguration.class,
                        WebClientAutoConfiguration.class,
                        ToolCallingAutoConfiguration.class,
                        AnthropicChatAutoConfiguration.class,
                        ChatClientAutoConfiguration.class))
                .withPropertyValues("spring.ai.anthropic.api-key=dummy-key-for-a1-probe")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ChatModel.class);
                    assertThat(context).hasSingleBean(ChatClient.Builder.class);
                });
    }

    /** Embedding 走 OpenAI 兼容端点（自托管 TEI）。 */
    @Test
    @DisplayName("EmbeddingModel 可装配并指向本地 TEI 端点")
    void teiEmbeddingModelAssembles() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RestClientAutoConfiguration.class,
                        WebClientAutoConfiguration.class,
                        OpenAiEmbeddingAutoConfiguration.class))
                .withPropertyValues(
                        "spring.ai.openai.api-key=not-needed-for-tei",
                        "spring.ai.openai.base-url=http://localhost:8080",
                        "spring.ai.openai.embedding.options.model=BAAI/bge-m3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EmbeddingModel.class);
                });
    }

    /**
     * pgvector 向量存储自动配置。
     *
     * <p><b>这条测试只证明自动配置被识别、1024 维 + HNSW 属性可绑定、bean 可创建。</b>
     * 已由 {@link PgVectorAssemblyProbeTest} 判定：PgVectorStore 的 bean 创建<b>不需要</b>
     * 真实数据库连接（连接推迟到首次查询）。因此本测试通过<b>不构成</b> pgvector 可用性
     * 的证据 —— 硬约束 H-1（维度上限）、H-2（iterative_scan）、H-3（ef_construction）
     * 必须在 A3 用真实 pgvector 实例验证。
     */
    @Test
    @DisplayName("pgvector 自动配置被识别；1024 维 + HNSW 属性可绑定（非可用性证据）")
    void pgVectorStoreAutoConfigurationIsRecognised() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class,
                        PgVectorStoreAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://localhost:5432/springkb",
                        "spring.datasource.username=postgres",
                        "spring.datasource.password=postgres",
                        "spring.ai.vectorstore.pgvector.initialize-schema=false",
                        "spring.ai.vectorstore.pgvector.dimensions=1024",
                        "spring.ai.vectorstore.pgvector.index-type=hnsw",
                        "spring.ai.vectorstore.pgvector.distance-type=cosine_distance")
                .withBean(EmbeddingModel.class, () -> new StubEmbeddingModel(1024))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(VectorStore.class);
                });
    }

}
