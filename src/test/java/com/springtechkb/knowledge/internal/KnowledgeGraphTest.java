package com.springtechkb.knowledge.internal;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import com.springtechkb.knowledge.KnowledgeGraph.Topic;
import org.junit.jupiter.api.Test;

class KnowledgeGraphTest {
    @Test
    void sortsDiamondsWithoutDuplicatingSharedPrerequisites() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        Topic first = new Topic(a, "A", "core", "", List.of());
        Topic left = new Topic(b, "B", "core", "", List.of(a));
        Topic right = new Topic(c, "C", "core", "", List.of(a));
        Topic last = new Topic(d, "D", "core", "", List.of(b, c));
        assertThat(JdbcKnowledgeGraph.topological(List.of(last, right, first, left)))
                .containsExactly(first, left, right, last);
    }

    @Test
    void rejectsCyclesAndMissingPrerequisites() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Topic left = new Topic(a, "A", "core", "", List.of(b));
        Topic right = new Topic(b, "B", "core", "", List.of(a));
        assertThatThrownBy(() -> JdbcKnowledgeGraph.topological(List.of(left, right)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JdbcKnowledgeGraph.topological(List.of(left)))
                .isInstanceOf(IllegalStateException.class);
    }
}
