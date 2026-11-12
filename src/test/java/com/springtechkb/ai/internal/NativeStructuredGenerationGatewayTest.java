package com.springtechkb.ai.internal;

import java.time.Duration;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import com.anthropic.models.messages.Model;
import com.springtechkb.ai.ChatTier;
import com.springtechkb.ai.GenerationTelemetry;
import com.springtechkb.ai.LlmCallRecorder;
import com.springtechkb.ai.PromptCatalog;
import com.springtechkb.ai.TokenQuotaService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.opentelemetry.api.OpenTelemetry;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeStructuredGenerationGatewayTest {
    private final ChatModel lite = mock(ChatModel.class);
    private final ChatModel standard = mock(ChatModel.class);
    private final ChatModel expert = mock(ChatModel.class);
    private final TokenQuotaService quota = mock(TokenQuotaService.class);
    private final LlmCallRecorder recorder = mock(LlmCallRecorder.class);
    private final PromptCatalog prompts = (name, caller) -> new PromptCatalog.Prompt(name, "7", "Stable registered instructions");
    private final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
            .minimumNumberOfCalls(1).slidingWindowSize(10).build());

    record Sample(@NotBlank String title, List<Child> children) {}
    record Child(String value) {}
    record Repeated(Child left, Child right) {}

    @BeforeEach
    void setUp() {
        when(quota.selectTier(ChatTier.STANDARD, "caller")).thenReturn(ChatTier.LITE);
    }

    @Test
    void sendsProviderNativeSchemaAndUsesQuotaSelectedModelWithProvenanceAndAccounting() {
        when(lite.call(any(Prompt.class))).thenReturn(response("""
                {"title":"Grounded","children":[{"value":"data"}]}
                """));
        var result = gateway(Duration.ofSeconds(2)).generate("code-example", "caller", "data", Sample.class);
        assertThat(result.value().title()).isEqualTo("Grounded");
        assertThat(result.modelId()).isEqualTo("lite-id");
        assertThat(result.promptVersion()).isEqualTo("code-example:v7");
        ArgumentCaptor<Prompt> request = ArgumentCaptor.forClass(Prompt.class);
        verify(lite).call(request.capture());
        AnthropicChatOptions options = (AnthropicChatOptions) request.getValue().getOptions();
        assertThat(options.getOutputConfig()).isNotNull();
        assertThat(options.getOutputConfig().format()).isPresent();
        assertThat(options.getOutputSchema()).contains("\"title\"", "\"additionalProperties\":false");
        assertThat(options.getMaxRetries()).isZero();
        assertThat(options.getModel()).isEqualTo("lite-id");
        assertThat(request.getValue().getContents()).doesNotContain("Here is the JSON Schema");
        verifyNoInteractions(standard, expert);
        verify(quota).recordUsage("caller", 12L, 7L);
        verify(recorder).recordOperation(eq("code-example"), eq("caller"), eq("lite-id"), eq(ChatTier.LITE),
                eq(12), eq(7), nullable(Long.class), isNull(), anyLong(), eq(false));
    }

    @Test
    void inlinesSharedDefinitionsBeforeAnthropicDropsDefs() {
        when(lite.call(any(Prompt.class))).thenReturn(response("""
                {"left":{"value":"l"},"right":{"value":"r"}}
                """));
        assertThat(gateway(Duration.ofSeconds(2)).generate("test", "caller", "input", Repeated.class)
                .value().right().value()).isEqualTo("r");
        ArgumentCaptor<Prompt> request = ArgumentCaptor.forClass(Prompt.class);
        verify(lite).call(request.capture());
        assertThat(((AnthropicChatOptions) request.getValue().getOptions()).getOutputSchema())
                .doesNotContain("$ref", "$defs");
    }

    @Test
    void retriesOnlyInvalidStructureAndBeanValidationAtMostThreeTimes() {
        when(lite.call(any(Prompt.class)))
                .thenReturn(response("{\"title\":\"missing children\"}"),
                        response("{\"title\":\"\",\"children\":[]}"),
                        response("{\"title\":\"valid\",\"children\":[]}"));
        assertThat(gateway(Duration.ofSeconds(2)).generate("learning-plan", "caller", "input", Sample.class)
                .value().title()).isEqualTo("valid");
        verify(lite, times(3)).call(any(Prompt.class));
        verify(quota, times(3)).recordUsage("caller", 12L, 7L);
    }

    @Test
    void neverReturnsFallbackForPersistentlyInvalidOutputs() {
        when(lite.call(any(Prompt.class))).thenReturn(response("""
                {"title":"x","children":[],"inventedSourceId":"fake"}
                """));
        assertThatThrownBy(() -> gateway(Duration.ofSeconds(2)).generate("code-example", "caller", "input", Sample.class))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("validation failed");
        verify(lite, times(3)).call(any(Prompt.class));
    }

    @Test
    void providerDeadlineIsBoundedAndOpensCircuitWithoutBlindRetries() {
        when(lite.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(1000);
            return response("{\"title\":\"late\",\"children\":[]}");
        });
        long start = System.nanoTime();
        assertThatThrownBy(() -> gateway(Duration.ofMillis(40)).generate("code-example", "caller", "input", Sample.class))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        verify(lite).call(any(Prompt.class));
        assertThat(breakers.circuitBreaker("llm-structured-lite").getState().name()).isEqualTo("OPEN");
    }

    @Test
    void nativeAnthropicAdapterActuallySerializesSchemaIntoHttpRequest() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        AtomicReference<JsonNode> outgoing = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            outgoing.set(json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] body = json.writeValueAsBytes(Map.of(
                    "id", "msg_local_schema_test", "type", "message", "role", "assistant",
                    "model", "claude-sonnet-4-5",
                    "content", List.of(Map.of("type", "text", "text", "{\"title\":\"wire\",\"children\":[]}")),
                    "stop_reason", "end_turn", "usage", Map.of("input_tokens", 12, "output_tokens", 7)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
            exchange.close();
        });
        server.start();
        try {
            var nativeModel = AnthropicChatModel.builder().options(AnthropicChatOptions.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .apiKey("local-wire-test-not-a-secret").model(Model.of("claude-sonnet-4-5"))
                    .maxTokens(512).maxRetries(0).timeout(Duration.ofSeconds(5)).build()).build();
            var gateway = new NativeStructuredGenerationGateway(nativeModel, standard, expert,
                    "claude-sonnet-4-5", "standard-id", "expert-id", prompts, quota, recorder, breakers,
                    Validation.buildDefaultValidatorFactory().getValidator(),
                    new GenerationTelemetry(OpenTelemetry.noop()), Duration.ofSeconds(5), 3);
            assertThat(gateway.generate("code-example", "caller", "local data", Sample.class)
                    .value().title()).isEqualTo("wire");
            JsonNode format = outgoing.get().path("output_config").path("format");
            assertThat(outgoing.get().path("model").asString()).isEqualTo("claude-sonnet-4-5");
            assertThat(outgoing.get().path("max_tokens").asInt()).isEqualTo(512);
            assertThat(format.path("type").asString()).isEqualTo("json_schema");
            assertThat(format.path("schema").path("type").asString()).isEqualTo("object");
            assertThat(format.path("schema").path("properties").has("title")).isTrue();
            assertThat(format.path("schema").path("properties").path("children").path("items")
                    .path("properties").has("value")).isTrue();
            assertThat(format.path("schema").path("additionalProperties").asBoolean()).isFalse();
            assertThat(outgoing.get().path("messages").toString()).doesNotContain("Here is the JSON Schema");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesNonJsonAndTruncatedJsonWithoutReturningPartialContent() {
        when(lite.call(any(Prompt.class))).thenReturn(response("not JSON"),
                response("{\"title\":\"partial\",\"children\":["),
                response("{\"title\":\"recovered\",\"children\":[]}"));
        assertThat(gateway(Duration.ofSeconds(2)).generate("code-example", "caller", "input", Sample.class)
                .value().title()).isEqualTo("recovered");
        verify(lite, times(3)).call(any(Prompt.class));
    }

    @Test
    void rejectsTokenTruncationEvenWhenReturnedJsonIsStructurallyComplete() {
        ChatResponse truncated = new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"title\":\"partial\",\"children\":[]}"),
                ChatGenerationMetadata.builder().finishReason("max_tokens").build())));
        when(lite.call(any(Prompt.class))).thenReturn(truncated,
                response("{\"title\":\"complete\",\"children\":[]}"));
        assertThat(gateway(Duration.ofSeconds(2)).generate("code-example", "caller", "input", Sample.class)
                .value().title()).isEqualTo("complete");
        verify(lite, times(2)).call(any(Prompt.class));
    }

    @Test
    void rejectsOversizedInputBeforeAnyProviderAndBoundsOversizedOutputRetries() {
        var gateway = gateway(Duration.ofSeconds(2));
        assertThatThrownBy(() -> gateway.generate("code-example", "caller", "x".repeat(100_001), Sample.class))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(lite);
        when(lite.call(any(Prompt.class))).thenReturn(response("x".repeat(200_001)));
        assertThatThrownBy(() -> gateway.generate("code-example", "caller", "input", Sample.class))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("validation failed");
        verify(lite, times(3)).call(any(Prompt.class));
    }

    @Test
    void retriesShareOneDeadlineAndLaterAttemptGetsOnlyRemainingTime() {
        when(lite.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(80);
            return response("invalid");
        }).thenAnswer(invocation -> {
            Thread.sleep(2000);
            return response("{\"title\":\"late\",\"children\":[]}");
        });
        long start = System.nanoTime();
        assertThatThrownBy(() -> gateway(Duration.ofMillis(300))
                .generate("code-example", "caller", "input", Sample.class))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(lite, times(2)).call(requests.capture());
        Duration first = ((AnthropicChatOptions) requests.getAllValues().getFirst().getOptions()).getTimeout();
        Duration second = ((AnthropicChatOptions) requests.getAllValues().getLast().getOptions()).getTimeout();
        assertThat(second).isLessThan(first.minusMillis(50));
    }

    @Test
    void validatesConfigurationAndHonorsTwoAttemptLimit() {
        assertThatThrownBy(() -> gateway(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway(Duration.ofSeconds(121))).isInstanceOf(IllegalArgumentException.class);
        var limited = new NativeStructuredGenerationGateway(lite, standard, expert, "lite", "standard", "expert",
                prompts, quota, recorder, breakers, Validation.buildDefaultValidatorFactory().getValidator(),
                new GenerationTelemetry(OpenTelemetry.noop()), Duration.ofSeconds(2), 2);
        when(lite.call(any(Prompt.class))).thenReturn(response("invalid"));
        assertThatThrownBy(() -> limited.generate("code-example", "caller", "input", Sample.class))
                .isInstanceOf(ResponseStatusException.class);
        verify(lite, times(2)).call(any(Prompt.class));
    }

    private NativeStructuredGenerationGateway gateway(Duration deadline) {
        return new NativeStructuredGenerationGateway(lite, standard, expert, "lite-id", "standard-id", "expert-id",
                prompts, quota, recorder, breakers, Validation.buildDefaultValidatorFactory().getValidator(),
                new GenerationTelemetry(OpenTelemetry.noop()), deadline, 3);
    }

    private static ChatResponse response(String json) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(12, 7)).build());
    }
}
