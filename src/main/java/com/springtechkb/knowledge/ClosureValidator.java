package com.springtechkb.knowledge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.springtechkb.knowledge.KnowledgeGraph.Topic;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared validation logic for topic prerequisite closures.
 *
 * <p>Both the Assessment and Learning modules perform identical topological-order checks on the
 * result of {@link com.springtechkb.knowledge.KnowledgeGraph#orderedClosure}. This utility
 * centralises that logic so it does not drift between callers.
 */
public final class ClosureValidator {

    private ClosureValidator() {}

    /**
     * Verifies that every prerequisite in {@code closure} appears at a lower index than the topic
     * that depends on it (topological order). Throws HTTP 400 on violation.
     */
    public static void assertTopologicalOrder(List<Topic> closure) {
        Map<UUID, Integer> positions = new HashMap<>();
        for (int i = 0; i < closure.size(); i++) {
            positions.put(closure.get(i).id(), i);
        }
        for (int i = 0; i < closure.size(); i++) {
            List<UUID> prerequisites = closure.get(i).prerequisites();
            if (prerequisites == null) continue;
            for (UUID prerequisite : prerequisites) {
                Integer position = positions.get(prerequisite);
                if (position == null || position >= i) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "The knowledge graph returned a non-topological prerequisite closure");
                }
            }
        }
    }
}
