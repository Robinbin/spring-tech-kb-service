package com.springtechkb.ai.internal;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.springtechkb.ai.ChatTier;
import com.springtechkb.ai.GenerationTelemetry;
import com.springtechkb.ai.LlmCallRecorder;
import com.springtechkb.ai.PromptCatalog;
import com.springtechkb.ai.StructuredGenerationGateway;
import com.springtechkb.ai.TokenQuotaService;
import com.anthropic.models.messages.Model;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import jakarta.validation.Validator;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Uses Anthropic's output_config.format JSON schema, not a formatting-only prompt. */
@Component
class NativeStructuredGenerationGateway implements StructuredGenerationGateway {
    private final Map<ChatTier, ChatModel> models = new EnumMap<>(ChatTier.class);
    private final Map<ChatTier, String> modelIds = new EnumMap<>(ChatTier.class);
    private final PromptCatalog prompts;
    private final TokenQuotaService quota;
    private final LlmCallRecorder recorder;
    private final CircuitBreakerRegistry breakers;
    private final Validator validator;
    private final GenerationTelemetry telemetry;
    private final Duration deadline;
    private final int attempts;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();

    NativeStructuredGenerationGateway(
            @Qualifier("liteChatModel") ChatModel lite,
            @Qualifier("standardChatModel") ChatModel standard,
            @Qualifier("expertChatModel") ChatModel expert,
            @Value("${springtechkb.ai.models.lite}") String liteId,
            @Value("${springtechkb.ai.models.standard}") String standardId,
            @Value("${springtechkb.ai.models.expert}") String expertId,
            PromptCatalog prompts, TokenQuotaService quota, LlmCallRecorder recorder,
            CircuitBreakerRegistry breakers, Validator validator, GenerationTelemetry telemetry,
            @Value("${springtechkb.ai.structured.deadline:90s}") Duration deadline,
            @Value("${springtechkb.ai.structured.max-attempts:3}") int attempts) {
        if (deadline.isNegative() || deadline.isZero() || deadline.compareTo(Duration.ofMinutes(2)) > 0
                || attempts < 1 || attempts > 3) {
            throw new IllegalArgumentException("Invalid structured generation limits");
        }
        models.put(ChatTier.LITE, lite);
        models.put(ChatTier.STANDARD, standard);
        models.put(ChatTier.EXPERT, expert);
        modelIds.put(ChatTier.LITE, liteId);
        modelIds.put(ChatTier.STANDARD, standardId);
        modelIds.put(ChatTier.EXPERT, expertId);
        this.prompts = prompts;
        this.quota = quota;
        this.recorder = recorder;
        this.breakers = breakers;
        this.validator = validator;
        this.telemetry = telemetry;
        this.deadline = deadline;
        this.attempts = attempts;
    }

    @Override
    public <T> Generated<T> generate(String promptName, String callerKey, String input, Class<T> outputType) {
        if (input == null || input.length() > 100_000) {
            throw new IllegalArgumentException("Structured generation input exceeds its limit");
        }
        PromptCatalog.Prompt prompt = prompts.resolvePrompt(promptName, callerKey);
        return telemetry.observe(promptName, callerKey, prompt,
                () -> generate(prompt, callerKey, input, outputType));
    }

