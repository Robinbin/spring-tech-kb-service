package com.springtechkb.knowledge.internal;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springtechkb.knowledge.CrawledDocument;
import com.springtechkb.knowledge.KnowledgeChunk;
import com.springtechkb.knowledge.KnowledgeIndexedEvent;
import com.springtechkb.knowledge.SourceAdmission;
import com.springtechkb.knowledge.internal.SourceAdmissionPolicy;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically persists approved source text, chunks, FTS documents, vectors and an outbox record. */
@Service
public class KnowledgeIngestionService {

    private final JdbcTemplate jdbc;
    private final VectorStore vectorStore;
    private final ContextualChunker chunker;
    private final SourceAdmissionPolicy admissionPolicy;
    private final InstructionInjectionDetector injectionDetector;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;

    KnowledgeIngestionService(
            JdbcTemplate jdbc,
            VectorStore vectorStore,
            ContextualChunker chunker,
            SourceAdmissionPolicy admissionPolicy,
            InstructionInjectionDetector injectionDetector,
            ApplicationEventPublisher events,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.vectorStore = vectorStore;
        this.chunker = chunker;
        this.admissionPolicy = admissionPolicy;
        this.injectionDetector = injectionDetector;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public IngestionResult ingest(SourceAdmission admission, CrawledDocument document) {
        admissionPolicy.validate(admission, true);
        if (!admission.uri().normalize().equals(document.uri().normalize())) {
            throw new IllegalArgumentException("Crawled URI does not match the admitted source URI");
        }
        injectionDetector.assertSafe(document.text());
        List<KnowledgeChunk> chunks = chunker.chunk(document, admission);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("Refusing to ingest a document without knowledge chunks");
        }

        UUID sourceId = upsertSource(admission, document);
        // Delete via VectorStore abstraction so this survives a backend change
        vectorStore.delete(new FilterExpressionBuilder().eq("source_id", sourceId.toString()).build());
        jdbc.update("DELETE FROM knowledge_chunk WHERE source_id = ?", sourceId);
        insertChunks(sourceId, chunks);

        List<Document> vectorDocuments = chunks.stream()
                .map(chunk -> vectorDocument(sourceId, chunk))
                .toList();
        vectorStore.add(vectorDocuments);

        UUID outboxId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO knowledge_index_outbox (id, source_id, event_type, payload)
                VALUES (?, ?, 'SOURCE_REINDEXED', ?::jsonb)
                """,
                outboxId, sourceId, outboxPayload(sourceId, document.contentHash(), chunks.size()));
        events.publishEvent(new KnowledgeIndexedEvent(outboxId, sourceId));
        return new IngestionResult(sourceId, outboxId, chunks.size(), document.contentHash());
    }

    private UUID upsertSource(SourceAdmission admission, CrawledDocument document) {
        return jdbc.queryForObject(
                """
                INSERT INTO knowledge_source
                    (title, url, license_type, author, spring_module, version, is_approved,
                     crawled_at, content_hash, etag, last_modified, license_url,
                     approved_by, approval_reference)
                VALUES (?, ?, ?, ?, ?, ?, TRUE, now(), ?, ?, ?, ?, ?, ?)
                ON CONFLICT (url) DO UPDATE SET
                    title = EXCLUDED.title,
                    license_type = EXCLUDED.license_type,
                    author = EXCLUDED.author,
                    spring_module = EXCLUDED.spring_module,
                    version = EXCLUDED.version,
                    is_approved = TRUE,
                    crawled_at = now(),
                    content_hash = EXCLUDED.content_hash,
                    etag = EXCLUDED.etag,
                    last_modified = EXCLUDED.last_modified,
                    license_url = EXCLUDED.license_url,
                    approved_by = EXCLUDED.approved_by,
                    approval_reference = EXCLUDED.approval_reference
                RETURNING id
                """,
                UUID.class,
                admission.title(),
                admission.uri().toString(),
                admission.license().databaseValue(),
                admission.author(),
                admission.springModule(),
                admission.version(),
                document.contentHash(),
                document.etag(),
                document.lastModified(),
                admission.licenseUrl(),
                admission.approvedBy(),
                admission.approvalReference()
        );
    }

    private void insertChunks(UUID sourceId, List<KnowledgeChunk> chunks) {
        jdbc.batchUpdate(
                """
                INSERT INTO knowledge_chunk
                    (id, source_id, content, chunk_index, token_count, spring_module,
                     spring_version_min, spring_version_max)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement ps, int index)
                            throws java.sql.SQLException {
                        KnowledgeChunk chunk = chunks.get(index);
                        ps.setObject(1, chunk.id());
                        ps.setObject(2, sourceId);
                        ps.setString(3, chunk.content());
                        ps.setInt(4, chunk.chunkIndex());
                        ps.setInt(5, chunk.tokenCount());
                        ps.setString(6, chunk.springModule());
                        ps.setString(7, chunk.springVersionMin());
                        ps.setString(8, chunk.springVersionMax());
                    }

                    @Override
                    public int getBatchSize() {
                        return chunks.size();
                    }
                });
        jdbc.batchUpdate(
                "INSERT INTO knowledge_chunk_review (chunk_id, status) VALUES (?, 'PENDING')",
                chunks,
                100,
                (ps, chunk) -> ps.setObject(1, chunk.id()));
    }

    private Document vectorDocument(UUID sourceId, KnowledgeChunk chunk) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("chunk_id", chunk.id().toString());
        metadata.put("source_id", sourceId.toString());
        metadata.put("spring_module", chunk.springModule());
        if (chunk.springVersionMin() != null) {
            metadata.put("spring_version_min", chunk.springVersionMin());
        }
        if (chunk.springVersionMax() != null) {
            metadata.put("spring_version_max", chunk.springVersionMax());
        }
        metadata.put("spring_version_min_sort", versionSortKey(chunk.springVersionMin(), false));
        metadata.put("spring_version_max_sort", versionSortKey(chunk.springVersionMax(), true));
        metadata.put("section_path", chunk.sectionPath());
        return new Document(chunk.id().toString(), chunk.content(), metadata);
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

    private String outboxPayload(UUID sourceId, String contentHash, int chunkCount) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sourceId", sourceId);
        payload.put("contentHash", contentHash);
        payload.put("chunkCount", chunkCount);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize knowledge indexing outbox event", exception);
        }
    }

    public record IngestionResult(UUID sourceId, UUID outboxId, int chunkCount, String contentHash) {}
}
