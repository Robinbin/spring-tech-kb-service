package com.springtechkb.search.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.springtechkb.search.KnowledgeQueries;
import com.springtechkb.search.SearchHit;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
class ApprovedKnowledgeQueries implements KnowledgeQueries {
    private final HybridSearchService search;
    private final JdbcTemplate jdbc;

    ApprovedKnowledgeQueries(HybridSearchService search, JdbcTemplate jdbc) {
        this.search = search;
        this.jdbc = jdbc;
    }

    @Override
    public List<SearchHit> search(String query, String module, String version, int limit) {
        if (query == null || query.isBlank() || query.length() > 10000 || limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid search query or limit");
        }
        return search.search(query, module, version, limit);
    }

    @Override
    public Source source(UUID id) {
        return jdbc.query("""
                SELECT id, title, url, license_type, license_url, author
                FROM knowledge_source WHERE id = ? AND is_approved = TRUE
                """, (rs, row) -> new Source(rs.getObject("id", UUID.class), rs.getString("title"),
                rs.getString("url"), rs.getString("license_type"), rs.getString("license_url"),
                rs.getString("author")), id).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Approved source not found"));
    }

    @Override
    public List<VersionChange> compareVersions(String className, String fromVersion, String toVersion) {
        if (className == null || !className.matches("[a-zA-Z_$][a-zA-Z0-9_$.]{0,499}")
                || fromVersion == null || toVersion == null || fromVersion.length() > 50 || toVersion.length() > 50
                || fromVersion.isBlank() || toVersion.isBlank() || fromVersion.equals(toVersion)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide a Java class and two distinct versions");
        }
        List<VersionChange> symbols = jdbc.query("""
                SELECT s.class_name, s.symbol_kind, s.signature, s.spring_version, s.change_type,
                       ks.id, ks.title, ks.url, ks.license_type, ks.license_url, ks.author
                FROM knowledge_api_symbol s JOIN knowledge_source ks ON ks.id = s.source_id
                WHERE ks.is_approved = TRUE AND s.class_name = ? AND s.spring_version IN (?, ?)
                ORDER BY s.spring_version, s.symbol_kind, s.signature, ks.id
                LIMIT 2001
                """, (rs, row) -> new VersionChange(rs.getString("class_name"), rs.getString("symbol_kind"),
                rs.getString("signature"), rs.getString("spring_version"), rs.getString("change_type"),
                new Source(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("url"),
                        rs.getString("license_type"), rs.getString("license_url"), rs.getString("author"))),
                className, fromVersion, toVersion);
        if (symbols.size() > 2000) throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "Version comparison exceeds 2000 API symbols");
        if (symbols.stream().noneMatch(s -> fromVersion.equals(s.version()))
                || symbols.stream().noneMatch(s -> toVersion.equals(s.version()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Approved API snapshots required for both versions");
        }
        Map<String, VersionChange> before = new LinkedHashMap<>();
        Map<String, VersionChange> after = new LinkedHashMap<>();
        for (VersionChange symbol : symbols) {
            if ("REMOVED".equals(symbol.changeType())) continue;
            (fromVersion.equals(symbol.version()) ? before : after).putIfAbsent(
                    symbol.kind() + ":" + symbol.signature(), symbol);
        }
        List<VersionChange> changes = new ArrayList<>();
        before.forEach((key, symbol) -> {
            if (!after.containsKey(key)) changes.add(withChange(symbol, "REMOVED"));
        });
        after.forEach((key, symbol) -> changes.add(withChange(symbol, before.containsKey(key) ? "UNCHANGED" : "ADDED")));
        return List.copyOf(changes);
    }

    @Override
    public Optional<KnowledgeQueries.ChunkDetail> chunkDetail(UUID id) {
        return jdbc.query("""
                SELECT kc.id, kc.content, kc.spring_module, kc.spring_version_min, kc.spring_version_max,
                       ks.id AS source_id, ks.title, ks.url, ks.license_type, ks.license_url, ks.author
                FROM knowledge_chunk kc
                JOIN knowledge_source ks ON ks.id = kc.source_id
                WHERE kc.id = ? AND ks.is_approved = TRUE
                """, (rs, rowNum) -> new KnowledgeQueries.ChunkDetail(
                rs.getObject("id", UUID.class),
                rs.getString("content"),
                rs.getString("spring_module"),
                rs.getString("spring_version_min"),
                rs.getString("spring_version_max"),
                new KnowledgeQueries.Source(
                        rs.getObject("source_id", UUID.class),
                        rs.getString("title"),
                        rs.getString("url"),
                        rs.getString("license_type"),
                        rs.getString("license_url"),
                        rs.getString("author"))), id)
                .stream().findFirst();
    }

    private VersionChange withChange(VersionChange symbol, String change) {
        return new VersionChange(symbol.className(), symbol.kind(), symbol.signature(), symbol.version(), change, symbol.source());
    }
}
