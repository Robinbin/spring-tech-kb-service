CREATE TABLE knowledge_prerequisite (
    topic_id UUID NOT NULL REFERENCES knowledge_topic(id) ON DELETE CASCADE,
    prerequisite_id UUID NOT NULL REFERENCES knowledge_topic(id) ON DELETE CASCADE,
    PRIMARY KEY (topic_id, prerequisite_id),
    CONSTRAINT knowledge_prerequisite_no_self CHECK (topic_id <> prerequisite_id)
);
CREATE INDEX knowledge_prerequisite_reverse_idx ON knowledge_prerequisite (prerequisite_id);

CREATE FUNCTION validate_knowledge_dag() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(73519201);
    IF EXISTS (
        WITH RECURSIVE ancestors(id) AS (
            SELECT NEW.prerequisite_id
            UNION
            SELECT kp.prerequisite_id FROM knowledge_prerequisite kp JOIN ancestors a ON kp.topic_id = a.id
        )
        SELECT 1 FROM ancestors WHERE id = NEW.topic_id
    ) THEN
        RAISE EXCEPTION 'Knowledge prerequisites must form a DAG' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER knowledge_prerequisite_dag
    BEFORE INSERT OR UPDATE ON knowledge_prerequisite
    FOR EACH ROW EXECUTE FUNCTION validate_knowledge_dag();
