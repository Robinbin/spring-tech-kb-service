package com.springtechkb.search.internal;

import java.util.List;
import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.springtechkb.search.FtsHit;

/**
 * B3：PostgreSQL FTS 词法检索服务。
 *
 * <h2>实现细节</h2>
 * <ul>
 *   <li>{@code websearch_to_tsquery} — 支持短语（双引号）和 AND/OR 布尔运算；
 *       空停用词查询返回空结果而非报错</li>
 *   <li>{@code ts_rank_cd(query, 1)} — Cover Density 排名 + 文档长度归一化，
 *       近似 BM25 行为；norm=1 即 divide-by-1+log(length)</li>
 *   <li>中文分词方案待 DEC-03 决策后通过新 Flyway 迁移切换（目前 English 分词可处理
 *       大多数英文 Spring 技术术语）</li>
 * </ul>
 */
@Component
public class KnowledgeChunkFtsService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeChunkFtsService.class);

    private final JdbcTemplate jdbc;

    KnowledgeChunkFtsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 执行 FTS 查询，返回按相关性降序排列的 chunk 列表。
     *
     * @param queryText   自然语言查询（支持 {@code websearch_to_tsquery} 语法）
     * @param springModule 可选的 Spring 子模块过滤（null = 不过滤）
     * @param topK        最大返回条数
     * @return FTS 命中列表
     */
    @Transactional(readOnly = true)
    public List<FtsHit> search(String queryText, @Nullable String springModule, int topK) {
        return search(queryText, springModule, null, topK);
    }

    @Transactional(readOnly = true)
    public List<FtsHit> search(
            String queryText, @Nullable String springModule, @Nullable String springVersion, int topK) {
        if (queryText == null || queryText.isBlank() || topK <= 0) {
            return List.of();
        }
        int boundedTopK = Math.min(topK, 100);
        log.debug("FTS search: query='{}', module={}, topK={}", queryText, springModule, topK);

        String moduleFilter = springModule == null ? "" : "AND kc.spring_module = ? ";
        String versionFilter = springVersion == null || springVersion.isBlank() ? "" : """
                  AND (kc.spring_version_min IS NULL
                       OR spring_version_key(kc.spring_version_min, FALSE) <= spring_version_key(?, FALSE))
                  AND (kc.spring_version_max IS NULL
                       OR spring_version_key(kc.spring_version_max, TRUE) >= spring_version_key(?, FALSE))
                """;
        List<Object> parameters = new ArrayList<>();
        parameters.add(queryText);
        if (springModule != null) parameters.add(springModule);
        if (springVersion != null && !springVersion.isBlank()) {
            parameters.add(springVersion);
            parameters.add(springVersion);
        }
        parameters.add(boundedTopK);
        List<FtsHit> results = jdbc.query(
                """
                SELECT
                    kc.id,
                    kc.content,
                    ts_rank_cd(kc.content_tsvector, query, 1) AS score,
                    kc.spring_module,
                    kc.spring_version_min,
                    kc.spring_version_max
                FROM knowledge_chunk kc,
                     websearch_to_tsquery('english', ?) AS query
                WHERE kc.content_tsvector @@ query
                """ + moduleFilter + versionFilter + """
                ORDER BY score DESC
                LIMIT ?
                """,
                (rs, n) -> new FtsHit(
                        rs.getString("id"),
                        rs.getString("content"),
                        rs.getDouble("score"),
                        rs.getString("spring_module"),
                        rs.getString("spring_version_min"),
                        rs.getString("spring_version_max")),
                parameters.toArray());

        log.debug("FTS search returned {} results", results.size());
        return results;
    }
}
