package com.springtechkb.a1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A1 整应用上下文启动验证。
 *
 * <p>加载完整的 {@code @SpringBootApplication}（Web + Actuator + Anthropic + OpenAI/TEI
 * + pgvector + MCP Server + Modulith + JPA），验证全部自动配置可共存且三个核心 AI bean 可注入。
 * 不发起任何真实外部调用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=openai",
        "spring.ai.anthropic.api-key=dummy-key-for-a1-probe",
        "spring.ai.openai.api-key=not-needed-for-tei",
        "spring.ai.openai.base-url=http://localhost:8080",
        "spring.ai.vectorstore.pgvector.initialize-schema=false",
        "spring.ai.vectorstore.pgvector.dimensions=1024",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/nonexistent",
        "spring.jpa.hibernate.ddl-auto=none",
        // 无库环境下避免 Hibernate 在启动期探测数据库元数据
        "spring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults=false",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        // Flyway 需要可用的数据库连接；此测试使用不存在的端口，禁用迁移
        "spring.flyway.enabled=false"
})
class ApplicationContextStartupTest {

    @Autowired
    @Qualifier("liteChatModel")
    private ChatModel liteChatModel;

    @Autowired
    @Qualifier("standardChatModel")
    private ChatModel standardChatModel;

    @Autowired
    @Qualifier("expertChatModel")
    private ChatModel expertChatModel;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private VectorStore vectorStore;

    @Test
    @DisplayName("完整应用上下文可启动，三个核心 AI bean 均注入成功")
    void contextLoadsWithAllCoreBeans() {
        assertThat(liteChatModel).isNotNull();
        assertThat(standardChatModel).isNotNull();
        assertThat(expertChatModel).isNotNull();
        assertThat(liteChatModel).isNotSameAs(standardChatModel).isNotSameAs(expertChatModel);
        assertThat(embeddingModel).isNotNull();
        assertThat(vectorStore).isNotNull();
        System.out.println("### A1-PROBE full context started; VectorStore impl = "
                + vectorStore.getClass().getName());
    }
}
