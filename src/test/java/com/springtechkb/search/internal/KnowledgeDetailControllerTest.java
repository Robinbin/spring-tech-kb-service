package com.springtechkb.search.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

class KnowledgeDetailControllerTest {

    @Test
    void returnsApprovedKnowledgeWithAttributionAndVersionBounds() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID id = UUID.randomUUID();
        var source = new KnowledgeDetailController.DetailSource(UUID.randomUUID(),
                "Spring", "https://docs.spring.io/", "APACHE_2", "https://www.apache.org/licenses/LICENSE-2.0", "Spring");
        var detail = new KnowledgeDetailController.KnowledgeDetail(id, "Context", "core", "6.0", "6.2", source);
        when(jdbc.query(contains("ks.is_approved = TRUE"),
                org.mockito.ArgumentMatchers.<RowMapper<KnowledgeDetailController.KnowledgeDetail>>any(),
                eq(id))).thenReturn(List.of(detail));

        assertThat(new KnowledgeDetailController(jdbc).detail(id)).isEqualTo(detail);
    }

    @Test
    void missingOrUnapprovedKnowledgeIsNotExposed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID id = UUID.randomUUID();
        when(jdbc.query(contains("ks.is_approved = TRUE"),
                org.mockito.ArgumentMatchers.<RowMapper<KnowledgeDetailController.KnowledgeDetail>>any(),
                eq(id))).thenReturn(List.of());

        assertThatThrownBy(() -> new KnowledgeDetailController(jdbc).detail(id))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }
}
