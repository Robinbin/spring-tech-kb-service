package com.springtechkb.knowledge.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.springtechkb.knowledge.KnowledgeIndexedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifies vector projections and retries failed outbox delivery with bounded backoff. */
@Component
public class KnowledgeIndexOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexOutboxDispatcher.class);

    private final JdbcTemplate jdbc;
    private final VectorStore vectorStore;
    private final TransactionTemplate transactions;
    private final int maxAttempts;

    KnowledgeIndexOutboxDispatcher(
            JdbcTemplate jdbc,
            VectorStore vectorStore,
            PlatformTransactionManager transactionManager,
            @Value("${springtechkb.ingestion.outbox-max-attempts:5}") int maxAttempts) {
        this.jdbc = jdbc;
        this.vectorStore = vectorStore;
        this.transactions = new TransactionTemplate(transactionManager);
        this.maxAttempts = maxAttempts;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(KnowledgeIndexedEvent event) {
        dispatch(event.outboxId());
    }

    @Scheduled(
            fixedDelayString = "${springtechkb.ingestion.outbox-poll-ms:30000}",
            initialDelayString = "${springtechkb.ingestion.outbox-initial-delay-ms:30000}")
    public void retryPendingEvents() {
        List<UUID> ids = jdbc.query(
                """
                SELECT id FROM knowledge_index_outbox
                WHERE status IN ('PENDING', 'PROCESSING') AND available_at <= now()
                ORDER BY created_at
                LIMIT 100
                """,
                (rs, row) -> rs.getObject("id", UUID.class));
        ids.forEach(this::dispatch);
    }

    public int rebuildModule(String springModule) {
        List<UUID> sourceIds = jdbc.query(
                """
                SELECT DISTINCT s.id FROM knowledge_source s
                JOIN knowledge_chunk c ON c.source_id = s.id
                WHERE s.spring_module = ?
                ORDER BY s.id
                """,
                (rs, row) -> rs.getObject("id", UUID.class),
                springModule);
        for (UUID sourceId : sourceIds) {
            transactions.executeWithoutResult(status -> rebuildVectors(sourceId));
        }
        return sourceIds.size();
    }

    private void dispatch(UUID outboxId) {
        try {
            transactions.executeWithoutResult(status -> verifyAndComplete(outboxId));
        } catch (RuntimeException exception) {
            recordFailure(outboxId, exception);
            log.error("Knowledge indexing outbox delivery failed for {}", outboxId, exception);
        }
    }

    private void verifyAndComplete(UUID outboxId) {
        Map<String, Object> event = jdbc.queryForMap(
                """
                SELECT source_id, status FROM knowledge_index_outbox
                WHERE id = ? FOR UPDATE
                """,
                outboxId);
        if ("COMPLETED".equals(event.get("status")) || "DEAD_LETTER".equals(event.get("status"))) return;

        UUID sourceId = (UUID) event.get("source_id");
        int chunks = count("SELECT COUNT(*) FROM knowledge_chunk WHERE source_id = ?", sourceId);
        int vectors = count(
                "SELECT COUNT(*) FROM vector_store WHERE metadata ->> 'source_id' = ?",
                sourceId.toString());
        int matchingVectors = count(
                """
                SELECT COUNT(*)
                FROM vector_store v
                JOIN knowledge_chunk c ON v.metadata ->> 'chunk_id' = c.id::text
                WHERE v.metadata ->> 'source_id' = ? AND c.source_id = ?
                """,
                sourceId.toString(), sourceId);
        if (chunks == 0) {
            throw new IllegalStateException("Outbox source has no persisted chunks: " + sourceId);
        }
        if (vectors != chunks || matchingVectors != chunks) {
            rebuildVectors(sourceId);
        }
        jdbc.update(
                """
                UPDATE knowledge_index_outbox
                SET status = 'COMPLETED', completed_at = now(), last_error = NULL
                WHERE id = ?
                """,
                outboxId);
    }

    private void rebuildVectors(UUID sourceId) {
        jdbc.update("DELETE FROM vector_store WHERE metadata ->> 'source_id' = ?", sourceId.toString());
        List<Document> documents = jdbc.query(
                """
                SELECT id, content, spring_module, spring_version_min, spring_version_max
                FROM knowledge_chunk WHERE source_id = ? ORDER BY chunk_index
                """,
                (rs, row) -> {
                    Map<String, Object> metadata = new java.util.HashMap<>();
                    metadata.put("chunk_id", rs.getString("id"));
                    metadata.put("source_id", sourceId.toString());
                    if (rs.getString("spring_module") != null) {
                        metadata.put("spring_module", rs.getString("spring_module"));
                    }
                    if (rs.getString("spring_version_min") != null) {
                        metadata.put("spring_version_min", rs.getString("spring_version_min"));
                    }
                    if (rs.getString("spring_version_max") != null) {
                        metadata.put("spring_version_max", rs.getString("spring_version_max"));
                    }
                    metadata.put("spring_version_min_sort",
                            versionSortKey(rs.getString("spring_version_min"), false));
                    metadata.put("spring_version_max_sort",
                            versionSortKey(rs.getString("spring_version_max"), true));
                    return new Document(rs.getString("id"), rs.getString("content"), metadata);
                },
                sourceId);
        vectorStore.add(documents);
    }

    private String versionSortKey(String version, boolean upperBound) {
        if (version == null) {
            return upperBound ? "999999.999999.999999" : "000000.000000.000000";
        }
        String base = version.endsWith(".x") ? version.substring(0, version.length() - 2) : version;
        String[] parts = base.split("\\.");
        StringBuilder key = new StringBuilder();
        for (int index = 0; index < 3; index++) {
            if (index > 0) key.append('.');
            int segment = upperBound && version.endsWith(".x") && index == 2
                    ? 999_999
                    : index < parts.length ? Integer.parseInt(parts[index]) : 0;
            key.append(String.format(java.util.Locale.ROOT, "%06d", segment));
        }
        return key.toString();
    }

    private int count(String sql, Object... arguments) {
        Integer result = jdbc.queryForObject(sql, Integer.class, arguments);
        return result == null ? 0 : result;
    }

    private void recordFailure(UUID outboxId, RuntimeException exception) {
        Integer attempts = jdbc.queryForObject(
                "SELECT attempt_count FROM knowledge_index_outbox WHERE id = ?",
                Integer.class,
                outboxId);
        if (attempts == null) return;
        int nextAttempt = attempts + 1;
        int delaySeconds = Math.min(3600, (int) Math.pow(2, Math.min(nextAttempt, 10)) * 30);
        jdbc.update(
                """
                UPDATE knowledge_index_outbox
                SET attempt_count = ?, status = ?, available_at = now() + (? * interval '1 second'),
                    last_error = ?
                WHERE id = ?
                """,
                nextAttempt,
                nextAttempt >= maxAttempts ? "DEAD_LETTER" : "PENDING",
                delaySeconds,
                exception.getMessage(),
                outboxId);
    }
}
