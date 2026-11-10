package com.springtechkb.search;

import java.util.List;
import java.util.UUID;

public interface KnowledgeQueries {
    List<SearchHit> search(String query, String module, String version, int limit);
    Source source(UUID id);
    List<VersionChange> compareVersions(String className, String fromVersion, String toVersion);
    java.util.Optional<ChunkDetail> chunkDetail(UUID id);

    record Source(UUID id, String title, String url, String license, String licenseUrl, String author) {}
    record VersionChange(String className, String kind, String signature, String version,
                         String changeType, Source source) {}
    record ChunkDetail(UUID id, String content, String springModule,
                       String springVersionMin, String springVersionMax, Source source) {}
}
