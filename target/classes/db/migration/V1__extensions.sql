-- pgvector：向量相似度搜索（H-1/H-2/H-3 硬约束依赖此扩展）
CREATE EXTENSION IF NOT EXISTS vector;

-- pg_trgm：Trigram 相似度与 ILIKE 加速，为 B3 PG FTS 准备
CREATE EXTENSION IF NOT EXISTS pg_trgm;
