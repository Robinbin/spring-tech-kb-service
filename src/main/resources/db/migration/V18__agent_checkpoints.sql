-- Agent graph checkpoint store for cross-request Memory (Phase 3)
CREATE TABLE agent_checkpoints (
    thread_id      VARCHAR(255)  NOT NULL,
    checkpoint_id  VARCHAR(36)   NOT NULL,
    node_id        VARCHAR(255)  NOT NULL,
    next_node_id   VARCHAR(255)  NOT NULL,
    state_json     JSONB         NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (thread_id, checkpoint_id)
);

-- Latest-first lookup per thread (get() fetches the most recent checkpoint)
CREATE INDEX idx_agent_checkpoints_thread_created
    ON agent_checkpoints (thread_id, created_at DESC);
