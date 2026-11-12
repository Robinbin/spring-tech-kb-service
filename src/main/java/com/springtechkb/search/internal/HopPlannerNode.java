package com.springtechkb.search.internal;

import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Graph node: plans the ordered list of retrieval hop queries from the original question. */
@Component
class HopPlannerNode implements AsyncNodeAction<KbAgentState> {

    private static final Pattern VERSION = Pattern.compile("\\b\\d+\\.\\d+(?:\\.\\d+)?(?:\\.x)?\\b");
    private static final Pattern COMPARISON_SPLIT = Pattern.compile(
            "(?i)\\b(?:versus|vs|and|between|compare|difference)\\b|以及|和|与|对比|区别");

    @Override
    public CompletableFuture<Map<String, Object>> apply(KbAgentState state) {
        String question = state.question().orElseThrow(() -> new IllegalArgumentException("question missing"));
        String version  = state.version().orElse("");
        List<String> hops = buildHops(question, version);
        return CompletableFuture.completedFuture(Map.of(KbAgentState.HOPS, hops));
    }

    List<String> buildHops(String question, String requestedVersion) {
        if (requestedVersion != null && !requestedVersion.isBlank()) {
            return List.of(question, question + " compatibility and behavior");
        }
        List<String> versions = extractVersions(question);
        if (versions.size() >= 2) {
            return versions.stream()
                    .map(v -> question + " Spring version " + v)
                    .toList();
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
            String v = matcher.group();
            if (!versions.contains(v)) versions.add(v);
        }
        return versions;
    }

    static String versionFrom(String query) {
        Matcher matcher = VERSION.matcher(query);
        String result = null;
        while (matcher.find()) result = matcher.group();
        return result;
    }
}
