package com.springtechkb.ai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import com.springtechkb.ai.ChatTier;
import com.springtechkb.ai.RagChatGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ResilientRagChatGatewayTest {

    @Test
    void firstTokenTimeoutCountsAgainstCircuitBreaker() {
        RagChatGateway provider = mock(RagChatGateway.class);
        when(provider.stream(eq(ChatTier.STANDARD), anyString(), anyString(), anyString()))
                .thenReturn(Flux.never());
        CircuitBreakerRegistry breakers = registry(1);
        ResilientRagChatGateway gateway =
                new ResilientRagChatGateway(provider, breakers, Duration.ofMillis(20), Duration.ofMillis(200));

        assertThatThrownBy(() -> gateway.stream(
                ChatTier.STANDARD, "system", "question", "rag-system:v1").blockLast(Duration.ofSeconds(1)))
                .hasRootCauseInstanceOf(TimeoutException.class);

        assertThat(breakers.circuitBreaker("llm-standard").getState().name()).isEqualTo("OPEN");
    }

    @Test
    void totalStreamDeadlineDoesNotCountAsCircuitBreakerFailure() {
        RagChatGateway provider = mock(RagChatGateway.class);
        RagChatGateway.ChatChunk first = new RagChatGateway.ChatChunk("first", null, null, null);
        when(provider.stream(eq(ChatTier.STANDARD), anyString(), anyString(), anyString()))
                .thenReturn(Flux.concat(Flux.just(first), Flux.never()));
        CircuitBreakerRegistry breakers = registry(10);
        ResilientRagChatGateway gateway =
                new ResilientRagChatGateway(provider, breakers, Duration.ofMillis(10), Duration.ofMillis(40));

        assertThatThrownBy(() -> gateway.stream(
                ChatTier.STANDARD, "system", "question", "rag-system:v1").blockLast(Duration.ofSeconds(1)))
                .hasRootCauseInstanceOf(TimeoutException.class);

        assertThat(breakers.circuitBreaker("llm-standard").getState().name()).isEqualTo("CLOSED");
        assertThat(breakers.circuitBreaker("llm-standard").getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(breakers.circuitBreaker("llm-standard").getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    private static CircuitBreakerRegistry registry(int windowSize) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(windowSize)
                .minimumNumberOfCalls(1)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(1))
                .build();
        return CircuitBreakerRegistry.of(config);
    }
}
