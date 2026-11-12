package com.springtechkb.search.internal;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
class KnowledgeDetailController {

    private static final String DETAIL_SQL = """
            SELECT kc.id, kc.content, kc.spring_module, kc.spring_version_min, kc.spring_version_max,
                   ks.id AS source_id, ks.title, ks.url, ks.license_type, ks.license_url, ks.author
            FROM knowledge_chunk kc
            JOIN knowledge_source ks ON ks.id = kc.source_id
            WHERE kc.id = ? AND ks.is_approved = TRUE
            """;

    private final JdbcTemplate jdbc;

    KnowledgeDetailController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/knowledge/{id}")
    KnowledgeDetail detail(@PathVariable UUID id) {
        return jdbc.query(DETAIL_SQL,
                        (rs, rowNum) -> new KnowledgeDetail(
                                rs.getObject("id", UUID.class),
                                rs.getString("content"),
                                rs.getString("spring_module"),
                                rs.getString("spring_version_min"),
                                rs.getString("spring_version_max"),
                                new DetailSource(
                                        rs.getObject("source_id", UUID.class),
                                        rs.getString("title"),
                                        rs.getString("url"),
                                        rs.getString("license_type"),
                                        rs.getString("license_url"),
                                        rs.getString("author"))),
                        id)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Knowledge chunk was not found"));
    }

    record KnowledgeDetail(
            UUID id, String content, String springModule, String springVersionMin, String springVersionMax,
            DetailSource source) {}

    record DetailSource(UUID id, String title, String url, String license, String licenseUrl, String author) {}
}
