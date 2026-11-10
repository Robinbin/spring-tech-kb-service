-- ── question ──────────────────────────────────────────────────────────────────
-- 题库（L1 Phase 3 生成，但 Schema 现在建好，便于 B1 外键完整）
CREATE TABLE question (
    id               UUID         DEFAULT gen_random_uuid() PRIMARY KEY,
    topic_id         UUID         REFERENCES knowledge_topic(id),
    source_chunk_id  UUID         REFERENCES knowledge_chunk(id),
    type             VARCHAR(50)  NOT NULL,     -- MULTIPLE_CHOICE, CODE_COMPLETION, OPEN_ENDED
    difficulty       VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM',  -- EASY, MEDIUM, HARD
    content          TEXT         NOT NULL,
    options          JSONB,                     -- [{label, text, is_correct}, ...]
    correct_answer   TEXT,
    explanation      TEXT,
    spring_module    VARCHAR(100),
    spring_version   VARCHAR(50),
    is_reviewed      BOOLEAN      NOT NULL DEFAULT FALSE,
    model_id         VARCHAR(100),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ── assessment_session ────────────────────────────────────────────────────────
CREATE TABLE assessment_session (
    id               UUID         DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id          UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_type     VARCHAR(50)  NOT NULL,    -- QUIZ, CHECKPOINT, FULL_ASSESSMENT
    status           VARCHAR(50)  NOT NULL DEFAULT 'IN_PROGRESS',  -- IN_PROGRESS, COMPLETED, ABANDONED
    total_questions  INT,
    correct_count    INT,
    score            NUMERIC(5,2),
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ
);

-- ── user_answer ───────────────────────────────────────────────────────────────
CREATE TABLE user_answer (
    id            UUID         DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id    UUID         NOT NULL REFERENCES assessment_session(id) ON DELETE CASCADE,
    question_id   UUID         NOT NULL REFERENCES question(id),
    user_response TEXT,
    is_correct    BOOLEAN,
    ai_score      NUMERIC(5,2),
    ai_feedback   TEXT,
    answered_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ── user_skill_state ──────────────────────────────────────────────────────────
-- BKT 参数存储（Phase 3 L5）—— 4 个概率值 + 响应计数
CREATE TABLE user_skill_state (
    id              UUID         DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    topic_id        UUID         NOT NULL REFERENCES knowledge_topic(id),
    p_mastery       NUMERIC(6,5) NOT NULL DEFAULT 0.10000,  -- P(L_0)
    p_transit       NUMERIC(6,5) NOT NULL DEFAULT 0.30000,  -- P(T)
    p_guess         NUMERIC(6,5) NOT NULL DEFAULT 0.20000,  -- P(G)
    p_slip          NUMERIC(6,5) NOT NULL DEFAULT 0.10000,  -- P(S)
    response_count  INT          NOT NULL DEFAULT 0,
    last_updated    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT user_skill_state_user_topic_uk UNIQUE (user_id, topic_id)
);

CREATE INDEX question_topic_idx             ON question (topic_id);
CREATE INDEX assessment_session_user_idx    ON assessment_session (user_id);
CREATE INDEX user_answer_session_idx        ON user_answer (session_id);
CREATE INDEX user_answer_question_idx       ON user_answer (question_id);
CREATE INDEX user_skill_state_user_idx      ON user_skill_state (user_id);
