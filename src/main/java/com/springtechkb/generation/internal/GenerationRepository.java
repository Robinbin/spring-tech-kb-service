package com.springtechkb.generation.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import static com.springtechkb.generation.internal.GenerationDtos.*;

@Repository
class GenerationRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    GenerationRepository(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void save(UUID owner, Output output) {
        jdbc.update("""
                INSERT INTO generated_content (id, user_id, kind, payload, created_at)
                VALUES (?, ?, ?, ?::jsonb, ?)
                """, output.id(), owner, output.kind().name(), json.writeValueAsString(output),
                java.sql.Timestamp.from(output.createdAt()));
    }

    Output find(UUID owner, UUID id) {
        List<Output> results = jdbc.query("""
                SELECT payload::text FROM generated_content WHERE id = ? AND user_id = ?
                """, (row, number) -> json.readValue(row.getString(1), Output.class), id, owner);
        if (results.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Generated content not found");
        return results.getFirst();
    }
}
