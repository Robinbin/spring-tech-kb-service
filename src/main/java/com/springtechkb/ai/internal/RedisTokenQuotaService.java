package com.springtechkb.ai.internal;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

import com.springtechkb.ai.ChatTier;
import com.springtechkb.ai.TokenQuotaService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
class RedisTokenQuotaService implements TokenQuotaService {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenQuotaService.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry meters;
    private final long dailyTokenLimit;
    private final String keyPrefix;

    RedisTokenQuotaService(
            StringRedisTemplate redis,
            MeterRegistry meters,
            @Value("${springtechkb.llm.quota.daily-token-limit:100000}") long dailyTokenLimit,
            @Value("${springtechkb.llm.quota.key-prefix:springkb:llm:tokens}") String keyPrefix) {
        if (dailyTokenLimit < 1 || keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("Invalid LLM token quota configuration");
        }
        this.redis = redis;
        this.meters = meters;
        this.dailyTokenLimit = dailyTokenLimit;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public ChatTier selectTier(ChatTier requestedTier, String callerKey) {
        try {
            String usage = redis.opsForValue().get(key(callerKey));
            long used = usage == null ? 0 : Long.parseLong(usage);
            boolean exceeded = used >= dailyTokenLimit;
            meters.counter("llm.quota.decisions", "result", exceeded ? "lite" : "requested").increment();
            return exceeded ? ChatTier.LITE : requestedTier;
        } catch (DataAccessException exception) {
            log.warn("Redis token quota unavailable; routing this request to the lite LLM tier", exception);
            meters.counter("llm.quota.decisions", "result", "redis_fallback").increment();
            return ChatTier.LITE;
        }
    }

    @Override
    public void recordUsage(String callerKey, long inputTokens, long outputTokens) {
        long tokens = Math.max(0, inputTokens) + Math.max(0, outputTokens);
        if (tokens == 0) return;
        try {
            Long total = redis.opsForValue().increment(key(callerKey), tokens);
            if (total != null && total == tokens) {
                Boolean expirationSet = redis.expire(key(callerKey), Duration.ofDays(2));
                if (!Boolean.TRUE.equals(expirationSet)) {
                    log.error("Redis token quota key did not receive its expiration");
                }
            }
        } catch (DataAccessException exception) {
            log.error("Could not record LLM token usage in Redis quota", exception);
        }
    }

    private String key(String callerKey) {
        String identity = callerKey == null || callerKey.isBlank() ? "anonymous" : callerKey;
        return keyPrefix + ":" + LocalDate.now(ZoneOffset.UTC) + ":" + identity;
    }
}
