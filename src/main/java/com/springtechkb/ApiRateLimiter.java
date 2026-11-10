package com.springtechkb;

import java.time.Clock;
import java.util.List;

import com.springtechkb.HashUtils;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
class ApiRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ApiRateLimiter.class);
    private static final long WINDOW_MILLIS = 60_000;
    private static final long REDIS_TTL_SECONDS = 70;
    private static final int MAX_LOCAL_CLIENTS = 10_000;
    private static final RedisScript<Long> INCREMENT_WINDOW = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return count
            """, Long.class);

    private final RedisCounter redisCounter;
    private final MeterRegistry meters;
    private final int requestLimit;
    private final String keyPrefix;
    private final Clock clock;
    private final ConcurrentHashMap<String, LocalWindow> localWindows = new ConcurrentHashMap<>();
    private final AtomicBoolean redisUnavailableLogged = new AtomicBoolean();
    private final LongAdder requestCount = new LongAdder();

    ApiRateLimiter(
            StringRedisTemplate redis,
            MeterRegistry meters,
            @Value("${springtechkb.gateway.rate-limit.requests-per-minute:60}") int requestLimit,
            @Value("${springtechkb.gateway.rate-limit.key-prefix:springkb:gateway:requests}") String keyPrefix) {
        this((key, ttl) -> redis.execute(INCREMENT_WINDOW, List.of(key), Long.toString(ttl)),
                meters, requestLimit, keyPrefix, Clock.systemUTC());
    }

    ApiRateLimiter(
            RedisCounter redisCounter, MeterRegistry meters, int requestLimit, String keyPrefix, Clock clock) {
        if (requestLimit < 1 || keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("Invalid API rate-limit configuration");
        }
        this.redisCounter = redisCounter;
        this.meters = meters;
        this.requestLimit = requestLimit;
        this.keyPrefix = keyPrefix;
        this.clock = clock;
    }

    boolean allow(String clientAddress) {
        String identity = clientAddress == null || clientAddress.isBlank() ? "unknown" : clientAddress;
        String key = keyPrefix + ":" + HashUtils.sha256Hex(identity) + ":" + Math.floorDiv(clock.millis(), WINDOW_MILLIS);
        try {
            Long count = redisCounter.increment(key, REDIS_TTL_SECONDS);
            if (count != null) {
                redisUnavailableLogged.set(false);
                boolean allowed = count <= requestLimit;
                meters.counter("gateway.rate_limit.requests", "result", allowed ? "allowed" : "rejected")
                        .increment();
                return allowed;
            }
            logRedisUnavailable(null);
        } catch (DataAccessException exception) {
            logRedisUnavailable(exception);
        }
        boolean allowed = allowLocally(HashUtils.sha256Hex(identity), clock.millis());
        meters.counter("gateway.rate_limit.fallback", "result", allowed ? "allowed" : "rejected")
                .increment();
        return allowed;
    }

    private boolean allowLocally(String identity, long nowMillis) {
        long windowStart = Math.floorDiv(nowMillis, WINDOW_MILLIS) * WINDOW_MILLIS;
        requestCount.increment();
        long requestNumber = requestCount.sum();
        if ((requestNumber & 255) == 0) {
            localWindows.entrySet().removeIf(entry -> entry.getValue().windowStart() != windowStart);
        }
        String bucket = localWindows.size() >= MAX_LOCAL_CLIENTS && !localWindows.containsKey(identity)
                ? "overflow"
                : identity;

        LocalWindow window = localWindows.compute(bucket, (key, previous) -> {
            if (previous == null || previous.windowStart() != windowStart) {
                return new LocalWindow(windowStart, 1);
            }
            return new LocalWindow(windowStart, previous.count() + 1);
        });
        return window.count() <= requestLimit;
    }

    private void logRedisUnavailable(DataAccessException exception) {
        if (redisUnavailableLogged.compareAndSet(false, true)) {
            if (exception == null) {
                log.warn("Redis API rate limiter returned no counter; using the per-process limiter");
            } else {
                log.warn("Redis API rate limiter unavailable; using the per-process limiter", exception);
            }
        }
    }

    @FunctionalInterface
    interface RedisCounter {
        Long increment(String key, long ttlSeconds);
    }

    private record LocalWindow(long windowStart, long count) {}
}
