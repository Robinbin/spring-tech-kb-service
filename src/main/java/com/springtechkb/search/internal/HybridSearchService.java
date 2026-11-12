package com.springtechkb.search.internal;

import java.sql.Array;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.springtechkb.knowledge.KnowledgeSource;
import com.springtechkb.search.FtsHit;
import com.springtechkb.search.SearchHit;

/** Combines metadata-filtered vector and PostgreSQL FTS candidates using reciprocal-rank fusion. */
@Service
public class HybridSearchService {

    private static final int CANDIDATE_LIMIT = 100;
    private static final int RRF_CONSTANT = 60;

    private final VectorStore vectorStore;
    private final KnowledgeChunkFtsService ftsService;
    private final JdbcTemplate jdbc;

    HybridSearchService(VectorStore vectorStore, KnowledgeChunkFtsService ftsService, JdbcTemplate jdbc) {
        this.vectorStore = vectorStore;
        this.ftsService = ftsService;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<SearchHit> search(String query, @Nullable String springModule,
            @Nullable String springVersion, int topK) {
        if (query == null || query.isBlank() || topK <= 0) return List.of();
        int boundedTopK = Math.min(topK, CANDIDATE_LIMIT);
        if (springVersion != null && !springVersion.isBlank()) {
            SpringVersionRange.includes(springVersion, null, null);
        }

        SearchRequest.Builder vectorRequest = SearchRequest.builder()
                .query(query)
                .topK(CANDIDATE_LIMIT)
                .similarityThresholdAll();
        FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
        List<FilterExpressionBuilder.Op> filters = new ArrayList<>();
        if (springModule != null && !springModule.isBlank()) {
            filters.add(filterBuilder.eq("spring_module", springModule));
        }
        if (springVersion != null && !springVersion.isBlank()) {
            String versionKey = SpringVersionRange.sortKey(springVersion, false);
            filters.add(filterBuilder.and(
                    filterBuilder.lte("spring_version_min_sort", versionKey),
                    filterBuilder.gte("spring_version_max_sort", versionKey)));
        }
        if (!filters.isEmpty()) {
            FilterExpressionBuilder.Op filter = filters.get(0);
            for (int index = 1; index < filters.size(); index++) {
                filter = filterBuilder.and(filter, filters.get(index));
            }
            vectorRequest.filterExpression(filter.build());
        }
        List<Document> vectorHits = vectorStore.similaritySearch(vectorRequest.build());
        List<FtsHit> lexicalHits = ftsService.search(query,
                springModule == null || springModule.isBlank() ? null : springModule,
                springVersion, CANDIDATE_LIMIT);

        Map<String, Double> scores = new HashMap<>();
        addVectorCandidates(scores, vectorHits, springVersion);
        addFtsCandidates(scores, lexicalHits, springVersion);
        if (scores.isEmpty()) return List.of();

        List<RankedId> ranked = scores.entrySet().stream()
                .map(e -> new RankedId(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingDouble(RankedId::score).reversed())
                .limit(boundedTopK)
                .toList();
        return hydrate(ranked);
    }

    private void addVectorCandidates(Map<String, Double> scores, List<Document> documents,
            @Nullable String version) {
        int rank = 0;
        for (Document document : documents) {
            String id = stringMetadata(document, "chunk_id");
            String candidateId = id == null ? document.getId() : id;
            if (candidateId == null) continue;
            String minimum = stringMetadata(document, "spring_version_min");
            String maximum = stringMetadata(document, "spring_version_max");
            if (!SpringVersionRange.includes(version, minimum, maximum)) continue;
            scores.merge(candidateId, reciprocalRank(++rank), Double::sum);
        }
    }

    private void addFtsCandidates(Map<String, Double> scores, List<FtsHit> hits,
            @Nullable String version) {
        int rank = 0;
        for (FtsHit hit : hits) {
            if (!SpringVersionRange.includes(version, hit.springVersionMin(), hit.springVersionMax())) continue;
            scores.merge(hit.chunkId(), reciprocalRank(++rank), Double::sum);
        }
    }

    private List<SearchHit> hydrate(List<RankedId> ranked) {
        if (ranked.isEmpty()) return List.of();
        List<String> ids = ranked.stream().map(RankedId::id).toList();
        Map<String, SearchHit> hydrated = new HashMap<>();
        jdbc.execute((Connection conn) -> {
            Array uuidArray = conn.createArrayOf("uuid", ids.toArray());
            try (var ps = conn.prepareStatement("""
                    SELECT kc.id, kc.content, kc.spring_version_min, kc.spring_version_max,
                           kc.spring_module, ks.title AS source_title, ks.url AS source_url,
                           ks.license_type, ks.id AS source_id
                    FROM knowledge_chunk kc
                    JOIN knowledge_source ks ON ks.id = kc.source_id
                    WHERE ks.is_approved = TRUE AND kc.id = ANY(?)
                    """)) {
                ps.setArray(1, uuidArray);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String minimum = rs.getString("spring_version_min");
                        String maximum = rs.getString("spring_version_max");
                        String springVersionLabel = minimum == null ? maximum
                                : maximum == null ? minimum : minimum + " - " + maximum;
                        String url = rs.getString("source_url");
                        KnowledgeSource source = url != null && url.contains("github.com")
                                ? KnowledgeSource.SPRING_GITHUB : KnowledgeSource.SPRING_OFFICIAL_DOCS;
                        String licenseType = rs.getString("license_type");
                        String license = "OFFICIAL_DOCS".equals(licenseType)
                                ? "Official Spring Documentation / Apache-2.0" : licenseType;
                        String id = rs.getString("id");
                        hydrated.put(id, new SearchHit(id, rs.getString("content"), 0.0,
                                springVersionLabel, source, rs.getString("source_title"), url,
                                rs.getString("spring_module"), license, rs.getString("source_id")));
                    }
                }
            }
            return null;
        });

        List<SearchHit> result = new ArrayList<>(ranked.size());
        for (RankedId rankedId : ranked) {
            SearchHit hit = hydrated.get(rankedId.id());
            if (hit != null) {
                result.add(new SearchHit(hit.id(), hit.content(), rankedId.score(),
                        hit.springVersion(), hit.source(), hit.sourceTitle(), hit.sourceUrl(),
                        hit.springModule(), hit.sourceLicense(), hit.sourceId()));
            }
        }
        return List.copyOf(result);
    }

    private static double reciprocalRank(int rank) {
        return 1.0 / (RRF_CONSTANT + rank);
    }

    private static String stringMetadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null ? null : value.toString();
    }

    private record RankedId(String id, double score) {}
}
