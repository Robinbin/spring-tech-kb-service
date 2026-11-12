package com.springtechkb.ai.internal;

import java.time.Duration;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class LlmResilienceConfiguration {

    @Bean
    CircuitBreakerRegistry llmCircuitBreakerRegistry(
            @Value("${springtechkb.llm.circuit-breaker.sliding-window-size:10}") int windowSize,
            @Value("${springtechkb.llm.circuit-breaker.minimum-number-of-calls:5}") int minimumCalls,
            @Value("${springtechkb.llm.circuit-breaker.failure-rate-threshold:50}") float failureRate,
            @Value("${springtechkb.llm.circuit-breaker.wait-duration-in-open-state:30s}") Duration openWait) {
        if (windowSize < 1 || minimumCalls < 1 || minimumCalls > windowSize
                || failureRate <= 0 || failureRate > 100 || openWait.isNegative() || openWait.isZero()) {
            throw new IllegalArgumentException("Invalid LLM circuit-breaker configuration");
        }
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(windowSize)
                .minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(failureRate)
                .waitDurationInOpenState(openWait)
                .build();
        return CircuitBreakerRegistry.of(config);
    }
}
