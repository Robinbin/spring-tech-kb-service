package com.springtechkb;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class ApiRateLimiterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void enforcesFixedWindowLimitWhenRedisIsAvailable() {
        ConcurrentHashMap<String, AtomicLong> requests = new ConcurrentHashMap<>();
        ApiRateLimiter limiter = new ApiRateLimiter(
                (key, ttl) -> requests.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet(),
                new SimpleMeterRegistry(), 2, "test:api", CLOCK);

        assertThat(limiter.allow("192.0.2.10")).isTrue();
        assertThat(limiter.allow("192.0.2.10")).isTrue();
        assertThat(limiter.allow("192.0.2.10")).isFalse();
        assertThat(limiter.allow("192.0.2.11")).isTrue();
    }

    @Test
    void fallsBackToBoundedPerProcessLimiterWhenRedisFails() {
        ApiRateLimiter limiter = new ApiRateLimiter(
                (key, ttl) -> {
                    throw new DataAccessResourceFailureException("Redis unavailable");
                },
                new SimpleMeterRegistry(), 2, "test:api", CLOCK);

        assertThat(limiter.allow("192.0.2.10")).isTrue();
        assertThat(limiter.allow("192.0.2.10")).isTrue();
        assertThat(limiter.allow("192.0.2.10")).isFalse();
        assertThat(limiter.allow("192.0.2.11")).isTrue();
    }
}
