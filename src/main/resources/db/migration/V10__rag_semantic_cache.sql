CREATE TABLE rag_semantic_cache (
    id                  UUID         DEFAULT gen_random_uuid() PRIMARY KEY,
    spring_version      VARCHAR(50)  NOT NULL,
    intent              VARCHAR(50)  NOT NULL,
    knowledge_domain    VARCHAR(100) NOT NULL,
    spring_module       VARCHAR(100),
    question            TEXT         NOT NULL,
    answer              TEXT         NOT NULL,
    citations           JSONB        NOT NULL,
    citation_chunk_ids  JSONB        NOT NULL,
    embedding           vector(1024) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT rag_semantic_cache_expiry_ck CHECK (expires_at > created_at)
);

CREATE INDEX rag_semantic_cache_namespace_idx
    ON rag_semantic_cache (spring_version, intent, knowledge_domain, expires_at);

CREATE INDEX rag_semantic_cache_embedding_idx
    ON rag_semantic_cache USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 200);

CREATE INDEX rag_semantic_cache_module_idx
    ON rag_semantic_cache (spring_module, expires_at);

CREATE FUNCTION spring_version_key(version_text TEXT, upper_bound BOOLEAN)
RETURNS TEXT IMMUTABLE STRICT LANGUAGE plpgsql AS $$
DECLARE
    version_parts TEXT[];
    segment INTEGER;
    part_index INTEGER;
    result TEXT := '';
    wildcard BOOLEAN;
BEGIN
    wildcard := lower(version_text) LIKE '%.x';
    version_parts := string_to_array(regexp_replace(lower(version_text), '\.x$', ''), '.');
    FOR part_index IN 1..3 LOOP
        IF upper_bound AND wildcard AND part_index = 3 THEN
            segment := 999999;
        ELSIF part_index <= array_length(version_parts, 1) THEN
            segment := version_parts[part_index]::INTEGER;
        ELSE
            segment := 0;
        END IF;
        result := result || CASE WHEN part_index = 1 THEN '' ELSE '.' END
                || lpad(segment::TEXT, 6, '0');
    END LOOP;
    RETURN result;
END;
$$;

UPDATE vector_store
SET metadata = COALESCE(metadata, '{}'::jsonb) || jsonb_build_object(
    'spring_version_min_sort',
        COALESCE(spring_version_key(metadata ->> 'spring_version_min', FALSE), '000000.000000.000000'),
    'spring_version_max_sort',
        COALESCE(spring_version_key(metadata ->> 'spring_version_max', TRUE), '999999.999999.999999')
);

CREATE FUNCTION evict_rag_cache_on_knowledge_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        DELETE FROM rag_semantic_cache
        WHERE spring_module IS NOT DISTINCT FROM NEW.spring_module;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        DELETE FROM rag_semantic_cache
        WHERE spring_module IS NOT DISTINCT FROM OLD.spring_module;
        RETURN OLD;
    ELSE
        DELETE FROM rag_semantic_cache
        WHERE spring_module IS NOT DISTINCT FROM OLD.spring_module
           OR spring_module IS NOT DISTINCT FROM NEW.spring_module;
        RETURN NEW;
    END IF;
END;
$$;

CREATE TRIGGER knowledge_source_insert_evict_rag_cache
AFTER INSERT ON knowledge_source
FOR EACH ROW
EXECUTE FUNCTION evict_rag_cache_on_knowledge_update();

CREATE TRIGGER knowledge_source_evict_rag_cache
AFTER UPDATE OF spring_module, version, content_hash ON knowledge_source
FOR EACH ROW
WHEN (OLD.spring_module IS DISTINCT FROM NEW.spring_module
      OR OLD.version IS DISTINCT FROM NEW.version
      OR OLD.content_hash IS DISTINCT FROM NEW.content_hash)
EXECUTE FUNCTION evict_rag_cache_on_knowledge_update();

CREATE TRIGGER knowledge_source_delete_evict_rag_cache
AFTER DELETE ON knowledge_source
FOR EACH ROW
EXECUTE FUNCTION evict_rag_cache_on_knowledge_update();
