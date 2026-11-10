package com.springtechkb.search;

/** Classified RAG intent; only general knowledge intents may use semantic answer caching. */
public enum SearchIntent {
    KNOWLEDGE_QA(true),
    VERSION_COMPARISON(true),
    LONG_CONTEXT(true),
    PERSONALIZED_TASK(false);

    private final boolean cacheable;

    SearchIntent(boolean cacheable) {
        this.cacheable = cacheable;
    }

    public boolean cacheable() {
        return cacheable;
    }
}
