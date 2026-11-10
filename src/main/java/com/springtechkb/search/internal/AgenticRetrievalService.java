package com.springtechkb.search.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.springtechkb.search.SearchHit;

/** Performs bounded multi-hop retrieval, with one targeted hop per detected Spring version. */
@Service
public class AgenticRetrievalService {

    private static final int HARD_ITERATION_LIMIT = 5;
    private static final Pattern VERSION = Pattern.compile("\\b\\d+\\.\\d+(?:\\.\\d+)?(?:\\.x)?\\b");
    private static final Pattern COMPARISON_SPLIT = Pattern.compile(
            "(?i)\\b(?:versus|vs|and|between|compare|difference)\\b|以及|和|与|对比|区别");

    private final KnowledgeRetrievalTool retrievalTool;
    private final int maxIterations;

    AgenticRetrievalService(
            KnowledgeRetrievalTool retrievalTool,
            @Value("${springtechkb.rag.agentic.max-iterations:3}") int configuredMaxIterations) {
        this.retrievalTool = retrievalTool;
        this.maxIterations = Math.max(1, Math.min(HARD_ITERATION_LIMIT, configuredMaxIterations));
    }

    public RetrievalResult retrieve(String question, String module, String requestedVersion) {
        List<String> hops = buildHops(question, requestedVersion);
        Map<String, SearchHit> uniqueHits = new LinkedHashMap<>();
        int iterations = 0;
        for (String hop : hops) {
            if (iterations >= maxIterations) break;
            List<SearchHit> hits = retrievalTool.search(
                    hop, module, requestedVersion == null || requestedVersion.isBlank()
                            ? versionFrom(hop) : requestedVersion);
            iterations++;
            for (SearchHit hit : hits) uniqueHits.putIfAbsent(hit.id(), hit);
            if (hits.isEmpty() && iterations > 1) break;
        }
        return new RetrievalResult(List.copyOf(uniqueHits.values()), iterations);
    }

    private List<String> buildHops(String question, String requestedVersion) {
        if (requestedVersion != null && !requestedVersion.isBlank()) {
            return List.of(question, question + " compatibility and behavior");
        }
        List<String> versions = extractVersions(question);
        if (versions.size() >= 2) {
            return versions.stream().map(version -> question + " Spring version " + version).toList();
        }

        String[] parts = COMPARISON_SPLIT.split(question, 3);
        if (parts.length >= 2 && !parts[1].isBlank()) {
            return List.of(question, parts[1].trim());
        }
        return List.of(question, question + " compatibility and behavior");
    }

    private List<String> extractVersions(String question) {
        Matcher matcher = VERSION.matcher(question);
        List<String> versions = new ArrayList<>();
        while (matcher.find()) {
            String version = matcher.group();
            if (!versions.contains(version)) versions.add(version);
        }
        return versions;
    }

    private String versionFrom(String query) {
        Matcher matcher = VERSION.matcher(query);
        String result = null;
        while (matcher.find()) result = matcher.group();
        return result;
    }

    public record RetrievalResult(List<SearchHit> hits, int iterations) {}
}
