package com.springtechkb.search.internal;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springtechkb.search.ClassifiedQuery;
import com.springtechkb.search.RagCitation;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pgvector-backed semantic answer cache with strict version/intent/domain isolation. */
@Service
public class SemanticAnswerCache {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int EXPECTED_DIMENSIONS = 1024;
    private static final TypeReference<List<RagCitation>> CITATIONS_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;
    private final double minimumSimilarity;
    private final int ttlHours;

    SemanticAnswerCache(
            JdbcTemplate jdbc,
            EmbeddingModel embeddingModel,
            @Value("${springtechkb.rag.semantic-cache.minimum-similarity:0.88}") double minimumSimilarity,
            @Value("${springtechkb.rag.semantic-cache.ttl-hours:6}") int ttlHours) {
        if (minimumSimilarity < 0 || minimumSimilarity > 1 || ttlHours <= 0) {
            throw new IllegalArgumentException("Invalid semantic cache similarity threshold or TTL");
        }
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        this.minimumSimilarity = minimumSimilarity;
        this.ttlHours = ttlHours;
    }

    @Transactional(readOnly = true)
    public Optional<CachedRagAnswer> find(
            String question, ClassifiedQuery classified, String springVersion, String springModule) {
        return find(question, classified, springVersion, springModule, "v1");
    }

    @Transactional(readOnly = true)
    public Optional<CachedRagAnswer> find(
            String question,
            ClassifiedQuery classified,
            String springVersion,
            String springModule,
            String promptVersion) {
        if (!classified.intent().cacheable()) return Optional.empty();
        String version = normalizedVersion(springVersion);
        float[] embedding = embed(question);
        String vector = vectorLiteral(embedding);
        HnswAwarePgVectorStore.enableIterativeScan(jdbc);
        return jdbc.query(
                """
                SELECT answer, citations::text, 1 - (embedding <=> ?::vector) AS similarity
                FROM rag_semantic_cache
                WHERE spring_version = ?
                  AND intent = ?
                  AND knowledge_domain = ?
                  AND spring_module IS NOT DISTINCT FROM ?
                  AND prompt_version = ?
                  AND expires_at > now()
                  AND 1 - (embedding <=> ?::vector) >= ?
                ORDER BY embedding <=> ?::vector
                LIMIT 1
                """,
                rs -> {
                    if (!rs.next()) return Optional.empty();
                    try {
                        return Optional.of(new CachedRagAnswer(
                                rs.getString("answer"),
                                JSON.readValue(rs.getString("citations"), CITATIONS_TYPE)));
                    } catch (JsonProcessingException exception) {
                        throw new IllegalStateException("Could not decode semantic-cache citations", exception);
                    }
                },
                vector, version, classified.intent().name(), classified.knowledgeDomain(), springModule,
                promptVersion,
                vector, minimumSimilarity, vector);
    }

    @Transactional
    public void put(
            String question,
            String answer,
            List<RagCitation> citations,
            ClassifiedQuery classified,
            String springVersion,
            String springModule) {
        put(question, answer, citations, classified, springVersion, springModule, "v1");
    }

    @Transactional
    public void put(
            String question,
            String answer,
            List<RagCitation> citations,
            ClassifiedQuery classified,
            String springVersion,
            String springModule,
            String promptVersion) {
        if (!classified.intent().cacheable() || answer == null || answer.isBlank()) return;
        float[] embedding = embed(question);
        String citationsJson = json(citations);
        String citationIdsJson = json(citations.stream().map(RagCitation::chunkId).toList());
        jdbc.update(
                """
                DELETE FROM rag_semantic_cache
                WHERE spring_version = ? AND intent = ? AND knowledge_domain = ?
                  AND spring_module IS NOT DISTINCT FROM ? AND prompt_version = ? AND question = ?
                """,
                normalizedVersion(springVersion), classified.intent().name(),
                classified.knowledgeDomain(), springModule, promptVersion, question);
        jdbc.update(
                """
                INSERT INTO rag_semantic_cache
                    (spring_version, intent, knowledge_domain, spring_module, prompt_version, question, answer,
                     citations, citation_chunk_ids, embedding, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::vector,
                        now() + (? * INTERVAL '1 hour'))
                """,
                normalizedVersion(springVersion), classified.intent().name(),
                classified.knowledgeDomain(), springModule, promptVersion, question, answer,
                citationsJson, citationIdsJson, vectorLiteral(embedding), ttlHours);
    }

    private float[] embed(String question) {
        float[] vector = embeddingModel.embed(question);
        if (vector == null || vector.length != EXPECTED_DIMENSIONS) {
            throw new IllegalStateException("Semantic cache requires a 1024-dimensional embedding");
        }
        return Arrays.copyOf(vector, vector.length);
    }

    private String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize semantic-cache value", exception);
        }
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) literal.append(',');
            literal.append(Float.toString(vector[i]));
        }
        return literal.append(']').toString();
    }

    private static String normalizedVersion(String version) {
        return version == null || version.isBlank() ? "latest" : version.trim();
    }
}
