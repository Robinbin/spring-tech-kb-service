package com.springtechkb.knowledge.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.springtechkb.knowledge.KnowledgeGraph;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
class JdbcKnowledgeGraph implements KnowledgeGraph {
    private static final String TOPIC_SELECT = """
            SELECT t.id, t.name, t.spring_module, t.description,
                   ARRAY(SELECT prerequisite_id FROM knowledge_prerequisite WHERE topic_id = t.id
                         ORDER BY prerequisite_id) AS prerequisites
            FROM knowledge_topic t
            """;
    private final JdbcTemplate jdbc;

    JdbcKnowledgeGraph(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<Topic> topics() {
        List<Topic> topics = jdbc.query(TOPIC_SELECT + " ORDER BY t.name, t.id LIMIT 1001", this::mapTopic);
        if (topics.size() > 1000) throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "Topic catalog exceeds the supported 1000-topic limit");
        return topics;
    }

    @Override
    public List<Topic> prerequisites(UUID topicId) {
        return orderedClosure(List.of(topicId)).stream().filter(topic -> !topic.id().equals(topicId)).toList();
    }

    @Override
    public List<Topic> orderedClosure(List<UUID> topicIds) {
        if (topicIds == null || topicIds.isEmpty() || topicIds.size() > 50
                || topicIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose between 1 and 50 topics");
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(topicIds.size(), "?"));
        List<Topic> closure = jdbc.query("""
                WITH RECURSIVE closure(id) AS (
                    SELECT id FROM knowledge_topic WHERE id IN (%s)
                    UNION
                    SELECT kp.prerequisite_id FROM knowledge_prerequisite kp JOIN closure c ON kp.topic_id = c.id
                )
                """.formatted(placeholders) + TOPIC_SELECT + " WHERE t.id IN (SELECT id FROM closure)",
                this::mapTopic, topicIds.toArray());
        Set<UUID> actual = new HashSet<>(closure.stream().map(Topic::id).toList());
        if (!actual.containsAll(topicIds)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Topic not found");
        if (closure.size() > 200) throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "Prerequisite closure exceeds 200 topics");
        return topological(closure);
    }

    static List<Topic> topological(List<Topic> topics) {
        Map<UUID, Topic> pending = new LinkedHashMap<>();
        topics.stream().sorted(Comparator.comparing(Topic::name).thenComparing(Topic::id))
                .forEach(topic -> pending.put(topic.id(), topic));
        Set<UUID> visited = new HashSet<>();
        List<Topic> ordered = new ArrayList<>();
        while (!pending.isEmpty()) {
            List<Topic> ready = pending.values().stream()
                    .filter(topic -> visited.containsAll(topic.prerequisites())).toList();
            if (ready.isEmpty()) throw new IllegalStateException("Knowledge graph contains a cycle or dangling edge");
            ready.forEach(topic -> { ordered.add(topic); visited.add(topic.id()); pending.remove(topic.id()); });
        }
        return List.copyOf(ordered);
    }

    @Override
    @Transactional
    public UUID createTopic(String name, String module, String description) {
        try {
            return jdbc.queryForObject("""
                    INSERT INTO knowledge_topic(name, spring_module, description) VALUES (?, ?, ?) RETURNING id
                    """, UUID.class, name, module, description);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Topic name already exists", duplicate);
        }
    }

    @Override
    @Transactional
    public void addPrerequisite(UUID topicId, UUID prerequisiteId) {
        jdbc.execute("SELECT pg_advisory_xact_lock(73519201)");
        List<Topic> ancestors = orderedClosure(List.of(prerequisiteId));
        orderedClosure(List.of(topicId));
        if (ancestors.stream().anyMatch(topic -> topic.id().equals(topicId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Prerequisite would create a cycle");
        }
        jdbc.update("""
                INSERT INTO knowledge_prerequisite(topic_id, prerequisite_id) VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """, topicId, prerequisiteId);
    }

    @Override
    @Transactional
    public void removePrerequisite(UUID topicId, UUID prerequisiteId) {
        jdbc.execute("SELECT pg_advisory_xact_lock(73519201)");
        jdbc.update("DELETE FROM knowledge_prerequisite WHERE topic_id = ? AND prerequisite_id = ?",
                topicId, prerequisiteId);
    }

    @Override
    public List<UUID> approvedSourceIds(UUID topicId) {
        return jdbc.queryForList("""
                SELECT DISTINCT ks.id FROM knowledge_source ks
                JOIN knowledge_chunk kc ON kc.source_id = ks.id
                WHERE ks.is_approved = TRUE AND kc.topic_id = ?
                ORDER BY ks.id
                """, UUID.class, topicId);
    }

    private Topic mapTopic(ResultSet rs, int row) throws SQLException {
        Object[] values = (Object[]) rs.getArray("prerequisites").getArray();
        return new Topic(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("spring_module"),
                rs.getString("description"), Arrays.stream(values).map(UUID.class::cast).toList());
    }
}
