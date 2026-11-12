package com.springtechkb.generation.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.springtechkb.search.KnowledgeQueries.Source;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

final class GenerationDtos {
    private GenerationDtos() {}
    enum Level { CONCEPT, PRACTICAL, SOURCE }
    enum Kind { EXAMPLE, COMPARISON, PROJECT }

    record ExampleRequest(@NotBlank @Size(max = 2000) String query, @NotNull Level level,
                          @Size(max = 100) String module, @Size(max = 50) String springVersion) {}
    /** Each side is a topic name/UUID, or the same fully qualified ClassName@version for API diffs. */
    record ComparisonRequest(@NotBlank @Size(max = 500) String left,
                             @NotBlank @Size(max = 500) String right,
                             @Size(max = 50) String springVersion, @Size(max = 100) String module) {}
    record ProjectRequest(
            @NotBlank @Size(max = 120)
            @Pattern(regexp = "[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+") String groupId,
            @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z][a-z0-9]*(?:-[a-z0-9]+)*") String artifactId,
            @NotBlank @Size(max = 100) @Pattern(regexp = "[\\p{L}\\p{N} ._-]+") String name,
            UUID generationId) {}
    record Draft(@NotBlank @Size(max = 200) String title,
                 @NotBlank @Size(max = 30000) String content,
                 @NotNull @Size(max = 40000) String code,
                 @NotBlank @Size(max = 40) String language) {}
    record Output(UUID id, Kind kind, String title, String content, String code, String language,
                  boolean aiGenerated, String origin, List<Source> sources, String modelId, String promptVersion,
                  String level, String springVersion, Instant createdAt, ProjectRequest project) {}
    record Archive(UUID id, String filename, byte[] bytes) {}
}
