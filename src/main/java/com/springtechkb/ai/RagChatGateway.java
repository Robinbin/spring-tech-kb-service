package com.springtechkb.ai;

import reactor.core.publisher.Flux;

/** Search-module-safe interface for streaming an answer from the configured chat provider. */
public interface RagChatGateway {

    /** Primary method: all parameters explicit. Implementations must override this. */
    Flux<ChatChunk> stream(ChatTier tier, String stableSystemPrefix, String userQuestion, String promptNamespace);

    default String modelId(ChatTier tier) {
        return tier.name();
    }

    default Flux<ChatChunk> stream(String stableSystemPrefix, String userQuestion) {
        return stream(ChatTier.STANDARD, stableSystemPrefix, userQuestion, "v1");
    }

    default Flux<ChatChunk> stream(ChatTier tier, String stableSystemPrefix, String userQuestion) {
        return stream(tier, stableSystemPrefix, userQuestion, "v1");
    }

    record ChatChunk(
            String text,
            Integer inputTokens,
            Integer outputTokens,
            Long cacheReadTokens,
            String modelId,
            ChatTier tier) {
        public ChatChunk(String text, Integer inputTokens, Integer outputTokens, Long cacheReadTokens) {
            this(text, inputTokens, outputTokens, cacheReadTokens, null, null);
        }
    }
}
