package com.springtechkb.ai.internal;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import com.springtechkb.ai.ChatTier;
import com.springtechkb.ai.RagChatGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Applies a TTFT circuit-breaker window separately from the wall-clock stream deadline. */
@Component
@Primary
class ResilientRagChatGateway implements RagChatGateway {

    private final RagChatGateway provider;
    private final CircuitBreakerRegistry circuitBreakers;
    private final Duration ttftTimeout;
    private final Duration streamTimeout;

    ResilientRagChatGateway(
            @Qualifier("providerRagChatGateway") RagChatGateway provider,
            CircuitBreakerRegistry circuitBreakers,
            @Value("${springtechkb.llm.ttft-timeout:5s}") Duration ttftTimeout,
            @Value("${springtechkb.llm.stream-timeout:120s}") Duration streamTimeout) {
        if (ttftTimeout.isNegative() || ttftTimeout.isZero()
                || streamTimeout.compareTo(ttftTimeout) <= 0) {
            throw new IllegalArgumentException("Invalid LLM timeout configuration");
        }
        this.provider = provider;
        this.circuitBreakers = circuitBreakers;
        this.ttftTimeout = ttftTimeout;
        this.streamTimeout = streamTimeout;
    }

    @Override
    public Flux<ChatChunk> stream(String stableSystemPrefix, String userQuestion) {
        return stream(ChatTier.STANDARD, stableSystemPrefix, userQuestion);
    }

    @Override
    public String modelId(ChatTier tier) {
        return provider.modelId(tier);
    }

    @Override
    public Flux<ChatChunk> stream(ChatTier tier, String stableSystemPrefix, String userQuestion) {
        return stream(tier, stableSystemPrefix, userQuestion, "v1");
    }

    @Override
    public Flux<ChatChunk> stream(
            ChatTier tier, String stableSystemPrefix, String userQuestion, String promptNamespace) {
        return Flux.defer(() -> provider.stream(tier, stableSystemPrefix, userQuestion, promptNamespace)
                .publish(shared -> {
                    Mono<ChatChunk> firstChunk = shared.next()
                            .timeout(ttftTimeout)
                            .switchIfEmpty(Mono.error(new TimeoutException("LLM produced no first response chunk")))
                            .transformDeferred(CircuitBreakerOperator.of(
                                    circuitBreakers.circuitBreaker("llm-" + tier.name().toLowerCase())));
                    Flux<ChatChunk> completeStream = firstChunk.flatMapMany(
                            first -> Flux.concat(Mono.just(first), shared.skip(1)));
                    return completeStream.timeout(
                            Mono.delay(streamTimeout)
                                    .then(Mono.error(new TimeoutException("LLM stream exceeded its total deadline"))));
                }));
    }
}
