package com.springtechkb.knowledge.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.springtechkb.knowledge.CrawledDocument;
import com.springtechkb.knowledge.KnowledgeChunk;
import com.springtechkb.knowledge.SourceAdmission;

/** Splits cleaned documentation into bounded, overlapping chunks with stable context prefixes. */
@Component
public class ContextualChunker {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int CHUNK_TOKENS = 512;
    private static final int OVERLAP_TOKENS = 64;

    public List<KnowledgeChunk> chunk(CrawledDocument document, SourceAdmission source) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        String currentSection = source.title();
        StringBuilder sectionText = new StringBuilder();
        int chunkIndex = 0;
        for (String line : document.text().split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.matches("^#{1,6}\\s+.+")) {
                chunkIndex = appendSectionChunks(chunks, source, currentSection, sectionText.toString(), chunkIndex);
                sectionText.setLength(0);
                currentSection = trimmed.replaceFirst("^#{1,6}\\s+", "");
            } else if (!trimmed.isBlank()) {
                if (!sectionText.isEmpty()) sectionText.append(' ');
                sectionText.append(trimmed);
            }
        }
        appendSectionChunks(chunks, source, currentSection, sectionText.toString(), chunkIndex);
        return List.copyOf(chunks);
    }

    private int appendSectionChunks(
            List<KnowledgeChunk> chunks,
            SourceAdmission source,
            String section,
            String sectionText,
            int chunkIndex) {
        List<String> sectionTokens = tokens(sectionText);
        if (sectionTokens.isEmpty()) return chunkIndex;
        String context = contextPrefix(source, section);
        int bodyLimit = CHUNK_TOKENS - tokenCount(context);
        if (bodyLimit <= OVERLAP_TOKENS) {
            throw new IllegalArgumentException("Context metadata leaves no room for source content");
        }
        int bodyStart = 0;
        while (bodyStart < sectionTokens.size()) {
            int bodyEnd = Math.min(bodyStart + bodyLimit, sectionTokens.size());
            String body = String.join(" ", sectionTokens.subList(bodyStart, bodyEnd));
            String enriched = context + body;
            chunks.add(new KnowledgeChunk(
                    UUID.randomUUID(),
                    chunkIndex++,
                    enriched,
                    body,
                    tokenCount(enriched),
                    source.springModule(),
                    source.version(),
                    null,
                    section
            ));
            if (bodyEnd == sectionTokens.size()) break;
            bodyStart = bodyEnd - OVERLAP_TOKENS;
        }
        return chunkIndex;
    }

    private String contextPrefix(SourceAdmission source, String section) {
        return "[Knowledge point: " + source.title()
                + "; class: " + (source.javaClassName() == null ? "not specified" : source.javaClassName())
                + "; module: " + source.springModule()
                + "; version: " + (source.version() == null ? "all supported versions" : source.version())
                + "; section: " + section + "] ";
    }

    private int tokenCount(String content) {
        return tokens(content).size();
    }

    private List<String> tokens(String content) {
        String trimmed = content.trim();
        return trimmed.isEmpty() ? List.of() : List.of(WHITESPACE.split(trimmed));
    }
}
