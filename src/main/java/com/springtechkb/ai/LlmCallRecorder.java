package com.springtechkb.ai;

/** Durable, operation-attributed token and cost accounting for model requests. */
public interface LlmCallRecorder {

    void recordOperation(
            String operation, String callerKey, String modelId, ChatTier tier,
            Integer inputTokens, Integer outputTokens, Long cacheReadTokens,
            Long ttftMillis, long totalMillis, boolean error);

    default void record(
            String callerKey,
            String modelId,
            ChatTier tier,
            Integer inputTokens,
            Integer outputTokens,
            Long cacheReadTokens,
            Long ttftMillis,
            long totalMillis,
            boolean error) {
        recordOperation("rag.answer", callerKey, modelId, tier, inputTokens, outputTokens,
                cacheReadTokens, ttftMillis, totalMillis, error);
    }
}
