package com.springtechkb;

import static org.assertj.core.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.springtechkb.ai.GenerationTelemetry;
import com.springtechkb.ai.PromptCatalog;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class LangfuseExportTest {
    @Test
    void exportsMetadataOnlyToConfiguredLocalLangfuseWithBasicAuth() throws Exception {
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> ingestionVersion = new AtomicReference<>();
        AtomicReference<byte[]> payload = new AtomicReference<>();
        receiver.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (exchange.getRequestURI().getPath().equals("/api/public/otel/v1/traces")) {
                auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                ingestionVersion.set(exchange.getRequestHeaders().getFirst("x-langfuse-ingestion-version"));
                payload.set(body);
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        receiver.start();
        String base = "http://127.0.0.1:" + receiver.getAddress().getPort();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("springtechkb.langfuse.enabled", "true")
                .withProperty("springtechkb.langfuse.base-url", base)
                .withProperty("springtechkb.langfuse.public-key", "test-public-key")
                .withProperty("springtechkb.langfuse.secret-key", "test-secret-key");
        try (OpenTelemetrySdk telemetry = (OpenTelemetrySdk) new ObservabilityConfig()
                .openTelemetry(base + "/v1/traces", 1.0, environment)) {
            new GenerationTelemetry(telemetry).observe("learning-plan", "private-user",
                    new PromptCatalog.Prompt("learning-plan", "2", "private instructions not exported"), () -> true);
            assertThat(telemetry.getSdkTracerProvider().forceFlush().join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
            assertThat(auth.get()).isEqualTo("Basic " + Base64.getEncoder().encodeToString(
                    "test-public-key:test-secret-key".getBytes(StandardCharsets.UTF_8)));
            assertThat(ingestionVersion.get()).isEqualTo("4");
            assertThat(payload.get()).isNotEmpty();
            String wire = new String(payload.get(), StandardCharsets.UTF_8);
            assertThat(wire).contains("learning-plan", "springkb.prompt.checksum")
                    .doesNotContain("private-user", "private instructions not exported");
        } finally { receiver.stop(0); }
    }

    @Test
    void rejectsEnabledExportWithoutKeysOrOverCleartextRemoteTransport() {
        var noKeys = new MockEnvironment().withProperty("springtechkb.langfuse.enabled", "true")
                .withProperty("springtechkb.langfuse.base-url", "https://example.invalid");
        assertThatThrownBy(() -> new ObservabilityConfig().openTelemetry("http://localhost:4318/v1/traces", 0, noKeys))
                .isInstanceOf(IllegalStateException.class);
        var insecure = new MockEnvironment().withProperty("springtechkb.langfuse.enabled", "true")
                .withProperty("springtechkb.langfuse.base-url", "http://example.invalid");
        assertThatThrownBy(() -> new ObservabilityConfig().openTelemetry("http://localhost:4318/v1/traces", 0, insecure))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
