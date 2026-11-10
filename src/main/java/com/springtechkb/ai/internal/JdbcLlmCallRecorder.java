package com.springtechkb.ai.internal;

import java.util.UUID;
import java.math.BigDecimal;
import java.math.RoundingMode;

import com.springtechkb.ai.ChatTier;
import com.springtechkb.ai.GenerationTelemetry;
import com.springtechkb.ai.LlmCallRecorder;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;

@Service
class JdbcLlmCallRecorder implements LlmCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(LlmCallRecorder.class);

    private final JdbcTemplate jdbc;
    private final Environment environment;

    JdbcLlmCallRecorder(JdbcTemplate jdbc, Environment environment) {
        this.jdbc = jdbc;
        this.environment = environment;
    }

    @Override
    public void recordOperation(
            String operation, String callerKey, String modelId, ChatTier tier,
            Integer inputTokens, Integer outputTokens, Long cacheReadTokens,
            Long ttftMillis, long totalMillis, boolean error) {
        UUID userId = parseUserId(callerKey);
        Span span = Span.current();
        var prompt = GenerationTelemetry.currentPrompt();
        BigDecimal cost = cost(tier, inputTokens, outputTokens, cacheReadTokens);
        writeSpanAttributes(span, modelId, tier, inputTokens, outputTokens, cacheReadTokens, cost);
        try {
            jdbc.update(
                    """
                    INSERT INTO llm_call_log
                        (user_id, spring_module, operation, model_id, model_tier,
                         input_tokens, output_tokens, cache_read_tokens, ttft_ms, total_ms, is_error,
                         trace_id, span_id, prompt_name, prompt_version, prompt_checksum, estimated_cost_usd)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    userId,
                    operation.startsWith("learning") ? "learning" : operation.equals("rag.answer") ? "search" : "generation",
                    operation,
                    modelId == null || modelId.isBlank() ? "unknown" : modelId,
                    tier == null ? null : tier.name(),
                    positive(inputTokens),
                    positive(outputTokens),
                    positive(cacheReadTokens),
                    boundedInt(ttftMillis),
                    boundedInt(totalMillis),
                    error,
                    span.getSpanContext().isValid() ? span.getSpanContext().getTraceId() : null,
                    span.getSpanContext().isValid() ? span.getSpanContext().getSpanId() : null,
                    prompt == null ? null : prompt.name(),
                    prompt == null ? null : prompt.version(),
                    prompt == null ? null : GenerationTelemetry.checksum(prompt.content()),
                    cost);
        } catch (DataAccessException exception) {
            log.error("Failed to persist LLM call accounting record", exception);
            throw exception;
        }

    }

    private static void writeSpanAttributes(Span span, String modelId, ChatTier tier,
            Number inputTokens, Number outputTokens, Number cacheReadTokens, BigDecimal cost) {
        span.setAttribute("gen_ai.response.model", modelId == null ? "unknown" : modelId);
        span.setAttribute("gen_ai.usage.input_tokens", positive(inputTokens));
        span.setAttribute("gen_ai.usage.output_tokens", positive(outputTokens));
        span.setAttribute("gen_ai.usage.cache_read.input_tokens", positive(cacheReadTokens));
        span.setAttribute("langfuse.observation.usage_details", "{\"input\":" + positive(inputTokens)
                + ",\"output\":" + positive(outputTokens) + ",\"cache_read\":" + positive(cacheReadTokens) + "}");
        if (tier != null) span.setAttribute("langfuse.observation.metadata.model_tier", tier.name());
        if (cost != null) span.setAttribute("langfuse.observation.cost_details", "{\"total\":" + cost + "}");
    }

    private BigDecimal cost(ChatTier tier, Number input, Number output, Number cacheRead) {
        if (tier == null || input == null || output == null) return null;
        String prefix = "springtechkb.llm.pricing." + tier.name().toLowerCase(java.util.Locale.ROOT) + ".";
        BigDecimal inputPrice = price(prefix, tier, "input");
        BigDecimal outputPrice = price(prefix, tier, "output");
        BigDecimal cachePrice = price(prefix, tier, "cache-read");
        if (inputPrice == null || outputPrice == null || cachePrice == null) return null;
        if (inputPrice.signum() < 0 || outputPrice.signum() < 0 || cachePrice.signum() < 0) {
            throw new IllegalArgumentException("LLM pricing must be nonnegative");
        }
        return inputPrice.multiply(BigDecimal.valueOf(positive(input)))
                .add(outputPrice.multiply(BigDecimal.valueOf(positive(output))))
                .add(cachePrice.multiply(BigDecimal.valueOf(positive(cacheRead))))
                .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal price(String prefix, ChatTier tier, String category) {
        return environment.getProperty(prefix + category + "-per-million", BigDecimal.class,
                environment.getProperty("LLM_PRICE_" + tier.name() + "_"
                        + category.toUpperCase(java.util.Locale.ROOT).replace('-', '_') + "_PER_MILLION", BigDecimal.class));
    }

    private static UUID parseUserId(String callerKey) {
        if (callerKey == null) return null;
        try {
            return UUID.fromString(callerKey);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static long positive(Number value) {
        return value == null ? 0 : Math.max(0, value.longValue());
    }

    private static Integer boundedInt(Long value) {
        return value == null ? null : (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }
}
