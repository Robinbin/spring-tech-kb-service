package com.springtechkb.ai;

/**
 * Synchronous intent classification backed by the lite chat model.
 * Keeps routing decisions out of the search module's prompt budget.
 */
public interface QueryClassificationGateway {

    /**
     * Classifies the query into one of the returned route labels.
     *
     * @param question the raw user question
     * @param candidates  the allowed route labels (e.g. "comparison", "retrieval")
     * @return one of the provided {@code candidates}, lowercase, or the first candidate as fallback
     */
    String classify(String question, String... candidates);
}
