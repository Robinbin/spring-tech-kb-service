package com.springtechkb.search.internal;

import java.util.List;
import java.util.UUID;

import com.springtechkb.knowledge.KnowledgeGraph;
import com.springtechkb.search.KnowledgeQueries;
import com.springtechkb.search.SearchResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeMcpTools {
    private final KnowledgeQueries queries;
    private final KnowledgeGraph graph;
    private final JdbcTemplate jdbc;

    KnowledgeMcpTools(KnowledgeQueries queries, KnowledgeGraph graph, JdbcTemplate jdbc) {
        this.queries = queries;
        this.graph = graph;
        this.jdbc = jdbc;
    }

    @Tool(name = "search_knowledge", description = "Search approved Spring knowledge, returning version, source URL and license attribution.")
    public SearchResponse search(
            @ToolParam(description = "Search question or keywords") String query,
            @ToolParam(required = false, description = "Spring module filter") String module,
            @ToolParam(required = false, description = "Spring version filter") String springVersion) {
        return SearchResponse.from(queries.search(query, module, springVersion, 20));
    }

    @Tool(name = "get_source", description = "Get an approved source by its source UUID, including license URL and author.")
    public KnowledgeQueries.Source source(@ToolParam(description = "Source UUID, not chunk UUID") String sourceId) {
        return queries.source(UUID.fromString(sourceId));
    }

    @Tool(name = "compare_versions", description = "Compare two approved Java API snapshots; returns added, removed and unchanged symbols with source/license attribution.")
    public List<KnowledgeQueries.VersionChange> compare(
            @ToolParam(description = "Fully qualified Java class name") String className,
            @ToolParam(description = "Baseline version") String fromVersion,
            @ToolParam(description = "Target version") String toVersion) {
        return queries.compareVersions(className, fromVersion, toVersion);
    }

    @Tool(name = "check_prerequisites", description = "Get the recursively resolved topological prerequisites for a topic with approved source/license attribution; fails explicitly when prerequisite topics lack approved evidence.")
    public List<Prerequisite> prerequisites(@ToolParam(description = "Knowledge topic UUID") String topicId) {
        return graph.prerequisites(UUID.fromString(topicId)).stream().map(topic -> {
            List<UUID> sources = jdbc.queryForList(
                    "SELECT ks.id FROM knowledge_source ks " +
                    "JOIN knowledge_topic_source kts ON kts.source_id = ks.id " +
                    "WHERE kts.topic_id = ? AND ks.is_approved = TRUE",
                    UUID.class, topic.id());
            if (sources.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Prerequisite topic lacks approved source attribution: " + topic.id());
            return new Prerequisite(topic, sources.stream().map(queries::source).toList(), sources.isEmpty());
        }).toList();
    }

    public record Prerequisite(KnowledgeGraph.Topic topic, List<KnowledgeQueries.Source> sources, boolean lacksApprovedSource) {}
}
