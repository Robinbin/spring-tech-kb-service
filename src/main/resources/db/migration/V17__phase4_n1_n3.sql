CREATE TABLE knowledge_source_catalog (
    source_key       VARCHAR(80) PRIMARY KEY,
    title            VARCHAR(255) NOT NULL,
    source_uri       TEXT NOT NULL UNIQUE,
    spring_module    VARCHAR(100) NOT NULL,
    version          VARCHAR(50) NOT NULL,
    license_type     VARCHAR(40) NOT NULL CHECK (license_type IN ('APACHE_2', 'OFFICIAL_DOCS')),
    license_url      TEXT NOT NULL,
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    last_crawl_at    TIMESTAMPTZ,
    last_crawl_status VARCHAR(20) CHECK (last_crawl_status IN ('UNCHANGED', 'INGESTED')),
    CHECK (source_uri LIKE 'https://docs.spring.io/%'),
    CHECK (license_url LIKE 'https://github.com/spring-projects/%')
);

INSERT INTO knowledge_source_catalog
    (source_key, title, source_uri, spring_module, version, license_type, license_url)
VALUES
    ('spring-cloud', 'Spring Cloud Reference Documentation',
     'https://docs.spring.io/spring-cloud/docs/current/reference/html/',
     'spring-cloud', 'current', 'APACHE_2',
     'https://github.com/spring-projects/spring-cloud/blob/main/LICENSE.txt'),
    ('spring-batch', 'Spring Batch Reference Documentation',
     'https://docs.spring.io/spring-batch/reference/',
     'spring-batch', 'current', 'APACHE_2',
     'https://github.com/spring-projects/spring-batch/blob/main/LICENSE.txt'),
    ('spring-integration', 'Spring Integration Reference Documentation',
     'https://docs.spring.io/spring-integration/reference/',
     'spring-integration', 'current', 'APACHE_2',
     'https://github.com/spring-projects/spring-integration/blob/main/LICENSE.txt');

CREATE TABLE achievement_definition (
    code          VARCHAR(60) PRIMARY KEY,
    title         VARCHAR(120) NOT NULL,
    description   VARCHAR(500) NOT NULL,
    metric_code   VARCHAR(60) NOT NULL,
    threshold     INT NOT NULL CHECK (threshold > 0),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (metric_code, threshold)
);

INSERT INTO achievement_definition (code, title, description, metric_code, threshold)
VALUES
    ('FIRST_LEARNING_TASK', 'First step', 'Complete your first learning task.', 'COMPLETED_TASKS', 1),
    ('LEARNING_TASKS_10', 'Steady learner', 'Complete ten learning tasks.', 'COMPLETED_TASKS', 10),
    ('FIRST_ASSESSMENT', 'Knowledge check', 'Complete your first assessment.', 'COMPLETED_ASSESSMENTS', 1),
    ('ASSESSMENTS_5', 'Assessment regular', 'Complete five assessments.', 'COMPLETED_ASSESSMENTS', 5),
    ('STREAK_3', 'Three-day streak', 'Learn on three consecutive days.', 'CURRENT_STREAK_DAYS', 3),
    ('STREAK_7', 'Seven-day streak', 'Learn on seven consecutive days.', 'CURRENT_STREAK_DAYS', 7),
    ('FIRST_MASTERED_TOPIC', 'Topic mastered', 'Reach 80% mastery in a topic.', 'MASTERED_TOPICS', 1),
    ('MASTERED_TOPICS_5', 'Broad foundation', 'Reach 80% mastery in five topics.', 'MASTERED_TOPICS', 5);

CREATE TABLE user_achievement (
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    achievement_code VARCHAR(60) NOT NULL REFERENCES achievement_definition(code),
    awarded_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, achievement_code)
);

CREATE INDEX user_achievement_user_awarded_idx ON user_achievement (user_id, awarded_at DESC);

CREATE TABLE user_leaderboard_preference (
    user_id       UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    opted_in      BOOLEAN NOT NULL DEFAULT FALSE,
    display_alias VARCHAR(32) NOT NULL UNIQUE
        CHECK (display_alias ~ '^Learner-[A-F0-9]{16}$'),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX user_leaderboard_opted_in_idx
    ON user_leaderboard_preference (opted_in, updated_at DESC)
    WHERE opted_in = TRUE;

CREATE INDEX user_learning_progress_completed_idx
    ON user_learning_progress (user_id, completed_at DESC)
    WHERE status = 'COMPLETED';
CREATE INDEX assessment_session_completed_idx
    ON assessment_session (user_id, completed_at DESC)
    WHERE status = 'COMPLETED';
