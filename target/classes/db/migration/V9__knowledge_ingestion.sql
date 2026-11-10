-- D1/D2/D4/D5/D6: auditable knowledge ingestion, review, retry and API extraction.

ALTER TABLE knowledge_source
    ADD COLUMN etag VARCHAR(512),
    ADD COLUMN last_modified VARCHAR(255),
    ADD COLUMN license_url TEXT,
    ADD COLUMN approved_by VARCHAR(255),
    ADD COLUMN approval_reference VARCHAR(500);

CREATE TABLE knowledge_index_outbox (
    id              UUID         DEFAULT gen_random_uuid() PRIMARY KEY,
    source_id       UUID         NOT NULL REFERENCES knowledge_source(id) ON DELETE CASCADE,
    event_type      VARCHAR(50)  NOT NULL,
    payload         JSONB        NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempt_count   INT          NOT NULL DEFAULT 0,
    available_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    CONSTRAINT knowledge_index_outbox_status_ck
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'DEAD_LETTER'))
);

CREATE INDEX knowledge_index_outbox_pending_idx
    ON knowledge_index_outbox (available_at, created_at)
    WHERE status IN ('PENDING', 'PROCESSING');

CREATE TABLE knowledge_chunk_review (
    id              UUID         DEFAULT gen_random_uuid() PRIMARY KEY,
    chunk_id        UUID         NOT NULL REFERENCES knowledge_chunk(id) ON DELETE CASCADE,
    reviewer        VARCHAR(255),
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    accuracy_score NUMERIC(5,2),
    notes           TEXT,
    reviewed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT knowledge_chunk_review_status_ck
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT knowledge_chunk_review_score_ck
        CHECK (accuracy_score IS NULL OR accuracy_score BETWEEN 0 AND 100)
);

CREATE INDEX knowledge_chunk_review_status_idx
    ON knowledge_chunk_review (status, created_at);

CREATE TABLE knowledge_api_symbol (
    id                UUID         DEFAULT gen_random_uuid() PRIMARY KEY,
    source_id         UUID         NOT NULL REFERENCES knowledge_source(id) ON DELETE CASCADE,
    class_name        TEXT         NOT NULL,
    symbol_kind       VARCHAR(20)  NOT NULL,
    signature         TEXT         NOT NULL,
    javadoc           TEXT,
    spring_version    VARCHAR(50)  NOT NULL,
    change_type       VARCHAR(20)  NOT NULL DEFAULT 'ADDED',
    previous_signature TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT knowledge_api_symbol_kind_ck
        CHECK (symbol_kind IN ('CLASS', 'INTERFACE', 'ENUM', 'RECORD', 'ANNOTATION',
                               'CONSTRUCTOR', 'METHOD', 'FIELD')),
    CONSTRAINT knowledge_api_symbol_change_ck
        CHECK (change_type IN ('ADDED', 'MODIFIED', 'REMOVED', 'UNCHANGED')),
    CONSTRAINT knowledge_api_symbol_version_uk
        UNIQUE (source_id, spring_version, class_name, symbol_kind, signature)
);

CREATE INDEX knowledge_api_symbol_class_version_idx
    ON knowledge_api_symbol (class_name, spring_version);
