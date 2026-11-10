package com.springtechkb.ai.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Low-cardinality, aggregate-only LLM accounting and resilience summary. */
@Service
class OperationsSummaryService {

    private static final List<String> BREAKER_NAMES = List.of(
            "llm-lite", "llm-standard", "llm-expert",
            "llm-structured-lite", "llm-structured-standard", "llm-structured-expert");

    private final JdbcTemplate jdbc;
    private final CircuitBreakerRegistry breakers;

    OperationsSummaryService(JdbcTemplate jdbc, CircuitBreakerRegistry breakers) {
        this.jdbc = jdbc;
        this.breakers = breakers;
    }

    Summary summary(int days) {
        if (days < 1 || days > 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be between 1 and 30");
        }
        Instant end = Instant.now();
        Instant start = end.minus(days, ChronoUnit.DAYS);
        List<TierUsage> usage = jdbc.query("""
                SELECT CASE
                           WHEN model_tier IN ('LITE', 'STANDARD', 'EXPERT') THEN model_tier
                           ELSE 'UNKNOWN'
                       END AS model_tier,
                       count(*) AS calls,
                       COALESCE(sum(input_tokens), 0) AS input_tokens,
                       COALESCE(sum(output_tokens), 0) AS output_tokens,
                       COALESCE(sum(cache_read_tokens), 0) AS cache_read_tokens,
                       sum(estimated_cost_usd) AS known_cost_usd,
                       count(*) FILTER (WHERE estimated_cost_usd IS NULL) AS unknown_cost_calls,
                       count(*) FILTER (WHERE operation ILIKE '%batch%') AS batch_calls,
                       count(*) FILTER (WHERE is_error = TRUE) AS error_calls
                FROM llm_call_log
                WHERE created_at >= ? AND created_at < ?
                GROUP BY CASE
                             WHEN model_tier IN ('LITE', 'STANDARD', 'EXPERT') THEN model_tier
                             ELSE 'UNKNOWN'
                         END
                ORDER BY model_tier
                """, (rs, row) -> {
            long calls = rs.getLong("calls");
            long inputTokens = rs.getLong("input_tokens");
            long cacheReadTokens = rs.getLong("cache_read_tokens");
            long batchCalls = rs.getLong("batch_calls");
            BigDecimal cost = rs.getBigDecimal("known_cost_usd");
            return new TierUsage(
                    rs.getString("model_tier"),
                    calls,
                    inputTokens,
                    rs.getLong("output_tokens"),
                    cacheReadTokens,
                    cacheReadRatio(cacheReadTokens, inputTokens),
                    batchCalls,
                    ratio(batchCalls, calls),
                    cost,
                    rs.getLong("unknown_cost_calls"),
                    rs.getLong("error_calls"));
        }, start, end);

        List<BreakerState> breakerStates = BREAKER_NAMES.stream()
                .map(name -> new BreakerState(name,
                        breakers.find(name).map(breaker -> breaker.getState().name()).orElse("NOT_INITIALIZED")))
                .toList();
        return new Summary(start, end, days, usage, breakerStates);
    }

    static BigDecimal ratio(long numerator, long denominator) {
        return denominator == 0 ? null
                : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 6, java.math.RoundingMode.HALF_UP);
    }

    static BigDecimal cacheReadRatio(long cacheReadTokens, long nonCachedInputTokens) {
        BigDecimal totalInput = BigDecimal.valueOf(cacheReadTokens)
                .add(BigDecimal.valueOf(nonCachedInputTokens));
        return totalInput.signum() == 0 ? null
                : BigDecimal.valueOf(cacheReadTokens)
                        .divide(totalInput, 6, java.math.RoundingMode.HALF_UP);
    }

    record Summary(Instant windowStart, Instant windowEnd, int windowDays,
            List<TierUsage> byModelTier, List<BreakerState> circuitBreakers) {}

    record TierUsage(String modelTier, long calls, long inputTokens, long outputTokens, long cacheReadTokens,
            BigDecimal cacheReadRatio, long batchCalls, BigDecimal batchShare,
            BigDecimal knownCostUsd, long unknownCostCalls, long errorCalls) {}

    record BreakerState(String name, String state) {}
}
