-- Spring Modulith JPA 事件登记表（outbox）
-- 对应 org.springframework.modulith.events.jpa.updating.DefaultJpaEventPublication 实体
-- @Table(name = "EVENT_PUBLICATION") → PostgreSQL 标准化为小写 event_publication
-- 因 spring.jpa.hibernate.ddl-auto=none，Hibernate 不自动建表，由 Flyway 管理。
--
-- 字段说明（来自 JpaEventPublication 2.1.2 字节码反编译）：
--   status：EventPublication$Status（PUBLISHED / PROCESSING / COMPLETED / FAILED / RESUBMITTED）
--   last_resubmission_date：失败重试时间戳（可为 NULL）
--   completion_attempts：重试次数
CREATE TABLE event_publication (
    id                     UUID         NOT NULL,
    listener_id            TEXT         NOT NULL,
    event_type             TEXT         NOT NULL,
    serialized_event       TEXT         NOT NULL,
    publication_date       TIMESTAMPTZ  NOT NULL,
    completion_date        TIMESTAMPTZ,
    last_resubmission_date TIMESTAMPTZ,
    completion_attempts    INT          NOT NULL DEFAULT 0,
    status                 VARCHAR(20)  NOT NULL,
    PRIMARY KEY (id)
);

-- 加速"查找未完成事件"：重试进程定期扫描 completion_date IS NULL 的行
CREATE INDEX event_publication_incomplete_idx
    ON event_publication (publication_date)
    WHERE completion_date IS NULL;

-- 加速按 listener_id + event_type 查找（Modulith 事件投递去重）
CREATE INDEX event_publication_listener_idx
    ON event_publication (listener_id, event_type);
