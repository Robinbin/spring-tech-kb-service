package com.springtechkb.search.internal;

import java.util.List;

import org.springframework.stereotype.Component;

import com.springtechkb.search.SearchHit;

/** Narrow retrieval capability used by the bounded Agentic Retrieval loop. */
@Component
public class KnowledgeRetrievalTool {

    private final HybridSearchService searchService;

    KnowledgeRetrievalTool(HybridSearchService searchService) {
        this.searchService = searchService;
    }

    public List<SearchHit> search(String query, String module, String version) {
        return searchService.search(query, module, version, 20);
    }
}
