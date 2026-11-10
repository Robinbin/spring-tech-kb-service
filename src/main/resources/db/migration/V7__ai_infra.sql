-- ── llm_call_log ──────────────────────────────────────────────────────────────
-- H-7：Token 必须按 input / output / cache_read 三列分别计量（三者单价不同）。
-- ttft_ms：Time To First Token，H-4 熔断策略（TTFT > 5s 计入熔断）的数据依据。
CREATE TABLE llm_call_log (
    id                 UUID          DEFAULT gen_random_uuid() PRIMARY KEY,
    trace_id           VARCHAR(32),             -- OTel trace ID，用于关联 Jaeger/Tempo 链路
    user_id            UUID          REFERENCES users(id),
    spring_module      VARCHAR(100),            -- 发起调用的业务模块
    operation          VARCHAR(100),            -- 如 rag.answer / learning.plan / assessment.grade
    model_id           VARCHAR(100)  NOT NULL,
    model_tier         VARCHAR(20),             -- LIGHT, STANDARD, HEAVY
    input_tokens       BIGINT        NOT NULL DEFAULT 0,
    output_tokens      BIGINT        NOT NULL DEFAULT 0,
    cache_read_tokens  BIGINT        NOT NULL DEFAULT 0,
    ttft_ms            INT,
    total_ms           INT,
    estimated_cost_usd NUMERIC(12,8),
    is_error           BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- ── prompt_version ────────────────────────────────────────────────────────────
-- F4 Prompt Registry：版本化存储 + 灰度分流 + 回滚（不依赖重部署）。
-- H-12：Prompt 前缀必须稳定 → 按 (name, version) 唯一；active 记录唯一性由业务逻辑保证。
-- traffic_weight [0-100]：A/B 灰度权重，100 = 全量；灰度时不污染缓存命中统计（H-12 约束）。
CREATE TABLE prompt_version (
    id             UUID         DEFAULT gen_random_uuid() PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    version        INT          NOT NULL,
    content        TEXT         NOT NULL,
    model_tier     VARCHAR(20),
    description    VARCHAR(500),
    checksum       VARCHAR(64)  NOT NULL,  -- content 的 SHA-256，完整性校验
    is_active      BOOLEAN      NOT NULL DEFAULT FALSE,
    traffic_weight INT          NOT NULL DEFAULT 100,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT prompt_version_name_version_uk UNIQUE (name, version)
);

CREATE INDEX llm_call_log_user_created_idx  ON llm_call_log (user_id, created_at DESC);
CREATE INDEX llm_call_log_module_created_idx ON llm_call_log (spring_module, created_at DESC);
CREATE INDEX llm_call_log_trace_idx         ON llm_call_log (trace_id);
CREATE INDEX prompt_version_name_active_idx ON prompt_version (name) WHERE is_active;
