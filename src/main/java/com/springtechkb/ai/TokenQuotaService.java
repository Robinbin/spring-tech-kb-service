package com.springtechkb.ai;

/** Daily token budget service; Redis failures deliberately select the low-cost tier. */
public interface TokenQuotaService {

    ChatTier selectTier(ChatTier requestedTier, String callerKey);

    void recordUsage(String callerKey, long inputTokens, long outputTokens);
}
