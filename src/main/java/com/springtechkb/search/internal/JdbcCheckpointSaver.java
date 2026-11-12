package com.springtechkb.search.internal;

import com.springtechkb.search.SearchHit;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;

/**
 * PostgreSQL-backed checkpoint saver for the agentic retrieval graph.
 *
 * <p>State is serialized as JSONB. The {@code hits} key (List&lt;SearchHit&gt;) is
 * handled explicitly to survive Jackson's type-erasure on generic collections.
 */
@Component
class JdbcCheckpointSaver implements BaseCheckpointSaver {

    private static final String INSERT_SQL = """
            INSERT INTO agent_checkpoints (thread_id, checkpoint_id, node_id, next_node_id, state_json, created_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (thread_id, checkpoint_id)
            DO UPDATE SET node_id = EXCLUDED.node_id,
                          next_node_id = EXCLUDED.next_node_id,
                          state_json = EXCLUDED.state_json
            """;

    private static final String SELECT_LATEST_SQL = """
            SELECT checkpoint_id, node_id, next_node_id, state_json
            FROM agent_checkpoints
            WHERE thread_id = ?
            ORDER BY created_at DESC
            LIMIT 1
            """;

    private static final String SELECT_BY_ID_SQL = """
            SELECT checkpoint_id, node_id, next_node_id, state_json
            FROM agent_checkpoints
            WHERE thread_id = ? AND checkpoint_id = ?
            """;

    private static final String SELECT_ALL_SQL = """
            SELECT checkpoint_id, node_id, next_node_id, state_json
            FROM agent_checkpoints
            WHERE thread_id = ?
            ORDER BY created_at DESC
            """;

    private static final String DELETE_SQL =
            "DELETE FROM agent_checkpoints WHERE thread_id = ? RETURNING checkpoint_id, node_id, next_node_id, state_json";

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final JavaType searchHitListType;

    JdbcCheckpointSaver(JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.searchHitListType = jsonMapper.getTypeFactory().constructCollectionType(List.class, SearchHit.class);
    }

    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        String threadId = config.threadId().orElse(THREAD_ID_DEFAULT);
        return jdbc.query(SELECT_ALL_SQL, this::mapCheckpoint, threadId);
    }

    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        String threadId = config.threadId().orElse(THREAD_ID_DEFAULT);
        return config.checkPointId()
                .map(cpId -> jdbc.query(SELECT_BY_ID_SQL, this::mapCheckpoint, threadId, cpId)
                        .stream().findFirst().orElse(null))
                .or(() -> jdbc.query(SELECT_LATEST_SQL, this::mapCheckpoint, threadId)
                        .stream().findFirst());
    }

    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
        String threadId = config.threadId().orElse(THREAD_ID_DEFAULT);
        String stateJson = jsonMapper.writeValueAsString(checkpoint.getState());
        jdbc.update(INSERT_SQL,
                threadId,
                checkpoint.getId(),
                checkpoint.getNodeId(),
                checkpoint.getNextNodeId(),
                stateJson,
                Instant.now());
        return RunnableConfig.builder(config)
                .checkPointId(checkpoint.getId())
                .build();
    }

    @Override
    public Tag release(RunnableConfig config) throws Exception {
        String threadId = config.threadId().orElse(THREAD_ID_DEFAULT);
        List<Checkpoint> released = jdbc.query(DELETE_SQL, this::mapCheckpoint, threadId);
        return new Tag(threadId, released);
    }

    private Checkpoint mapCheckpoint(ResultSet rs, int rowNum) throws java.sql.SQLException {
        String stateJson = rs.getString("state_json");
        Map<String, Object> state = deserializeState(stateJson);
        return Checkpoint.builder()
                .id(rs.getString("checkpoint_id"))
                .nodeId(rs.getString("node_id"))
                .nextNodeId(rs.getString("next_node_id"))
                .state(state)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserializeState(String json) {
        try {
            Map<String, Object> raw = jsonMapper.readValue(json, Map.class);
            // Re-hydrate List<SearchHit> — Jackson deserializes it as List<Map> without explicit type
            Object rawHits = raw.get(KbAgentState.HITS);
            if (rawHits instanceof List<?> list && !list.isEmpty()) {
                raw.put(KbAgentState.HITS,
                        jsonMapper.convertValue(list, searchHitListType));
            }
            return raw;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize checkpoint state", e);
        }
    }
}
