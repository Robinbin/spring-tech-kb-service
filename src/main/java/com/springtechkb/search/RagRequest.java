package com.springtechkb.search;

public record RagRequest(String query, String springModule, String springVersion) {
    public RagRequest {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        query = query.trim();
        springModule = springModule == null || springModule.isBlank() ? null : springModule.trim();
        springVersion = springVersion == null || springVersion.isBlank() ? null : springVersion.trim();
    }

    public String question() {
        return query;
    }
}
