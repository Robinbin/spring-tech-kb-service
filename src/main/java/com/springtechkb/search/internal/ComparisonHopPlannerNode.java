package com.springtechkb.search.internal;

import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Graph node: plans parallel per-version hops for comparison queries.
 * Produces one focused query per detected version, plus a diff-level query.
 */
@Component
class ComparisonHopPlannerNode implements AsyncNodeAction<KbAgentState> {

    private static final Pattern VERSION = Pattern.compile("\\b\\d+\\.\\d+(?:\\.\\d+)?(?:\\.x)?\\b");

    @Override
    public CompletableFuture<Map<String, Object>> apply(KbAgentState state) {
        String question = state.question().orElseThrow();
        List<String> versions = extractVersions(question);

        List<String> hops = new ArrayList<>();
        if (versions.size() >= 2) {
            for (String v : versions) {
                hops.add(question + " Spring version " + v);
            }
        } else {
            hops.add(question);
            hops.add(question + " differences migration changes");
        }
        hops.add(question + " compatibility breaking changes");

        return CompletableFuture.completedFuture(Map.of(KbAgentState.HOPS, List.copyOf(hops)));
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
}
