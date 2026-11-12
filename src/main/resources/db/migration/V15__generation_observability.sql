ALTER TABLE llm_call_log
    ADD COLUMN span_id VARCHAR(16),
    ADD COLUMN prompt_name VARCHAR(100),
    ADD COLUMN prompt_version VARCHAR(100),
    ADD COLUMN prompt_checksum VARCHAR(64);
CREATE INDEX llm_call_log_operation_created_idx ON llm_call_log (operation, created_at DESC);
