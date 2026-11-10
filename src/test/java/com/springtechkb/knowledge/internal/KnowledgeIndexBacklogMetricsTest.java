package com.springtechkb.knowledge.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeIndexBacklogMetricsTest {

    @Test
    void refreshPublishesPendingOutboxCount() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(
                """
                SELECT count(*) FROM knowledge_index_outbox
                WHERE status IN ('PENDING', 'PROCESSING')
                """, Long.class)).thenReturn(7L);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        KnowledgeIndexBacklogMetrics metrics = new KnowledgeIndexBacklogMetrics(jdbc, meters);

        assertThat(meters.get("knowledge.index.outbox.pending").gauge().value()).isZero();
        metrics.refresh();

        assertThat(meters.get("knowledge.index.outbox.pending").gauge().value()).isEqualTo(7);
    }
}
