package com.springtechkb.search;

import com.springtechkb.ai.ChatTier;

public record ClassifiedQuery(SearchIntent intent, QueryRoute route, String knowledgeDomain) {

    public boolean shouldCache() {
        return intent.cacheable();
    }

    public ChatTier recommendedTier() {
        return route == QueryRoute.FAST_PATH ? ChatTier.STANDARD : ChatTier.EXPERT;
    }
}
