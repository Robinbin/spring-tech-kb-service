package com.springtechkb.search.internal;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.springtechkb.search.ClassifiedQuery;
import com.springtechkb.search.RagCitation;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Pgvector-backed semantic answer cache with strict version/intent/domain isolation. */
@Service
public class SemanticAnswerCache {

    private static final TypeReference<List<RagCitation>> CITATIONS_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;
    private final JsonMapper objectMapper;
    private final double minimumSimilarity;
    private final int ttlHours;
    private final int expectedDimensions;

    SemanticAnswerCache(
            JdbcTemplate jdbc,
            EmbeddingModel embeddingModel,
            JsonMapper objectMapper,
            @Value("${springtechkb.rag.semantic-cache.minimum-similarity:0.88}") double minimumSimilarity,
            @Value("${springtechkb.rag.semantic-cache.ttl-hours:6}") int ttlHours,
            @Value("${springtechkb.rag.embedding-dimensions:1024}") int expectedDimensions) {
        if (minimumSimilarity < 0 || minimumSimilarity > 1 || ttlHours <= 0 || expectedDimensions < 1) {
            throw new IllegalArgumentException("Invalid semantic cache similarity threshold, TTL, or embedding dimensions");
        }
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
        this.minimumSimilarity = minimumSimilarity;
        this.ttlHours = ttlHours;
        this.expectedDimensions = expectedDimensions;
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
                                objectMapper.readValue(rs.getString("citations"), CITATIONS_TYPE)));
                    } catch (JacksonException exception) {
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
        if (vector == null || vector.length != expectedDimensions) {
            throw new IllegalStateException(
                    "Semantic cache requires a " + expectedDimensions + "-dimensional embedding, got "
                    + (vector == null ? "null" : vector.length));
        }
        return Arrays.copyOf(vector, vector.length);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
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
