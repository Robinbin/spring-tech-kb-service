package com.springtechkb.search.internal;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * B2：H-2 硬约束包装器 — 在每次相似度检索前通过 {@code SET LOCAL} 开启 HNSW 迭代扫描。
 *
 * <h2>为什么需要这个包装器</h2>
 * <p>pgvector 的 HNSW 索引在有元数据预过滤（{@code WHERE} 子句）时，普通图遍历只能
 * 看到邻居图的一个子集，若恰好没有满足过滤条件的向量进入候选集，召回率会骤降到 0。
 * {@code hnsw.iterative_scan = relaxed_order} 让 HNSW 遍历按距离顺序持续扩展直到
 * 找到足够多符合过滤条件的结果，从根本上消除召回塌陷。
 *
 * <h2>为什么用 SET LOCAL</h2>
 * <p>Spring AI 2.x 的 {@link PgVectorStore} 不暴露此 GUC 参数（A1 发现 4）。
 * {@code SET LOCAL} 仅作用于当前事务，在连接池环境中不会"污染"其他请求的连接。
 * {@code @Transactional} 保证 {@code SET LOCAL} 与实际查询使用同一个物理连接。
 */
class HnswAwarePgVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(HnswAwarePgVectorStore.class);

    private final PgVectorStore delegate;
    private final JdbcTemplate jdbc;

    HnswAwarePgVectorStore(PgVectorStore delegate, JdbcTemplate jdbc) {
        this.delegate = delegate;
        this.jdbc = jdbc;
    }

    /**
     * 在事务内先执行 {@code SET LOCAL hnsw.iterative_scan = relaxed_order}，
     * 再委托给 {@link PgVectorStore#similaritySearch}。
     *
     * <p>事务传播默认为 {@code REQUIRED}：若调用者已有事务则加入，否则新建。
     * {@code readOnly = true} 告知连接池这是只读操作。
     */
    /** Enables iterative HNSW scan for the current transaction. Both this class and
     *  {@link SemanticAnswerCache} use this helper so the literal is not duplicated. */
    static void enableIterativeScan(JdbcTemplate jdbc) {
        jdbc.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
    }

    @Override
    @Transactional(readOnly = true)
    public List<Document> similaritySearch(SearchRequest request) {
        // H-2：带元数据过滤的查询必须开启迭代扫描，否则召回率塌陷
        enableIterativeScan(jdbc);
        log.debug("hnsw.iterative_scan = relaxed_order set for this transaction");
        return delegate.similaritySearch(request);
    }

    @Override
    public void add(List<Document> documents) {
        delegate.add(documents);
    }

    @Override
    public void delete(List<String> idList) {
        delegate.delete(idList);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        delegate.delete(filterExpression);
    }

    @Override
    public <T> Optional<T> getNativeClient() {
        return delegate.getNativeClient();
    }

    @Override
    public String getName() {
        return "HnswAware[" + delegate.getName() + "]";
    }
}
