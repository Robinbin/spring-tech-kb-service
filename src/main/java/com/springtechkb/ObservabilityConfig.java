package com.springtechkb;

import java.util.Collections;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.time.Duration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * A5 可观测性基座配置。
 *
 * <p>Spring Boot 4 移除了内置的 Micrometer Tracing 自动配置（Boot 3.x 中由
 * {@code spring-boot-actuator-autoconfigure} 提供），此处手动装配 OTel SDK + Tracer bridge。
 * Prometheus 指标导出与 Spring AI GenAI 语义约定观测由 pom.xml 中的三项依赖自动生效：
 * <ol>
 *   <li>{@code micrometer-tracing-bridge-otel} — Micrometer Tracing → OTel bridge</li>
 *   <li>{@code opentelemetry-exporter-otlp} — OTLP span 导出到 Jaeger / Grafana Tempo</li>
 *   <li>{@code micrometer-registry-prometheus} — /actuator/prometheus 端点</li>
 * </ol>
 */
@Configuration
class ObservabilityConfig {

    @Bean
    MeterRegistryCustomizer<MeterRegistry> applicationCommonTags(
            @Value("${spring.application.name}") String appName,
            @Value("${springtechkb.runtime.role:all}") String runtimeRole) {
        return registry -> registry.config().commonTags(
                "application", appName, "role", runtimeRole);
    }

    // Boot 4 不再自动创建 OpenTelemetry bean，需手动构建 SDK 并注册到 Spring 容器。
    // 不调用 buildAndRegisterGlobal() 以避免多 Spring 上下文（测试场景）冲突。
    @Bean(destroyMethod = "close")
    OpenTelemetry openTelemetry(
            @Value("${management.otlp.tracing.endpoint:http://localhost:4318/v1/traces}") String endpoint,
            @Value("${management.tracing.sampling.probability:1.0}") double samplingProbability,
            Environment environment) {

        Sampler sampler = samplingProbability >= 1.0 ? Sampler.alwaysOn()
                : samplingProbability <= 0.0 ? Sampler.alwaysOff()
                : Sampler.traceIdRatioBased(samplingProbability);

        OtlpHttpSpanExporter langfuseExporter = null;
        if (environment.getProperty("springtechkb.langfuse.enabled", Boolean.class, false)) {
            String base = environment.getRequiredProperty("springtechkb.langfuse.base-url");
            URI uri = URI.create(base);
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !java.util.Set.of("https", "http").contains(uri.getScheme())
                    || (!"https".equals(uri.getScheme())
                    && !java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()))) {
                throw new IllegalArgumentException("Langfuse requires HTTPS, or HTTP on loopback for local development");
            }
            String publicKey = environment.getRequiredProperty("springtechkb.langfuse.public-key");
            String secretKey = environment.getRequiredProperty("springtechkb.langfuse.secret-key");
            if (publicKey.isBlank() || secretKey.isBlank()) {
                throw new IllegalArgumentException("Langfuse is enabled but API keys are missing");
            }
            String authorization = Base64.getEncoder().encodeToString(
                    (publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
            langfuseExporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(base.replaceAll("/+$", "") + "/api/public/otel/v1/traces")
                    .addHeader("Authorization", "Basic " + authorization)
                    .addHeader("x-langfuse-ingestion-version", "4")
                    .setTimeout(Duration.ofSeconds(5)).build();
        }
        var provider = SdkTracerProvider.builder()
                .setResource(io.opentelemetry.sdk.resources.Resource.getDefault().merge(
                        io.opentelemetry.sdk.resources.Resource.create(io.opentelemetry.api.common.Attributes.of(
                                io.opentelemetry.api.common.AttributeKey.stringKey("service.name"),
                                environment.getProperty("spring.application.name", "spring-tech-kb-service")))))
                .addSpanProcessor(BatchSpanProcessor.builder(
                        OtlpHttpSpanExporter.builder().setEndpoint(endpoint).build()).build())
                .setSampler(sampler);
        if (langfuseExporter != null) {
            provider.addSpanProcessor(BatchSpanProcessor.builder(langfuseExporter).build());
        }
        SdkTracerProvider tracerProvider = provider.build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(TextMapPropagator.composite(
                        W3CTraceContextPropagator.getInstance(),
                        W3CBaggagePropagator.getInstance())))
                .build();
    }

    @Bean
    Tracer micrometerTracer(OpenTelemetry openTelemetry) {
        OtelCurrentTraceContext currentTraceContext = new OtelCurrentTraceContext();
        OtelBaggageManager baggageManager = new OtelBaggageManager(
                currentTraceContext, Collections.emptyList(), Collections.emptyList());
        return new OtelTracer(
                openTelemetry.getTracer("spring-tech-kb-service"),
                currentTraceContext,
                event -> {},
                baggageManager);
    }
}
