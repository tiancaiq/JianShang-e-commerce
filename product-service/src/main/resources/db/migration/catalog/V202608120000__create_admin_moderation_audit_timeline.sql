CREATE TABLE moderation_case_events (
    id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    moderation_case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    listing_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    actor_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    previous_state VARCHAR(64) NULL,
    new_state VARCHAR(64) NULL,
    previous_assigned_admin_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    new_assigned_admin_user_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(1000) NULL,
    correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_moderation_case_events_case
        FOREIGN KEY (moderation_case_id) REFERENCES moderation_cases (id),
    CONSTRAINT fk_moderation_case_events_listing
        FOREIGN KEY (listing_id) REFERENCES listings (id),
    CONSTRAINT chk_moderation_case_events_type CHECK (
        event_type IN ('CASE_CREATED', 'CASE_REOPENED', 'CASE_CLAIMED', 'CASE_RELEASED', 'CASE_RESOLVED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX idx_moderation_case_events_case_timeline
    ON moderation_case_events (moderation_case_id, created_at, id);

ALTER TABLE listing_moderation_decisions
    ADD COLUMN moderation_case_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER listing_id,
    ADD COLUMN previous_state VARCHAR(64) NULL AFTER listing_version,
    ADD COLUMN new_state VARCHAR(64) NULL AFTER previous_state,
    ADD COLUMN correlation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER new_state,
    ADD CONSTRAINT fk_listing_moderation_decisions_case
        FOREIGN KEY (moderation_case_id) REFERENCES moderation_cases (id);

CREATE INDEX idx_listing_moderation_decisions_case_timeline
    ON listing_moderation_decisions (moderation_case_id, created_at, id);
