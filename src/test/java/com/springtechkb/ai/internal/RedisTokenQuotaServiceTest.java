package com.springtechkb.ai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

import com.springtechkb.ai.ChatTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisTokenQuotaServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    void exceededDailyBudgetSelectsLiteTier() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        String key = key("alice");
        when(values.get(key)).thenReturn("1000");
        RedisTokenQuotaService service = new RedisTokenQuotaService(
                redis, new SimpleMeterRegistry(), 1000, "test:quota");

        assertThat(service.selectTier(ChatTier.EXPERT, "alice")).isEqualTo(ChatTier.LITE);
    }

    @SuppressWarnings("unchecked")
    @Test
    void redisFailureSafelyDowngradesToLiteTier() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(any())).thenThrow(new RedisConnectionFailureException("redis unavailable"));
        RedisTokenQuotaService service = new RedisTokenQuotaService(
                redis, new SimpleMeterRegistry(), 1000, "test:quota");

        assertThat(service.selectTier(ChatTier.STANDARD, "alice")).isEqualTo(ChatTier.LITE);
    }

    @SuppressWarnings("unchecked")
    @Test
    void usageIsAccumulatedWithAnExpiration() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(key("alice"), 9)).thenReturn(9L);
        RedisTokenQuotaService service = new RedisTokenQuotaService(
                redis, new SimpleMeterRegistry(), 1000, "test:quota");

        service.recordUsage("alice", 6, 3);

        verify(values).increment(key("alice"), 9);
        verify(redis).expire(eq(key("alice")), any(Duration.class));
    }

    private static String key(String caller) {
        return "test:quota:" + LocalDate.now(ZoneOffset.UTC) + ":" + caller;
    }
}