    private <T> Generated<T> generate(PromptCatalog.Prompt prompt, String callerKey, String input, Class<T> type) {
        JsonNode originalSchema = json.readTree(new BeanOutputConverter<>(type).getJsonSchema());
        JsonNode schemaNode = inlineSchema(originalSchema, originalSchema, 0);
        String schema = json.writeValueAsString(schemaNode);
        long end = System.nanoTime() + deadline.toNanos();
        for (int attempt = 0; attempt < attempts; attempt++) {
            ChatTier tier = quota.selectTier(ChatTier.STANDARD, callerKey);
            long start = System.nanoTime();
            Duration remaining = Duration.ofNanos(end - start);
            if (remaining.isNegative() || remaining.isZero()) {
                throw unavailable("Structured generation deadline exceeded", null);
            }
            ChatResponse response;
            try {
                // Spring AI 2 AnthropicChatModel does not merge non-null runtime options with model defaults.
                var defaults = models.get(tier).getOptions();
                var builder = defaults instanceof AnthropicChatOptions nativeDefaults
                        ? nativeDefaults.mutate() : AnthropicChatOptions.builder();
                var options = builder.model(Model.of(modelIds.get(tier))).outputSchema(schema)
                        .timeout(remaining).maxRetries(0).build();
                var request = new Prompt(List.of(
                        new SystemMessage("[prompt namespace: " + prompt.namespace() + "]\n" + prompt.content()),
                        new UserMessage(input + (attempt == 0 ? "" :
                                "\nReturn valid schema-conforming JSON; the previous output failed structural validation."))),
                        options);
                response = Mono.fromCallable(() -> models.get(tier).call(request))
                        .subscribeOn(Schedulers.boundedElastic()).timeout(remaining)
                        .transformDeferred(CircuitBreakerOperator.of(
                                breakers.circuitBreaker("llm-structured-" + tier.name().toLowerCase())))
                        .block(remaining.plusMillis(100));
            } catch (RuntimeException exception) {
                record(prompt.name(), callerKey, tier, null, start, true);
                throw unavailable("Structured generation provider unavailable", exception);
            }
            Usage usage = response == null || response.getMetadata() == null
                    ? null : response.getMetadata().getUsage();
            if (usage != null) {
                quota.recordUsage(callerKey, count(usage.getPromptTokens()), count(usage.getCompletionTokens()));
            }
            T value;
            try {
                if (response == null || response.getResult() == null) throw new IllegalArgumentException("Empty output");
                if (response.hasFinishReasons(Set.of("max_tokens", "length", "refusal"))) {
                    throw new IllegalArgumentException("Truncated or refused structured output");
                }
                String text = ChatResponses.safeText(response);
                if (text == null || text.isBlank() || text.length() > 200_000) {
                    throw new IllegalArgumentException("Missing or oversized output");
                }
                JsonNode node = json.readTree(text);
                validateSchema(schemaNode, node);
                value = json.readValue(text, type);
                if (value == null || !validator.validate(value).isEmpty()) {
                    throw new IllegalArgumentException("Bean validation failed");
                }
            } catch (RuntimeException exception) {
                record(prompt.name(), callerKey, tier, usage, start, true);
                if (attempt + 1 == attempts) throw unavailable("Structured output validation failed", exception);
                continue;
            }
            record(prompt.name(), callerKey, tier, usage, start, false);
            return new Generated<>(value, modelIds.get(tier), prompt.namespace());
        }
        throw unavailable("Structured output validation failed", null);
    }

    private void record(String operation, String caller, ChatTier tier, Usage usage, long start, boolean error) {
        recorder.recordOperation(operation, caller, modelIds.get(tier), tier,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getCacheReadInputTokens(), null,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), error);
    }

    private static long count(Integer value) { return value == null ? 0 : Math.max(0, value); }

    private static ResponseStatusException unavailable(String message, Throwable cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }

    private JsonNode inlineSchema(JsonNode node, JsonNode root, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Recursive output schemas are not supported");
        if (node.isObject()) {
            if (node.has("$ref")) {
                String ref = node.get("$ref").asString();
                if (!ref.startsWith("#/$defs/")) throw new IllegalArgumentException("Unsupported schema reference");
                JsonNode definition = root.path("$defs").get(ref.substring("#/$defs/".length()));
                if (definition == null) throw new IllegalArgumentException("Unresolved schema reference");
                return inlineSchema(definition, root, depth + 1);
            }
            ObjectNode result = json.createObjectNode();
            for (String name : node.propertyNames()) {
                if (!name.equals("$schema") && !name.equals("$defs") && !name.equals("format")) {
                    result.set(name, inlineSchema(node.get(name), root, depth + 1));
                }
            }
            return result;
        }
        if (node.isArray()) {
            var result = json.createArrayNode();
            for (JsonNode child : node) result.add(inlineSchema(child, root, depth + 1));
            return result;
        }
        return node;
    }

    /* Check structure locally as well: providers can refuse or truncate despite native schema constraints. */
    static void validateSchema(JsonNode schema, JsonNode value) {
        if (value == null || value.isNull()) throw new IllegalArgumentException("Unexpected null");
        JsonNode type = schema.path("type");
        if (type.isString()) {
            boolean valid = switch (type.asString()) {
                case "object" -> value.isObject();
                case "array" -> value.isArray();
                case "string" -> value.isString();
                case "integer" -> value.isIntegralNumber();
                case "number" -> value.isNumber();
                case "boolean" -> value.isBoolean();
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("Unexpected JSON type");
        }
        if (schema.has("enum")) {
            boolean match = false;
            for (JsonNode candidate : schema.path("enum")) match |= candidate.equals(value);
            if (!match) throw new IllegalArgumentException("Unexpected enum");
        }
        if (value.isObject()) {
            for (JsonNode required : schema.path("required")) {
                if (!value.has(required.asString())) throw new IllegalArgumentException("Missing required property");
            }
            for (String name : value.propertyNames()) {
                JsonNode property = schema.path("properties").get(name);
                if (property == null) throw new IllegalArgumentException("Unknown property");
                validateSchema(property, value.get(name));
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) validateSchema(schema.path("items"), item);
        }
    }
}
