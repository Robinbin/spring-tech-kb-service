package com.springtechkb.knowledge;

import java.util.UUID;

/** A prepared contextual chunk plus the metadata shared by lexical and vector search. */
public record KnowledgeChunk(
        UUID id,
        int chunkIndex,
        String content,
        String sourceContent,
        int tokenCount,
        String springModule,
        String springVersionMin,
        String springVersionMax,
        String sectionPath
) {}
