package com.springtechkb.search;

import java.util.List;

/** Search API envelope used by the online evaluator and clients. */
public record SearchResponse(List<SearchResult> results) {

    public static SearchResponse from(List<SearchHit> hits) {
        return new SearchResponse(hits.stream()
                .map(hit -> new SearchResult(
                        hit.id(),
                        hit.content(),
                        hit.springVersion(),
                        new SearchSource(hit.sourceId(), hit.sourceUrl(), hit.sourceTitle(),
                                hit.sourceLicense(), hit.springVersion())))
                .toList());
    }

    public record SearchResult(String id, String content, String springVersion, SearchSource source) {}

    public record SearchSource(String id, String url, String title, String license, String version) {}
}
