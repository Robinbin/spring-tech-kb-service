package com.springtechkb.search.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.springtechkb.knowledge.KnowledgeGraph;
import com.springtechkb.knowledge.KnowledgeSource;
import com.springtechkb.search.KnowledgeQueries;
import com.springtechkb.search.SearchHit;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.jdbc.core.JdbcTemplate;

class KnowledgeMcpToolsTest {
    @Test
    void toolCallbacksUseFrozenSchemasAndReturnAttribution() {
        var queries = mock(KnowledgeQueries.class);
        var graph = mock(KnowledgeGraph.class);
        var jdbc = mock(JdbcTemplate.class);
        UUID sourceId = UUID.randomUUID(), topicId = UUID.randomUUID(), prerequisiteId = UUID.randomUUID();
        var source = new KnowledgeQueries.Source(sourceId, "Spring", "https://docs.spring.io/",
                "APACHE_2", null, "Spring");
        when(queries.search("beans", null, null, 20)).thenReturn(List.of(new SearchHit(
                UUID.randomUUID().toString(), "Bean lifecycle", 1, "6.1", KnowledgeSource.SPRING_OFFICIAL_DOCS,
                "Spring", source.url(), "core", source.license(), sourceId.toString())));
        when(queries.source(sourceId)).thenReturn(source);
        when(queries.compareVersions("org.example.Bean", "6.1", "6.2")).thenReturn(List.of(
                new KnowledgeQueries.VersionChange("org.example.Bean", "METHOD", "void next()", "6.2", "ADDED", source)));
        when(graph.prerequisites(topicId)).thenReturn(List.of(
                new KnowledgeGraph.Topic(prerequisiteId, "Beans", "core", "", List.of())));
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(prerequisiteId))).thenReturn(List.of(sourceId));
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(new KnowledgeMcpTools(queries, graph, jdbc)).build().getToolCallbacks();
        assertThat(callbacks).hasSize(4);
        assertThat(call(callbacks, "search_knowledge", "{\"query\":\"beans\"}")).contains("APACHE_2", "https://docs.spring.io/");
        assertThat(call(callbacks, "get_source", "{\"sourceId\":\"" + sourceId + "\"}")).contains("APACHE_2");
        assertThat(call(callbacks, "compare_versions",
                "{\"className\":\"org.example.Bean\",\"fromVersion\":\"6.1\",\"toVersion\":\"6.2\"}"))
                .contains("ADDED", "APACHE_2");
        assertThat(call(callbacks, "check_prerequisites", "{\"topicId\":\"" + topicId + "\"}"))
                .contains("Beans", "APACHE_2");
    }

    private static String call(ToolCallback[] callbacks, String name, String json) {
        return Arrays.stream(callbacks).filter(tool -> tool.getToolDefinition().name().equals(name))
                .findFirst().orElseThrow().call(json);
    }
}
