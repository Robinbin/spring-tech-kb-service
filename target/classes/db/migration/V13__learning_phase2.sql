ALTER TABLE learning_plan
    ADD COLUMN prompt_version VARCHAR(100),
    ADD COLUMN source_topic_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN source_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT learning_plan_source_topics_array CHECK (jsonb_typeof(source_topic_ids) = 'array'),
    ADD CONSTRAINT learning_plan_sources_array CHECK (jsonb_typeof(source_ids) = 'array'),
    ADD CONSTRAINT learning_plan_status_check
        CHECK (status IN ('ACTIVE', 'COMPLETED', 'PAUSED', 'ARCHIVED'));

ALTER TABLE learning_task
    ADD CONSTRAINT learning_task_prerequisites_array
        CHECK (jsonb_typeof(prerequisite_task_ids) = 'array'),
    ADD CONSTRAINT learning_task_status_check
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'SKIPPED'));

ALTER TABLE assessment_session
    ADD COLUMN submitted_at TIMESTAMPTZ,
    ADD CONSTRAINT assessment_session_status_check
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'ABANDONED'));

CREATE TABLE assessment_session_question (
    session_id UUID NOT NULL REFERENCES assessment_session(id) ON DELETE CASCADE,
    question_id UUID NOT NULL REFERENCES question(id),
    source_id UUID REFERENCES knowledge_source(id),
    question_order INT NOT NULL CHECK (question_order >= 0),
    PRIMARY KEY (session_id, question_id),
    UNIQUE (session_id, question_order)
);

INSERT INTO assessment_session_question (session_id, question_id, source_id, question_order)
SELECT ua.session_id, ua.question_id, kc.source_id,
       (row_number() OVER (PARTITION BY ua.session_id ORDER BY ua.answered_at, ua.question_id) - 1)::INT
FROM user_answer ua
JOIN question q ON q.id = ua.question_id
LEFT JOIN knowledge_chunk kc ON kc.id = q.source_chunk_id
ON CONFLICT DO NOTHING;

ALTER TABLE user_answer
    ADD CONSTRAINT user_answer_session_question_fk
        FOREIGN KEY (session_id, question_id)
        REFERENCES assessment_session_question(session_id, question_id) ON DELETE CASCADE;

CREATE UNIQUE INDEX user_answer_session_question_uk ON user_answer (session_id, question_id);
CREATE INDEX assessment_session_question_question_idx ON assessment_session_question (question_id);
CREATE INDEX question_reviewed_multiple_choice_topic_idx
    ON question (topic_id, id) WHERE is_reviewed = TRUE AND type = 'MULTIPLE_CHOICE';

CREATE TABLE learning_plan_adaptation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id UUID NOT NULL REFERENCES learning_plan(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    completion_rate NUMERIC(5, 4) NOT NULL CHECK (completion_rate >= 0 AND completion_rate <= 1),
    action VARCHAR(100) NOT NULL,
    details JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT learning_plan_adaptation_details_object CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX learning_plan_adaptation_owner_idx
    ON learning_plan_adaptation (user_id, plan_id, created_at DESC);
