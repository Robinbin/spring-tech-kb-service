-- Spring AI PgVectorStore 默认表（spring.ai.vectorstore.pgvector.initialize-schema=false）
-- 由 Flyway 管理；Spring AI 运行时使用此表，不再自行 DDL。
CREATE TABLE vector_store (
    id        UUID    DEFAULT gen_random_uuid() PRIMARY KEY,
    content   TEXT    NOT NULL,
    metadata  JSONB,
    embedding vector(1024)    -- H-1：维度 ≤ 2000 才能建 HNSW 索引；选 1024 满足约束
);

-- HNSW 余弦距离索引
-- H-3：ef_construction ≥ 200（默认 64 对技术语料召回偏低）
-- H-2：hnsw.iterative_scan = relaxed_order 在查询会话中设置（B2 工作），不需 DDL
CREATE INDEX vector_store_hnsw_idx ON vector_store
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 200);

-- metadata JSON 路径索引，加速 spring_module / version 等字段的元数据过滤
CREATE INDEX vector_store_metadata_gin_idx ON vector_store USING GIN (metadata jsonb_path_ops);
