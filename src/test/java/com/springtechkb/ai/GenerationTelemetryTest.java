package com.springtechkb.ai;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;

class GenerationTelemetryTest {
    @Test
    void exportsParentChildSpansWithPromptProvenanceButNoSensitiveContent() {
        List<SpanData> spans = new ArrayList<>();
        SpanExporter exporter = new SpanExporter() {
            public CompletableResultCode export(Collection<SpanData> batch) { spans.addAll(batch); return CompletableResultCode.ofSuccess(); }
            public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };
        try (var sdk = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()).build()) {
            var prompt = new PromptCatalog.Prompt("learning-plan", "2", "private system instructions");
            String result = new GenerationTelemetry(sdk).observe("learning-plan", "caller", prompt, () -> {
                assertThat(GenerationTelemetry.currentPrompt()).isEqualTo(prompt);
                return "ok";
            });
            assertThat(result).isEqualTo("ok");
            assertThat(spans).extracting(SpanData::getName).containsExactly("prompt.select", "gen_ai.generate", "learning-plan");
            SpanData root = spans.get(2);
            assertThat(spans.get(0).getParentSpanId()).isEqualTo(root.getSpanId());
            assertThat(spans.get(1).getParentSpanId()).isEqualTo(root.getSpanId());
            assertThat(spans.get(1).getAttributes().get(AttributeKey.stringKey("springkb.prompt.version"))).isEqualTo("2");
            assertThat(spans.toString()).doesNotContain("private system instructions");
            assertThat(GenerationTelemetry.currentPrompt()).isNull();
        }
    }
}
