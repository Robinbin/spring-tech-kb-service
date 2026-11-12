package com.springtechkb.search;

public record RagStreamEvent(
        String type,
        String id,
        String text,
        RagCitation citation,
        String route,
        boolean cacheHit,
        String error
) {
    public static RagStreamEvent token(String text) {
        return new RagStreamEvent("token", null, text, null, null, false, null);
    }

    public static RagStreamEvent citation(RagCitation citation) {
        return new RagStreamEvent("citation", citation.chunkId(), null, citation, null, false, null);
    }

    public static RagStreamEvent done(String route, boolean cacheHit) {
        return new RagStreamEvent("done", null, null, null, route, cacheHit, null);
    }

    public static RagStreamEvent error(String error) {
        return new RagStreamEvent("error", null, null, null, null, false, error);
    }
}
