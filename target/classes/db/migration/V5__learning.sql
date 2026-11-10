-- ── learning_plan ─────────────────────────────────────────────────────────────
CREATE TABLE learning_plan (
    id             UUID         DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id        UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title          VARCHAR(500) NOT NULL,
    goal           TEXT,
    status         VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE, COMPLETED, PAUSED, ARCHIVED
    model_id       VARCHAR(100),   -- 生成该计划所用的 Claude 模型 ID
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ── learning_task ─────────────────────────────────────────────────────────────
-- prerequisite_task_ids 存储前置任务 UUID 数组（DAG 边）；I2 阶段写入拓扑校验逻辑。
CREATE TABLE learning_task (
    id                    UUID         DEFAULT gen_random_uuid() PRIMARY KEY,
    plan_id               UUID         NOT NULL REFERENCES learning_plan(id) ON DELETE CASCADE,
    topic_id              UUID         REFERENCES knowledge_topic(id),
    title                 VARCHAR(500) NOT NULL,
    description           TEXT,
    task_order            INT          NOT NULL,
    status                VARCHAR(50)  NOT NULL DEFAULT 'PENDING',  -- PENDING, IN_PROGRESS, COMPLETED, SKIPPED
    prerequisite_task_ids JSONB        NOT NULL DEFAULT '[]',       -- [UUID, ...]
    target_version        VARCHAR(50),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT learning_task_plan_order_uk UNIQUE (plan_id, task_order)
);

-- ── user_learning_progress ────────────────────────────────────────────────────
CREATE TABLE user_learning_progress (
    id                   UUID         DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id              UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    task_id              UUID         NOT NULL REFERENCES learning_task(id) ON DELETE CASCADE,
    status               VARCHAR(50)  NOT NULL DEFAULT 'NOT_STARTED',
    score                NUMERIC(5,2),
    time_spent_seconds   INT,
    completed_at         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT user_learning_progress_user_task_uk UNIQUE (user_id, task_id)
);

CREATE INDEX learning_plan_user_idx            ON learning_plan (user_id);
CREATE INDEX learning_task_plan_idx            ON learning_task (plan_id);
CREATE INDEX learning_task_topic_idx           ON learning_task (topic_id);
CREATE INDEX user_learning_progress_user_idx   ON user_learning_progress (user_id);
CREATE INDEX user_learning_progress_task_idx   ON user_learning_progress (task_id);
