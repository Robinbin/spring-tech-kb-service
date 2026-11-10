-- ── knowledge_source ─────────────────────────────────────────────────────────
-- 每行对应一个已通过 D1 准入审查的知识来源（Apache-2.0 / CC 许可）。
-- H-9：第三方博客正文不入库，仅存 URL + 标题 + 作者。
CREATE TABLE knowledge_source (
    id            UUID         DEFAULT gen_random_uuid() PRIMARY KEY,
    title         VARCHAR(500) NOT NULL,
    url           TEXT         NOT NULL,
    license_type  VARCHAR(50)  NOT NULL,   -- APACHE_2, CC_BY, CC_BY_SA, OFFICIAL_DOCS
    author        VARCHAR(255),
    spring_module VARCHAR(100),            -- core, mvc, data, security, batch ...
    version       VARCHAR(50),
    is_approved   BOOLEAN      NOT NULL DEFAULT FALSE,
    crawled_at    TIMESTAMPTZ,
    content_hash  VARCHAR(64),            -- SHA-256；用于增量采集变更检测
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT knowledge_source_url_uk UNIQUE (url)
);

-- ── knowledge_topic ───────────────────────────────────────────────────────────
-- 知识主题分类树（自引用 DAG），为 Phase 2 I1 知识图谱递归 CTE 查询提供骨架。
CREATE TABLE knowledge_topic (
    id            UUID         DEFAULT gen_random_uuid() PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    parent_id     UUID         REFERENCES knowledge_topic(id),
    spring_module VARCHAR(100),
    description   TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT knowledge_topic_name_uk UNIQUE (name)
);

-- ── knowledge_chunk ───────────────────────────────────────────────────────────
-- 文档分块：512 token / 10-15% 重叠（D3 Contextual Retrieval）。
-- content_tsvector 为生成列，供 B3 PG FTS GIN 索引使用。
CREATE TABLE knowledge_chunk (
    id                UUID         DEFAULT gen_random_uuid() PRIMARY KEY,
    source_id         UUID         NOT NULL REFERENCES knowledge_source(id) ON DELETE CASCADE,
    topic_id          UUID         REFERENCES knowledge_topic(id),
    content           TEXT         NOT NULL,
    chunk_index       INT          NOT NULL,
    token_count       INT,
    spring_module     VARCHAR(100),
    spring_version_min VARCHAR(50),   -- 适用的最低 Spring 版本
    spring_version_max VARCHAR(50),   -- 适用的最高版本（NULL = 至今适用）
    -- B3：全文检索生成列（英文分词；中文分词方案待 DEC-03 决策后通过 migration 切换）
    content_tsvector  TSVECTOR     GENERATED ALWAYS AS (
                                       to_tsvector('english', content)
                                   ) STORED,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT knowledge_chunk_source_index_uk UNIQUE (source_id, chunk_index)
);

CREATE INDEX knowledge_chunk_source_idx   ON knowledge_chunk (source_id);
CREATE INDEX knowledge_chunk_topic_idx    ON knowledge_chunk (topic_id);
CREATE INDEX knowledge_chunk_fts_idx      ON knowledge_chunk USING GIN (content_tsvector);
CREATE INDEX knowledge_chunk_version_idx  ON knowledge_chunk (spring_module, spring_version_min, spring_version_max);
