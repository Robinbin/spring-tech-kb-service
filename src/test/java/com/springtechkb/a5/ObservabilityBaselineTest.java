package com.springtechkb.a5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * A5 可观测性基座验证。
 *
 * <p>验收标准：
 * <ol>
 *   <li>MeterRegistry bean 已配置且不是简单占位实现</li>
 *   <li>Tracer bean（OTel bridge）已注入</li>
 *   <li>/actuator/prometheus 端点可访问，且包含 application 公共标签</li>
 * </ol>
 *
 * <p>与 A1 {@code ApplicationContextStartupTest} 共用同一套无数据库启动配置。
 * 追踪采样率设为 0 避免测试期间尝试连接 OTel collector。
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.ai.model.chat=anthropic",
        "spring.ai.model.embedding=openai",
        "spring.ai.anthropic.api-key=dummy-key-for-a5-probe",
        "spring.ai.openai.api-key=not-needed-for-tei",
        "spring.ai.openai.base-url=http://localhost:8081",
        "spring.ai.vectorstore.pgvector.initialize-schema=false",
        "spring.ai.vectorstore.pgvector.dimensions=1024",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/nonexistent",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults=false",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "management.endpoints.web.exposure.include=health,prometheus",
        // 采样率 0：不产生 span，OTLP 导出器不会尝试连接 collector
        "management.tracing.sampling.probability=0.0",
        // Flyway 需要可用的数据库连接；此测试使用不存在的端口，禁用迁移
        "spring.flyway.enabled=false"
})
class ObservabilityBaselineTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private Tracer tracer;

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("MeterRegistry bean 已配置（非简单占位实现）")
    void meterRegistryIsConfigured() {
        assertThat(meterRegistry).isNotNull();
        String implName = meterRegistry.getClass().getName();
        System.out.println("### A5-PROBE MeterRegistry impl = " + implName);
        // CompositeMeterRegistry（含 Prometheus）或直接 PrometheusMeterRegistry
        assertThat(implName).doesNotContain("SimpleMeterRegistry");
    }

    @Test
    @DisplayName("Tracer bean（Micrometer-OTel bridge）已注入")
    void tracerIsConfigured() {
        assertThat(tracer).isNotNull();
        System.out.println("### A5-PROBE Tracer impl = " + tracer.getClass().getName());
    }

    @Test
    @DisplayName("/actuator/prometheus 端点可访问且携带 application 标签")
    void prometheusEndpointExposesMetrics() {
        ResponseEntity<String> response = RestClient.create("http://localhost:" + port)
                .get()
                .uri("/actuator/prometheus")
                .retrieve()
                .toEntity(String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .as("Prometheus 端点应包含 JVM 堆内存指标")
                .contains("jvm_memory_used_bytes")
                .as("ObservabilityConfig 应注入 application 公共标签")
                .contains("application=\"spring-tech-kb-service\"");
    }
}
