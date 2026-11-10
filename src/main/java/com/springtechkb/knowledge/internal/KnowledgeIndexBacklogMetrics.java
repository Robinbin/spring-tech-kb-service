package com.springtechkb.knowledge.internal;

import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "springtechkb.ingestion.outbox.metrics", name = "enabled", havingValue = "true")
class KnowledgeIndexBacklogMetrics {

    private final JdbcTemplate jdbc;
    private final AtomicLong pending = new AtomicLong();

    KnowledgeIndexBacklogMetrics(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        meters.gauge("knowledge.index.outbox.pending", pending);
    }

    @Scheduled(
            fixedDelayString = "${springtechkb.ingestion.outbox-metrics-refresh-ms:15000}",
            initialDelayString = "${springtechkb.ingestion.outbox-metrics-initial-delay-ms:15000}")
    void refresh() {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM knowledge_index_outbox
                WHERE status IN ('PENDING', 'PROCESSING')
                """, Long.class);
        pending.set(count == null ? 0L : count);
    }
}
